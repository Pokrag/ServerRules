package com.pokrag.serverrules.oath;

import com.pokrag.serverrules.ServerRulesPlugin;
import com.pokrag.serverrules.complaint.SanctionKind;
import com.pokrag.serverrules.storage.Database;
import com.pokrag.serverrules.util.Sync;
import com.pokrag.serverrules.util.TimeUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Gestion des serments (Phase 9).
 *
 * - Les serments et leurs parties sont mis en cache en memoire (charges au demarrage),
 *   car le conflit d'interets doit etre verifie de facon synchrone.
 * - Les demandes d'annulation sont ephemere (delai court) et gardees en memoire.
 * - Une tache periodique fait passer les codes et les durees a expiration.
 */
public final class OathManager {

    /** Resultat de la verification d'une plainte citant un serment. */
    public enum ComplaintCheck {
        OK,
        NOT_FOUND,
        CANCELLED,
        NOT_RUNNING,
        NOT_PARTY
    }

    private record CancelRequest(UUID requester, String requesterName, long expiresAt, Set<UUID> confirmed) {
    }

    private record Attempts(int count, long lockoutUntil) {
    }

    private final ServerRulesPlugin plugin;
    private final Database database;
    private final OathDisplay display;
    private final Map<Long, Oath> oaths = new ConcurrentHashMap<>();
    private final Map<Long, List<OathMember>> members = new ConcurrentHashMap<>();
    private final Map<UUID, Long> pendingJoin = new ConcurrentHashMap<>();
    private final Map<Long, CancelRequest> cancelRequests = new ConcurrentHashMap<>();
    private final Map<UUID, Attempts> failedAttempts = new ConcurrentHashMap<>();
    private BukkitTask ticker;

    public OathManager(ServerRulesPlugin plugin, Database database) {
        this.plugin = plugin;
        this.database = database;
        this.display = new OathDisplay(plugin);
    }

    // ---------------------------------------------------------------- cycle de vie

    /** Charge les serments et leurs parties depuis la base, puis rattrape les expirations. */
    public void load() {
        database.listOaths()
                .thenCompose(list -> {
                    for (Oath oath : list) {
                        oaths.put(oath.id(), oath);
                    }
                    return database.loadOathMembers();
                })
                .thenAccept(map -> {
                    for (Map.Entry<Long, List<OathMember>> entry : map.entrySet()) {
                        members.put(entry.getKey(), new CopyOnWriteArrayList<>(entry.getValue()));
                    }
                    Sync.run(plugin, this::tick);
                })
                .exceptionally(error -> {
                    plugin.getLogger().severe("Chargement des serments impossible : " + error.getMessage());
                    return null;
                });
    }

    /** Demarre la tache d'expiration (code, duree, demandes d'annulation). */
    public void startTicker() {
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    /** Arrete la tache d'expiration. */
    public void stopTicker() {
        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (Oath oath : new ArrayList<>(oaths.values())) {
            if (oath.status() == OathStatus.PROPOSE && now > oath.codeExpiresAt()) {
                transition(oath, OathStatus.PROPOSITION_EXPIREE, oath.activatedAt());
            } else if (oath.status() == OathStatus.ACTIF && !oath.isInfinite()
                    && oath.activatedAt() > 0 && now > oath.expiresAt()) {
                transition(oath, OathStatus.EXPIRE, oath.activatedAt());
            }
        }
        for (Long id : new ArrayList<>(cancelRequests.keySet())) {
            CancelRequest request = cancelRequests.get(id);
            if (request != null && now > request.expiresAt()) {
                cancelRequests.remove(id);
            }
        }
    }

    // ------------------------------------------------------------------- creation

    /** Cree un serment, genere son code et ajoute le fondateur comme premiere partie. */
    public void create(Player founder, long durationSeconds, SanctionKind kind, double amount,
                       long sanctionDurationSeconds, String customText, String promise) {
        int length = plugin.getConfig().getInt("oath.code-length", 4);
        int maxPlayers = maxPlayers();
        long now = System.currentTimeMillis();
        long expiry = now + Math.max(1, plugin.getConfig().getLong("oath.code-expiry-seconds", 60)) * 1000L;
        String code = OathCode.generate(length, this::codeTaken);
        Oath oath = new Oath(0, founder.getUniqueId(), founder.getName(), promise, durationSeconds,
                kind, amount, sanctionDurationSeconds, customText, code, now, expiry, 0, OathStatus.PROPOSE);
        OathMember member = new OathMember(founder.getUniqueId(), founder.getName(), now);

        database.insertOath(oath, member).thenAccept(id -> Sync.run(plugin, () -> {
            oaths.put(id, withId(oath, id));
            members.put(id, new CopyOnWriteArrayList<>(List.of(member)));
            display.created(founder, oath(id));
            if (maxPlayers < 2) {
                plugin.getLogger().warning("oath.max-players est inferieur a 2 : aucun serment ne pourra s'activer.");
            }
        })).exceptionally(error -> {
            plugin.getLogger().severe("Creation du serment impossible : " + error.getMessage());
            Sync.run(plugin, () -> founder.sendMessage(plugin.getLang().prefixed(founder, "error.database")));
            return null;
        });
    }

    // -------------------------------------------------------------------- adhesion

    /** Traite la saisie d'un code : valide le code, puis propose Accepter / Refuser. */
    public void join(Player player, String rawCode) {
        Oath found = requestJoin(player, rawCode);
        if (found != null) {
            display.joinRecap(player, found, membersOf(found.id()));
        }
    }

    /**
     * Valide un code et memorise l'adhesion en attente, sans envoyer de message de recapitulatif.
     *
     * @return le serment a rejoindre, ou null si le code est invalide (un message a deja ete envoye).
     */
    public Oath requestJoin(Player player, String rawCode) {
        long now = System.currentTimeMillis();
        Attempts attempts = failedAttempts.get(player.getUniqueId());
        if (attempts != null && now < attempts.lockoutUntil()) {
            player.sendMessage(plugin.getLang().prefixed(player, "oath.join.lockout",
                    "time", TimeUtil.format(Duration.ofMillis(attempts.lockoutUntil() - now))));
            return null;
        }

        String code = rawCode == null ? "" : rawCode.trim().toUpperCase(Locale.ROOT);
        Oath found = null;
        for (Oath oath : oaths.values()) {
            if (oath.code().equalsIgnoreCase(code)) {
                found = oath;
                break;
            }
        }

        boolean joinable = found != null
                && (found.status() == OathStatus.PROPOSE || found.status() == OathStatus.ACTIF)
                && now <= found.codeExpiresAt();
        if (!joinable) {
            registerFailure(player);
            player.sendMessage(plugin.getLang().prefixed(player, "oath.join.invalid"));
            return null;
        }
        if (isParty(found.id(), player.getUniqueId())) {
            player.sendMessage(plugin.getLang().prefixed(player, "oath.join.already-member"));
            return null;
        }
        if (membersOf(found.id()).size() >= maxPlayers()) {
            player.sendMessage(plugin.getLang().prefixed(player, "oath.join.full"));
            return null;
        }

        failedAttempts.remove(player.getUniqueId());
        pendingJoin.put(player.getUniqueId(), found.id());
        return found;
    }

    /** @return l'identifiant du serment dont l'adhesion est en attente pour ce joueur, s'il existe. */
    public java.util.OptionalLong pendingJoin(UUID uuid) {
        Long id = pendingJoin.get(uuid);
        return id == null ? java.util.OptionalLong.empty() : java.util.OptionalLong.of(id);
    }

    /**
     * @return true si une autre partie a demande l'annulation de ce serment et que le joueur
     *         peut donc la confirmer (demande encore valide).
     */
    public boolean hasOtherCancelRequest(long oathId, UUID player) {
        CancelRequest request = cancelRequests.get(oathId);
        return request != null && System.currentTimeMillis() <= request.expiresAt()
                && !request.requester().equals(player);
    }

    /** Accepte l'adhesion en attente et lie le joueur. Active le serment si besoin. */
    public void accept(Player player) {
        Long oathId = pendingJoin.remove(player.getUniqueId());
        if (oathId == null) {
            player.sendMessage(plugin.getLang().prefixed(player, "oath.join.none-pending"));
            return;
        }
        Oath oath = oaths.get(oathId);
        if (oath == null || (oath.status() != OathStatus.PROPOSE && oath.status() != OathStatus.ACTIF)) {
            player.sendMessage(plugin.getLang().prefixed(player, "oath.join.invalid"));
            return;
        }
        if (isParty(oathId, player.getUniqueId())) {
            player.sendMessage(plugin.getLang().prefixed(player, "oath.join.already-member"));
            return;
        }

        OathMember member = new OathMember(player.getUniqueId(), player.getName(), System.currentTimeMillis());
        database.addOathMember(oathId, member).thenRun(() -> Sync.run(plugin, () -> {
            members.computeIfAbsent(oathId, key -> new CopyOnWriteArrayList<>()).add(member);
            player.sendMessage(plugin.getLang().prefixed(player, "oath.join.accepted",
                    "id", String.valueOf(oathId)));
            Player founder = Bukkit.getPlayer(oath.founder());
            if (founder != null) {
                founder.sendMessage(plugin.getLang().prefixed(founder, "oath.join.joined-notify",
                        "player", player.getName(), "id", String.valueOf(oathId)));
            }
            activateIfReady(oathId);
        })).exceptionally(error -> {
            plugin.getLogger().severe("Adhesion au serment impossible : " + error.getMessage());
            return null;
        });
    }

    /** Refuse l'adhesion en attente. */
    public void refuse(Player player) {
        Long oathId = pendingJoin.remove(player.getUniqueId());
        if (oathId == null) {
            player.sendMessage(plugin.getLang().prefixed(player, "oath.join.none-pending"));
            return;
        }
        player.sendMessage(plugin.getLang().prefixed(player, "oath.join.refused", "id", String.valueOf(oathId)));
    }

    /** Active le serment des que le fondateur et au moins une autre partie sont lies. */
    private void activateIfReady(long oathId) {
        Oath oath = oaths.get(oathId);
        if (oath == null || oath.status() != OathStatus.PROPOSE || membersOf(oathId).size() < 2) {
            return;
        }
        long now = System.currentTimeMillis();
        oaths.put(oathId, withStatus(oath, OathStatus.ACTIF, now));
        database.updateOathStatus(oathId, OathStatus.ACTIF, now).exceptionally(error -> {
            plugin.getLogger().severe("Activation du serment impossible : " + error.getMessage());
            return null;
        });
        for (OathMember member : membersOf(oathId)) {
            Player online = Bukkit.getPlayer(member.uuid());
            if (online != null) {
                online.sendMessage(plugin.getLang().prefixed(online, "oath.join.activated",
                        "id", String.valueOf(oathId), "count", String.valueOf(membersOf(oathId).size())));
            }
        }
    }

    // ------------------------------------------------------------------ annulation

    /**
     * Demande l'annulation d'un serment, ou la confirme si une autre partie l'a deja demandee.
     * Le serment est annule des que toutes les parties sont d'accord.
     */
    public void requestCancel(Player player, long oathId) {
        Oath oath = oaths.get(oathId);
        if (oath == null) {
            player.sendMessage(plugin.getLang().prefixed(player, "oath.not-found", "id", String.valueOf(oathId)));
            return;
        }
        if (oath.status() != OathStatus.PROPOSE && oath.status() != OathStatus.ACTIF) {
            player.sendMessage(plugin.getLang().prefixed(player, "oath.cancel.not-allowed",
                    "id", String.valueOf(oathId)));
            return;
        }
        if (!isParty(oathId, player.getUniqueId())) {
            player.sendMessage(plugin.getLang().prefixed(player, "oath.cancel.not-party",
                    "id", String.valueOf(oathId)));
            return;
        }

        long now = System.currentTimeMillis();
        CancelRequest request = cancelRequests.get(oathId);
        if (request == null || now > request.expiresAt()) {
            long ttl = Math.max(1, plugin.getConfig().getLong("oath.cancel-request-expiry-seconds", 60)) * 1000L;
            request = new CancelRequest(player.getUniqueId(), player.getName(), now + ttl,
                    ConcurrentHashMap.newKeySet());
            cancelRequests.put(oathId, request);
            player.sendMessage(plugin.getLang().prefixed(player, "oath.cancel.requested",
                    "id", String.valueOf(oathId)));
            notifyCancelRequest(oath, request);
            return;
        }

        if (request.requester().equals(player.getUniqueId())) {
            player.sendMessage(plugin.getLang().prefixed(player, "oath.cancel.already-requested",
                    "id", String.valueOf(oathId)));
            return;
        }

        request.confirmed().add(player.getUniqueId());
        if (allConfirmed(oath, request)) {
            cancelRequests.remove(oathId);
            applyCancel(oath);
        } else {
            player.sendMessage(plugin.getLang().prefixed(player, "oath.cancel.confirmed",
                    "id", String.valueOf(oathId)));
        }
    }

    private boolean allConfirmed(Oath oath, CancelRequest request) {
        for (OathMember member : membersOf(oath.id())) {
            if (member.uuid().equals(request.requester())) {
                continue;
            }
            if (!request.confirmed().contains(member.uuid())) {
                return false;
            }
        }
        return true;
    }

    private void applyCancel(Oath oath) {
        oaths.put(oath.id(), withStatus(oath, OathStatus.ANNULE, oath.activatedAt()));
        database.updateOathStatus(oath.id(), OathStatus.ANNULE, oath.activatedAt()).exceptionally(error -> {
            plugin.getLogger().severe("Annulation du serment impossible : " + error.getMessage());
            return null;
        });
        for (OathMember member : membersOf(oath.id())) {
            Player online = Bukkit.getPlayer(member.uuid());
            if (online != null) {
                online.sendMessage(plugin.getLang().prefixed(online, "oath.cancel.cancelled",
                        "id", String.valueOf(oath.id())));
            }
        }
    }

    private void notifyCancelRequest(Oath oath, CancelRequest request) {
        for (OathMember member : membersOf(oath.id())) {
            if (member.uuid().equals(request.requester())) {
                continue;
            }
            Player online = Bukkit.getPlayer(member.uuid());
            if (online != null) {
                online.sendMessage(plugin.getLang().prefixed(online, "oath.cancel.confirm-request",
                        "player", request.requesterName(),
                        "id", String.valueOf(oath.id()),
                        "confirm", "/" + plugin.getConfig().getString("commands.oath.name", "oath")
                                + " cancel " + oath.id()));
            }
        }
    }

    // ------------------------------------------------------------------ plaintes

    /** Verifie qu'une plainte citant un serment est recevable. */
    public ComplaintCheck canComplain(long oathId, UUID plaintiff, UUID accused) {
        Oath oath = oaths.get(oathId);
        if (oath == null) {
            return ComplaintCheck.NOT_FOUND;
        }
        if (oath.status() == OathStatus.ANNULE) {
            return ComplaintCheck.CANCELLED;
        }
        int graceDays = plugin.getConfig().getInt("oath.complaint-grace-days", 2);
        if (!oath.isWithinGrace(System.currentTimeMillis(), graceDays)) {
            return ComplaintCheck.NOT_RUNNING;
        }
        if (!isParty(oathId, plaintiff) || !isParty(oathId, accused)) {
            return ComplaintCheck.NOT_PARTY;
        }
        return ComplaintCheck.OK;
    }

    /** Marque le serment comme non respecte (une sanction a ete appliquee). */
    public void onBreachSanctioned(long oathId) {
        Oath oath = oaths.get(oathId);
        if (oath != null && oath.status() != OathStatus.NON_RESPECTE) {
            transition(oath, OathStatus.NON_RESPECTE, oath.activatedAt());
        }
    }

    /** Marque le dossier lie au serment comme clos (plainte classee sans suite). */
    public void onCaseClosed(long oathId) {
        Oath oath = oaths.get(oathId);
        if (oath != null && oath.status() != OathStatus.TRAITE) {
            transition(oath, OathStatus.TRAITE, oath.activatedAt());
        }
    }

    // ------------------------------------------------------------------ lecture

    /** @return le serment d'identifiant donne, ou null. */
    public Oath oath(long id) {
        return oaths.get(id);
    }

    /** @return les parties d'un serment (liste vide si inconnu). */
    public List<OathMember> membersOf(long id) {
        List<OathMember> list = members.get(id);
        return list == null ? List.of() : list;
    }

    /** @return true si le joueur est une des parties du serment. */
    public boolean isParty(long id, UUID uuid) {
        for (OathMember member : membersOf(id)) {
            if (member.uuid().equals(uuid)) {
                return true;
            }
        }
        return false;
    }

    /** @return les serments auxquels un joueur participe. */
    public List<Oath> oathsOf(UUID uuid) {
        List<Oath> result = new ArrayList<>();
        for (Oath oath : oaths.values()) {
            if (isParty(oath.id(), uuid)) {
                result.add(oath);
            }
        }
        result.sort((a, b) -> Long.compare(a.id(), b.id()));
        return result;
    }

    /** @return le rendu des messages de serments. */
    public OathDisplay getDisplay() {
        return display;
    }

    // ------------------------------------------------------------------ interne

    private void transition(Oath oath, OathStatus status, long activatedAt) {
        oaths.put(oath.id(), withStatus(oath, status, activatedAt));
        database.updateOathStatus(oath.id(), status, activatedAt).exceptionally(error -> {
            plugin.getLogger().severe("Mise a jour du serment impossible : " + error.getMessage());
            return null;
        });
    }

    private boolean codeTaken(String code) {
        for (Oath oath : oaths.values()) {
            if ((oath.status() == OathStatus.PROPOSE || oath.status() == OathStatus.ACTIF)
                    && oath.code().equalsIgnoreCase(code)) {
                return true;
            }
        }
        return false;
    }

    private void registerFailure(Player player) {
        int max = Math.max(1, plugin.getConfig().getInt("oath.code-max-failed-attempts", 5));
        long lockoutMillis = Math.max(1, plugin.getConfig().getLong("oath.code-lockout-seconds", 300)) * 1000L;
        Attempts previous = failedAttempts.getOrDefault(player.getUniqueId(), new Attempts(0, 0));
        int count = previous.count() + 1;
        if (count >= max) {
            failedAttempts.put(player.getUniqueId(), new Attempts(0, System.currentTimeMillis() + lockoutMillis));
        } else {
            failedAttempts.put(player.getUniqueId(), new Attempts(count, 0));
        }
    }

    private int maxPlayers() {
        return Math.max(2, plugin.getConfig().getInt("oath.max-players", 10));
    }

    private Oath withId(Oath oath, long id) {
        return new Oath(id, oath.founder(), oath.founderName(), oath.promise(), oath.durationSeconds(),
                oath.sanctionKind(), oath.amount(), oath.sanctionDurationSeconds(), oath.customText(),
                oath.code(), oath.createdAt(), oath.codeExpiresAt(), oath.activatedAt(), oath.status());
    }

    private Oath withStatus(Oath oath, OathStatus status, long activatedAt) {
        return new Oath(oath.id(), oath.founder(), oath.founderName(), oath.promise(), oath.durationSeconds(),
                oath.sanctionKind(), oath.amount(), oath.sanctionDurationSeconds(), oath.customText(),
                oath.code(), oath.createdAt(), oath.codeExpiresAt(), activatedAt, status);
    }
}

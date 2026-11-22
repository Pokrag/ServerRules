package com.pokrag.serverrules.complaint;

import com.pokrag.serverrules.ServerRulesPlugin;
import com.pokrag.serverrules.display.ComplaintDisplay;
import com.pokrag.serverrules.rule.Rule;
import com.pokrag.serverrules.sanction.AppliedSanction;
import com.pokrag.serverrules.sanction.BanService;
import com.pokrag.serverrules.sanction.FineService;
import com.pokrag.serverrules.sanction.SanctionResult;
import com.pokrag.serverrules.storage.Database;
import com.pokrag.serverrules.util.Sync;
import com.pokrag.serverrules.util.TimeUtil;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Depot, consultation et traitement des plaintes.
 *
 * - Le depot est soumis a un delai minimum entre deux plaintes d'un meme joueur.
 * - Les juges sont notifies immediatement s'ils sont en ligne, sinon a leur connexion.
 * - Le conflit d'interets est verifie a chaque action, pas seulement a l'affichage.
 */
public final class ComplaintManager {

    private final ServerRulesPlugin plugin;
    private final Database database;
    private final ComplaintDisplay display;
    private final FineService fines;
    private final BanService bans;
    private final Map<UUID, Long> lastDeposit = new ConcurrentHashMap<>();

    public ComplaintManager(ServerRulesPlugin plugin, Database database, ComplaintDisplay display,
                            FineService fines, BanService bans) {
        this.plugin = plugin;
        this.database = database;
        this.display = display;
        this.fines = fines;
        this.bans = bans;
    }

    // ------------------------------------------------------------------ depot

    /** Enregistre une plainte, apres verification du delai anti-spam. */
    public void deposit(Player plaintiff, OfflinePlayer accused, Citation citation, String details) {
        long cooldownMillis = Math.max(0, plugin.getConfig().getLong("complaint.cooldown-seconds", 60)) * 1000L;
        long now = System.currentTimeMillis();
        Long last = lastDeposit.get(plaintiff.getUniqueId());
        if (last != null && now - last < cooldownMillis) {
            long remaining = (cooldownMillis - (now - last) + 999) / 1000;
            plaintiff.sendMessage(plugin.getLang().prefixed(plaintiff, "complaint.cooldown",
                    "time", TimeUtil.format(Duration.ofSeconds(remaining))));
            return;
        }

        Complaint draft = new Complaint(0, plaintiff.getUniqueId(), plaintiff.getName(),
                accused.getUniqueId(), nameOf(accused), citation, details, now, ComplaintStatus.EN_ATTENTE);

        database.insertComplaint(draft).thenAccept(id -> {
            lastDeposit.put(plaintiff.getUniqueId(), now);
            Complaint stored = new Complaint(id, draft.plaintiff(), draft.plaintiffName(), draft.accused(),
                    draft.accusedName(), draft.citation(), draft.details(), draft.date(), draft.status());
            Sync.run(plugin, () -> {
                plaintiff.sendMessage(plugin.getLang().prefixed(plaintiff, "complaint.deposited",
                        "id", String.valueOf(id)));
                notifyJudges(stored);
            });
        }).exceptionally(error -> {
            plugin.getLogger().severe("Enregistrement de la plainte impossible : " + error.getMessage());
            Sync.run(plugin, () -> plaintiff.sendMessage(plugin.getLang().prefixed(plaintiff, "error.database")));
            return null;
        });
    }

    /** Notifie les juges en ligne des nouvelles plaintes (sauf conflit d'interets). */
    private void notifyJudges(Complaint complaint) {
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!isJudge(online) || Conflicts.isInvolved(online, complaint, plugin)) {
                continue;
            }
            online.sendMessage(plugin.getLang().prefixed(online, "judge.notify",
                    "id", String.valueOf(complaint.id()),
                    "plaintiff", complaint.plaintiffName(),
                    "accused", complaint.accusedName()));
        }
    }

    /** A la connexion d'un juge, rappelle le nombre de plaintes en attente accessibles. */
    public void notifyOnJoin(Player player) {
        if (!isJudge(player)) {
            return;
        }
        database.listComplaints(ComplaintStatus.EN_ATTENTE).thenAccept(list -> {
            long count = list.stream().filter(c -> !Conflicts.isInvolved(player, c, plugin)).count();
            if (count == 0) {
                return;
            }
            Sync.run(plugin, () -> {
                if (player.isOnline()) {
                    player.sendMessage(plugin.getLang().prefixed(player, "judge.pending-on-join",
                            "count", String.valueOf(count)));
                }
            });
        }).exceptionally(error -> {
            plugin.getLogger().warning("Lecture des plaintes en attente impossible : " + error.getMessage());
            return null;
        });
    }

    // ------------------------------------------------------------- consultation

    /** Liste les plaintes visibles par un destinataire, filtrees par statut si fourni. */
    public void list(CommandSender viewer, ComplaintStatus status) {
        database.listComplaints(status).thenAccept(all -> {
            List<Complaint> visible = new ArrayList<>();
            for (Complaint complaint : all) {
                if (!Conflicts.isInvolved(viewer, complaint, plugin)) {
                    visible.add(complaint);
                }
            }
            Sync.run(plugin, () -> display.list(viewer, visible));
        }).exceptionally(error -> {
            plugin.getLogger().warning("Lecture des plaintes impossible : " + error.getMessage());
            return null;
        });
    }

    /** Affiche le detail d'une plainte. */
    public void view(CommandSender viewer, long id) {
        withComplaint(viewer, id, complaint -> display.detail(viewer, complaint));
    }

    // -------------------------------------------------------------- traitement

    /** Applique la sanction citee ; une valeur nulle applique la valeur telle quelle. */
    public void applySanction(CommandSender viewer, long id, String valueText) {
        withComplaint(viewer, id, complaint -> applyResolved(viewer, complaint, valueText));
    }

    private void applyResolved(CommandSender viewer, Complaint complaint, String valueText) {
        Citation citation = complaint.citation();
        if (citation.sanctionKind() == SanctionKind.CUSTOM) {
            database.updateComplaintStatus(complaint.id(), ComplaintStatus.SANCTION_A_EXECUTER)
                    .thenRun(() -> Sync.run(plugin, () -> viewer.sendMessage(plugin.getLang().prefixed(
                            viewer, "judge.custom-to-execute", "id", String.valueOf(complaint.id())))))
                    .exceptionally(error -> {
                        plugin.getLogger().severe("Mise a jour de la plainte impossible : " + error.getMessage());
                        return null;
                    });
            return;
        }

        double amount = citation.amount();
        long seconds = citation.durationSeconds();

        if (valueText != null && !valueText.isBlank()) {
            if (!display.isRuleCitation(citation)) {
                viewer.sendMessage(plugin.getLang().prefixed(viewer, "judge.no-modulation"));
                return;
            }

            if (citation.sanctionKind() == SanctionKind.FINE) {
                double parsed;
                try {
                    parsed = Double.parseDouble(valueText.trim().replace(',', '.'));
                } catch (NumberFormatException e) {
                    viewer.sendMessage(plugin.getLang().prefixed(viewer, "judge.invalid-value"));
                    return;
                }
                if (parsed <= 0) {
                    viewer.sendMessage(plugin.getLang().prefixed(viewer, "judge.invalid-value"));
                    return;
                }
                amount = parsed;
            } else {
                Duration parsed = TimeUtil.parse(valueText);
                if (parsed == null) {
                    viewer.sendMessage(plugin.getLang().prefixed(viewer, "judge.invalid-value"));
                    return;
                }
                seconds = parsed.toSeconds();
            }

            double base = citation.sanctionKind() == SanctionKind.FINE
                    ? citation.amount() : citation.durationSeconds();
            double chosen = citation.sanctionKind() == SanctionKind.FINE ? amount : seconds;
            double[] range = percentRange(viewer);
            double min = base * range[0] / 100.0;
            double max = base * range[1] / 100.0;
            if (base <= 0 || chosen < min || chosen > max) {
                viewer.sendMessage(plugin.getLang().prefixed(viewer, "judge.out-of-range",
                        "min", displayValue(viewer, citation, min),
                        "max", displayValue(viewer, citation, max)));
                return;
            }
        }

        applyAndRecord(viewer, complaint, amount, seconds);
    }

    /** Applique effectivement la sanction, l'enregistre dans l'historique et clot la plainte. */
    private void applyAndRecord(CommandSender viewer, Complaint complaint, double amount, long seconds) {
        Citation citation = complaint.citation();
        SanctionResult result;
        if (citation.sanctionKind() == SanctionKind.FINE) {
            OfflinePlayer target = Bukkit.getOfflinePlayer(complaint.accused());
            result = fines.applyFine(target, amount);
        } else {
            result = bans.applyTempBan(complaint.accused(), complaint.accusedName(),
                    Duration.ofSeconds(seconds), banReason(viewer, complaint));
        }

        if (!result.success()) {
            viewer.sendMessage(plugin.getLang().prefixed(viewer, "judge.sanction-failed",
                    "error", plugin.getLang().raw(viewer, result.errorKey())));
            return;
        }

        AppliedSanction record = new AppliedSanction(complaint.id(), citation.refId(), citation.number(),
                citation.name(), citation.sanctionKind(), amount, seconds,
                viewer.getName(), System.currentTimeMillis());

        database.appendSanction(record)
                .thenCompose(ignored -> database.updateComplaintStatus(complaint.id(), ComplaintStatus.TRAITEE))
                .thenRun(() -> Sync.run(plugin, () -> {
                    viewer.sendMessage(plugin.getLang().prefixed(
                            viewer, "judge.sanction-applied",
                            "id", String.valueOf(complaint.id()),
                            "sanction", displayValue(viewer, citation,
                                    citation.sanctionKind() == SanctionKind.FINE ? amount : seconds)));
                    if (citation.type() == CitationType.OATH) {
                        plugin.getOathManager().onBreachSanctioned(citation.refId());
                    }
                }))
                .exceptionally(error -> {
                    plugin.getLogger().severe("Enregistrement de la sanction impossible : " + error.getMessage());
                    return null;
                });
    }

    /** Classe une plainte sans suite. */
    public void dismiss(CommandSender viewer, long id) {
        withComplaint(viewer, id, complaint -> database
                .updateComplaintStatus(complaint.id(), ComplaintStatus.CLASSEE_SANS_SUITE)
                .thenRun(() -> Sync.run(plugin, () -> {
                    viewer.sendMessage(plugin.getLang().prefixed(
                            viewer, "judge.dismissed", "id", String.valueOf(complaint.id())));
                    if (complaint.citation().type() == CitationType.OATH) {
                        plugin.getOathManager().onCaseClosed(complaint.citation().refId());
                    }
                }))
                .exceptionally(error -> {
                    plugin.getLogger().severe("Classement de la plainte impossible : " + error.getMessage());
                    return null;
                }));
    }

    /** Marque une sanction personnalisee (serment) comme executee. */
    public void markDone(CommandSender viewer, long id) {
        withComplaint(viewer, id, complaint -> {
            if (complaint.citation().sanctionKind() != SanctionKind.CUSTOM) {
                viewer.sendMessage(plugin.getLang().prefixed(viewer, "judge.done-not-custom"));
                return;
            }
            database.updateComplaintStatus(complaint.id(), ComplaintStatus.TRAITEE)
                    .thenRun(() -> Sync.run(plugin, () -> {
                        viewer.sendMessage(plugin.getLang().prefixed(
                                viewer, "judge.done", "id", String.valueOf(complaint.id())));
                        if (complaint.citation().type() == CitationType.OATH) {
                            plugin.getOathManager().onBreachSanctioned(complaint.citation().refId());
                        }
                    }))
                    .exceptionally(error -> {
                        plugin.getLogger().severe("Marquage de la plainte impossible : " + error.getMessage());
                        return null;
                    });
        });
    }

    /** Remplace la regle citee (superjuge uniquement) et conserve une trace du changement. */
    public void changeCitation(CommandSender viewer, long id, int number) {
        if (!viewer.hasPermission("serverrules.superjudge")) {
            viewer.sendMessage(plugin.getLang().prefixed(viewer, "judge.superjudge-only"));
            return;
        }
        Rule rule = plugin.getRulesManager().byNumber(number);
        if (rule == null) {
            viewer.sendMessage(plugin.getLang().prefixed(viewer, "complaint.rule-not-found",
                    "number", String.valueOf(number)));
            return;
        }
        withComplaint(viewer, id, complaint -> {
            Citation old = complaint.citation();
            Citation updated = Citation.ofRule(rule, number);
            CitationChange trace = new CitationChange(complaint.id(), old.number(), old.name(),
                    number, rule.name(), viewer.getName(), System.currentTimeMillis());
            database.updateComplaintCitation(complaint.id(), updated)
                    .thenCompose(ignored -> database.appendCitationChange(trace))
                    .thenRun(() -> Sync.run(plugin, () -> viewer.sendMessage(plugin.getLang().prefixed(
                            viewer, "judge.rule-changed",
                            "id", String.valueOf(complaint.id()),
                            "old", old.name(),
                            "new", rule.name()))))
                    .exceptionally(error -> {
                        plugin.getLogger().severe("Changement de regle impossible : " + error.getMessage());
                        return null;
                    });
        });
    }

    // ----------------------------------------------------------------- interne

    /** Charge une plainte et applique les verifications communes avant une action. */
    private void withComplaint(CommandSender viewer, long id, Consumer<Complaint> action) {
        database.findComplaint(id).thenAccept(found -> Sync.run(plugin, () -> {
            if (found.isEmpty()) {
                viewer.sendMessage(plugin.getLang().prefixed(viewer, "judge.not-found", "id", String.valueOf(id)));
                return;
            }
            Complaint complaint = found.get();
            if (Conflicts.isInvolved(viewer, complaint, plugin)) {
                viewer.sendMessage(plugin.getLang().prefixed(viewer, "judge.conflict", "id", String.valueOf(id)));
                return;
            }
            action.accept(complaint);
        })).exceptionally(error -> {
            plugin.getLogger().severe("Lecture de la plainte impossible : " + error.getMessage());
            return null;
        });
    }

    /** @return la fourchette de modulation (pourcentages) autorisee pour un destinataire. */
    private double[] percentRange(CommandSender sender) {
        if (sender.hasPermission("serverrules.superjudge")) {
            return new double[]{
                    plugin.getConfig().getDouble("superjudge.min-percent", 10),
                    plugin.getConfig().getDouble("superjudge.max-percent", 500)};
        }
        return new double[]{
                plugin.getConfig().getDouble("judge.min-percent", 40),
                plugin.getConfig().getDouble("judge.max-percent", 200)};
    }

    /** @return le texte d'une valeur de sanction (montant formate ou duree lisible). */
    private String displayValue(CommandSender viewer, Citation citation, double value) {
        if (citation.sanctionKind() == SanctionKind.FINE) {
            return plugin.getLang().raw(viewer, "rules.sanction.fine",
                    "amount", plugin.getEconomy().format(value));
        }
        return plugin.getLang().raw(viewer, "rules.sanction.temp-ban",
                "duration", TimeUtil.format(Duration.ofSeconds((long) value)));
    }

    /** @return la raison inscrite dans le bannissement (texte simple, sans balises). */
    private String banReason(CommandSender viewer, Complaint complaint) {
        return plugin.getLang().plain(viewer, "judge.ban-reason",
                "citation", display.citationText(viewer, complaint.citation()),
                "id", String.valueOf(complaint.id()));
    }

    /** @return true si le destinataire est juge ou superjuge. */
    public static boolean isJudge(CommandSender sender) {
        return sender.hasPermission("serverrules.judge") || sender.hasPermission("serverrules.superjudge");
    }

    private String nameOf(OfflinePlayer player) {
        String name = player.getName();
        return name != null ? name : player.getUniqueId().toString();
    }
}

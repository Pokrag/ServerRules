package com.pokrag.serverrules.complaint;

import com.pokrag.serverrules.ServerRulesPlugin;
import com.pokrag.serverrules.ai.OpenRouterService;
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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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

    /**
     * Charge les plaintes filtrees par statut et rappelle sur le thread principal.
     * Utilise par les menus d'inventaire (Phase 11), sans verifier le conflit d'interets.
     */
    public void loadComplaints(ComplaintStatus status, Consumer<List<Complaint>> callback) {
        database.listComplaints(status)
                .thenAccept(list -> Sync.run(plugin, () -> callback.accept(list)))
                .exceptionally(error -> {
                    plugin.getLogger().warning("Lecture des plaintes impossible : " + error.getMessage());
                    return null;
                });
    }

    /** Charge une plainte et rappelle sur le thread principal (menus d'inventaire, Phase 11). */
    public void loadComplaint(long id, Consumer<Optional<Complaint>> callback) {
        database.findComplaint(id)
                .thenAccept(found -> Sync.run(plugin, () -> callback.accept(found)))
                .exceptionally(error -> {
                    plugin.getLogger().warning("Lecture de la plainte impossible : " + error.getMessage());
                    return null;
                });
    }

    /** @return la fourchette de modulation (pourcentages) autorisee pour un destinataire. */
    public double[] modulationRange(CommandSender sender) {
        return percentRange(sender);
    }

    // -------------------------------------------------------------- traitement

    /** Applique la sanction citee ; une valeur nulle applique la valeur telle quelle. */
    public void applySanction(CommandSender viewer, long id, String valueText) {
        withComplaint(viewer, id, complaint -> applyResolved(viewer, complaint, valueText));
    }

    private void applyResolved(CommandSender viewer, Complaint complaint, String valueText) {
        Citation citation = complaint.citation();

        // Modulation : reservee a une citation ne comportant qu'une seule sanction modulable.
        Double override = null;
        if (valueText != null && !valueText.isBlank()) {
            CitedSanction single = citation.single();
            if (!display.isRuleCitation(citation) || single == null) {
                viewer.sendMessage(plugin.getLang().prefixed(viewer, "judge.no-modulation"));
                return;
            }
            double chosen;
            if (single.kind() == SanctionKind.FINE) {
                try {
                    chosen = Double.parseDouble(valueText.trim().replace(',', '.'));
                } catch (NumberFormatException e) {
                    viewer.sendMessage(plugin.getLang().prefixed(viewer, "judge.invalid-value"));
                    return;
                }
                if (chosen <= 0) {
                    viewer.sendMessage(plugin.getLang().prefixed(viewer, "judge.invalid-value"));
                    return;
                }
            } else {
                Duration parsed = TimeUtil.parse(valueText);
                if (parsed == null) {
                    viewer.sendMessage(plugin.getLang().prefixed(viewer, "judge.invalid-value"));
                    return;
                }
                chosen = parsed.toSeconds();
            }

            double base = single.kind() == SanctionKind.FINE ? single.amount() : single.durationSeconds();
            double[] range = percentRange(viewer);
            if (base <= 0 || chosen < base * range[0] / 100.0 || chosen > base * range[1] / 100.0) {
                viewer.sendMessage(plugin.getLang().prefixed(viewer, "judge.out-of-range",
                        "min", displayValue(viewer, single, base * range[0] / 100.0),
                        "max", displayValue(viewer, single, base * range[1] / 100.0)));
                return;
            }
            override = chosen;
        }

        applyAndRecord(viewer, complaint, override);
    }

    /** Applique toutes les sanctions de la citation, puis clot la plainte (ou la laisse a executer). */
    private void applyAndRecord(CommandSender viewer, Complaint complaint, Double override) {
        Citation citation = complaint.citation();
        List<AppliedSanction> applied = new ArrayList<>();
        List<String> parts = new ArrayList<>();
        List<CitedSanction> customs = new ArrayList<>();

        for (CitedSanction sanction : citation.sanctions()) {
            switch (sanction.kind()) {
                case FINE -> {
                    double amount = override != null ? override : sanction.amount();
                    OfflinePlayer target = Bukkit.getOfflinePlayer(complaint.accused());
                    SanctionResult result = fines.applyFine(target, amount);
                    if (!result.success()) {
                        sanctionFailed(viewer, result);
                        return;
                    }
                    applied.add(record(complaint, citation, SanctionKind.FINE, amount, 0, viewer));
                    parts.add(plugin.getLang().raw(viewer, "rules.sanction.fine",
                            "amount", plugin.getEconomy().format(amount)));
                }
                case TEMP_BAN -> {
                    long seconds = override != null ? override.longValue() : sanction.durationSeconds();
                    SanctionResult result = bans.applyTempBan(complaint.accused(), complaint.accusedName(),
                            Duration.ofSeconds(seconds), banReason(viewer, complaint));
                    if (!result.success()) {
                        sanctionFailed(viewer, result);
                        return;
                    }
                    applied.add(record(complaint, citation, SanctionKind.TEMP_BAN, 0, seconds, viewer));
                    parts.add(plugin.getLang().raw(viewer, "rules.sanction.temp-ban",
                            "duration", TimeUtil.format(Duration.ofSeconds(seconds))));
                }
                case CUSTOM -> customs.add(sanction);
            }
        }

        recordAndFinalize(viewer, complaint, applied, parts, customs);
    }

    /** Enregistre les sanctions appliquees, traite les sanctions libres, puis met a jour la plainte. */
    private void recordAndFinalize(CommandSender viewer, Complaint complaint,
                                   List<AppliedSanction> applied, List<String> parts,
                                   List<CitedSanction> customs) {
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (AppliedSanction sanction : applied) {
            chain = chain.thenCompose(ignored -> database.appendSanction(sanction));
        }

        CompletableFuture<Boolean> afterCustoms = customs.isEmpty()
                ? chain.thenApply(ignored -> false)
                : processCustoms(chain, viewer, complaint, customs);

        afterCustoms
                .thenCompose(deferred -> database.updateComplaintStatus(complaint.id(),
                                deferred ? ComplaintStatus.SANCTION_A_EXECUTER : ComplaintStatus.TRAITEE)
                        .thenApply(ignored -> deferred))
                .thenAccept(deferred -> Sync.run(plugin, () -> {
                    if (deferred) {
                        viewer.sendMessage(plugin.getLang().prefixed(viewer, "judge.custom-to-execute",
                                "id", String.valueOf(complaint.id())));
                        return;
                    }
                    viewer.sendMessage(plugin.getLang().prefixed(viewer, "judge.sanction-applied",
                            "id", String.valueOf(complaint.id()),
                            "sanction", String.join("<gray>, ", parts)));
                    if (complaint.citation().type() == CitationType.OATH) {
                        plugin.getOathManager().onBreachSanctioned(complaint.citation().refId());
                    }
                }))
                .exceptionally(error -> {
                    plugin.getLogger().severe("Enregistrement de la sanction impossible : " + error.getMessage());
                    return null;
                });
    }

    /**
     * Traite chaque sanction libre (texte) : l'IA en fait une commande si possible,
     * sinon elle est laissee a la charge du juge.
     *
     * @return un futur valant true si au moins une sanction doit encore etre executee manuellement.
     */
    private CompletableFuture<Boolean> processCustoms(CompletableFuture<Void> chain, CommandSender viewer,
                                                      Complaint complaint, List<CitedSanction> customs) {
        CompletableFuture<Boolean> result = chain.thenApply(ignored -> false);
        for (CitedSanction custom : customs) {
            result = result.thenCompose(deferred ->
                    applyCustom(viewer, complaint, custom).thenApply(handled -> deferred || !handled));
        }
        return result;
    }

    /**
     * @return un futur valant true si la sanction a ete traduite en commande et executee,
     *         false si elle reste a la charge du juge (IA desactivee, refus, erreur ou credit epuise).
     */
    private CompletableFuture<Boolean> applyCustom(CommandSender viewer, Complaint complaint, CitedSanction custom) {
        OpenRouterService ai = plugin.getOpenRouter();
        if (!ai.configured()) {
            // Aucune cle API : l'administrateur applique la sanction manuellement.
            Sync.run(plugin, () -> viewer.sendMessage(plugin.getLang().prefixed(viewer, "judge.ai-disabled")));
            return CompletableFuture.completedFuture(false);
        }

        return ai.toCommand(custom.customText(), complaint.accusedName(), complaint.citation().name())
                .thenCompose(verdict -> {
                    switch (verdict.kind()) {
                        case COMMAND -> {
                            return executeCommand(verdict.command()).thenCompose(ok -> {
                                if (!ok) {
                                    Sync.run(plugin, () -> viewer.sendMessage(
                                            plugin.getLang().prefixed(viewer, "judge.ai-failed")));
                                    return CompletableFuture.completedFuture(false);
                                }
                                return database.appendSanction(record(complaint, complaint.citation(),
                                                SanctionKind.CUSTOM, 0, 0, viewer))
                                        .thenApply(ignored -> {
                                            Sync.run(plugin, () -> viewer.sendMessage(plugin.getLang().prefixed(
                                                    viewer, "judge.ai-applied", "command", verdict.command())));
                                            return Boolean.TRUE;
                                        });
                            });
                        }
                        case NO_CREDIT -> {
                            Sync.run(plugin, () -> viewer.sendMessage(
                                    plugin.getLang().prefixed(viewer, "judge.ai-no-credit")));
                            return CompletableFuture.completedFuture(false);
                        }
                        case ERROR -> {
                            Sync.run(plugin, () -> viewer.sendMessage(
                                    plugin.getLang().prefixed(viewer, "judge.ai-failed")));
                            return CompletableFuture.completedFuture(false);
                        }
                        default -> {
                            // PASS : l'IA laisse la main au juge.
                            Sync.run(plugin, () -> viewer.sendMessage(
                                    plugin.getLang().prefixed(viewer, "judge.ai-passed")));
                            return CompletableFuture.completedFuture(false);
                        }
                    }
                });
    }

    /** Execute une commande en tant que console, sur le thread principal. */
    private CompletableFuture<Boolean> executeCommand(String command) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        Sync.run(plugin, () -> {
            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
                future.complete(true);
            } catch (RuntimeException error) {
                plugin.getLogger().warning("Commande de sanction IA refusee : " + error.getMessage());
                future.complete(false);
            }
        });
        return future;
    }

    /** Construit l'entree d'historique d'une sanction appliquee. */
    private AppliedSanction record(Complaint complaint, Citation citation, SanctionKind kind,
                                   double amount, long seconds, CommandSender judge) {
        return new AppliedSanction(complaint.id(), citation.refId(), citation.number(),
                citation.name(), kind, amount, seconds, judge.getName(), System.currentTimeMillis());
    }

    private void sanctionFailed(CommandSender viewer, SanctionResult result) {
        viewer.sendMessage(plugin.getLang().prefixed(viewer, "judge.sanction-failed",
                "error", plugin.getLang().raw(viewer, result.errorKey())));
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

    /** Marque une sanction personnalisee (ou une sanction libre) comme executee. */
    public void markDone(CommandSender viewer, long id) {
        withComplaint(viewer, id, complaint -> {
            if (!complaint.citation().hasCustom()) {
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
    private String displayValue(CommandSender viewer, CitedSanction sanction, double value) {
        return switch (sanction.kind()) {
            case FINE -> plugin.getLang().raw(viewer, "rules.sanction.fine",
                    "amount", plugin.getEconomy().format(value));
            case TEMP_BAN -> plugin.getLang().raw(viewer, "rules.sanction.temp-ban",
                    "duration", TimeUtil.format(Duration.ofSeconds((long) value)));
            case CUSTOM -> "";
        };
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

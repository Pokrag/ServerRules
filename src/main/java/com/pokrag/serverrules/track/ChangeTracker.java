package com.pokrag.serverrules.track;

import com.pokrag.serverrules.ServerRulesPlugin;
import com.pokrag.serverrules.display.RulesDisplay;
import com.pokrag.serverrules.rule.ChangeType;
import com.pokrag.serverrules.rule.Rule;
import com.pokrag.serverrules.rule.RuleChange;
import com.pokrag.serverrules.rule.RulesManager;
import com.pokrag.serverrules.storage.Database;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Suit les changements de regles et informe les joueurs.
 *
 * - Au demarrage (et au reload), compare les regles avec l'etat connu en base :
 *   detecte aussi les modifications faites a la main dans config.yml.
 * - Deplacer une regle ne change pas sa revision, donc ne declenche rien.
 * - A la connexion : affiche toutes les regles la premiere fois, sinon un resume
 *   des nouveautes uniquement s'il y en a.
 */
public final class ChangeTracker {

    private final ServerRulesPlugin plugin;
    private final Database database;
    private final RulesManager rules;
    private final RulesDisplay display;

    private volatile CompletableFuture<Void> ready = CompletableFuture.completedFuture(null);

    /** Derniere version de regles vue dans la GUI des regles, par joueur (Phase 11). */
    private final Map<UUID, Long> guiVisits = new java.util.concurrent.ConcurrentHashMap<>();

    public ChangeTracker(ServerRulesPlugin plugin, Database database, RulesManager rules, RulesDisplay display) {
        this.plugin = plugin;
        this.database = database;
        this.rules = rules;
        this.display = display;
    }

    /** Compare les regles chargees avec l'etat connu et journalise les changements detectes. */
    public void detectStartup() {
        ready = database.loadSnapshot()
                .thenCompose(snapshot -> {
                    List<Database.Change> changes = diff(snapshot, rules.rules());
                    CompletableFuture<List<RuleChange>> applied = changes.isEmpty()
                            ? CompletableFuture.completedFuture(List.of())
                            : database.appendChanges(changes);
                    return applied.thenCompose(created -> {
                        if (!created.isEmpty()) {
                            sync(() -> display.broadcastChanges(created));
                        }
                        return database.saveSnapshot(rules.rules());
                    });
                })
                .exceptionally(error -> {
                    plugin.getLogger().severe("Detection des changements de regles impossible : " + error.getMessage());
                    return null;
                });
    }

    /** Enregistre une liste de changements et previent immediatement les joueurs en ligne. */
    public void record(List<Database.Change> changes) {
        if (changes.isEmpty()) {
            return;
        }
        ready.thenCompose(ignored -> database.appendChanges(changes))
                .thenCompose(created -> {
                    sync(() -> display.broadcastChanges(created));
                    return database.saveSnapshot(rules.rules());
                })
                .exceptionally(error -> {
                    plugin.getLogger().severe("Enregistrement des changements impossible : " + error.getMessage());
                    return null;
                });
    }

    /** Traitement a la connexion d'un joueur. */
    public void onJoin(Player player) {
        UUID uuid = player.getUniqueId();
        ready.thenCompose(ignored -> database.lastSeq(uuid))
                .thenCompose(seen -> {
                    if (seen.isEmpty()) {
                        // Premiere connexion : on affiche tout, apres le delai configure.
                        return database.maxSeq()
                                .thenCompose(max -> database.setLastSeq(uuid, max)
                                        .thenRun(() -> scheduleFirstDisplay(player)));
                    }
                    long last = seen.getAsLong();
                    return database.maxSeq().thenCompose(max -> {
                        if (last >= max) {
                            return CompletableFuture.completedFuture(null);
                        }
                        return database.changesAfter(last).thenCompose(changes ->
                                database.setLastSeq(uuid, max).thenRun(() -> sync(() -> {
                                    if (player.isOnline()) {
                                        display.showChanges(player, changes);
                                    }
                                })));
                    });
                })
                .exceptionally(error -> {
                    plugin.getLogger().warning("Suivi des regles pour " + player.getName()
                            + " : " + error.getMessage());
                    return null;
                });
    }

    /** Affiche a un destinataire les nouveautes depuis sa derniere visite (commande /rules new). */
    public void showChangesFor(CommandSender viewer) {
        if (!(viewer instanceof Player player)) {
            display.showChanges(viewer, List.of());
            return;
        }
        ready.thenCompose(ignored -> database.lastSeq(player.getUniqueId()))
                .thenCompose(seen -> database.changesAfter(seen.orElse(0)))
                .thenAccept(changes -> sync(() -> display.showChanges(viewer, changes)))
                .exceptionally(error -> {
                    plugin.getLogger().warning("Lecture des nouveautes impossible : " + error.getMessage());
                    return null;
                });
    }

    /**
     * Recupere les numeros des regles ajoutees ou modifiees depuis la derniere ouverture
     * de la GUI des regles par ce joueur, puis met a jour cette visite (Phase 11).
     */
    public void changedRuleNumbers(Player player, java.util.function.Consumer<Set<Integer>> callback) {
        UUID uuid = player.getUniqueId();
        ready.thenCompose(ignored -> database.maxSeq())
                .thenCompose(max -> {
                    Long previous = guiVisits.put(uuid, max);
                    if (previous == null || previous >= max) {
                        return CompletableFuture.completedFuture(List.<RuleChange>of());
                    }
                    return database.changesAfter(previous);
                })
                .thenAccept(changes -> {
                    Set<Integer> numbers = new HashSet<>();
                    for (RuleChange change : changes) {
                        if (change.type() != ChangeType.REMOVED) {
                            numbers.add(change.ruleNumber());
                        }
                    }
                    sync(() -> callback.accept(numbers));
                })
                .exceptionally(error -> {
                    plugin.getLogger().warning("Lecture des changements pour la GUI impossible : " + error.getMessage());
                    return null;
                });
    }

    private void scheduleFirstDisplay(Player player) {
        long delaySeconds = Math.max(0, plugin.getConfig().getLong("rules-display-delay-seconds", 5));
        sync(() -> Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                display.showTable(player);
            }
        }, delaySeconds * 20L));
    }

    /** Compare l'etat connu (base) et les regles actuelles. */
    private List<Database.Change> diff(Map<Integer, Database.SnapshotRow> snapshot, List<Rule> current) {
        List<Database.Change> changes = new ArrayList<>();
        Set<Integer> present = new HashSet<>();

        int number = 1;
        for (Rule rule : current) {
            Database.SnapshotRow known = snapshot.get(rule.id());
            if (known == null) {
                changes.add(new Database.Change(ChangeType.ADDED, number, rule.name()));
            } else if (!known.revision().equals(rule.revision())) {
                changes.add(new Database.Change(ChangeType.MODIFIED, number, rule.name()));
            }
            present.add(rule.id());
            number++;
        }

        for (Database.SnapshotRow known : snapshot.values()) {
            if (!present.contains(known.id())) {
                changes.add(new Database.Change(ChangeType.REMOVED, known.number(), known.name()));
            }
        }
        return changes;
    }

    private void sync(Runnable task) {
        if (!plugin.isEnabled()) {
            return;
        }
        try {
            Bukkit.getScheduler().runTask(plugin, task);
        } catch (IllegalStateException ignored) {
            // Plugin en cours de desactivation : on ignore.
        }
    }
}

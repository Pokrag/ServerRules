package com.pokrag.serverrules.rule;

import com.pokrag.serverrules.ServerRulesPlugin;
import com.pokrag.serverrules.lang.Lang;
import com.pokrag.serverrules.storage.Database;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gere les modifications de regles demandees par les administrateurs (Phase 8).
 *
 * - Ajout, modification et suppression sont memorises puis appliques seulement
 *   apres confirmation de leur auteur, dans un delai configurable
 *   ({@code rules.confirm-timeout-seconds}) : les commandes et les menus GUI
 *   partagent cette meme logique.
 * - Le deplacement s'applique immediatement, sans confirmation ni notification.
 * - Une modification appliquee est ecrite dans {@code config.yml} et signalee au
 *   {@link com.pokrag.serverrules.track.ChangeTracker} (notifications Phase 3).
 */
public final class RulesAdminManager {

    private record Pending(PendingRuleChange change, long expiresAt) {
    }

    private final ServerRulesPlugin plugin;
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    public RulesAdminManager(ServerRulesPlugin plugin) {
        this.plugin = plugin;
    }

    /** Memorise une modification et affiche le recapitulatif de confirmation a son auteur. */
    public void request(CommandSender sender, PendingRuleChange change) {
        RulesManager rules = plugin.getRulesManager();
        Lang lang = plugin.getLang();

        PendingRuleChange effective = change;
        if (change.kind() == PendingRuleChange.Kind.ADD) {
            // Le numero affiche est celui que la regle prendra une fois ajoutee.
            effective = new PendingRuleChange(PendingRuleChange.Kind.ADD,
                    rules.rules().size() + 1, change.name(), change.description(), change.sanctions());
        } else {
            Rule existing = rules.byNumber(change.number());
            if (existing == null) {
                sender.sendMessage(lang.prefixed(sender, "rules.not-found",
                        "number", String.valueOf(change.number())));
                return;
            }
            if (change.kind() == PendingRuleChange.Kind.REMOVE) {
                // Le recapitulatif montre la regle reelle, pas un nom fourni.
                effective = new PendingRuleChange(PendingRuleChange.Kind.REMOVE,
                        change.number(), existing.name(), "", List.of());
            }
        }

        pending.put(key(sender), new Pending(effective, expiresAt()));
        showSummary(sender, effective);
    }

    /** Applique la modification en attente de ce demandeur (si elle n'a pas expire). */
    public void confirm(CommandSender sender) {
        Lang lang = plugin.getLang();
        Pending current = pending.remove(key(sender));
        if (current == null) {
            sender.sendMessage(lang.prefixed(sender, "rules.admin.nothing-pending"));
            return;
        }
        if (System.currentTimeMillis() > current.expiresAt()) {
            sender.sendMessage(lang.prefixed(sender, "rules.admin.pending-expired"));
            return;
        }
        apply(sender, current.change());
    }

    /** Annule la modification en attente de ce demandeur. */
    public void cancel(CommandSender sender) {
        Lang lang = plugin.getLang();
        if (pending.remove(key(sender)) == null) {
            sender.sendMessage(lang.prefixed(sender, "rules.admin.nothing-pending"));
            return;
        }
        sender.sendMessage(lang.prefixed(sender, "rules.admin.cancelled"));
    }

    /** Deplace une regle immediatement : aucune confirmation, aucune notification. */
    public void move(CommandSender sender, int number, int position) {
        RulesManager rules = plugin.getRulesManager();
        Lang lang = plugin.getLang();

        if (rules.byNumber(number) == null) {
            sender.sendMessage(lang.prefixed(sender, "rules.not-found", "number", String.valueOf(number)));
            return;
        }
        if (position < 1 || position > rules.rules().size()) {
            sender.sendMessage(lang.prefixed(sender, "rules.admin.invalid-position",
                    "position", String.valueOf(position)));
            return;
        }

        rules.move(number, position);
        rules.save();
        sender.sendMessage(lang.prefixed(sender, "rules.admin.moved",
                "number", String.valueOf(number), "position", String.valueOf(position)));
    }

    /** Recharge la configuration, les langues et les regles, puis detecte les changements. */
    public void reload(CommandSender sender) {
        pending.clear();
        plugin.reloadConfig();
        plugin.getLang().load(plugin.getConfig().getString("language.default", "en"));
        plugin.getRulesManager().load();
        plugin.getChangeTracker().detectStartup();
        sender.sendMessage(plugin.getLang().prefixed(sender, "rules.admin.reloaded"));
    }

    /**
     * Applique immediatement une modification de regle (ajout, modification ou suppression).
     * Utilise par la confirmation de la commande et par les menus d'inventaire (Phase 11) :
     * l'auteur de l'action est responsable de la permission et de la confirmation.
     */
    public void apply(CommandSender sender, PendingRuleChange change) {
        RulesManager rules = plugin.getRulesManager();
        Database.Change recorded;

        if (change.kind() == PendingRuleChange.Kind.ADD) {
            Rule rule = rules.create(change.name(), change.description(), change.sanctions());
            recorded = new Database.Change(ChangeType.ADDED, rules.rules().size(), rule.name());
        } else if (change.kind() == PendingRuleChange.Kind.EDIT) {
            Rule rule = rules.replace(change.number(), change.name(), change.description(), change.sanctions());
            if (rule == null) {
                notFound(sender, change.number());
                return;
            }
            recorded = new Database.Change(ChangeType.MODIFIED, change.number(), rule.name());
        } else {
            Rule rule = rules.delete(change.number());
            if (rule == null) {
                notFound(sender, change.number());
                return;
            }
            recorded = new Database.Change(ChangeType.REMOVED, change.number(), rule.name());
        }

        rules.save();
        plugin.getChangeTracker().record(List.of(recorded));
        sender.sendMessage(plugin.getLang().prefixed(sender, "rules.admin.confirmed"));
    }

    private void showSummary(CommandSender sender, PendingRuleChange change) {
        Lang lang = plugin.getLang();
        String confirm = commandName() + " confirm";
        String cancel = commandName() + " cancel";

        Component line = switch (change.kind()) {
            case ADD -> lang.component(sender, "rules.admin.pending-add",
                    "number", String.valueOf(change.number()),
                    "name", change.name(),
                    "description", change.description(),
                    "sanction", plugin.getDisplay().sanctionText(sender, change.sanctions()));
            case EDIT -> lang.component(sender, "rules.admin.pending-edit",
                    "number", String.valueOf(change.number()),
                    "name", change.name(),
                    "description", change.description(),
                    "sanction", plugin.getDisplay().sanctionText(sender, change.sanctions()));
            case REMOVE -> lang.component(sender, "rules.admin.pending-remove",
                    "number", String.valueOf(change.number()),
                    "name", change.name());
        };

        Component message = lang.component(sender, "rules.admin.pending-header")
                .append(Component.newline())
                .append(line)
                .append(Component.newline())
                .append(lang.component(sender, "rules.admin.pending-note",
                        "confirm", confirm, "cancel", cancel,
                        "timeout", String.valueOf(timeoutSeconds())));
        sender.sendMessage(message);
    }

    private void notFound(CommandSender sender, int number) {
        sender.sendMessage(plugin.getLang().prefixed(sender, "rules.not-found",
                "number", String.valueOf(number)));
    }

    /** Cle de suivi de la modification en attente : le joueur, ou une cle unique pour la console. */
    private String key(CommandSender sender) {
        return sender instanceof Player player ? player.getUniqueId().toString() : "console";
    }

    private long timeoutSeconds() {
        return Math.max(1, plugin.getConfig().getLong("rules.confirm-timeout-seconds", 30));
    }

    private long expiresAt() {
        return System.currentTimeMillis() + timeoutSeconds() * 1000L;
    }

    /** @return le nom configure de la commande des regles, precede d'un slash. */
    private String commandName() {
        return "/" + plugin.getConfig().getString("commands.rules.name", "rules");
    }
}

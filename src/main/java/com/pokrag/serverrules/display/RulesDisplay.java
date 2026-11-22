package com.pokrag.serverrules.display;

import com.pokrag.serverrules.ServerRulesPlugin;
import com.pokrag.serverrules.lang.Lang;
import com.pokrag.serverrules.rule.Rule;
import com.pokrag.serverrules.rule.RuleChange;
import com.pokrag.serverrules.rule.Sanction;
import com.pokrag.serverrules.rule.SanctionType;
import com.pokrag.serverrules.util.TimeUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** Construit et envoie les messages lies aux regles (tableau, regle seule, nouveautes). */
public final class RulesDisplay {

    private final ServerRulesPlugin plugin;
    private final Lang lang;

    public RulesDisplay(ServerRulesPlugin plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLang();
    }

    /** Affiche le tableau complet des regles. */
    public void showTable(CommandSender viewer) {
        List<Component> lines = new ArrayList<>();
        lines.add(lang.component(viewer, "rules.header"));

        int number = 1;
        for (Rule rule : plugin.getRulesManager().rules()) {
            lines.add(row(viewer, number, rule));
            number++;
        }
        if (number == 1) {
            lines.add(lang.component(viewer, "rules.empty"));
        }

        lines.add(lang.component(viewer, "rules.footer"));
        viewer.sendMessage(join(lines));
    }

    /** Affiche une regle precise. */
    public void showRule(CommandSender viewer, int number, Rule rule) {
        List<Component> lines = new ArrayList<>();
        lines.add(lang.component(viewer, "rules.single-header", "number", String.valueOf(number)));
        lines.add(row(viewer, number, rule));
        lines.add(lang.component(viewer, "rules.footer"));
        viewer.sendMessage(join(lines));
    }

    /** Affiche la liste des nouveautes pour un destinataire. */
    public void showChanges(CommandSender viewer, List<RuleChange> changes) {
        List<Component> lines = new ArrayList<>();
        lines.add(lang.component(viewer, "rules.changes.header"));
        if (changes.isEmpty()) {
            lines.add(lang.component(viewer, "rules.changes.none"));
        } else {
            for (RuleChange change : changes) {
                lines.add(changeLine(viewer, change));
            }
        }
        lines.add(lang.component(viewer, "rules.changes.footer", "rules-command", rulesCommand()));
        viewer.sendMessage(join(lines));
    }

    /** Envoie les nouveautes a tous les joueurs en ligne. */
    public void broadcastChanges(List<RuleChange> changes) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            showChanges(player, changes);
        }
    }

    private Component row(CommandSender viewer, int number, Rule rule) {
        return lang.component(viewer, "rules.row",
                "number", String.valueOf(number),
                "name", rule.name(),
                "description", rule.description(),
                "sanction", sanctionText(viewer, rule.sanction()));
    }

    private Component changeLine(CommandSender viewer, RuleChange change) {
        String key = switch (change.type()) {
            case ADDED -> "rules.changes.added";
            case MODIFIED -> "rules.changes.modified";
            case REMOVED -> "rules.changes.removed";
        };
        return lang.component(viewer, key,
                "number", String.valueOf(change.ruleNumber()),
                "name", change.ruleName());
    }

    /** @return le texte MiniMessage de la sanction (montant en monnaie du serveur, duree lisible). */
    private String sanctionText(CommandSender viewer, Sanction sanction) {
        if (sanction.type() == SanctionType.FINE) {
            return lang.raw(viewer, "rules.sanction.fine",
                    "amount", plugin.getEconomy().format(sanction.amount()));
        }
        return lang.raw(viewer, "rules.sanction.temp-ban",
                "duration", TimeUtil.format(sanction.duration()));
    }

    /** @return le nom configure de la commande des regles, precede d'un slash. */
    private String rulesCommand() {
        return "/" + plugin.getConfig().getString("commands.rules.name", "rules");
    }

    private Component join(List<Component> lines) {
        Component result = Component.empty();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                result = result.append(Component.newline());
            }
            result = result.append(lines.get(i));
        }
        return result;
    }
}

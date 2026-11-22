package com.pokrag.serverrules.gui;

import com.pokrag.serverrules.ServerRulesPlugin;
import com.pokrag.serverrules.lang.Lang;
import com.pokrag.serverrules.rule.Rule;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * GUI des regles des juges (/rules gui, section 11.1) : lecture seule.
 * Les regles ajoutees ou modifiees depuis la derniere ouverture sont mises en evidence.
 */
public final class RulesGui extends GuiMenu {

    private final Set<Integer> changed;

    private RulesGui(ServerRulesPlugin plugin, Set<Integer> changed) {
        super(plugin, 54);
        this.changed = changed;
    }

    /** Ouvre la GUI apres avoir recupere les regles mises en evidence pour ce joueur. */
    public static void open(ServerRulesPlugin plugin, Player viewer) {
        plugin.getChangeTracker().changedRuleNumbers(viewer,
                changed -> new RulesGui(plugin, changed).open(viewer));
    }

    @Override
    protected Component title(Player viewer) {
        return plugin.getLang().component(viewer, "gui.rules.title");
    }

    @Override
    protected void build(Player viewer) {
        Lang lang = plugin.getLang();
        List<Rule> rules = plugin.getRulesManager().rules();
        int pages = Math.max(1, (rules.size() + PER_PAGE - 1) / PER_PAGE);
        if (page >= pages) {
            page = pages - 1;
        }
        int start = page * PER_PAGE;

        for (int i = 0; i < PER_PAGE && start + i < rules.size(); i++) {
            int number = start + i + 1;
            Rule rule = rules.get(start + i);
            boolean glint = changed.contains(number);

            List<Component> lore = new ArrayList<>();
            if (!rule.description().isBlank()) {
                lore.add(lang.component(viewer, "gui.rules.entry-description", "description", rule.description()));
            }
            lore.add(lang.component(viewer, "gui.rules.entry-sanction",
                    "sanction", plugin.getDisplay().sanctionText(viewer, rule.sanction())));
            if (glint) {
                lore.add(lang.component(viewer, "gui.rules.changed"));
            }

            set(i, Menus.item(Material.WRITABLE_BOOK,
                            lang.component(viewer, "gui.rules.entry-name",
                                    "number", String.valueOf(number), "name", rule.name()),
                            lore, glint),
                    player -> closeThen(player, () -> plugin.getDisplay().showRule(player, number, rule)));
        }

        fillEmpty(fillerItem(viewer));
        navigation(viewer, page, pages);
    }
}

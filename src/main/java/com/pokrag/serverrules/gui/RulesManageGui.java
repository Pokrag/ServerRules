package com.pokrag.serverrules.gui;

import com.pokrag.serverrules.ServerRulesPlugin;
import com.pokrag.serverrules.lang.Lang;
import com.pokrag.serverrules.rule.PendingRuleChange;
import com.pokrag.serverrules.rule.Rule;
import com.pokrag.serverrules.rule.Sanction;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * GUI de gestion des regles (/rules manage, section 11.7), reservee aux superjuges.
 *
 * Une regle par ligne, 5 par page : l'objet de la regle (clic = modifier),
 * un bouton monter, un bouton descendre et un bouton supprimer.
 * Chaque action verifie la permission de la commande equivalente.
 */
public final class RulesManageGui extends GuiMenu {

    /** Nombre de regles affichees par page (une regle = une ligne de 9 cases). */
    private static final int RULES_PER_PAGE = 5;

    private RulesManageGui(ServerRulesPlugin plugin) {
        super(plugin, 54);
    }

    /** Ouvre la GUI de gestion des regles. */
    public static void open(ServerRulesPlugin plugin, Player viewer) {
        new RulesManageGui(plugin).open(viewer);
    }

    @Override
    protected Component title(Player viewer) {
        return plugin.getLang().component(viewer, "gui.manage.title");
    }

    @Override
    protected void build(Player viewer) {
        Lang lang = plugin.getLang();
        List<Rule> rules = plugin.getRulesManager().rules();
        int pages = Math.max(1, (rules.size() + RULES_PER_PAGE - 1) / RULES_PER_PAGE);
        if (page >= pages) {
            page = pages - 1;
        }
        int start = page * RULES_PER_PAGE;

        boolean canMove = viewer.hasPermission("serverrules.move");
        boolean canRemove = viewer.hasPermission("serverrules.remove");

        for (int i = 0; i < RULES_PER_PAGE && start + i < rules.size(); i++) {
            int number = start + i + 1;
            Rule rule = rules.get(start + i);
            int base = i * 9;

            set(base, ruleSummary(plugin, viewer, number, rule.name(), rule.description(), rule.sanction()),
                    player -> closeThen(player, () -> RuleEditGui.open(plugin, player, number)));

            if (canMove) {
                if (number > 1) {
                    set(base + 6, Menus.item(Material.ARROW, lang.component(viewer, "gui.manage.up"), List.of()),
                            player -> {
                                plugin.getRulesAdminManager().move(player, number, number - 1);
                                refresh(player);
                            });
                }
                if (number < rules.size()) {
                    set(base + 7, Menus.item(Material.SPECTRAL_ARROW,
                                    lang.component(viewer, "gui.manage.down"), List.of()),
                            player -> {
                                plugin.getRulesAdminManager().move(player, number, number + 1);
                                refresh(player);
                            });
                }
            }

            if (canRemove) {
                set(base + 8, Menus.item(Material.RED_CONCRETE,
                                lang.component(viewer, "gui.manage.delete"), List.of()),
                        player -> closeThen(player, () -> askDelete(player, number, rule)));
            }
        }

        if (rules.isEmpty()) {
            set(22, Menus.item(Material.BARRIER, lang.component(viewer, "gui.manage.empty"), List.of()));
        }

        fillEmpty(fillerItem(viewer));
        navigation(viewer, page, pages);

        if (viewer.hasPermission("serverrules.add")) {
            set(53, Menus.item(Material.EMERALD, lang.component(viewer, "gui.manage.add"), List.of()),
                    player -> closeThen(player, () -> RuleEditGui.openNew(plugin, player)));
        }
    }

    /** Demande la confirmation avant de supprimer une regle. */
    private void askDelete(Player viewer, int number, Rule rule) {
        ConfirmMenu.open(plugin, viewer,
                List.of(ruleSummary(plugin, viewer, number, rule.name(), rule.description(), rule.sanction())),
                player -> {
                    plugin.getRulesAdminManager().apply(player,
                            new PendingRuleChange(PendingRuleChange.Kind.REMOVE, number, rule.name(), "", null));
                    open(plugin, player);
                },
                player -> open(plugin, player));
    }

    /** Objet recapitulant une regle (utilise dans la liste et dans les confirmations). */
    static ItemStack ruleSummary(ServerRulesPlugin plugin, Player viewer, int number,
                                 String name, String description, Sanction sanction) {
        Lang lang = plugin.getLang();
        List<Component> lore = new ArrayList<>();
        if (description != null && !description.isBlank()) {
            lore.add(lang.component(viewer, "gui.manage.entry-description", "description", description));
        }
        lore.add(lang.component(viewer, "gui.manage.entry-sanction",
                "sanction", plugin.getDisplay().sanctionText(viewer, sanction)));
        return Menus.item(Material.WRITABLE_BOOK,
                lang.component(viewer, "gui.manage.entry-name",
                        "number", String.valueOf(number), "name", name),
                lore);
    }
}

package com.pokrag.serverrules.gui;

import com.pokrag.serverrules.ServerRulesPlugin;
import com.pokrag.serverrules.lang.Lang;
import com.pokrag.serverrules.rule.PendingRuleChange;
import com.pokrag.serverrules.rule.Rule;
import com.pokrag.serverrules.rule.Sanction;
import com.pokrag.serverrules.rule.SanctionType;
import com.pokrag.serverrules.util.TimeUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.List;

/**
 * Menu de modification d'une regle (/rules manage, section 11.7).
 *
 * Boutons : Nom, Description, Type de sanction (bascule amende / ban temporaire)
 * et Valeur. Toutes les saisies se font dans le chat. L'application (ajout ou
 * modification) passe par le menu de confirmation.
 */
public final class RuleEditGui extends GuiMenu {

    private final boolean creating;
    private final int number;
    private String name;
    private String description;
    private Sanction sanction;

    private RuleEditGui(ServerRulesPlugin plugin, boolean creating, int number,
                        String name, String description, Sanction sanction) {
        super(plugin, 45);
        this.creating = creating;
        this.number = number;
        this.name = name;
        this.description = description;
        this.sanction = sanction;
    }

    /** Ouvre le menu d'edition d'une regle existante. */
    public static void open(ServerRulesPlugin plugin, Player viewer, int number) {
        Rule rule = plugin.getRulesManager().byNumber(number);
        if (rule == null) {
            viewer.sendMessage(plugin.getLang().prefixed(viewer, "rules.not-found", "number", String.valueOf(number)));
            return;
        }
        new RuleEditGui(plugin, false, number, rule.name(), rule.description(), rule.sanction()).open(viewer);
    }

    /** Ouvre le menu de creation d'une nouvelle regle. */
    public static void openNew(ServerRulesPlugin plugin, Player viewer) {
        new RuleEditGui(plugin, true, 0, "", "", Sanction.fine(0)).open(viewer);
    }

    @Override
    protected Component title(Player viewer) {
        return plugin.getLang().component(viewer,
                creating ? "gui.manage.add-title" : "gui.manage.edit-title",
                "number", String.valueOf(previewNumber()));
    }

    @Override
    protected void build(Player viewer) {
        Lang lang = plugin.getLang();
        set(4, RulesManageGui.ruleSummary(plugin, viewer, previewNumber(), name, description, sanction));

        set(20, Menus.item(Material.NAME_TAG, lang.component(viewer, "gui.manage.edit-name"), List.of()),
                player -> closeThen(player, () -> askName(player)));
        set(21, Menus.item(Material.PAPER, lang.component(viewer, "gui.manage.edit-description"), List.of()),
                player -> closeThen(player, () -> askDescription(player)));
        set(22, Menus.item(sanction.type() == SanctionType.FINE ? Material.GOLD_INGOT : Material.CLOCK,
                        lang.component(viewer, "gui.manage.edit-type",
                                "type", typeText(viewer)), List.of()),
                player -> {
                    sanction = sanction.type() == SanctionType.FINE
                            ? Sanction.tempBan(Duration.ofHours(1)) : Sanction.fine(0);
                    refresh(player);
                });
        set(23, Menus.item(Material.EMERALD, lang.component(viewer, "gui.manage.edit-value"), List.of()),
                player -> closeThen(player, () -> askValue(player)));
        set(24, Menus.item(Material.LIME_CONCRETE, lang.component(viewer, "gui.manage.edit-save"), List.of()),
                player -> closeThen(player, () -> save(player)));
        set(40, Menus.item(Material.ARROW, lang.component(viewer, "gui.common.back"), List.of()),
                player -> closeThen(player, () -> RulesManageGui.open(plugin, player)));
        fillEmpty(fillerItem(viewer));
    }

    private void askName(Player viewer) {
        Lang lang = plugin.getLang();
        viewer.sendMessage(lang.prefixed(viewer, "gui.manage.ask-name"));
        plugin.getChatInput().request(viewer, text -> {
            if (text.isBlank()) {
                viewer.sendMessage(lang.prefixed(viewer, "gui.manage.name-required"));
                askName(viewer);
                return;
            }
            name = text.trim();
            new RuleEditGui(plugin, creating, number, name, description, sanction).open(viewer);
        }, () -> new RuleEditGui(plugin, creating, number, name, description, sanction).open(viewer));
    }

    private void askDescription(Player viewer) {
        Lang lang = plugin.getLang();
        viewer.sendMessage(lang.prefixed(viewer, "gui.manage.ask-description"));
        plugin.getChatInput().request(viewer, text -> {
            description = text.trim();
            new RuleEditGui(plugin, creating, number, name, description, sanction).open(viewer);
        }, () -> new RuleEditGui(plugin, creating, number, name, description, sanction).open(viewer));
    }

    private void askValue(Player viewer) {
        Lang lang = plugin.getLang();
        boolean fine = sanction.type() == SanctionType.FINE;
        viewer.sendMessage(lang.prefixed(viewer, fine ? "gui.manage.ask-amount" : "gui.manage.ask-duration"));
        plugin.getChatInput().request(viewer, text -> {
            if (fine) {
                double amount = parseAmount(text);
                if (amount < 0) {
                    viewer.sendMessage(lang.prefixed(viewer, "error.invalid-amount"));
                    askValue(viewer);
                    return;
                }
                sanction = Sanction.fine(amount);
            } else {
                Duration duration = TimeUtil.parse(text.trim());
                if (duration == null || duration.toSeconds() <= 0) {
                    viewer.sendMessage(lang.prefixed(viewer, "error.invalid-duration"));
                    askValue(viewer);
                    return;
                }
                sanction = Sanction.tempBan(duration);
            }
            new RuleEditGui(plugin, creating, number, name, description, sanction).open(viewer);
        }, () -> new RuleEditGui(plugin, creating, number, name, description, sanction).open(viewer));
    }

    /** Ouvre le menu de confirmation avant d'appliquer l'ajout ou la modification. */
    private void save(Player viewer) {
        Lang lang = plugin.getLang();
        if (name.isBlank()) {
            viewer.sendMessage(lang.prefixed(viewer, "gui.manage.name-required"));
            return;
        }
        int preview = previewNumber();
        PendingRuleChange change = creating
                ? new PendingRuleChange(PendingRuleChange.Kind.ADD, 0, name, description, sanction)
                : new PendingRuleChange(PendingRuleChange.Kind.EDIT, number, name, description, sanction);
        ConfirmMenu.open(plugin, viewer,
                List.of(RulesManageGui.ruleSummary(plugin, viewer, preview, name, description, sanction)),
                player -> {
                    plugin.getRulesAdminManager().apply(player, change);
                    RulesManageGui.open(plugin, player);
                },
                player -> new RuleEditGui(plugin, creating, number, name, description, sanction).open(player));
    }

    private int previewNumber() {
        return creating ? plugin.getRulesManager().rules().size() + 1 : number;
    }

    private String typeText(Player viewer) {
        return plugin.getLang().raw(viewer, sanction.type() == SanctionType.FINE
                ? "gui.manage.type-fine" : "gui.manage.type-ban");
    }

    private static double parseAmount(String text) {
        try {
            double value = Double.parseDouble(text.trim().replace(',', '.'));
            return Double.isFinite(value) && value >= 0 ? value : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}

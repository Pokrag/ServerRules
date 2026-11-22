package com.pokrag.serverrules.gui;

import com.pokrag.serverrules.ServerRulesPlugin;
import com.pokrag.serverrules.lang.Lang;
import com.pokrag.serverrules.rule.PendingRuleChange;
import com.pokrag.serverrules.rule.Rule;
import com.pokrag.serverrules.rule.Sanction;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Menu de modification d'une regle (/rules manage, section 11.7).
 *
 * Boutons : Nom, Description, Sanctions (ouvre la liste des sanctions) et
 * Enregistrer. Toutes les saisies se font dans le chat. Une regle peut prevoir
 * plusieurs sanctions : elles sont gerees dans {@link SanctionListGui}.
 * L'application (ajout ou modification) passe par le menu de confirmation.
 */
public final class RuleEditGui extends GuiMenu {

    private final RuleDraft draft;

    private RuleEditGui(ServerRulesPlugin plugin, RuleDraft draft) {
        super(plugin, 45);
        this.draft = draft;
    }

    /** Ouvre le menu d'edition d'une regle existante. */
    public static void open(ServerRulesPlugin plugin, Player viewer, int number) {
        Rule rule = plugin.getRulesManager().byNumber(number);
        if (rule == null) {
            viewer.sendMessage(plugin.getLang().prefixed(viewer, "rules.not-found", "number", String.valueOf(number)));
            return;
        }
        open(plugin, viewer, new RuleDraft(false, number, rule.name(), rule.description(), rule.sanctions()));
    }

    /** Ouvre le menu de creation d'une nouvelle regle. */
    public static void openNew(ServerRulesPlugin plugin, Player viewer) {
        open(plugin, viewer, new RuleDraft(true, 0, "", "", List.of()));
    }

    /** Ouvre le menu d'edition a partir d'un brouillon existant (retour depuis la liste des sanctions). */
    static void open(ServerRulesPlugin plugin, Player viewer, RuleDraft draft) {
        new RuleEditGui(plugin, draft).open(viewer);
    }

    @Override
    protected Component title(Player viewer) {
        return plugin.getLang().component(viewer,
                draft.creating ? "gui.manage.add-title" : "gui.manage.edit-title",
                "number", String.valueOf(previewNumber()));
    }

    @Override
    protected void build(Player viewer) {
        Lang lang = plugin.getLang();
        set(4, RulesManageGui.ruleSummary(plugin, viewer, previewNumber(), draft.name, draft.description,
                List.copyOf(draft.sanctions)));

        set(20, Menus.item(Material.NAME_TAG, lang.component(viewer, "gui.manage.edit-name"), List.of()),
                player -> closeThen(player, () -> askName(player)));
        set(21, Menus.item(Material.PAPER, lang.component(viewer, "gui.manage.edit-description"), List.of()),
                player -> closeThen(player, () -> askDescription(player)));
        set(22, Menus.item(Material.CHEST, lang.component(viewer, "gui.manage.edit-sanctions",
                        "count", String.valueOf(draft.sanctions.size())), List.of()),
                player -> closeThen(player, () -> SanctionListGui.open(plugin, player, draft)));
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
            draft.name = text.trim();
            open(plugin, viewer, draft);
        }, () -> open(plugin, viewer, draft));
    }

    private void askDescription(Player viewer) {
        Lang lang = plugin.getLang();
        viewer.sendMessage(lang.prefixed(viewer, "gui.manage.ask-description"));
        plugin.getChatInput().request(viewer, text -> {
            draft.description = text.trim();
            open(plugin, viewer, draft);
        }, () -> open(plugin, viewer, draft));
    }

    /** Ouvre le menu de confirmation avant d'appliquer l'ajout ou la modification. */
    private void save(Player viewer) {
        Lang lang = plugin.getLang();
        if (draft.name.isBlank()) {
            viewer.sendMessage(lang.prefixed(viewer, "gui.manage.name-required"));
            return;
        }
        if (draft.sanctions.isEmpty()) {
            viewer.sendMessage(lang.prefixed(viewer, "gui.manage.sanction-required"));
            return;
        }
        int preview = previewNumber();
        List<Sanction> sanctions = List.copyOf(draft.sanctions);
        PendingRuleChange change = draft.creating
                ? new PendingRuleChange(PendingRuleChange.Kind.ADD, 0, draft.name, draft.description, sanctions)
                : new PendingRuleChange(PendingRuleChange.Kind.EDIT, draft.number, draft.name, draft.description, sanctions);
        ConfirmMenu.open(plugin, viewer,
                List.of(RulesManageGui.ruleSummary(plugin, viewer, preview, draft.name, draft.description, sanctions)),
                player -> {
                    plugin.getRulesAdminManager().apply(player, change);
                    RulesManageGui.open(plugin, player);
                },
                player -> open(plugin, player, draft));
    }

    private int previewNumber() {
        return draft.creating ? plugin.getRulesManager().rules().size() + 1 : draft.number;
    }
}

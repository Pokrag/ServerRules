package com.pokrag.serverrules.gui;

import com.pokrag.serverrules.ServerRulesPlugin;
import com.pokrag.serverrules.lang.Lang;
import com.pokrag.serverrules.rule.Sanction;
import com.pokrag.serverrules.util.TimeUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.List;

/**
 * Menu d'edition d'une sanction libre (Phase 11, section 11.7).
 *
 * Le type est choisi ici (amende, ban temporaire ou texte libre), puis la valeur
 * est saisie dans le chat. Validee, elle est ajoutee au brouillon de regle et le
 * menu de la liste des sanctions est rouvert.
 */
public final class SanctionEditGui extends GuiMenu {

    private final RuleDraft draft;
    private final int index;

    private SanctionEditGui(ServerRulesPlugin plugin, RuleDraft draft, int index) {
        super(plugin, 27);
        this.draft = draft;
        this.index = index;
    }

    /** Ouvre le menu ; {@code index} vaut -1 pour une nouvelle sanction. */
    static void open(ServerRulesPlugin plugin, Player viewer, RuleDraft draft, int index) {
        new SanctionEditGui(plugin, draft, index).open(viewer);
    }

    @Override
    protected Component title(Player viewer) {
        return plugin.getLang().component(viewer, "gui.sanction-edit.title");
    }

    @Override
    protected void build(Player viewer) {
        Lang lang = plugin.getLang();
        set(11, Menus.item(Material.GOLD_INGOT, lang.component(viewer, "gui.sanction-edit.entry-fine"), List.of()),
                player -> closeThen(player, () -> askAmount(player)));
        set(13, Menus.item(Material.CLOCK, lang.component(viewer, "gui.sanction-edit.entry-ban"), List.of()),
                player -> closeThen(player, () -> askBan(player)));
        set(15, Menus.item(Material.NAME_TAG, lang.component(viewer, "gui.sanction-edit.entry-custom"), List.of()),
                player -> closeThen(player, () -> askCustom(player)));
        set(26, Menus.item(Material.ARROW, lang.component(viewer, "gui.common.back"), List.of()),
                player -> closeThen(player, () -> SanctionListGui.open(plugin, player, draft)));
        fillEmpty(fillerItem(viewer));
    }

    private void askAmount(Player viewer) {
        Lang lang = plugin.getLang();
        viewer.sendMessage(lang.prefixed(viewer, "gui.sanction-edit.ask-amount"));
        plugin.getChatInput().request(viewer, text -> {
            double amount = parseAmount(text);
            if (amount < 0) {
                viewer.sendMessage(lang.prefixed(viewer, "error.invalid-amount"));
                askAmount(viewer);
                return;
            }
            store(viewer, Sanction.fine(amount));
        }, () -> open(plugin, viewer, draft, index));
    }

    private void askBan(Player viewer) {
        Lang lang = plugin.getLang();
        viewer.sendMessage(lang.prefixed(viewer, "gui.sanction-edit.ask-ban"));
        plugin.getChatInput().request(viewer, text -> {
            Duration duration = TimeUtil.parse(text.trim());
            if (duration == null || duration.toSeconds() <= 0) {
                viewer.sendMessage(lang.prefixed(viewer, "error.invalid-duration"));
                askBan(viewer);
                return;
            }
            store(viewer, Sanction.tempBan(duration));
        }, () -> open(plugin, viewer, draft, index));
    }

    private void askCustom(Player viewer) {
        Lang lang = plugin.getLang();
        viewer.sendMessage(lang.prefixed(viewer, "gui.sanction-edit.ask-custom"));
        plugin.getChatInput().request(viewer, text -> {
            if (text.isBlank()) {
                viewer.sendMessage(lang.prefixed(viewer, "error.invalid-sanction"));
                askCustom(viewer);
                return;
            }
            store(viewer, Sanction.custom(text));
        }, () -> open(plugin, viewer, draft, index));
    }

    /** Ajoute ou remplace la sanction dans le brouillon, puis revient a la liste. */
    private void store(Player viewer, Sanction sanction) {
        if (index >= 0 && index < draft.sanctions.size()) {
            draft.sanctions.set(index, sanction);
        } else {
            draft.sanctions.add(sanction);
        }
        SanctionListGui.open(plugin, viewer, draft);
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

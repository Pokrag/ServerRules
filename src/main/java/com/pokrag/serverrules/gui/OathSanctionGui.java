package com.pokrag.serverrules.gui;

import com.pokrag.serverrules.ServerRulesPlugin;
import com.pokrag.serverrules.complaint.SanctionKind;
import com.pokrag.serverrules.lang.Lang;
import com.pokrag.serverrules.util.TimeUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.List;

/**
 * Choix de la sanction d'un serment (Phase 11, section 11.5).
 *
 * Meme principe que {@link SanctionEditGui} pour les regles : le type est choisi
 * ici (amende, ban temporaire ou texte libre), la valeur est saisie dans le chat,
 * puis le menu de creation du serment est rouvert.
 */
public final class OathSanctionGui extends GuiMenu {

    private final OathDraft draft;

    private OathSanctionGui(ServerRulesPlugin plugin, OathDraft draft) {
        super(plugin, 27);
        this.draft = draft;
    }

    /** Ouvre le menu de choix de la sanction du brouillon. */
    static void open(ServerRulesPlugin plugin, Player viewer, OathDraft draft) {
        new OathSanctionGui(plugin, draft).open(viewer);
    }

    @Override
    protected Component title(Player viewer) {
        return plugin.getLang().component(viewer, "gui.oath-create.sanction-title");
    }

    @Override
    protected void build(Player viewer) {
        Lang lang = plugin.getLang();
        set(11, Menus.item(Material.GOLD_INGOT, lang.component(viewer, "gui.oath-create.entry-fine"), List.of()),
                player -> closeThen(player, () -> askAmount(player)));
        set(13, Menus.item(Material.CLOCK, lang.component(viewer, "gui.oath-create.entry-ban"), List.of()),
                player -> closeThen(player, () -> askBan(player)));
        set(15, Menus.item(Material.NAME_TAG, lang.component(viewer, "gui.oath-create.entry-custom"), List.of()),
                player -> closeThen(player, () -> askCustom(player)));
        set(26, Menus.item(Material.ARROW, lang.component(viewer, "gui.common.back"), List.of()),
                player -> closeThen(player, () -> OathCreateGui.open(plugin, player, draft)));
        fillEmpty(fillerItem(viewer));
    }

    private void askAmount(Player viewer) {
        Lang lang = plugin.getLang();
        viewer.sendMessage(lang.prefixed(viewer, "gui.oath-create.ask-amount"));
        plugin.getChatInput().request(viewer, text -> {
            double amount = parseAmount(text);
            if (amount <= 0) {
                viewer.sendMessage(lang.prefixed(viewer, "oath.create.invalid-value"));
                askAmount(viewer);
                return;
            }
            store(viewer, SanctionKind.FINE, amount, 0, "");
        }, () -> open(plugin, viewer, draft));
    }

    private void askBan(Player viewer) {
        Lang lang = plugin.getLang();
        viewer.sendMessage(lang.prefixed(viewer, "gui.oath-create.ask-ban"));
        plugin.getChatInput().request(viewer, text -> {
            Duration ban = TimeUtil.parse(text.trim());
            if (ban == null || ban.toSeconds() <= 0) {
                viewer.sendMessage(lang.prefixed(viewer, "oath.create.invalid-value"));
                askBan(viewer);
                return;
            }
            store(viewer, SanctionKind.TEMP_BAN, 0, ban.toSeconds(), "");
        }, () -> open(plugin, viewer, draft));
    }

    private void askCustom(Player viewer) {
        Lang lang = plugin.getLang();
        viewer.sendMessage(lang.prefixed(viewer, "gui.oath-create.ask-custom"));
        plugin.getChatInput().request(viewer, text -> {
            if (text.isBlank()) {
                viewer.sendMessage(lang.prefixed(viewer, "oath.create.invalid-value"));
                askCustom(viewer);
                return;
            }
            store(viewer, SanctionKind.CUSTOM, 0, 0, text.trim());
        }, () -> open(plugin, viewer, draft));
    }

    /** Enregistre la sanction dans le brouillon, puis rouvre le menu de creation. */
    private void store(Player viewer, SanctionKind kind, double amount, long banSeconds, String customText) {
        draft.kind = kind;
        draft.amount = amount;
        draft.banSeconds = banSeconds;
        draft.customText = customText;
        OathCreateGui.open(plugin, viewer, draft);
    }

    private static double parseAmount(String text) {
        try {
            double value = Double.parseDouble(text.trim().replace(',', '.'));
            return Double.isFinite(value) ? value : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}

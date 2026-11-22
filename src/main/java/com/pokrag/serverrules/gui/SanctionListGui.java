package com.pokrag.serverrules.gui;

import com.pokrag.serverrules.ServerRulesPlugin;
import com.pokrag.serverrules.lang.Lang;
import com.pokrag.serverrules.rule.Sanction;
import com.pokrag.serverrules.util.TimeUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * Menu de la liste des sanctions d'un brouillon de regle (Phase 11, section 11.7).
 *
 * - Une sanction par case : clic = modifier.
 * - Un bouton ajoute une sanction, un autre revient au menu de la regle.
 * Permet donc a une regle de prevoir plusieurs sanctions.
 */
public final class SanctionListGui extends GuiMenu {

    private final RuleDraft draft;

    private SanctionListGui(ServerRulesPlugin plugin, RuleDraft draft) {
        super(plugin, 54);
        this.draft = draft;
    }

    /** Ouvre le menu de la liste des sanctions du brouillon. */
    static void open(ServerRulesPlugin plugin, Player viewer, RuleDraft draft) {
        new SanctionListGui(plugin, draft).open(viewer);
    }

    @Override
    protected Component title(Player viewer) {
        return plugin.getLang().component(viewer, "gui.sanction-list.title");
    }

    @Override
    protected void build(Player viewer) {
        Lang lang = plugin.getLang();

        for (int i = 0; i < PER_PAGE && i < draft.sanctions.size(); i++) {
            int index = i;
            set(i, sanctionItem(viewer, draft.sanctions.get(i)),
                    player -> closeThen(player, () -> SanctionEditGui.open(plugin, player, draft, index)));
        }

        if (draft.sanctions.isEmpty()) {
            set(22, Menus.item(Material.BARRIER, lang.component(viewer, "gui.sanction-list.empty"), List.of()));
        }

        set(45, Menus.item(Material.EMERALD, lang.component(viewer, "gui.sanction-list.add"), List.of()),
                player -> closeThen(player, () -> SanctionEditGui.open(plugin, player, draft, -1)));
        set(53, Menus.item(Material.ARROW, lang.component(viewer, "gui.common.back"), List.of()),
                player -> closeThen(player, () -> RuleEditGui.open(plugin, player, draft)));

        fillEmpty(fillerItem(viewer));
    }

    /** Objet representant une sanction du brouillon, dans la langue du spectateur. */
    private ItemStack sanctionItem(Player viewer, Sanction sanction) {
        Lang lang = plugin.getLang();
        return switch (sanction.type()) {
            case FINE -> Menus.item(Material.GOLD_INGOT, lang.component(viewer,
                    "gui.sanction-list.entry-fine", "amount", plugin.getEconomy().format(sanction.amount())), List.of());
            case TEMP_BAN -> Menus.item(Material.CLOCK, lang.component(viewer,
                    "gui.sanction-list.entry-ban", "duration", TimeUtil.format(sanction.duration())), List.of());
            case CUSTOM -> Menus.item(Material.NAME_TAG, lang.component(viewer,
                    "gui.sanction-list.entry-custom", "text", sanction.text()), List.of());
        };
    }
}

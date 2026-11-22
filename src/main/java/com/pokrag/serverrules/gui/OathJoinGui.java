package com.pokrag.serverrules.gui;

import com.pokrag.serverrules.ServerRulesPlugin;
import com.pokrag.serverrules.lang.Lang;
import com.pokrag.serverrules.oath.Oath;
import com.pokrag.serverrules.oath.OathMember;
import com.pokrag.serverrules.util.TimeUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Recapitulatif d'une demande d'adhesion a un serment (/oath, section 11.6).
 * Boutons Accepter et Refuser.
 */
public final class OathJoinGui extends GuiMenu {

    private final long oathId;

    private OathJoinGui(ServerRulesPlugin plugin, long oathId) {
        super(plugin, 27);
        this.oathId = oathId;
    }

    /** Ouvre le recapitulatif d'adhesion pour un serment deja valide par son code. */
    public static void open(ServerRulesPlugin plugin, Player viewer, long oathId) {
        new OathJoinGui(plugin, oathId).open(viewer);
    }

    @Override
    protected Component title(Player viewer) {
        return plugin.getLang().component(viewer, "gui.oaths.join-title", "id", String.valueOf(oathId));
    }

    @Override
    protected void build(Player viewer) {
        Lang lang = plugin.getLang();
        Oath oath = plugin.getOathManager().oath(oathId);
        if (oath == null) {
            set(13, Menus.item(Material.BARRIER,
                    lang.component(viewer, "oath.not-found", "id", String.valueOf(oathId)), List.of()));
            set(26, Menus.item(Material.ARROW, lang.component(viewer, "gui.common.back"), List.of()),
                    player -> closeThen(player, () -> OathGui.open(plugin, player)));
            fillEmpty(fillerItem(viewer));
            return;
        }

        set(13, recapItem(viewer, oath));
        set(11, Menus.item(Material.LIME_CONCRETE, lang.component(viewer, "gui.oaths.join-accept"), List.of()),
                player -> closeThen(player, () -> {
                    plugin.getOathManager().accept(player);
                    OathGui.open(plugin, player);
                }));
        set(15, Menus.item(Material.RED_CONCRETE, lang.component(viewer, "gui.oaths.join-refuse"), List.of()),
                player -> closeThen(player, () -> {
                    plugin.getOathManager().refuse(player);
                    OathGui.open(plugin, player);
                }));
        fillEmpty(fillerItem(viewer));
    }

    private ItemStack recapItem(Player viewer, Oath oath) {
        Lang lang = plugin.getLang();
        List<Component> lore = new ArrayList<>();
        lore.add(lang.component(viewer, "gui.oaths.entry-founder", "founder", oath.founderName()));
        lore.add(lang.component(viewer, "gui.oaths.entry-parties",
                "parties", partyNames(plugin.getOathManager().membersOf(oathId))));
        lore.add(lang.component(viewer, "gui.oaths.entry-promise", "promise", oath.promise()));
        lore.add(lang.component(viewer, "gui.oaths.detail-duration",
                "duration", plugin.getOathManager().getDisplay().durationText(viewer, oath.durationSeconds())));
        lore.add(lang.component(viewer, "gui.oaths.detail-sanction",
                "sanction", plugin.getOathManager().getDisplay().sanctionText(viewer, oath)));
        return Menus.item(Material.PAPER,
                lang.component(viewer, "gui.oaths.join-title", "id", String.valueOf(oathId)), lore);
    }

    private String partyNames(List<OathMember> members) {
        StringBuilder sb = new StringBuilder();
        for (OathMember member : members) {
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append(member.name());
        }
        return sb.toString();
    }
}

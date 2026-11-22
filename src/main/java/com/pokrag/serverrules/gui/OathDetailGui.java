package com.pokrag.serverrules.gui;

import com.pokrag.serverrules.ServerRulesPlugin;
import com.pokrag.serverrules.lang.Lang;
import com.pokrag.serverrules.oath.Oath;
import com.pokrag.serverrules.oath.OathMember;
import com.pokrag.serverrules.oath.OathStatus;
import com.pokrag.serverrules.util.TimeUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Detail d'un serment (/oath, section 11.6) : parties, promesse, duree, sanction, statut.
 * Le fondateur voit le code tant qu'il est valide.
 */
public final class OathDetailGui extends GuiMenu {

    private final long oathId;

    private OathDetailGui(ServerRulesPlugin plugin, long oathId) {
        super(plugin, 45);
        this.oathId = oathId;
    }

    /** Ouvre le detail d'un serment. */
    public static void open(ServerRulesPlugin plugin, Player viewer, long oathId) {
        new OathDetailGui(plugin, oathId).open(viewer);
    }

    @Override
    protected Component title(Player viewer) {
        return plugin.getLang().component(viewer, "gui.oaths.detail-title", "id", String.valueOf(oathId));
    }

    @Override
    protected void build(Player viewer) {
        Lang lang = plugin.getLang();
        Oath oath = plugin.getOathManager().oath(oathId);
        if (oath == null) {
            set(22, Menus.item(Material.BARRIER,
                    lang.component(viewer, "oath.not-found", "id", String.valueOf(oathId)), List.of()));
            set(40, Menus.item(Material.ARROW, lang.component(viewer, "gui.common.back"), List.of()),
                    player -> closeThen(player, () -> OathGui.open(plugin, player)));
            fillEmpty(fillerItem(viewer));
            return;
        }

        set(4, infoItem(viewer, oath));

        boolean cancellable = oath.status() == OathStatus.PROPOSE || oath.status() == OathStatus.ACTIF;
        if (cancellable) {
            boolean confirming = plugin.getOathManager().hasOtherCancelRequest(oathId, viewer.getUniqueId());
            set(20, Menus.item(confirming ? Material.RED_CONCRETE : Material.GRAY_CONCRETE,
                            lang.component(viewer, confirming
                                    ? "gui.oaths.cancel-confirm" : "gui.oaths.cancel-request"), List.of()),
                    player -> closeThen(player, () -> {
                        plugin.getOathManager().requestCancel(player, oathId);
                        open(plugin, player, oathId);
                    }));
        }

        if (canFileComplaint(viewer, oath)) {
            set(22, Menus.item(Material.WRITABLE_BOOK,
                            lang.component(viewer, "gui.oaths.complain"), List.of()),
                    player -> closeThen(player, () -> OathComplainGui.open(plugin, player, oathId)));
        }

        set(40, Menus.item(Material.ARROW, lang.component(viewer, "gui.common.back"), List.of()),
                player -> closeThen(player, () -> OathGui.open(plugin, player)));
        fillEmpty(fillerItem(viewer));
    }

    /** @return true si le joueur peut encore porter plainte au sujet de ce serment. */
    private boolean canFileComplaint(Player viewer, Oath oath) {
        int graceDays = plugin.getConfig().getInt("oath.complaint-grace-days", 2);
        return oath.status() != OathStatus.ANNULE
                && oath.isWithinGrace(System.currentTimeMillis(), graceDays)
                && !otherParties(viewer).isEmpty();
    }

    /** @return les parties du serment autres que le spectateur. */
    private List<OathMember> otherParties(Player viewer) {
        List<OathMember> result = new ArrayList<>();
        for (OathMember member : plugin.getOathManager().membersOf(oathId)) {
            if (!member.uuid().equals(viewer.getUniqueId())) {
                result.add(member);
            }
        }
        return result;
    }

    private ItemStack infoItem(Player viewer, Oath oath) {
        Lang lang = plugin.getLang();
        List<Component> lore = new ArrayList<>();
        lore.add(lang.component(viewer, "gui.oaths.entry-founder", "founder", oath.founderName()));
        lore.add(lang.component(viewer, "gui.oaths.entry-parties",
                "parties", partyNames(plugin.getOathManager().membersOf(oathId))));
        lore.add(lang.component(viewer, "gui.oaths.entry-promise", "promise", oath.promise()));
        lore.add(lang.component(viewer, "gui.oaths.detail-duration",
                "duration", TimeUtil.format(Duration.ofSeconds(oath.durationSeconds()))));
        lore.add(lang.component(viewer, "gui.oaths.detail-sanction",
                "sanction", plugin.getOathManager().getDisplay().sanctionText(viewer, oath)));
        lore.add(lang.component(viewer, "gui.oaths.entry-status",
                "status", plugin.getOathManager().getDisplay().statusText(viewer, oath.status())));
        if (oath.founder().equals(viewer.getUniqueId())
                && oath.status() == OathStatus.PROPOSE
                && oath.codeExpiresAt() > System.currentTimeMillis()) {
            lore.add(lang.component(viewer, "gui.oaths.detail-code", "code", oath.code()));
        }
        return Menus.item(Material.PAPER,
                lang.component(viewer, "gui.oaths.detail-title", "id", String.valueOf(oathId)), lore);
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

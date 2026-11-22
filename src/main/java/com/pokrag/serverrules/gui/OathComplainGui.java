package com.pokrag.serverrules.gui;

import com.pokrag.serverrules.ServerRulesPlugin;
import com.pokrag.serverrules.complaint.Citation;
import com.pokrag.serverrules.lang.Lang;
import com.pokrag.serverrules.oath.Oath;
import com.pokrag.serverrules.oath.OathManager;
import com.pokrag.serverrules.oath.OathMember;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Choix de l'accuse pour une plainte citant un serment (section 11.6).
 * Seules les autres parties du serment peuvent etre accusees.
 */
public final class OathComplainGui extends GuiMenu {

    private final long oathId;
    private final List<OathMember> others;

    private OathComplainGui(ServerRulesPlugin plugin, long oathId, List<OathMember> others) {
        super(plugin, 54);
        this.oathId = oathId;
        this.others = others;
    }

    /** Ouvre le choix de l'accuse parmi les autres parties du serment. */
    public static void open(ServerRulesPlugin plugin, Player viewer, long oathId) {
        Oath oath = plugin.getOathManager().oath(oathId);
        if (oath == null) {
            viewer.sendMessage(plugin.getLang().prefixed(viewer, "oath.not-found", "id", String.valueOf(oathId)));
            return;
        }
        List<OathMember> others = new ArrayList<>();
        for (OathMember member : plugin.getOathManager().membersOf(oathId)) {
            if (!member.uuid().equals(viewer.getUniqueId())) {
                others.add(member);
            }
        }
        if (others.isEmpty()) {
            viewer.sendMessage(plugin.getLang().prefixed(viewer, "gui.oaths.complain-empty"));
            return;
        }
        new OathComplainGui(plugin, oathId, others).open(viewer);
    }

    @Override
    protected Component title(Player viewer) {
        return plugin.getLang().component(viewer, "gui.oaths.complain-title", "id", String.valueOf(oathId));
    }

    @Override
    protected void build(Player viewer) {
        Lang lang = plugin.getLang();
        for (int i = 0; i < others.size() && i < PER_PAGE; i++) {
            OathMember member = others.get(i);
            set(i, Menus.head(Bukkit.getOfflinePlayer(member.uuid()),
                            lang.component(viewer, "gui.oaths.complain-entry", "player", member.name()),
                            List.of()),
                    player -> askDetails(player, member));
        }

        fillEmpty(fillerItem(viewer));
        navigation(viewer, page, 1);
        set(53, Menus.item(Material.ARROW, lang.component(viewer, "gui.common.back"), List.of()),
                player -> closeThen(player, () -> OathDetailGui.open(plugin, player, oathId)));
    }

    private void askDetails(Player viewer, OathMember accused) {
        Lang lang = plugin.getLang();
        closeThen(viewer, () -> {
            viewer.sendMessage(lang.prefixed(viewer, "gui.oaths.ask-details"));
            plugin.getChatInput().request(viewer,
                    text -> recap(viewer, accused, text.trim()),
                    () -> new OathComplainGui(plugin, oathId, others).open(viewer));
        });
    }

    private void recap(Player viewer, OathMember accused, String details) {
        Oath oath = plugin.getOathManager().oath(oathId);
        if (oath == null) {
            viewer.sendMessage(plugin.getLang().prefixed(viewer, "oath.not-found", "id", String.valueOf(oathId)));
            return;
        }
        ConfirmMenu.open(plugin, viewer, List.of(recapItem(viewer, accused, details)),
                player -> deposit(player, oath, accused, details),
                player -> new OathComplainGui(plugin, oathId, others).open(player));
    }

    private void deposit(Player player, Oath oath, OathMember accused, String details) {
        Lang lang = plugin.getLang();
        OathManager.ComplaintCheck check = plugin.getOathManager().canComplain(
                oathId, player.getUniqueId(), accused.uuid());
        switch (check) {
            case OK -> plugin.getComplaintManager().deposit(player, Bukkit.getOfflinePlayer(accused.uuid()),
                    Citation.ofOath(oath), details);
            case NOT_PARTY -> player.sendMessage(lang.prefixed(player, "oath.complaint.not-party"));
            default -> player.sendMessage(lang.prefixed(player, "oath.complaint.not-allowed"));
        }
    }

    private ItemStack recapItem(Player viewer, OathMember accused, String details) {
        Lang lang = plugin.getLang();
        List<Component> lore = new ArrayList<>();
        lore.add(lang.component(viewer, "gui.new-complaint.recap-accused", "accused", accused.name()));
        lore.add(lang.component(viewer, "gui.new-complaint.recap-citation",
                "citation", lang.raw(viewer, "citation.oath", "id", String.valueOf(oathId))));
        lore.add(lang.component(viewer, "gui.new-complaint.recap-details", "details", details));
        return Menus.head(Bukkit.getOfflinePlayer(accused.uuid()),
                lang.component(viewer, "gui.new-complaint.recap-title"), lore);
    }
}

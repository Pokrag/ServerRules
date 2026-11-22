package com.pokrag.serverrules.gui;

import com.pokrag.serverrules.ServerRulesPlugin;
import com.pokrag.serverrules.complaint.Complaint;
import com.pokrag.serverrules.complaint.ComplaintStatus;
import com.pokrag.serverrules.complaint.Conflicts;
import com.pokrag.serverrules.lang.Lang;
import com.pokrag.serverrules.util.TimeUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * GUI des plaintes pour les juges (/judge, section 11.2).
 * Par defaut seules les plaintes en attente sont affichees ; un bouton bascule vers les traitees.
 */
public final class ComplaintsGui extends GuiMenu {

    private final List<Complaint> all;
    private boolean handled;

    private ComplaintsGui(ServerRulesPlugin plugin, List<Complaint> all, boolean handled) {
        super(plugin, 54);
        this.all = all;
        this.handled = handled;
    }

    /** Ouvre la GUI des plaintes en attente. */
    public static void open(ServerRulesPlugin plugin, Player viewer) {
        open(plugin, viewer, false);
    }

    /** Ouvre la GUI des plaintes ; {@code handled} affiche les plaintes deja traitees. */
    public static void open(ServerRulesPlugin plugin, Player viewer, boolean handled) {
        plugin.getComplaintManager().loadComplaints(null, list -> {
            List<Complaint> visible = new ArrayList<>();
            for (Complaint complaint : list) {
                if (!Conflicts.isInvolved(viewer, complaint, plugin)) {
                    visible.add(complaint);
                }
            }
            new ComplaintsGui(plugin, visible, handled).open(viewer);
        });
    }

    @Override
    protected Component title(Player viewer) {
        return plugin.getLang().component(viewer, "gui.complaints.title");
    }

    @Override
    protected void build(Player viewer) {
        Lang lang = plugin.getLang();

        List<Complaint> shown = new ArrayList<>();
        for (Complaint complaint : all) {
            boolean isHandled = complaint.status() != ComplaintStatus.EN_ATTENTE;
            if (isHandled == handled) {
                shown.add(complaint);
            }
        }

        int pages = Math.max(1, (shown.size() + PER_PAGE - 1) / PER_PAGE);
        if (page >= pages) {
            page = pages - 1;
        }
        int start = page * PER_PAGE;

        for (int i = 0; i < PER_PAGE && start + i < shown.size(); i++) {
            Complaint complaint = shown.get(start + i);
            List<Component> lore = new ArrayList<>();
            lore.add(lang.component(viewer, "gui.complaints.entry-plaintiff",
                    "plaintiff", complaint.plaintiffName()));
            lore.add(lang.component(viewer, "gui.complaints.entry-citation",
                    "citation", plugin.getComplaintDisplay().citationText(viewer, complaint.citation())));
            lore.add(lang.component(viewer, "gui.complaints.entry-date",
                    "date", TimeUtil.formatDate(complaint.date())));
            lore.add(lang.component(viewer, "gui.complaints.entry-status",
                    "status", plugin.getComplaintDisplay().statusText(viewer, complaint.status())));

            set(i, Menus.head(Bukkit.getOfflinePlayer(complaint.accused()),
                            lang.component(viewer, "gui.complaints.entry-name",
                                    "id", String.valueOf(complaint.id()),
                                    "accused", complaint.accusedName()),
                            lore),
                    player -> closeThen(player, () -> ComplaintDetailGui.open(plugin, player, complaint.id())));
        }

        if (shown.isEmpty()) {
            set(22, Menus.item(Material.BARRIER, lang.component(viewer, "gui.complaints.empty"), List.of()));
        }

        fillEmpty(fillerItem(viewer));
        navigation(viewer, page, pages);

        // Bascule entre plaintes en attente et plaintes traitees.
        set(52, Menus.item(handled ? Material.LIME_DYE : Material.GUNPOWDER,
                        lang.component(viewer, handled ? "gui.complaints.show-pending" : "gui.complaints.show-handled"),
                        List.of()),
                player -> {
                    handled = !handled;
                    page = 0;
                    refresh(player);
                });
    }
}

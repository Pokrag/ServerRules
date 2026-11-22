package com.pokrag.serverrules.gui;

import com.pokrag.serverrules.ServerRulesPlugin;
import com.pokrag.serverrules.complaint.Complaint;
import com.pokrag.serverrules.lang.Lang;
import com.pokrag.serverrules.util.TimeUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * GUI des plaintes du joueur (/plaint, section 11.4) : lecture seule.
 * Le joueur ne voit que les plaintes qu'il a lui-meme deposees.
 */
public final class PlayerComplaintsGui extends GuiMenu {

    private final List<Complaint> mine;

    private PlayerComplaintsGui(ServerRulesPlugin plugin, List<Complaint> mine) {
        super(plugin, 54);
        this.mine = mine;
    }

    /** Ouvre la GUI apres avoir charge les plaintes deposees par le joueur. */
    public static void open(ServerRulesPlugin plugin, Player viewer) {
        plugin.getComplaintManager().loadComplaints(null, list -> {
            List<Complaint> mine = new ArrayList<>();
            for (Complaint complaint : list) {
                if (complaint.plaintiff().equals(viewer.getUniqueId())) {
                    mine.add(complaint);
                }
            }
            new PlayerComplaintsGui(plugin, mine).open(viewer);
        });
    }

    @Override
    protected Component title(Player viewer) {
        return plugin.getLang().component(viewer, "gui.player-complaints.title");
    }

    @Override
    protected void build(Player viewer) {
        Lang lang = plugin.getLang();
        int pages = Math.max(1, (mine.size() + PER_PAGE - 1) / PER_PAGE);
        if (page >= pages) {
            page = pages - 1;
        }
        int start = page * PER_PAGE;

        for (int i = 0; i < PER_PAGE && start + i < mine.size(); i++) {
            Complaint complaint = mine.get(start + i);
            List<Component> lore = new ArrayList<>();
            lore.add(lang.component(viewer, "gui.player-complaints.entry-citation",
                    "citation", plugin.getComplaintDisplay().citationText(viewer, complaint.citation())));
            lore.add(lang.component(viewer, "gui.player-complaints.entry-date",
                    "date", TimeUtil.formatDate(complaint.date())));
            lore.add(lang.component(viewer, "gui.player-complaints.entry-status",
                    "status", plugin.getComplaintDisplay().statusText(viewer, complaint.status())));

            set(i, Menus.head(Bukkit.getOfflinePlayer(complaint.accused()),
                    lang.component(viewer, "gui.player-complaints.entry-name",
                            "id", String.valueOf(complaint.id()),
                            "accused", complaint.accusedName()),
                    lore));
        }

        if (mine.isEmpty()) {
            set(22, Menus.item(Material.BARRIER, lang.component(viewer, "gui.player-complaints.empty"), List.of()));
        }

        fillEmpty(fillerItem(viewer));
        navigation(viewer, page, pages);

        // Bouton de creation d'une nouvelle plainte.
        set(53, Menus.item(Material.WRITABLE_BOOK,
                        lang.component(viewer, "gui.player-complaints.new"), List.of()),
                player -> closeThen(player, () -> NewComplaintGui.start(plugin, player)));
    }
}

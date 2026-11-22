package com.pokrag.serverrules.gui;

import com.pokrag.serverrules.ServerRulesPlugin;
import com.pokrag.serverrules.complaint.Citation;
import com.pokrag.serverrules.lang.Lang;
import com.pokrag.serverrules.oath.Oath;
import com.pokrag.serverrules.oath.OathManager;
import com.pokrag.serverrules.rule.Rule;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Parcours « Nouvelle plainte » (section 11.4, bouton de la GUI /plaint).
 *
 * 1. saisie du nom du joueur accuse dans le chat ;
 * 2. choix de ce qui est cite : une regle ou un serment commun ;
 * 3. saisie des details dans le chat ;
 * 4. recapitulatif avec Envoyer et Annuler.
 */
public final class NewComplaintGui extends GuiMenu {

    private final OfflinePlayer accused;
    private boolean showOaths;

    private NewComplaintGui(ServerRulesPlugin plugin, OfflinePlayer accused, boolean showOaths) {
        super(plugin, 54);
        this.accused = accused;
        this.showOaths = showOaths;
    }

    /** Etape 1 : demande le nom du joueur accuse, puis ouvre le choix de la citation. */
    public static void start(ServerRulesPlugin plugin, Player viewer) {
        Lang lang = plugin.getLang();
        viewer.sendMessage(lang.prefixed(viewer, "gui.player-complaints.ask-accused"));
        plugin.getChatInput().request(viewer, name -> {
            OfflinePlayer target = Menus.resolvePlayer(name.trim());
            if (target == null || target.getUniqueId() == null) {
                viewer.sendMessage(lang.prefixed(viewer, "complaint.player-not-found", "player", name.trim()));
                start(plugin, viewer);
                return;
            }
            if (target.getUniqueId().equals(viewer.getUniqueId())) {
                viewer.sendMessage(lang.prefixed(viewer, "complaint.self"));
                start(plugin, viewer);
                return;
            }
            new NewComplaintGui(plugin, target, false).open(viewer);
        }, () -> PlayerComplaintsGui.open(plugin, viewer));
    }

    @Override
    protected Component title(Player viewer) {
        return plugin.getLang().component(viewer, "gui.new-complaint.title",
                "accused", accusedName());
    }

    @Override
    protected void build(Player viewer) {
        Lang lang = plugin.getLang();

        if (showOaths) {
            buildOaths(viewer);
        } else {
            buildRules(viewer);
        }

        fillEmpty(fillerItem(viewer));

        // Deux boutons pour choisir ce qui est cite : une regle ou un serment.
        set(52, Menus.item(Material.WRITABLE_BOOK,
                        lang.component(viewer, "gui.new-complaint.rules-button"), List.of(), !showOaths),
                player -> {
                    showOaths = false;
                    page = 0;
                    refresh(player);
                });
        set(53, Menus.item(Material.PAPER,
                        lang.component(viewer, "gui.new-complaint.oaths-button"), List.of(), showOaths),
                player -> {
                    showOaths = true;
                    page = 0;
                    refresh(player);
                });
    }

    private void buildRules(Player viewer) {
        Lang lang = plugin.getLang();
        List<Rule> rules = plugin.getRulesManager().rules();
        int pages = Math.max(1, (rules.size() + PER_PAGE - 1) / PER_PAGE);
        if (page >= pages) {
            page = pages - 1;
        }
        int start = page * PER_PAGE;

        for (int i = 0; i < PER_PAGE && start + i < rules.size(); i++) {
            int number = start + i + 1;
            Rule rule = rules.get(start + i);
            Citation citation = Citation.ofRule(rule, number);
            List<Component> lore = new ArrayList<>();
            if (!rule.description().isBlank()) {
                lore.add(lang.component(viewer, "gui.rules.entry-description", "description", rule.description()));
            }
            lore.add(lang.component(viewer, "gui.rules.entry-sanction",
                    "sanction", plugin.getDisplay().sanctionText(viewer, rule.sanction())));

            set(i, Menus.item(Material.WRITABLE_BOOK,
                            lang.component(viewer, "gui.new-complaint.entry-rule",
                                    "number", String.valueOf(number), "name", rule.name()),
                            lore),
                    player -> askDetails(player, citation));
        }

        navigation(viewer, page, pages);
    }

    private void buildOaths(Player viewer) {
        Lang lang = plugin.getLang();
        List<Oath> oaths = eligibleOaths(viewer);
        int pages = Math.max(1, (oaths.size() + PER_PAGE - 1) / PER_PAGE);
        if (page >= pages) {
            page = pages - 1;
        }
        int start = page * PER_PAGE;

        for (int i = 0; i < PER_PAGE && start + i < oaths.size(); i++) {
            Oath oath = oaths.get(start + i);
            Citation citation = Citation.ofOath(oath);
            List<Component> lore = new ArrayList<>();
            lore.add(lang.component(viewer, "gui.oaths.entry-promise", "promise", oath.promise()));
            lore.add(lang.component(viewer, "gui.oaths.entry-status",
                    "status", plugin.getOathManager().getDisplay().statusText(viewer, oath.status())));

            set(i, Menus.item(Material.PAPER,
                            lang.component(viewer, "gui.new-complaint.entry-oath", "id", String.valueOf(oath.id())),
                            lore),
                    player -> askDetails(player, citation));
        }

        if (oaths.isEmpty()) {
            set(22, Menus.item(Material.BARRIER,
                    lang.component(viewer, "gui.new-complaint.no-oath"), List.of()));
        }

        navigation(viewer, page, pages);
    }

    /** Etape 3 et 4 : saisie des details dans le chat, puis recapitulatif avant envoi. */
    private void askDetails(Player viewer, Citation citation) {
        Lang lang = plugin.getLang();
        closeThen(viewer, () -> {
            viewer.sendMessage(lang.prefixed(viewer, "gui.new-complaint.ask-details"));
            plugin.getChatInput().request(viewer,
                    text -> recap(viewer, citation, text.trim()),
                    () -> new NewComplaintGui(plugin, accused, showOaths).open(viewer));
        });
    }

    private void recap(Player viewer, Citation citation, String details) {
        ConfirmMenu.open(plugin, viewer, List.of(recapItem(viewer, citation, details)),
                player -> plugin.getComplaintManager().deposit(player, accused, citation, details),
                player -> new NewComplaintGui(plugin, accused, showOaths).open(player));
    }

    private ItemStack recapItem(Player viewer, Citation citation, String details) {
        Lang lang = plugin.getLang();
        List<Component> lore = new ArrayList<>();
        lore.add(lang.component(viewer, "gui.new-complaint.recap-accused", "accused", accusedName()));
        lore.add(lang.component(viewer, "gui.new-complaint.recap-citation",
                "citation", plugin.getComplaintDisplay().citationText(viewer, citation)));
        lore.add(lang.component(viewer, "gui.new-complaint.recap-details", "details", details));
        return Menus.head(accused, lang.component(viewer, "gui.new-complaint.recap-title"), lore);
    }

    /** @return les serments communs au plaignant et a l'accuse, citables (actifs ou expires depuis peu). */
    private List<Oath> eligibleOaths(Player viewer) {
        List<Oath> result = new ArrayList<>();
        OathManager oaths = plugin.getOathManager();
        for (Oath oath : oaths.oathsOf(viewer.getUniqueId())) {
            if (oaths.canComplain(oath.id(), viewer.getUniqueId(), accused.getUniqueId())
                    == OathManager.ComplaintCheck.OK) {
                result.add(oath);
            }
        }
        return result;
    }

    private String accusedName() {
        String name = accused.getName();
        return name != null ? name : accused.getUniqueId().toString();
    }
}

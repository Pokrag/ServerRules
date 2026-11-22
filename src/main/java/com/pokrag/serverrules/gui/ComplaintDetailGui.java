package com.pokrag.serverrules.gui;

import com.pokrag.serverrules.ServerRulesPlugin;
import com.pokrag.serverrules.complaint.Citation;
import com.pokrag.serverrules.complaint.CitationType;
import com.pokrag.serverrules.complaint.CitedSanction;
import com.pokrag.serverrules.complaint.Complaint;
import com.pokrag.serverrules.complaint.ComplaintStatus;
import com.pokrag.serverrules.complaint.Conflicts;
import com.pokrag.serverrules.complaint.SanctionKind;
import com.pokrag.serverrules.lang.Lang;
import com.pokrag.serverrules.oath.OathMember;
import com.pokrag.serverrules.util.TimeUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Detail d'une plainte avec ses actions (section 11.3).
 * Chaque bouton verifie la permission et le conflit d'interets au clic.
 */
public final class ComplaintDetailGui extends GuiMenu {

    private final Complaint complaint;

    private ComplaintDetailGui(ServerRulesPlugin plugin, Complaint complaint) {
        super(plugin, 45);
        this.complaint = complaint;
    }

    /** Ouvre le detail d'une plainte, apres verification du conflit d'interets. */
    public static void open(ServerRulesPlugin plugin, Player viewer, long id) {
        plugin.getComplaintManager().loadComplaint(id, found -> {
            if (found.isEmpty()) {
                viewer.sendMessage(plugin.getLang().prefixed(viewer, "judge.not-found", "id", String.valueOf(id)));
                return;
            }
            Complaint complaint = found.get();
            if (Conflicts.isInvolved(viewer, complaint, plugin)) {
                viewer.sendMessage(plugin.getLang().prefixed(viewer, "judge.conflict", "id", String.valueOf(id)));
                return;
            }
            new ComplaintDetailGui(plugin, complaint).open(viewer);
        });
    }

    @Override
    protected Component title(Player viewer) {
        return plugin.getLang().component(viewer, "gui.complaint-detail.title",
                "id", String.valueOf(complaint.id()));
    }

    @Override
    protected void build(Player viewer) {
        Lang lang = plugin.getLang();
        Citation citation = complaint.citation();

        set(4, summaryItem(viewer));

        boolean pending = complaint.status() == ComplaintStatus.EN_ATTENTE;
        if (pending) {
            set(20, Menus.item(Material.LIME_CONCRETE,
                            lang.component(viewer, "gui.complaint-detail.apply"), List.of()),
                    player -> closeThen(player, () -> confirm(player, null)));

            if (plugin.getComplaintDisplay().isRuleCitation(citation)) {
                set(21, Menus.item(Material.ANVIL,
                                lang.component(viewer, "gui.complaint-detail.modulate"),
                                List.of(lang.component(viewer, "gui.complaint-detail.modulate-hint",
                                        "min", trim(plugin.getComplaintManager().modulationRange(viewer)[0]),
                                        "max", trim(plugin.getComplaintManager().modulationRange(viewer)[1])))),
                        player -> closeThen(player, () -> askModulation(player)));
            }

            set(22, Menus.item(Material.GRAY_CONCRETE,
                            lang.component(viewer, "gui.complaint-detail.dismiss"), List.of()),
                    player -> closeThen(player, () -> plugin.getComplaintManager().dismiss(player, complaint.id())));
        }

        if (citation.hasCustom()
                && complaint.status() == ComplaintStatus.SANCTION_A_EXECUTER) {
            set(23, Menus.item(Material.GOLD_INGOT,
                            lang.component(viewer, "gui.complaint-detail.mark-done"), List.of()),
                    player -> closeThen(player, () -> plugin.getComplaintManager().markDone(player, complaint.id())));
        }

        if (viewer.hasPermission("serverrules.superjudge")) {
            set(24, Menus.item(Material.NAME_TAG,
                            lang.component(viewer, "gui.complaint-detail.change-rule"), List.of()),
                    player -> closeThen(player, () -> askRule(player)));
        }

        set(40, Menus.item(Material.ARROW, lang.component(viewer, "gui.common.back"), List.of()),
                player -> closeThen(player, () -> ComplaintsGui.open(plugin, player)));

        fillEmpty(fillerItem(viewer));
    }

    /** Objet d'information recapitulant la plainte. */
    private ItemStack summaryItem(Player viewer) {
        Lang lang = plugin.getLang();
        Citation citation = complaint.citation();
        List<Component> lore = new ArrayList<>();
        lore.add(lang.component(viewer, "gui.complaint-detail.info-plaintiff",
                "plaintiff", complaint.plaintiffName()));
        lore.add(lang.component(viewer, "gui.complaint-detail.info-citation",
                "citation", plugin.getComplaintDisplay().citationText(viewer, citation)));
        if (citation.type() == CitationType.OATH) {
            lore.add(lang.component(viewer, "gui.complaint-detail.info-parties",
                    "parties", partyNames(citation.refId())));
        }
        lore.add(lang.component(viewer, "gui.complaint-detail.info-sanction",
                "sanction", plugin.getComplaintDisplay().sanctionText(viewer, citation)));
        lore.add(lang.component(viewer, "gui.complaint-detail.info-details",
                "details", complaint.details()));
        lore.add(lang.component(viewer, "gui.complaint-detail.info-date",
                "date", TimeUtil.formatDate(complaint.date())));
        lore.add(lang.component(viewer, "gui.complaint-detail.info-status",
                "status", plugin.getComplaintDisplay().statusText(viewer, complaint.status())));
        return Menus.head(Bukkit.getOfflinePlayer(complaint.accused()),
                lang.component(viewer, "gui.complaint-detail.info-accused",
                        "accused", complaint.accusedName()),
                lore);
    }

    /** Ouvre le menu de confirmation avant d'appliquer la sanction. */
    private void confirm(Player player, String value) {
        ConfirmMenu.open(plugin, player, List.of(summaryItem(player)),
                p -> plugin.getComplaintManager().applySanction(p, complaint.id(), value),
                p -> open(plugin, player, complaint.id()));
    }

    /** Demande la valeur modulee dans le chat, la valide, puis passe par la confirmation. */
    private void askModulation(Player player) {
        Lang lang = plugin.getLang();
        player.sendMessage(lang.prefixed(player, "gui.complaint-detail.ask-value"));
        plugin.getChatInput().request(player, text -> {
            if (!validModulation(player, text)) {
                double[] range = plugin.getComplaintManager().modulationRange(player);
                player.sendMessage(lang.prefixed(player, "judge.out-of-range",
                        "min", trim(range[0]) + "%", "max", trim(range[1]) + "%"));
                askModulation(player);
                return;
            }
            confirm(player, text.trim());
        }, () -> open(plugin, player, complaint.id()));
    }

    /** @return true si la valeur modulee est valide et dans la fourchette du role. */
    private boolean validModulation(Player player, String text) {
        Citation citation = complaint.citation();
        CitedSanction single = citation.single();
        if (single == null || !single.modulable()) {
            return false;
        }
        double base = single.kind() == SanctionKind.FINE ? single.amount() : single.durationSeconds();
        double chosen;
        if (single.kind() == SanctionKind.FINE) {
            try {
                chosen = Double.parseDouble(text.trim().replace(',', '.'));
            } catch (NumberFormatException e) {
                return false;
            }
            if (chosen <= 0) {
                return false;
            }
        } else {
            Duration duration = TimeUtil.parse(text);
            if (duration == null) {
                return false;
            }
            chosen = duration.toSeconds();
        }
        double[] range = plugin.getComplaintManager().modulationRange(player);
        return base > 0
                && chosen >= base * range[0] / 100.0
                && chosen <= base * range[1] / 100.0;
    }

    /** Demande le numero de la nouvelle regle citee dans le chat (superjuge). */
    private void askRule(Player player) {
        Lang lang = plugin.getLang();
        player.sendMessage(lang.prefixed(player, "gui.complaint-detail.ask-rule"));
        plugin.getChatInput().request(player, text -> {
            int number;
            try {
                number = Integer.parseInt(text.trim());
            } catch (NumberFormatException e) {
                player.sendMessage(lang.prefixed(player, "complaint.rule-not-found", "number", text.trim()));
                askRule(player);
                return;
            }
            if (plugin.getRulesManager().byNumber(number) == null) {
                player.sendMessage(lang.prefixed(player, "complaint.rule-not-found", "number", String.valueOf(number)));
                askRule(player);
                return;
            }
            plugin.getComplaintManager().changeCitation(player, complaint.id(), number);
            Bukkit.getScheduler().runTaskLater(plugin,
                    () -> open(plugin, player, complaint.id()), 10L);
        }, () -> open(plugin, player, complaint.id()));
    }

    private String partyNames(long oathId) {
        StringBuilder sb = new StringBuilder();
        for (OathMember member : plugin.getOathManager().membersOf(oathId)) {
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append(member.name());
        }
        return sb.toString();
    }

    /** @return un nombre sans decimale inutile (40.0 -> "40"). */
    private static String trim(double value) {
        return value == Math.floor(value) ? String.valueOf((long) value) : String.valueOf(value);
    }
}

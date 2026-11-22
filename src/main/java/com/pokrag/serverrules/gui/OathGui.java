package com.pokrag.serverrules.gui;

import com.pokrag.serverrules.ServerRulesPlugin;
import com.pokrag.serverrules.lang.Lang;
import com.pokrag.serverrules.oath.Oath;
import com.pokrag.serverrules.oath.OathMember;
import com.pokrag.serverrules.oath.OathStatus;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * GUI des serments (/oath, section 11.6).
 *
 * - Liste principale : les serments actifs du joueur et ses propositions en attente.
 * - Boutons : rejoindre un serment, creer un serment, serments expires.
 * - Un mode lecture seule affiche tous les serments termines du joueur.
 */
public final class OathGui extends GuiMenu {

    /** Serments termines, affiches dans la liste des serments expires. */
    private static final Set<OathStatus> FINISHED = EnumSet.of(
            OathStatus.PROPOSITION_EXPIREE, OathStatus.EXPIRE, OathStatus.ANNULE,
            OathStatus.NON_RESPECTE, OathStatus.TRAITE);

    private final List<Oath> oaths;
    private final boolean finished;

    private OathGui(ServerRulesPlugin plugin, List<Oath> oaths, boolean finished) {
        super(plugin, 54);
        this.oaths = oaths;
        this.finished = finished;
    }

    /** Ouvre la liste principale des serments du joueur. */
    public static void open(ServerRulesPlugin plugin, Player viewer) {
        new OathGui(plugin, mainOaths(plugin, viewer), false).open(viewer);
    }

    /** Ouvre la liste des serments termines du joueur. */
    public static void openFinished(ServerRulesPlugin plugin, Player viewer) {
        new OathGui(plugin, finishedOaths(plugin, viewer), true).open(viewer);
    }

    private static List<Oath> mainOaths(ServerRulesPlugin plugin, Player viewer) {
        List<Oath> result = new ArrayList<>();
        for (Oath oath : plugin.getOathManager().oathsOf(viewer.getUniqueId())) {
            if (oath.status() == OathStatus.ACTIF
                    || (oath.status() == OathStatus.PROPOSE && oath.founder().equals(viewer.getUniqueId()))) {
                result.add(oath);
            }
        }
        return result;
    }

    private static List<Oath> finishedOaths(ServerRulesPlugin plugin, Player viewer) {
        List<Oath> result = new ArrayList<>();
        for (Oath oath : plugin.getOathManager().oathsOf(viewer.getUniqueId())) {
            if (FINISHED.contains(oath.status())) {
                result.add(oath);
            }
        }
        return result;
    }

    @Override
    protected Component title(Player viewer) {
        return plugin.getLang().component(viewer, finished ? "gui.oaths.expired-title" : "gui.oaths.title");
    }

    @Override
    protected void build(Player viewer) {
        Lang lang = plugin.getLang();
        int pages = Math.max(1, (oaths.size() + PER_PAGE - 1) / PER_PAGE);
        if (page >= pages) {
            page = pages - 1;
        }
        int start = page * PER_PAGE;

        for (int i = 0; i < PER_PAGE && start + i < oaths.size(); i++) {
            Oath oath = oaths.get(start + i);
            List<Component> lore = new ArrayList<>();
            lore.add(lang.component(viewer, "gui.oaths.entry-founder", "founder", oath.founderName()));
            lore.add(lang.component(viewer, "gui.oaths.entry-parties",
                    "parties", partyNames(plugin.getOathManager().membersOf(oath.id()))));
            lore.add(lang.component(viewer, "gui.oaths.entry-promise", "promise", oath.promise()));
            lore.add(lang.component(viewer, "gui.oaths.entry-status",
                    "status", plugin.getOathManager().getDisplay().statusText(viewer, oath.status())));

            set(i, Menus.item(statusMaterial(oath.status()),
                            lang.component(viewer, "gui.oaths.entry-name", "id", String.valueOf(oath.id())),
                            lore),
                    player -> closeThen(player, () -> OathDetailGui.open(plugin, player, oath.id())));
        }

        if (oaths.isEmpty()) {
            set(22, Menus.item(Material.BARRIER,
                    lang.component(viewer, finished ? "gui.oaths.expired-empty" : "gui.oaths.empty"), List.of()));
        }

        fillEmpty(fillerItem(viewer));
        navigation(viewer, page, pages);

        if (finished) {
            set(53, Menus.item(Material.ARROW, lang.component(viewer, "gui.common.back"), List.of()),
                    player -> closeThen(player, () -> open(plugin, player)));
            return;
        }

        set(51, Menus.item(Material.NAME_TAG, lang.component(viewer, "gui.oaths.join"), List.of()),
                player -> closeThen(player, () -> askCode(player)));
        set(52, Menus.item(Material.WRITABLE_BOOK, lang.component(viewer, "gui.oaths.create"), List.of()),
                player -> closeThen(player, () -> OathCreateGui.start(plugin, player)));
        set(53, Menus.item(Material.CLOCK, lang.component(viewer, "gui.oaths.expired"), List.of()),
                player -> closeThen(player, () -> openFinished(plugin, player)));
    }

    /** Demande le code d'un serment, puis ouvre le recapitulatif Accepter / Refuser. */
    private void askCode(Player viewer) {
        Lang lang = plugin.getLang();
        viewer.sendMessage(lang.prefixed(viewer, "gui.oaths.ask-code"));
        plugin.getChatInput().request(viewer, code -> {
            Oath found = plugin.getOathManager().requestJoin(viewer, code.trim());
            if (found == null) {
                // Message d'erreur deja envoye par requestJoin : on redemande le code.
                askCode(viewer);
                return;
            }
            OathJoinGui.open(plugin, viewer, found.id());
        }, () -> open(plugin, viewer));
    }

    /** @return la couleur de l'objet selon le statut du serment. */
    private static Material statusMaterial(OathStatus status) {
        return switch (status) {
            case ACTIF -> Material.LIME_DYE;
            case PROPOSE -> Material.YELLOW_DYE;
            case NON_RESPECTE -> Material.ORANGE_DYE;
            case TRAITE -> Material.GREEN_DYE;
            case ANNULE -> Material.RED_DYE;
            default -> Material.GRAY_DYE;
        };
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

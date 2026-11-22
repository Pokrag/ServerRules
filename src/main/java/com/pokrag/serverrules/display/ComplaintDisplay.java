package com.pokrag.serverrules.display;

import com.pokrag.serverrules.ServerRulesPlugin;
import com.pokrag.serverrules.complaint.Citation;
import com.pokrag.serverrules.complaint.CitationType;
import com.pokrag.serverrules.complaint.CitedSanction;
import com.pokrag.serverrules.complaint.Complaint;
import com.pokrag.serverrules.complaint.ComplaintStatus;
import com.pokrag.serverrules.lang.Lang;
import com.pokrag.serverrules.util.TimeUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Construit et envoie les messages lies aux plaintes (liste et detail). */
public final class ComplaintDisplay {

    private final ServerRulesPlugin plugin;
    private final Lang lang;

    public ComplaintDisplay(ServerRulesPlugin plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLang();
    }

    /** Affiche une liste de plaintes dans le chat. */
    public void list(CommandSender viewer, List<Complaint> complaints) {
        List<Component> lines = new ArrayList<>();
        lines.add(lang.component(viewer, "complaint.list.header"));
        if (complaints.isEmpty()) {
            lines.add(lang.component(viewer, "complaint.list.empty"));
        } else {
            for (Complaint complaint : complaints) {
                lines.add(lang.component(viewer, "complaint.list.row",
                        "id", String.valueOf(complaint.id()),
                        "plaintiff", complaint.plaintiffName(),
                        "accused", complaint.accusedName(),
                        "citation", citationText(viewer, complaint.citation()),
                        "date", TimeUtil.formatDate(complaint.date()),
                        "status", statusText(viewer, complaint.status())));
            }
        }
        viewer.sendMessage(join(lines));
    }

    /** Affiche le detail d'une plainte. */
    public void detail(CommandSender viewer, Complaint complaint) {
        List<Component> lines = new ArrayList<>();
        lines.add(lang.component(viewer, "complaint.detail.header", "id", String.valueOf(complaint.id())));
        lines.add(lang.component(viewer, "complaint.detail.plaintiff", "plaintiff", complaint.plaintiffName()));
        lines.add(lang.component(viewer, "complaint.detail.accused", "accused", complaint.accusedName()));
        lines.add(lang.component(viewer, "complaint.detail.citation", "citation", citationText(viewer, complaint.citation())));
        lines.add(lang.component(viewer, "complaint.detail.sanction", "sanction", sanctionText(viewer, complaint.citation())));
        lines.add(lang.component(viewer, "complaint.detail.details", "details", complaint.details()));
        lines.add(lang.component(viewer, "complaint.detail.date", "date", TimeUtil.formatDate(complaint.date())));
        lines.add(lang.component(viewer, "complaint.detail.status", "status", statusText(viewer, complaint.status())));
        viewer.sendMessage(join(lines));
    }

    /** @return le texte de la reference citee (regle ou serment). */
    public String citationText(CommandSender viewer, Citation citation) {
        if (citation.type() == CitationType.OATH) {
            return lang.raw(viewer, "citation.oath", "id", String.valueOf(citation.refId()));
        }
        return lang.raw(viewer, "citation.rule",
                "number", String.valueOf(citation.number()),
                "name", citation.name());
    }

    /** @return le texte des sanctions citees (une regle peut en prevoir plusieurs). */
    public String sanctionText(CommandSender viewer, Citation citation) {
        List<String> parts = new ArrayList<>();
        for (CitedSanction sanction : citation.sanctions()) {
            parts.add(sanctionText(viewer, sanction));
        }
        return String.join("<gray>, ", parts);
    }

    /** @return le texte d'une sanction figee. */
    public String sanctionText(CommandSender viewer, CitedSanction sanction) {
        return switch (sanction.kind()) {
            case FINE -> lang.raw(viewer, "rules.sanction.fine",
                    "amount", plugin.getEconomy().format(sanction.amount()));
            case TEMP_BAN -> lang.raw(viewer, "rules.sanction.temp-ban",
                    "duration", TimeUtil.format(Duration.ofSeconds(sanction.durationSeconds())));
            case CUSTOM -> sanction.customText();
        };
    }

    /** @return le texte du statut d'une plainte. */
    public String statusText(CommandSender viewer, ComplaintStatus status) {
        return lang.raw(viewer, "complaint.status." + status.langKey());
    }

    /**
     * @return true si la citation peut etre modulee : une regle avec une seule sanction
     *         modulable (amende ou duree de ban). Plusieurs sanctions separentes sont
     *         appliquees telles quelles.
     */
    public boolean isRuleCitation(Citation citation) {
        CitedSanction single = citation.single();
        return citation.type() == CitationType.RULE && single != null && single.modulable();
    }

    private Component join(List<Component> lines) {
        Component result = Component.empty();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                result = result.append(Component.newline());
            }
            result = result.append(lines.get(i));
        }
        return result;
    }
}

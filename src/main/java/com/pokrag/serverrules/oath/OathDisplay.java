package com.pokrag.serverrules.oath;

import com.pokrag.serverrules.ServerRulesPlugin;
import com.pokrag.serverrules.complaint.SanctionKind;
import com.pokrag.serverrules.lang.Lang;
import com.pokrag.serverrules.util.TimeUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Construit et envoie les messages lies aux serments. */
public final class OathDisplay {

    private final ServerRulesPlugin plugin;
    private final Lang lang;

    public OathDisplay(ServerRulesPlugin plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLang();
    }

    /** Message de creation : confirmation, code copiable et duree de validite. */
    public void created(CommandSender founder, Oath oath) {
        Duration validity = Duration.ofMillis(Math.max(0, oath.codeExpiresAt() - System.currentTimeMillis()));
        founder.sendMessage(lang.prefixed(founder, "oath.create.created", "id", String.valueOf(oath.id())));
        founder.sendMessage(lang.component(founder, "oath.create.code", "code", oath.code()));
        founder.sendMessage(lang.component(founder, "oath.create.code-validity",
                "time", TimeUtil.format(validity)));
    }

    /** Affiche le recapitulatif complet d'un serment. */
    public void recap(CommandSender viewer, Oath oath, List<OathMember> members) {
        viewer.sendMessage(join(recapLines(viewer, oath, members)));
    }

    /** Recapitulatif d'une demande d'adhesion, avec les boutons Accepter / Refuser. */
    public void joinRecap(CommandSender viewer, Oath oath, List<OathMember> members) {
        List<Component> lines = recapLines(viewer, oath, members);
        lines.add(lang.component(viewer, "oath.join.prompt",
                "accept", "/" + commandName() + " accept",
                "refuse", "/" + commandName() + " refuse"));
        viewer.sendMessage(join(lines));
    }

    /** @return le texte lisible d'une sanction de serment. */
    public String sanctionText(CommandSender viewer, Oath oath) {
        return switch (oath.sanctionKind()) {
            case FINE -> lang.raw(viewer, "rules.sanction.fine",
                    "amount", plugin.getEconomy().format(oath.amount()));
            case TEMP_BAN -> lang.raw(viewer, "rules.sanction.temp-ban",
                    "duration", TimeUtil.format(Duration.ofSeconds(oath.sanctionDurationSeconds())));
            case CUSTOM -> oath.customText();
        };
    }

    /** @return le texte du statut d'un serment. */
    public String statusText(CommandSender viewer, OathStatus status) {
        return lang.raw(viewer, "oath.status." + status.langKey());
    }

    private List<Component> recapLines(CommandSender viewer, Oath oath, List<OathMember> members) {
        List<Component> lines = new ArrayList<>();
        lines.add(lang.component(viewer, "oath.recap.header", "id", String.valueOf(oath.id())));
        lines.add(lang.component(viewer, "oath.recap.founder", "founder", oath.founderName()));
        lines.add(lang.component(viewer, "oath.recap.parties", "parties", partyNames(members)));
        lines.add(lang.component(viewer, "oath.recap.promise", "promise", oath.promise()));
        lines.add(lang.component(viewer, "oath.recap.duration",
                "duration", TimeUtil.format(Duration.ofSeconds(oath.durationSeconds()))));
        lines.add(lang.component(viewer, "oath.recap.sanction", "sanction", sanctionText(viewer, oath)));
        lines.add(lang.component(viewer, "oath.recap.status", "status", statusText(viewer, oath.status())));
        if (oath.status() == OathStatus.PROPOSE && oath.codeExpiresAt() > System.currentTimeMillis()) {
            lines.add(lang.component(viewer, "oath.recap.code", "code", oath.code()));
        }
        return lines;
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

    private String commandName() {
        return plugin.getConfig().getString("commands.oath.name", "oath");
    }
}

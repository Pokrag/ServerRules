package com.pokrag.serverrules.complaint;

import com.pokrag.serverrules.ServerRulesPlugin;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Conflit d'interets : un juge ne traite pas un cas dans lequel il est implique. */
public final class Conflicts {

    private Conflicts() {
    }

    /**
     * @return true si le spectateur est implique dans la plainte (plaignant, accuse,
     *         ou partie du serment cite) sans posseder la permission de contournement.
     */
    public static boolean isInvolved(CommandSender viewer, Complaint complaint, ServerRulesPlugin plugin) {
        if (!(viewer instanceof Player player)) {
            return false;
        }
        if (player.hasPermission("serverrules.conflict.bypass")) {
            return false;
        }
        if (complaint.involves(player.getUniqueId())) {
            return true;
        }
        if (complaint.citation().type() == CitationType.OATH && plugin.getOathManager() != null) {
            return plugin.getOathManager().isParty(complaint.citation().refId(), player.getUniqueId());
        }
        return false;
    }
}

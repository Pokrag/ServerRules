package com.pokrag.serverrules.complaint;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Conflit d'interets : un juge ne traite pas un cas dans lequel il est implique. */
public final class Conflicts {

    private Conflicts() {
    }

    /**
     * @return true si le spectateur est directement implique dans la plainte
     *         (plaignant ou accuse) sans posseder la permission de contournement.
     */
    public static boolean isInvolved(CommandSender viewer, Complaint complaint) {
        if (!(viewer instanceof Player player)) {
            return false;
        }
        if (player.hasPermission("serverrules.conflict.bypass")) {
            return false;
        }
        return complaint.involves(player.getUniqueId());
    }
}

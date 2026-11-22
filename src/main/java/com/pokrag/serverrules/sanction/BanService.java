package com.pokrag.serverrules.sanction;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.pokrag.serverrules.ServerRulesPlugin;
import io.papermc.paper.ban.BanListType;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.ban.ProfileBanList;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.Date;
import java.util.UUID;

/**
 * Application des bannissements temporaires via l'API de ban de Paper.
 *
 * Le message de ban mentionne la regle enfreinte et la duree. Un joueur en ligne
 * est immediatement expulse.
 */
public final class BanService {

    private final ServerRulesPlugin plugin;

    public BanService(ServerRulesPlugin plugin) {
        this.plugin = plugin;
    }

    /** Bannit temporairement un joueur (en ligne ou non) jusqu'a la date d'expiration. */
    public SanctionResult applyTempBan(UUID uuid, String playerName, Duration duration, String reason) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            return SanctionResult.error("error.invalid-duration");
        }

        try {
            ProfileBanList bans = Bukkit.getBanList(BanListType.PROFILE);
            PlayerProfile profile = Bukkit.getOfflinePlayer(uuid).getPlayerProfile();
            bans.addBan(profile, reason, new Date(System.currentTimeMillis() + duration.toMillis()), plugin.getName());
        } catch (RuntimeException e) {
            plugin.getLogger().severe("Bannissement impossible pour " + playerName + " : " + e.getMessage());
            return SanctionResult.error("error.ban-failed");
        }

        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            online.kick(Component.text(reason));
        }
        return SanctionResult.ok();
    }
}

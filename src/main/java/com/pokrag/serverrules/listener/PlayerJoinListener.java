package com.pokrag.serverrules.listener;

import com.pokrag.serverrules.ServerRulesPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * A la connexion d'un joueur : affiche les regles (premiere visite) ou
 * les nouveautes depuis sa derniere visite.
 */
public final class PlayerJoinListener implements Listener {

    private final ServerRulesPlugin plugin;

    public PlayerJoinListener(ServerRulesPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        plugin.getChangeTracker().onJoin(event.getPlayer());
    }
}

package com.pokrag.serverrules.util;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/** Repasse une tache issue d'un thread de base de donnees sur le thread principal. */
public final class Sync {

    private Sync() {
    }

    /** Execute la tache sur le thread principal, en ignorant un plugin deja desactive. */
    public static void run(JavaPlugin plugin, Runnable task) {
        if (!plugin.isEnabled()) {
            return;
        }
        try {
            Bukkit.getScheduler().runTask(plugin, task);
        } catch (IllegalStateException ignored) {
            // Plugin en cours de desactivation : on ignore.
        }
    }
}

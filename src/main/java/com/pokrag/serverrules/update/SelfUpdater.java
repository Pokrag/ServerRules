package com.pokrag.serverrules.update;

import com.pokrag.serverrules.ServerRulesPlugin;
import com.pokrag.serverrules.util.Http;
import com.pokrag.serverrules.util.Sync;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;

/**
 * Installation a la demande de la derniere version (commande {@code /rules update}).
 *
 * 1. interroge GitHub ({@link UpdateChecker#fetchLatest()}) ;
 * 2. telecharge le JAR de la release et l'ecrit dans le dossier des plugins ;
 * 3. supprime l'ancien JAR ;
 * 4. recharge le plugin via PlugManX s'il est installe, sinon invite a redemarrer.
 *
 * Le fichier est ecrit sous un nom versionne ({@code ServerRules-<version>.jar}) :
 * PlugManX retrouve le plugin par le {@code name} de son plugin.yml, pas par le
 * nom de fichier. L'ancien JAR est donc supprime pour eviter tout doublon.
 */
public final class SelfUpdater {

    private final ServerRulesPlugin plugin;

    public SelfUpdater(ServerRulesPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Verifie, telecharge et installe la derniere version de facon asynchrone,
     * puis previent le demandeur du resultat.
     */
    public void update(CommandSender requester) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                UpdateChecker.Release release = UpdateChecker.fetchLatest();
                if (release == null || release.version().isEmpty()) {
                    message(requester, "update.none-found");
                    return;
                }

                String current = plugin.getDescription().getVersion();
                if (!UpdateChecker.isNewer(release.version(), current)) {
                    message(requester, "update.up-to-date", "current", current);
                    return;
                }
                if (release.assetUrl() == null || release.assetUrl().isBlank()) {
                    message(requester, "update.no-asset", "latest", release.version());
                    return;
                }

                plugin.getLogger().info("Telechargement de ServerRules " + release.version()
                        + " depuis " + release.pageUrl());
                message(requester, "update.downloading", "latest", release.version());
                byte[] jar = Http.getBytes(release.assetUrl(), downloadHeaders(), 60);
                Installed installed = install(release.version(), release.assetName(), jar);
                plugin.getLogger().info("ServerRules " + release.version() + " installe : "
                        + installed.path().getFileName());

                if (!installed.oldRemoved()) {
                    message(requester, "update.delete-failed", "latest", release.version());
                    return;
                }

                if (plugmanPresent()) {
                    message(requester, "update.installed-reload", "latest", release.version());
                    Sync.run(plugin, () -> Bukkit.getScheduler().runTaskLater(plugin,
                            () -> reloadWithPlugman(), 20L));
                } else {
                    message(requester, "update.installed-restart", "latest", release.version());
                }
            } catch (Exception error) {
                plugin.getLogger().warning("Echec de la mise a jour : " + error);
                message(requester, "update.failed", "error", String.valueOf(error.getMessage()));
            }
        });
    }

    /** Resultat de l'ecriture : JAR de destination et sort de la suppression de l'ancien. */
    private record Installed(Path path, boolean oldRemoved) {
    }

    /**
     * Ecrit le nouveau JAR a cote du JAR courant et supprime ce dernier.
     * L'ecriture passe par un fichier temporaire pour ne jamais laisser un JAR partiel.
     */
    private Installed install(String version, String assetName, byte[] jar) throws IOException {
        Path current = plugin.getPluginFile().toPath().toAbsolutePath();
        Path directory = current.getParent();
        String fileName = assetName != null && assetName.toLowerCase().endsWith(".jar")
                ? assetName
                : "ServerRules-" + version + ".jar";
        Path target = directory.resolve(fileName);

        Path temp = directory.resolve(fileName + ".tmp");
        Files.write(temp, jar, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }

        boolean oldRemoved = true;
        if (!current.equals(target)) {
            try {
                Files.deleteIfExists(current);
            } catch (IOException error) {
                oldRemoved = false;
                plugin.getLogger().warning("Ancien JAR non supprime (" + current.getFileName()
                        + ") : " + error.getMessage());
            }
        }
        return new Installed(target, oldRemoved);
    }

    /** @return true si PlugManX (ou son ancetre PlugMan) est installe. */
    private boolean plugmanPresent() {
        return plugin.getServer().getPluginManager().getPlugin("PlugManX") != null
                || plugin.getServer().getPluginManager().getPlugin("PlugMan") != null;
    }

    /** Recharge le plugin via PlugManX (l'ancien JAR a ete remplace auparavant). */
    private void reloadWithPlugman() {
        CommandSender console = plugin.getServer().getConsoleSender();
        String command = "plugman reload " + plugin.getName();
        boolean dispatched = false;
        try {
            dispatched = plugin.getServer().dispatchCommand(console, command);
        } catch (Exception error) {
            plugin.getLogger().warning("Rechargement PlugManX impossible : " + error.getMessage());
        }
        if (!dispatched) {
            plugin.getLogger().warning("Commande PlugManX introuvable (" + command
                    + "). Redemarrez le serveur pour charger la nouvelle version.");
        }
    }

    private Map<String, String> downloadHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Accept", "application/octet-stream");
        headers.put("User-Agent", plugin.getName());
        return headers;
    }

    /** Envoie un message de langue sur le thread principal. */
    private void message(CommandSender requester, String key, String... placeholders) {
        Sync.run(plugin, () -> requester.sendMessage(plugin.getLang().prefixed(requester, key, placeholders)));
    }
}

package com.pokrag.serverrules.update;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pokrag.serverrules.ServerRulesPlugin;
import com.pokrag.serverrules.util.Http;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Recherche de mise a jour (GitHub Releases).
 *
 * - Verifie periodiquement la derniere release du depot et compare le tag a la
 *   version courante (schema "1.0" &lt; "1.0a" &lt; "1.0b" &lt; "1.1").
 * - Une nouvelle version est signalee en console, et aux joueurs disposant de la
 *   permission dediee ({@code serverrules.update.notify}) a leur connexion.
 * - Aucun telechargement automatique : l'administrateur met a jour le JAR lui-meme.
 */
public final class UpdateChecker {

    private static final String REPOSITORY = "Pokrag/ServerRules";
    private static final String RELEASE_API = "https://api.github.com/repos/" + REPOSITORY + "/releases/latest";
    private static final String RELEASES_PAGE = "https://github.com/" + REPOSITORY + "/releases";
    private static final String NOTIFY_PERMISSION = "serverrules.update.notify";

    private final ServerRulesPlugin plugin;
    private final boolean enabled;
    private final String currentVersion;

    private volatile boolean hasUpdate;
    private volatile String newVersion = "";
    private volatile String releaseUrl = RELEASES_PAGE;
    private BukkitTask task;

    public UpdateChecker(ServerRulesPlugin plugin) {
        this.plugin = plugin;
        this.enabled = plugin.getConfig().getBoolean("updates.check", true);
        this.currentVersion = plugin.getDescription().getVersion().trim().toLowerCase(Locale.ROOT);
    }

    /** Demarre la verification periodique en tache asynchrone (sans bloquer le serveur). */
    public void start() {
        if (!enabled) {
            return;
        }
        long hours = Math.max(1, plugin.getConfig().getLong("updates.interval-hours", 6));
        // Premier controle apres 60 s, puis toutes les "interval-hours" heures.
        task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::check, 20L * 60L, hours * 60L * 60L * 20L);
    }

    /** Arrete la verification periodique. */
    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /** Interroge l'API GitHub et met a jour l'etat local (thread asynchrone). */
    private void check() {
        try {
            Map<String, String> headers = new HashMap<>();
            headers.put("Accept", "application/vnd.github+json");
            headers.put("User-Agent", REPOSITORY);

            Http.Response response = Http.get(RELEASE_API, headers, 15);
            if (response.status() > 299) {
                // Aucune release publiee (404) : rien a proposer.
                hasUpdate = false;
                return;
            }

            JsonObject release = JsonParser.parseString(response.body()).getAsJsonObject();
            if (!release.has("tag_name") || release.get("tag_name").isJsonNull()) {
                hasUpdate = false;
                return;
            }

            String tag = release.get("tag_name").getAsString().trim().toLowerCase(Locale.ROOT).replaceFirst("^v", "");
            if (tag.isEmpty() || !isNewer(tag, currentVersion)) {
                hasUpdate = false;
                return;
            }

            newVersion = tag;
            releaseUrl = release.has("html_url") && !release.get("html_url").isJsonNull()
                    ? release.get("html_url").getAsString()
                    : RELEASES_PAGE;
            hasUpdate = true;

            plugin.getLogger().info("Nouvelle version disponible : " + newVersion
                    + " (actuelle : " + currentVersion + ") - " + releaseUrl);
        } catch (Exception error) {
            plugin.getLogger().warning("Recherche de mise a jour impossible : " + error.getMessage());
        }
    }

    /** A la connexion d'un joueur autorise, rappelle qu'une mise a jour est disponible. */
    public void onJoin(Player player) {
        if (!hasUpdate || !player.hasPermission(NOTIFY_PERMISSION)) {
            return;
        }
        player.sendMessage(plugin.getLang().prefixed(player, "update.available",
                "current", currentVersion, "latest", newVersion, "url", releaseUrl));
    }

    /** @return true si une version plus recente a ete detectee. */
    public boolean hasUpdate() {
        return hasUpdate;
    }

    // Compare deux versions segment par segment, en gerant un suffixe lettre :
    // "1.5.10" > "1.5.9" et "1.0b" > "1.0a" > "1.0".
    private static boolean isNewer(String candidate, String current) {
        if (candidate == null || candidate.isEmpty()) {
            return false;
        }
        String[] candidateParts = candidate.split("\\.");
        String[] currentParts = current.split("\\.");
        int length = Math.max(candidateParts.length, currentParts.length);
        for (int i = 0; i < length; i++) {
            String candidatePart = i < candidateParts.length ? candidateParts[i] : "";
            String currentPart = i < currentParts.length ? currentParts[i] : "";
            int comparison = compareSegment(candidatePart, currentPart);
            if (comparison != 0) {
                return comparison > 0;
            }
        }
        return false;
    }

    private static int compareSegment(String candidate, String current) {
        int candidateNumber = parseNumber(candidate);
        int currentNumber = parseNumber(current);
        if (candidateNumber != currentNumber) {
            return Integer.compare(candidateNumber, currentNumber);
        }
        // Meme nombre : on compare le suffixe lettre ("1.0" < "1.0a" < "1.0b").
        return suffix(candidate).compareTo(suffix(current));
    }

    private static int parseNumber(String value) {
        try {
            return Integer.parseInt(value.replaceAll("[^0-9].*$", ""));
        } catch (NumberFormatException error) {
            return 0;
        }
    }

    private static String suffix(String value) {
        return value.replaceAll("^[0-9]+", "").toLowerCase(Locale.ROOT);
    }
}

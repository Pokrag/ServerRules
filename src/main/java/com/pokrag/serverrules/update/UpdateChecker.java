package com.pokrag.serverrules.update;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pokrag.serverrules.ServerRulesPlugin;
import com.pokrag.serverrules.util.Http;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
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
 * - Aucun telechargement automatique ici : l'installation a la demande est geree
 *   par {@link SelfUpdater} (commande {@code /rules update}).
 */
public final class UpdateChecker {

    private static final String REPOSITORY = "Pokrag/ServerRules";
    private static final String RELEASE_API = "https://api.github.com/repos/" + REPOSITORY + "/releases/latest";
    private static final String RELEASES_PAGE = "https://github.com/" + REPOSITORY + "/releases";
    private static final String NOTIFY_PERMISSION = "serverrules.update.notify";

    /**
     * Derniere release publiee sur GitHub.
     *
     * @param version   numero de version (tag sans le prefixe "v") ;
     * @param pageUrl   page web de la release ;
     * @param assetUrl  URL de telechargement du JAR, ou null si la release n'en contient pas ;
     * @param assetName nom du fichier JAR.
     */
    public record Release(String version, String pageUrl, String assetUrl, String assetName) {
    }

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
            Release release = fetchLatest();
            if (release == null || release.version().isEmpty() || !isNewer(release.version(), currentVersion)) {
                hasUpdate = false;
                return;
            }

            newVersion = release.version();
            releaseUrl = release.pageUrl();
            hasUpdate = true;

            plugin.getLogger().info("Nouvelle version disponible : " + newVersion
                    + " (actuelle : " + currentVersion + ") - " + releaseUrl);
        } catch (Exception error) {
            plugin.getLogger().warning("Recherche de mise a jour impossible : " + error.getMessage());
        }
    }

    /**
     * Interroge l'API GitHub et renvoie la derniere release (version, page, JAR),
     * ou null si aucune release n'est publiee.
     *
     * @throws IOException si la requete echoue.
     */
    public static Release fetchLatest() throws IOException {
        Map<String, String> headers = new HashMap<>();
        headers.put("Accept", "application/vnd.github+json");
        headers.put("User-Agent", REPOSITORY);

        Http.Response response = Http.get(RELEASE_API, headers, 15);
        if (response.status() == 404 || response.body().isBlank()) {
            return null;
        }
        if (response.status() > 299) {
            throw new IOException("GitHub HTTP " + response.status());
        }

        JsonObject release = JsonParser.parseString(response.body()).getAsJsonObject();
        if (!release.has("tag_name") || release.get("tag_name").isJsonNull()) {
            return null;
        }

        String version = release.get("tag_name").getAsString().trim().toLowerCase(Locale.ROOT).replaceFirst("^v", "");
        String pageUrl = release.has("html_url") && !release.get("html_url").isJsonNull()
                ? release.get("html_url").getAsString()
                : RELEASES_PAGE;
        String assetUrl = null;
        String assetName = null;
        if (release.has("assets") && release.get("assets").isJsonArray()) {
            for (JsonElement element : release.getAsJsonArray("assets")) {
                JsonObject asset = element.getAsJsonObject();
                String name = asset.has("name") ? asset.get("name").getAsString() : "";
                if (!name.toLowerCase(Locale.ROOT).endsWith(".jar")
                        || !asset.has("browser_download_url")) {
                    continue;
                }
                assetName = name;
                assetUrl = asset.get("browser_download_url").getAsString();
                break;
            }
        }
        return new Release(version, pageUrl, assetUrl, assetName);
    }

    /** @return la version courante du plugin (telle que declaree dans plugin.yml). */
    public String currentVersion() {
        return currentVersion;
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
    public static boolean isNewer(String candidate, String current) {
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

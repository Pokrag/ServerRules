package com.pokrag.serverrules.lang;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Gestionnaire de langues.
 *
 * - Charge les fichiers {@code lang/<code>.yml} (MiniMessage).
 * - Ecrit dans le dossier du plugin les fichiers fournis dans le JAR s'ils sont absents.
 * - Une cle absente retombe sur la langue par defaut, puis sur la cle brute (sans erreur).
 * - Si {@code language.per-player} est actif, chaque joueur recoit les textes dans la
 *   langue de son client quand un fichier existe.
 */
public final class Lang {

    /** Langues livrees avec le plugin. */
    public static final List<String> AVAILABLE = List.of(
            "en", "fr", "es", "de", "it", "pt", "nl", "pl", "ru", "tr", "ja", "ko", "zh_CN");

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final JavaPlugin plugin;
    private final Map<String, Map<String, String>> messages = new HashMap<>();
    private String defaultLang = "en";
    private boolean perPlayer = true;

    public Lang(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * (Re)charge tous les fichiers de langue.
     *
     * @param defaultLang code de la langue par defaut (ex. "en").
     */
    public void load(String defaultLang) {
        this.defaultLang = defaultLang;
        this.perPlayer = plugin.getConfig().getBoolean("language.per-player", true);
        this.messages.clear();

        File dir = new File(plugin.getDataFolder(), "lang");
        if (!dir.exists() && !dir.mkdirs()) {
            plugin.getLogger().warning("Impossible de creer le dossier lang/.");
        }

        for (String code : AVAILABLE) {
            File file = new File(dir, code + ".yml");
            if (!file.exists()) {
                plugin.saveResource("lang/" + code + ".yml", false);
            }
            messages.put(code, read(code, file));
        }
    }

    /**
     * Lit un fichier de langue : les valeurs du fichier externe ecrasent celles du JAR.
     */
    private Map<String, String> read(String code, File file) {
        Map<String, String> result = new HashMap<>();

        // Valeurs de reference integrees dans le JAR (garantit qu'aucune cle ne manque).
        try (InputStream in = plugin.getResource("lang/" + code + ".yml")) {
            if (in != null) {
                YamlConfiguration jarConfig = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(in, StandardCharsets.UTF_8));
                collect(jarConfig, result);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Lecture du fichier de langue interne " + code + " impossible : " + e.getMessage());
        }

        // Valeurs du fichier externe (prioritaires).
        if (file.exists()) {
            YamlConfiguration external = YamlConfiguration.loadConfiguration(file);
            collect(external, result);
        }
        return result;
    }

    private void collect(YamlConfiguration config, Map<String, String> target) {
        for (String key : config.getKeys(true)) {
            if (config.isConfigurationSection(key)) {
                continue;
            }
            Object value = config.get(key);
            if (value != null) {
                target.put(key, String.valueOf(value));
            }
        }
    }

    /** @return le code de langue a utiliser pour un destinataire. */
    public String codeFor(CommandSender viewer) {
        if (perPlayer && viewer instanceof Player player) {
            Locale locale = player.locale();
            if (locale != null) {
                return codeOf(locale);
            }
        }
        return defaultLang;
    }

    /** Convertit une locale de client en code de langue livre par le plugin. */
    private String codeOf(Locale locale) {
        String language = locale.getLanguage().toLowerCase(Locale.ROOT);
        if (language.equals("zh")) {
            String country = locale.getCountry().toUpperCase(Locale.ROOT);
            // zh_TW / zh_HK ne sont pas livres : on retombe sur le chinois simplifie.
            return country.equals("TW") || country.equals("HK") ? defaultLang : "zh_CN";
        }
        return AVAILABLE.contains(language) ? language : defaultLang;
    }

    /**
     * @return le texte brut (MiniMessage) pour la cle, dans la langue par defaut,
     *         avec repli sur la cle elle-meme si elle est introuvable.
     */
    public String raw(String key) {
        return rawFor(defaultLang, key);
    }

    /**
     * @return le texte brut (MiniMessage) pour un destinataire, avec ses variables remplacees.
     *         Les variables sont passees par paires : "nom", "valeur", "autre", "valeur".
     */
    public String raw(CommandSender viewer, String key, String... placeholders) {
        return applyPlaceholders(rawFor(codeFor(viewer), key), placeholders);
    }

    /** @return le composant MiniMessage correspondant a la cle, pour un destinataire. */
    public Component component(CommandSender viewer, String key, String... placeholders) {
        return MINI.deserialize(raw(viewer, key, placeholders));
    }

    /** @return le composant precede du prefixe du plugin. */
    public Component prefixed(CommandSender viewer, String key, String... placeholders) {
        return component(viewer, "prefix").append(component(viewer, key, placeholders));
    }

    private String rawFor(String code, String key) {
        Map<String, String> selected = messages.get(code);
        if (selected != null) {
            String value = selected.get(key);
            if (value != null) {
                return value;
            }
        }
        Map<String, String> fallback = messages.get(defaultLang);
        if (fallback != null) {
            String value = fallback.get(key);
            if (value != null) {
                return value;
            }
        }
        return key;
    }

    private String applyPlaceholders(String text, String... placeholders) {
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            text = text.replace("{" + placeholders[i] + "}", placeholders[i + 1]);
        }
        return text;
    }
}

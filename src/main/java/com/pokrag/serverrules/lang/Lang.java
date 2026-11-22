package com.pokrag.serverrules.lang;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Gestionnaire de langues.
 *
 * - Charge les fichiers {@code lang/<code>.yml} (MiniMessage).
 * - Ecrit dans le dossier du plugin les fichiers fournis dans le JAR s'ils sont absents.
 * - Une cle absente retombe sur la langue par defaut, puis sur la cle brute (sans erreur).
 */
public final class Lang {

    /** Langues livrees avec le plugin. */
    public static final List<String> AVAILABLE = List.of(
            "en", "fr", "es", "de", "it", "pt", "nl", "pl", "ru", "tr", "ja", "ko", "zh_CN");

    private final JavaPlugin plugin;
    private final Map<String, Map<String, String>> messages = new HashMap<>();
    private String defaultLang = "en";

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
                for (String key : jarConfig.getKeys(true)) {
                    Object value = jarConfig.get(key);
                    if (value != null && !jarConfig.isConfigurationSection(key)) {
                        result.put(key, String.valueOf(value));
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Lecture du fichier de langue interne " + code + " impossible : " + e.getMessage());
        }

        // Valeurs du fichier externe (prioritaires).
        if (file.exists()) {
            YamlConfiguration external = YamlConfiguration.loadConfiguration(file);
            for (String key : external.getKeys(true)) {
                Object value = external.get(key);
                if (value != null && !external.isConfigurationSection(key)) {
                    result.put(key, String.valueOf(value));
                }
            }
        }
        return result;
    }

    /**
     * @return le texte brut (MiniMessage) pour la cle, dans la langue par defaut,
     *         avec repli sur la cle elle-meme si elle est introuvable.
     */
    public String raw(String key) {
        Map<String, String> def = messages.get(defaultLang);
        if (def != null) {
            String value = def.get(key);
            if (value != null) {
                return value;
            }
        }
        return key;
    }
}

package com.pokrag.serverrules.rule;

import com.pokrag.serverrules.ServerRulesPlugin;
import com.pokrag.serverrules.util.TimeUtil;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Charge, expose et enregistre la liste des regles de {@code config.yml}.
 *
 * - L'ordre de la liste (la cle numerique) definit le numero affiche d'une regle.
 * - Chaque regle garde un identifiant interne stable : s'il manque ou s'il est
 *   duplique dans la config, un nouvel identifiant est attribue et ecrit.
 */
public final class RulesManager {

    private final ServerRulesPlugin plugin;
    private final List<Rule> rules = new ArrayList<>();

    public RulesManager(ServerRulesPlugin plugin) {
        this.plugin = plugin;
    }

    /** (Re)charge les regles depuis la configuration. */
    public void load() {
        rules.clear();

        ConfigurationSection list = plugin.getConfig().getConfigurationSection("rules.list");
        if (list == null) {
            return;
        }

        List<Integer> numbers = new ArrayList<>();
        for (String key : list.getKeys(false)) {
            try {
                numbers.add(Integer.parseInt(key));
            } catch (NumberFormatException ignored) {
                plugin.getLogger().warning("Cle de regle ignoree (ce n'est pas un nombre) : " + key);
            }
        }
        Collections.sort(numbers);

        Set<Integer> usedIds = new HashSet<>();
        int maxId = 0;
        boolean dirty = false;

        for (Integer number : numbers) {
            ConfigurationSection section = list.getConfigurationSection(String.valueOf(number));
            if (section == null) {
                continue;
            }
            String name = section.getString("name", "Rule " + number);
            String description = section.getString("description", "");
            Sanction sanction = readSanction(section, number);

            int id = section.getInt("id", -1);
            if (id <= 0 || !usedIds.add(id)) {
                do {
                    id = ++maxId;
                } while (!usedIds.add(id));
                section.set("id", id);
                dirty = true;
                plugin.getLogger().warning("Identifiant attribue a la regle " + number + " : " + id);
            }
            maxId = Math.max(maxId, id);

            rules.add(new Rule(id, name, description, sanction));
        }

        if (dirty) {
            plugin.saveConfig();
        }
    }

    /** Lit la sanction d'une regle, avec repli sur une amende nulle en cas de donnee invalide. */
    private Sanction readSanction(ConfigurationSection ruleSection, int number) {
        ConfigurationSection section = ruleSection.getConfigurationSection("sanction");
        if (section == null) {
            plugin.getLogger().warning("Regle " + number + " : sanction absente, amende 0 par defaut.");
            return Sanction.fine(0);
        }

        SanctionType type;
        try {
            type = SanctionType.valueOf(section.getString("type", "FINE").trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Regle " + number + " : type de sanction inconnu, amende par defaut.");
            type = SanctionType.FINE;
        }

        if (type == SanctionType.FINE) {
            return Sanction.fine(Math.max(0, section.getDouble("amount", 0)));
        }

        Duration duration = TimeUtil.parse(section.getString("duration", ""));
        if (duration == null) {
            plugin.getLogger().warning("Regle " + number + " : duree de ban invalide, 1h par defaut.");
            duration = Duration.ofHours(1);
        }
        return Sanction.tempBan(duration);
    }

    /** Reecrit toute la liste des regles dans {@code config.yml}. */
    public void save() {
        FileConfiguration config = plugin.getConfig();
        config.set("rules.list", null);

        int number = 1;
        for (Rule rule : rules) {
            String base = "rules.list." + number;
            config.set(base + ".id", rule.id());
            config.set(base + ".name", rule.name());
            config.set(base + ".description", rule.description());
            if (rule.sanction().type() == SanctionType.FINE) {
                config.set(base + ".sanction.type", "FINE");
                config.set(base + ".sanction.amount", rule.sanction().amount());
            } else {
                config.set(base + ".sanction.type", "TEMP_BAN");
                config.set(base + ".sanction.duration", TimeUtil.format(rule.sanction().duration()));
            }
            number++;
        }
        config.setComments("rules.list", List.of(
                "Liste des regles. Le numero (position) est la cle : 1, 2, 3...",
                "'id' est un identifiant interne stable : ne le modifiez pas a la main."));
        plugin.saveConfig();
    }

    /** @return la liste des regles, dans l'ordre. */
    public List<Rule> rules() {
        return List.copyOf(rules);
    }

    /** @return la regle au numero donne (1 = premiere), ou null si le numero n'existe pas. */
    public Rule byNumber(int number) {
        return number >= 1 && number <= rules.size() ? rules.get(number - 1) : null;
    }

    /** @return le prochain identifiant interne libre. */
    public int nextId() {
        int max = 0;
        for (Rule rule : rules) {
            max = Math.max(max, rule.id());
        }
        return max + 1;
    }
}

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
            List<Sanction> sanctions = readSanctions(section, number);

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

            rules.add(new Rule(id, name, description, sanctions));
        }

        if (dirty) {
            plugin.saveConfig();
        }
    }

    /**
     * Lit les sanctions d'une regle.
     *
     * Format actuel : une section {@code sanctions} contenant une liste.
     * Ancien format (1.0) : une section unique {@code sanction} : elle est relue
     * comme une liste d'un seul element.
     */
    private List<Sanction> readSanctions(ConfigurationSection ruleSection, int number) {
        List<Sanction> sanctions = new ArrayList<>();

        List<?> entries = ruleSection.getList("sanctions");
        if (entries != null && !entries.isEmpty()) {
            int index = 0;
            for (Object entry : entries) {
                if (entry instanceof ConfigurationSection section) {
                    Sanction sanction = readSanctionSection(section, number);
                    if (sanction != null) {
                        sanctions.add(sanction);
                    }
                } else if (entry instanceof java.util.Map<?, ?> map) {
                    ConfigurationSection section = new org.bukkit.configuration.MemoryConfiguration();
                    for (java.util.Map.Entry<?, ?> e : map.entrySet()) {
                        section.set(String.valueOf(e.getKey()), e.getValue());
                    }
                    Sanction sanction = readSanctionSection(section, number);
                    if (sanction != null) {
                        sanctions.add(sanction);
                    }
                } else {
                    plugin.getLogger().warning("Regle " + number + " : sanction " + index + " illisible, ignoree.");
                }
                index++;
            }
        } else {
            // Repli sur l'ancien format a sanction unique.
            ConfigurationSection section = ruleSection.getConfigurationSection("sanction");
            if (section != null) {
                Sanction sanction = readSanctionSection(section, number);
                if (sanction != null) {
                    sanctions.add(sanction);
                }
            }
        }

        if (sanctions.isEmpty()) {
            plugin.getLogger().warning("Regle " + number + " : aucune sanction lisible, amende 0 par defaut.");
            sanctions.add(Sanction.fine(0));
        }
        return sanctions;
    }

    /** Lit une sanction unique depuis sa section, ou null si elle est illisible. */
    private Sanction readSanctionSection(ConfigurationSection section, int number) {
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
        if (type == SanctionType.CUSTOM) {
            return Sanction.custom(section.getString("text", ""));
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
            int index = 0;
            for (Sanction sanction : rule.sanctions()) {
                String sanctionBase = base + ".sanctions." + index;
                switch (sanction.type()) {
                    case FINE -> {
                        config.set(sanctionBase + ".type", "FINE");
                        config.set(sanctionBase + ".amount", sanction.amount());
                    }
                    case TEMP_BAN -> {
                        config.set(sanctionBase + ".type", "TEMP_BAN");
                        config.set(sanctionBase + ".duration", TimeUtil.format(sanction.duration()));
                    }
                    case CUSTOM -> {
                        config.set(sanctionBase + ".type", "CUSTOM");
                        config.set(sanctionBase + ".text", sanction.text());
                    }
                }
                index++;
            }
            number++;
        }
        config.setComments("rules.list", List.of(
                "Liste des regles. Le numero (position) est la cle : 1, 2, 3...",
                "'id' est un identifiant interne stable : ne le modifiez pas a la main.",
                "Chaque regle peut prevoir plusieurs sanctions dans 'sanctions'."));
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

    /** Ajoute une regle a la fin de la liste et renvoie sa nouvelle instance. */
    public Rule create(String name, String description, List<Sanction> sanctions) {
        Rule rule = new Rule(nextId(), name, description, sanctions);
        rules.add(rule);
        return rule;
    }

    /**
     * Remplace le contenu d'une regle en conservant son identifiant interne.
     *
     * @return la regle mise a jour, ou null si le numero n'existe pas.
     */
    public Rule replace(int number, String name, String description, List<Sanction> sanctions) {
        Rule old = byNumber(number);
        if (old == null) {
            return null;
        }
        Rule updated = new Rule(old.id(), name, description, sanctions);
        rules.set(number - 1, updated);
        return updated;
    }

    /**
     * Supprime la regle au numero donne.
     *
     * @return la regle supprimee, ou null si le numero n'existe pas.
     */
    public Rule delete(int number) {
        if (number < 1 || number > rules.size()) {
            return null;
        }
        return rules.remove(number - 1);
    }

    /**
     * Deplace une regle vers une nouvelle position (1 = premiere).
     *
     * @return true si le deplacement a ete applique.
     */
    public boolean move(int number, int position) {
        if (number < 1 || number > rules.size() || position < 1 || position > rules.size()) {
            return false;
        }
        if (number != position) {
            Rule rule = rules.remove(number - 1);
            rules.add(position - 1, rule);
        }
        return true;
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

package com.pokrag.serverrules;

import com.pokrag.serverrules.lang.Lang;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Classe principale du plugin ServerRules.
 *
 * Phase 1 : base du projet.
 * - charge la configuration,
 * - charge les fichiers de langue,
 * - verifie au demarrage qu'EssentialsX est bien le fournisseur d'economie branche a Vault.
 *   Sinon : erreur claire dans la console et desactivation du plugin.
 */
public final class ServerRulesPlugin extends JavaPlugin {

    private Economy economy;
    private Lang lang;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.lang = new Lang(this);
        this.lang.load(getConfig().getString("language.default", "en"));

        if (!setupEconomy()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getLogger().info("ServerRules active (Paper " + getServer().getMinecraftVersion()
                + ", economie via " + economy.getName() + ").");
    }

    @Override
    public void onDisable() {
        getLogger().info("ServerRules desactive.");
    }

    /**
     * Verifie que Vault est present et que le fournisseur d'economie enregistre
     * est bien EssentialsX. Affiche une erreur claire en console si ce n'est pas le cas.
     *
     * @return true si l'economie est correctement disponible.
     */
    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            getLogger().severe(lang.raw("error.vault-missing"));
            return false;
        }

        RegisteredServiceProvider<Economy> registration =
                getServer().getServicesManager().getRegistration(Economy.class);
        if (registration == null) {
            getLogger().severe(lang.raw("error.economy-missing"));
            return false;
        }

        this.economy = registration.getProvider();
        if (!economy.getName().toLowerCase().contains("essentials")) {
            getLogger().severe(lang.raw("error.economy-not-essentials")
                    .replace("{provider}", economy.getName()));
            return false;
        }
        return true;
    }

    /**
     * @return le fournisseur d'economie Vault (jamais null une fois le plugin active).
     */
    public Economy getEconomy() {
        return economy;
    }

    /**
     * @return le gestionnaire de langues.
     */
    public Lang getLang() {
        return lang;
    }
}

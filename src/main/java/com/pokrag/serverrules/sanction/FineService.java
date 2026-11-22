package com.pokrag.serverrules.sanction;

import com.pokrag.serverrules.ServerRulesPlugin;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;

/**
 * Application des amendes via l'economie Vault.
 *
 * Le solde peut devenir negatif : l'amende n'est jamais refusee ni reduite par
 * le plugin. Si le plugin d'economie refuse le retrait, l'echec est signale.
 */
public final class FineService {

    private final ServerRulesPlugin plugin;
    private final Economy economy;

    public FineService(ServerRulesPlugin plugin) {
        this.plugin = plugin;
        this.economy = plugin.getEconomy();
    }

    /** Retire le montant du solde du joueur. */
    public SanctionResult applyFine(OfflinePlayer target, double amount) {
        if (amount <= 0) {
            return SanctionResult.error("error.invalid-amount");
        }

        EconomyResponse response;
        try {
            response = economy.withdrawPlayer(target, amount);
        } catch (RuntimeException e) {
            plugin.getLogger().severe("Erreur d'economie pendant l'amende : " + e.getMessage());
            return SanctionResult.error("error.fine-refused");
        }

        if (!response.transactionSuccess()) {
            plugin.getLogger().warning("Amende refusee par " + economy.getName() + " (" + response.errorMessage
                    + ") : " + amount + " pour " + target.getName());
            return SanctionResult.error("error.fine-refused");
        }
        return SanctionResult.ok();
    }

    /**
     * Verifie au demarrage que le plugin d'economie autorise les soldes negatifs.
     * Sans cela, une amende depassant le solde est refusee.
     */
    public void warnIfNegativeBalanceImpossible() {
        File file = new File(plugin.getDataFolder().getParentFile(), "Essentials/config.yml");
        if (!file.isFile()) {
            return;
        }
        double minMoney = YamlConfiguration.loadConfiguration(file).getDouble("min-money", 0.0);
        if (minMoney >= 0) {
            plugin.getLogger().warning("EssentialsX refuse les soldes negatifs (min-money = " + minMoney
                    + "). Les amendes superieures au solde seront refusees : passez min-money a une valeur negative.");
        }
    }
}

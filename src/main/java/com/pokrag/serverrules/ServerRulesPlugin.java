package com.pokrag.serverrules;

import com.pokrag.serverrules.ai.OpenRouterService;
import com.pokrag.serverrules.command.JudgeCommand;
import com.pokrag.serverrules.command.OathCommand;
import com.pokrag.serverrules.command.PlaintCommand;
import com.pokrag.serverrules.command.RulesCommand;
import com.pokrag.serverrules.complaint.ComplaintManager;
import com.pokrag.serverrules.display.ComplaintDisplay;
import com.pokrag.serverrules.display.RulesDisplay;
import com.pokrag.serverrules.gui.ChatInput;
import com.pokrag.serverrules.gui.MenuListener;
import com.pokrag.serverrules.lang.Lang;
import com.pokrag.serverrules.listener.PlayerJoinListener;
import com.pokrag.serverrules.oath.OathManager;
import com.pokrag.serverrules.rule.RulesAdminManager;
import com.pokrag.serverrules.rule.RulesManager;
import com.pokrag.serverrules.sanction.BanService;
import com.pokrag.serverrules.sanction.FineService;
import com.pokrag.serverrules.storage.Database;
import com.pokrag.serverrules.track.ChangeTracker;
import com.pokrag.serverrules.update.UpdateChecker;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;

/**
 * Classe principale du plugin ServerRules.
 *
 * - charge la configuration et les fichiers de langue,
 * - verifie que Vault est present et qu'EssentialsX est bien le fournisseur
 *   d'economie (sinon : erreur claire et desactivation),
 * - charge les regles, ouvre la base SQLite et suit les changements.
 */
public final class ServerRulesPlugin extends JavaPlugin {

    private Lang lang;
    private Economy economy;
    private RulesManager rulesManager;
    private RulesDisplay display;
    private Database database;
    private ChangeTracker changeTracker;
    private RulesAdminManager rulesAdminManager;
    private ComplaintDisplay complaintDisplay;
    private ComplaintManager complaintManager;
    private OathManager oathManager;
    private ChatInput chatInput;
    private OpenRouterService openRouter;
    private UpdateChecker updateChecker;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.lang = new Lang(this);
        this.lang.load(getConfig().getString("language.default", "en"));

        if (!setupEconomy()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.rulesManager = new RulesManager(this);
        this.rulesManager.load();

        this.display = new RulesDisplay(this);

        this.database = new Database(this);
        try {
            this.database.open();
        } catch (SQLException e) {
            getLogger().severe("Ouverture de la base SQLite impossible : " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.changeTracker = new ChangeTracker(this, database, rulesManager, display);
        this.changeTracker.detectStartup();

        this.rulesAdminManager = new RulesAdminManager(this);

        FineService fines = new FineService(this);
        BanService bans = new BanService(this);
        this.complaintDisplay = new ComplaintDisplay(this);
        this.openRouter = new OpenRouterService(this);
        this.complaintManager = new ComplaintManager(this, database, complaintDisplay, fines, bans);
        fines.warnIfNegativeBalanceImpossible();

        this.oathManager = new OathManager(this, database);
        this.oathManager.load();
        this.oathManager.startTicker();

        new RulesCommand(this).register();
        new PlaintCommand(this).register();
        new JudgeCommand(this).register();
        new OathCommand(this).register();
        getServer().getPluginManager().registerEvents(new PlayerJoinListener(this), this);

        // Recherche de mise a jour (Phase 13) : verifie les releases GitHub en arriere-plan.
        this.updateChecker = new UpdateChecker(this);
        this.updateChecker.start();

        // Menus d'inventaire (Phase 11) : clics annules et saisie de texte dans le chat.
        this.chatInput = new ChatInput(this);
        this.chatInput.register();
        this.chatInput.startTicker();
        getServer().getPluginManager().registerEvents(chatInput, this);
        getServer().getPluginManager().registerEvents(new MenuListener(), this);

        getLogger().info("ServerRules active (Paper " + getServer().getMinecraftVersion()
                + ", economie via " + economy.getName() + ", " + rulesManager.rules().size() + " regle(s)).");
    }

    @Override
    public void onDisable() {
        if (updateChecker != null) {
            updateChecker.stop();
        }
        if (oathManager != null) {
            oathManager.stopTicker();
        }
        if (chatInput != null) {
            chatInput.stopTicker();
        }
        if (database != null) {
            database.close();
        }
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

    /** @return le fournisseur d'economie Vault (jamais null une fois le plugin active). */
    public Economy getEconomy() {
        return economy;
    }

    /** @return le gestionnaire de langues. */
    public Lang getLang() {
        return lang;
    }

    /** @return le gestionnaire des regles. */
    public RulesManager getRulesManager() {
        return rulesManager;
    }

    /** @return le rendu des messages de regles. */
    public RulesDisplay getDisplay() {
        return display;
    }

    /** @return le suivi des changements de regles. */
    public ChangeTracker getChangeTracker() {
        return changeTracker;
    }

    /** @return le gestionnaire des modifications de regles (Phase 8). */
    public RulesAdminManager getRulesAdminManager() {
        return rulesAdminManager;
    }

    /** @return le gestionnaire des plaintes. */
    public ComplaintManager getComplaintManager() {
        return complaintManager;
    }

    /** @return le rendu des messages de plaintes. */
    public ComplaintDisplay getComplaintDisplay() {
        return complaintDisplay;
    }

    /** @return le gestionnaire des serments (Phase 9). */
    public OathManager getOathManager() {
        return oathManager;
    }

    /** @return la saisie de texte dans le chat des menus (Phase 11). */
    public ChatInput getChatInput() {
        return chatInput;
    }

    /** @return le service IA OpenRouter pour les sanctions libres (Phase 13). */
    public OpenRouterService getOpenRouter() {
        return openRouter;
    }

    /** @return la recherche de mise a jour GitHub (Phase 13). */
    public UpdateChecker getUpdateChecker() {
        return updateChecker;
    }
}

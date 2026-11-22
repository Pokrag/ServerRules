package com.pokrag.serverrules.gui;

import com.pokrag.serverrules.ServerRulesPlugin;
import com.pokrag.serverrules.complaint.SanctionKind;
import com.pokrag.serverrules.lang.Lang;
import com.pokrag.serverrules.util.TimeUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Parcours de creation d'un serment (/oath create, section 11.5).
 *
 * 1. saisie de la duree dans le chat ;
 * 2. menu du type de sanction : amende, ban temporaire ou personnalisee ;
 * 3. saisie de la valeur (montant, duree du ban ou texte libre) ;
 * 4. saisie de la promesse ;
 * 5. recapitulatif avec Creer et Annuler ; le code est ensuite donne dans le chat.
 */
public final class OathCreateGui extends GuiMenu {

    /** Mots acceptes pour un serment sans duree limite. */
    public static final Set<String> INFINITE = Set.of(
            "0", "-1", "inf", "infini", "infinite", "illimite", "illimitee", "permanent", "perm", "\u221e");

    /** @return true si le texte saisi designe un serment sans duree limite. */
    public static boolean isInfinite(String text) {
        return text != null && INFINITE.contains(text.trim().toLowerCase(Locale.ROOT));
    }

    private final long durationSeconds;

    private OathCreateGui(ServerRulesPlugin plugin, long durationSeconds) {
        super(plugin, 27);
        this.durationSeconds = durationSeconds;
    }

    /** Etape 1 : demande la duree du serment, puis ouvre le choix de la sanction. */
    public static void start(ServerRulesPlugin plugin, Player viewer) {
        Lang lang = plugin.getLang();
        viewer.sendMessage(lang.prefixed(viewer, "gui.oath-create.ask-duration"));
        plugin.getChatInput().request(viewer, text -> {
            if (isInfinite(text)) {
                new OathCreateGui(plugin, 0).open(viewer);
                return;
            }
            Duration duration = TimeUtil.parse(text.trim().toLowerCase(Locale.ROOT));
            if (duration == null || duration.toSeconds() <= 0) {
                viewer.sendMessage(lang.prefixed(viewer, "oath.create.invalid-duration"));
                start(plugin, viewer);
                return;
            }
            new OathCreateGui(plugin, duration.toSeconds()).open(viewer);
        }, () -> OathGui.open(plugin, viewer));
    }

    @Override
    protected Component title(Player viewer) {
        return plugin.getLang().component(viewer, "gui.oath-create.title");
    }

    @Override
    protected void build(Player viewer) {
        Lang lang = plugin.getLang();
        set(11, Menus.item(Material.GOLD_INGOT, lang.component(viewer, "gui.oath-create.entry-fine"), List.of()),
                player -> chooseKind(player, SanctionKind.FINE));
        set(13, Menus.item(Material.CLOCK, lang.component(viewer, "gui.oath-create.entry-ban"), List.of()),
                player -> chooseKind(player, SanctionKind.TEMP_BAN));
        set(15, Menus.item(Material.NAME_TAG, lang.component(viewer, "gui.oath-create.entry-custom"), List.of()),
                player -> chooseKind(player, SanctionKind.CUSTOM));
        set(26, Menus.item(Material.ARROW, lang.component(viewer, "gui.common.back"), List.of()),
                player -> closeThen(player, () -> OathGui.open(plugin, player)));
        fillEmpty(fillerItem(viewer));
    }

    /** Etape 3 : demande la valeur correspondant au type de sanction. */
    private void chooseKind(Player viewer, SanctionKind kind) {
        switch (kind) {
            case FINE -> askAmount(viewer);
            case TEMP_BAN -> askBanDuration(viewer);
            case CUSTOM -> askCustom(viewer);
        }
    }

    private void askAmount(Player viewer) {
        Lang lang = plugin.getLang();
        closeThen(viewer, () -> {
            viewer.sendMessage(lang.prefixed(viewer, "gui.oath-create.ask-amount"));
            plugin.getChatInput().request(viewer, text -> {
                double amount = parseAmount(text);
                if (amount <= 0) {
                    viewer.sendMessage(lang.prefixed(viewer, "oath.create.invalid-value"));
                    askAmount(viewer);
                    return;
                }
                askPromise(viewer, SanctionKind.FINE, amount, 0, "");
            }, () -> new OathCreateGui(plugin, durationSeconds).open(viewer));
        });
    }

    private void askBanDuration(Player viewer) {
        Lang lang = plugin.getLang();
        closeThen(viewer, () -> {
            viewer.sendMessage(lang.prefixed(viewer, "gui.oath-create.ask-ban"));
            plugin.getChatInput().request(viewer, text -> {
                Duration ban = TimeUtil.parse(text.trim());
                if (ban == null || ban.toSeconds() <= 0) {
                    viewer.sendMessage(lang.prefixed(viewer, "oath.create.invalid-value"));
                    askBanDuration(viewer);
                    return;
                }
                askPromise(viewer, SanctionKind.TEMP_BAN, 0, ban.toSeconds(), "");
            }, () -> new OathCreateGui(plugin, durationSeconds).open(viewer));
        });
    }

    private void askCustom(Player viewer) {
        Lang lang = plugin.getLang();
        closeThen(viewer, () -> {
            viewer.sendMessage(lang.prefixed(viewer, "gui.oath-create.ask-custom"));
            plugin.getChatInput().request(viewer, text -> {
                if (text.isBlank()) {
                    viewer.sendMessage(lang.prefixed(viewer, "oath.create.invalid-value"));
                    askCustom(viewer);
                    return;
                }
                askPromise(viewer, SanctionKind.CUSTOM, 0, 0, text.trim());
            }, () -> new OathCreateGui(plugin, durationSeconds).open(viewer));
        });
    }

    /** Etape 4 : demande la promesse, puis ouvre le recapitulatif. */
    private void askPromise(Player viewer, SanctionKind kind, double amount, long banSeconds, String customText) {
        Lang lang = plugin.getLang();
        viewer.sendMessage(lang.prefixed(viewer, "gui.oath-create.ask-promise"));
        plugin.getChatInput().request(viewer,
                text -> recap(viewer, kind, amount, banSeconds, customText, text.trim()),
                () -> new OathCreateGui(plugin, durationSeconds).open(viewer));
    }

    /** Etape 5 : recapitulatif avant creation. */
    private void recap(Player viewer, SanctionKind kind, double amount, long banSeconds,
                       String customText, String promise) {
        ConfirmMenu.open(plugin, viewer, List.of(recapItem(viewer, kind, amount, banSeconds, customText, promise)),
                player -> plugin.getOathManager().create(player, durationSeconds, kind, amount,
                        banSeconds, customText, promise),
                player -> new OathCreateGui(plugin, durationSeconds).open(player));
    }

    private ItemStack recapItem(Player viewer, SanctionKind kind, double amount, long banSeconds,
                                String customText, String promise) {
        Lang lang = plugin.getLang();
        List<Component> lore = new ArrayList<>();
        lore.add(lang.component(viewer, "gui.oath-create.recap-duration",
                "duration", plugin.getOathManager().getDisplay().durationText(viewer, durationSeconds)));
        lore.add(lang.component(viewer, "gui.oath-create.recap-sanction",
                "sanction", sanctionText(viewer, kind, amount, banSeconds, customText)));
        lore.add(lang.component(viewer, "gui.oath-create.recap-promise", "promise", promise));
        return Menus.item(Material.WRITABLE_BOOK,
                lang.component(viewer, "gui.oath-create.recap-title"), lore);
    }

    private String sanctionText(Player viewer, SanctionKind kind, double amount, long banSeconds, String customText) {
        return switch (kind) {
            case FINE -> plugin.getLang().raw(viewer, "rules.sanction.fine",
                    "amount", plugin.getEconomy().format(amount));
            case TEMP_BAN -> plugin.getLang().raw(viewer, "rules.sanction.temp-ban",
                    "duration", TimeUtil.format(Duration.ofSeconds(banSeconds)));
            case CUSTOM -> customText;
        };
    }

    private static double parseAmount(String text) {
        try {
            double value = Double.parseDouble(text.trim().replace(',', '.'));
            return Double.isFinite(value) ? value : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}

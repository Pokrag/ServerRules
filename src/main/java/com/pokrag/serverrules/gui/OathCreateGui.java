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
 * Menu de creation d'un serment (/oath create, section 11.5).
 *
 * Meme interface que l'edition d'une regle ({@link RuleEditGui}) : un seul menu
 * avec les boutons Duree, Sanction, Promesse et Enregistrer. Toutes les saisies
 * se font dans le chat ; l'application passe par {@link ConfirmMenu}.
 */
public final class OathCreateGui extends GuiMenu {

    /** Mots acceptes pour un serment sans duree limite. */
    public static final Set<String> INFINITE = Set.of(
            "0", "-1", "inf", "infini", "infinite", "illimite", "illimitee", "permanent", "perm", "\u221e");

    /** @return true si le texte saisi designe un serment sans duree limite. */
    public static boolean isInfinite(String text) {
        return text != null && INFINITE.contains(text.trim().toLowerCase(Locale.ROOT));
    }

    private final OathDraft draft;

    private OathCreateGui(ServerRulesPlugin plugin, OathDraft draft) {
        super(plugin, 45);
        this.draft = draft;
    }

    /** Ouvre le menu de creation d'un serment. */
    public static void start(ServerRulesPlugin plugin, Player viewer) {
        open(plugin, viewer, new OathDraft());
    }

    /** Ouvre le menu a partir d'un brouillon existant (retour depuis le choix de la sanction). */
    static void open(ServerRulesPlugin plugin, Player viewer, OathDraft draft) {
        new OathCreateGui(plugin, draft).open(viewer);
    }

    @Override
    protected Component title(Player viewer) {
        return plugin.getLang().component(viewer, "gui.oath-create.title");
    }

    @Override
    protected void build(Player viewer) {
        Lang lang = plugin.getLang();
        set(4, summary(viewer));

        set(20, Menus.item(Material.CLOCK, lang.component(viewer, "gui.oath-create.entry-duration"), List.of()),
                player -> closeThen(player, () -> askDuration(player)));
        set(21, Menus.item(Material.GOLD_INGOT, lang.component(viewer, "gui.oath-create.entry-sanction"), List.of()),
                player -> closeThen(player, () -> OathSanctionGui.open(plugin, player, draft)));
        set(22, Menus.item(Material.PAPER, lang.component(viewer, "gui.oath-create.entry-promise"), List.of()),
                player -> closeThen(player, () -> askPromise(player)));
        set(24, Menus.item(Material.LIME_CONCRETE, lang.component(viewer, "gui.oath-create.edit-save"), List.of()),
                player -> closeThen(player, () -> save(player)));
        set(40, Menus.item(Material.ARROW, lang.component(viewer, "gui.common.back"), List.of()),
                player -> closeThen(player, () -> OathGui.open(plugin, player)));
        fillEmpty(fillerItem(viewer));
    }

    private void askDuration(Player viewer) {
        Lang lang = plugin.getLang();
        viewer.sendMessage(lang.prefixed(viewer, "gui.oath-create.ask-duration"));
        plugin.getChatInput().request(viewer, text -> {
            if (isInfinite(text)) {
                draft.durationSeconds = 0L;
                open(plugin, viewer, draft);
                return;
            }
            Duration duration = TimeUtil.parse(text.trim().toLowerCase(Locale.ROOT));
            if (duration == null || duration.toSeconds() <= 0) {
                viewer.sendMessage(lang.prefixed(viewer, "oath.create.invalid-duration"));
                askDuration(viewer);
                return;
            }
            draft.durationSeconds = duration.toSeconds();
            open(plugin, viewer, draft);
        }, () -> open(plugin, viewer, draft));
    }

    private void askPromise(Player viewer) {
        Lang lang = plugin.getLang();
        viewer.sendMessage(lang.prefixed(viewer, "gui.oath-create.ask-promise"));
        plugin.getChatInput().request(viewer, text -> {
            draft.promise = text.trim();
            open(plugin, viewer, draft);
        }, () -> open(plugin, viewer, draft));
    }

    /** Ouvre le menu de confirmation avant de creer le serment. */
    private void save(Player viewer) {
        Lang lang = plugin.getLang();
        if (draft.durationSeconds == null) {
            viewer.sendMessage(lang.prefixed(viewer, "oath.create.invalid-duration"));
            return;
        }
        if (draft.kind == null) {
            viewer.sendMessage(lang.prefixed(viewer, "gui.oath-create.sanction-required"));
            return;
        }
        if (draft.promise.isBlank()) {
            viewer.sendMessage(lang.prefixed(viewer, "gui.oath-create.promise-required"));
            return;
        }
        long duration = draft.durationSeconds;
        SanctionKind kind = draft.kind;
        double amount = draft.amount;
        long banSeconds = draft.banSeconds;
        String customText = draft.customText;
        String promise = draft.promise;
        ConfirmMenu.open(plugin, viewer, List.of(summary(viewer)),
                player -> {
                    plugin.getOathManager().create(player, duration, kind, amount, banSeconds, customText, promise);
                    OathGui.open(plugin, player);
                },
                player -> open(plugin, player, draft));
    }

    /** Objet recapitulant le serment en cours de creation. */
    private ItemStack summary(Player viewer) {
        Lang lang = plugin.getLang();
        List<Component> lore = new ArrayList<>();
        lore.add(lang.component(viewer, "gui.oath-create.recap-duration",
                "duration", draft.durationSeconds == null
                        ? "-"
                        : plugin.getOathManager().getDisplay().durationText(viewer, draft.durationSeconds)));
        lore.add(lang.component(viewer, "gui.oath-create.recap-sanction",
                "sanction", draft.kind == null ? "-" : sanctionText(viewer)));
        lore.add(lang.component(viewer, "gui.oath-create.recap-promise",
                "promise", draft.promise.isBlank() ? "-" : draft.promise));
        return Menus.item(Material.WRITABLE_BOOK,
                lang.component(viewer, "gui.oath-create.recap-title"), lore);
    }

    private String sanctionText(Player viewer) {
        return switch (draft.kind) {
            case FINE -> plugin.getLang().raw(viewer, "rules.sanction.fine",
                    "amount", plugin.getEconomy().format(draft.amount));
            case TEMP_BAN -> plugin.getLang().raw(viewer, "rules.sanction.temp-ban",
                    "duration", TimeUtil.format(Duration.ofSeconds(draft.banSeconds)));
            case CUSTOM -> draft.customText;
        };
    }
}

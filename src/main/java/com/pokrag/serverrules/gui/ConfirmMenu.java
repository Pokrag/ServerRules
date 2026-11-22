package com.pokrag.serverrules.gui;

import com.pokrag.serverrules.ServerRulesPlugin;
import com.pokrag.serverrules.lang.Lang;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.function.Consumer;

/**
 * Petit menu « Confirmer / Annuler » avec un recapitulatif.
 *
 * Utilise avant d'appliquer une sanction, avant toute modification du contenu
 * des regles, avant d'envoyer une plainte et avant de creer un serment.
 */
public final class ConfirmMenu extends GuiMenu {

    private final List<ItemStack> info;
    private final Consumer<Player> onConfirm;
    private final Consumer<Player> onCancel;

    private ConfirmMenu(ServerRulesPlugin plugin, List<ItemStack> info,
                        Consumer<Player> onConfirm, Consumer<Player> onCancel) {
        super(plugin, 27);
        this.info = info;
        this.onConfirm = onConfirm;
        this.onCancel = onCancel;
    }

    @Override
    protected Component title(Player viewer) {
        return plugin.getLang().component(viewer, "gui.common.title.confirm");
    }

    @Override
    protected void build(Player viewer) {
        Lang lang = plugin.getLang();
        int slot = 9;
        for (ItemStack item : info) {
            if (slot > 17) {
                break;
            }
            set(slot, item);
            slot++;
        }
        set(20, Menus.item(Material.LIME_CONCRETE, lang.component(viewer, "gui.common.confirm"), List.of()),
                player -> invoke(player, onConfirm));
        set(24, Menus.item(Material.RED_CONCRETE, lang.component(viewer, "gui.common.cancel"), List.of()),
                player -> invoke(player, onCancel));
        fillEmpty(fillerItem(viewer));
    }

    private void invoke(Player player, Consumer<Player> action) {
        player.closeInventory();
        Consumer<Player> effective = action == null ? Player::closeInventory : action;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                effective.accept(player);
            }
        });
    }

    /** Ouvre un menu de confirmation. */
    public static void open(ServerRulesPlugin plugin, Player viewer, List<ItemStack> info,
                            Consumer<Player> onConfirm, Consumer<Player> onCancel) {
        new ConfirmMenu(plugin, info, onConfirm, onCancel).open(viewer);
    }
}

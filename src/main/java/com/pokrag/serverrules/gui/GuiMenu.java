package com.pokrag.serverrules.gui;

import com.pokrag.serverrules.ServerRulesPlugin;
import com.pokrag.serverrules.lang.Lang;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Base des menus d'inventaire (Phase 11).
 *
 * - Le menu remplit son inventaire dans {@link #build(Player)}, a chaque ouverture.
 * - Tous les clics sont annules par {@link MenuListener} : seule l'action
 *   enregistree pour une case est executee.
 * - Une ligne de navigation standard (page precedente, fermer, page suivante et
 *   indicateur de page) est proposee par {@link #navigation}.
 */
public abstract class GuiMenu implements InventoryHolder {

    /** Nombre d'elements affiches par page dans une liste. */
    protected static final int PER_PAGE = 45;

    protected final ServerRulesPlugin plugin;
    private final int size;
    private final Map<Integer, Consumer<Player>> actions = new HashMap<>();
    private Inventory inventory;

    /** Page courante (0 = premiere). */
    protected int page;

    protected GuiMenu(ServerRulesPlugin plugin, int size) {
        this.plugin = plugin;
        this.size = Math.max(9, Math.min(54, size / 9 * 9));
    }

    /** @return le titre du menu, dans la langue du joueur. */
    protected abstract Component title(Player viewer);

    /** Remplit l'inventaire du menu. */
    protected abstract void build(Player viewer);

    /** Ouvre le menu pour un joueur. */
    public final void open(Player viewer) {
        if (!plugin.isEnabled()) {
            return;
        }
        actions.clear();
        inventory = Bukkit.createInventory(this, size, title(viewer));
        build(viewer);
        viewer.openInventory(inventory);
    }

    /** Reconstruit le menu deja ouvert (changement de page, rafraichissement). */
    protected final void refresh(Player viewer) {
        if (inventory == null) {
            return;
        }
        actions.clear();
        inventory.clear();
        build(viewer);
    }

    /** Enregistre un objet sans action (objet decoratif ou d'information). */
    protected final void set(int slot, ItemStack item) {
        set(slot, item, null);
    }

    /** Enregistre un objet et l'action declenchee par un clic dessus. */
    protected final void set(int slot, ItemStack item, Consumer<Player> action) {
        if (inventory == null || slot < 0 || slot >= size) {
            return;
        }
        inventory.setItem(slot, item);
        if (action != null) {
            actions.put(slot, action);
        }
    }

    /** Remplit toutes les cases encore vides avec l'objet fourni. */
    protected final void fillEmpty(ItemStack filler) {
        if (inventory == null) {
            return;
        }
        for (int slot = 0; slot < size; slot++) {
            if (inventory.getItem(slot) == null) {
                inventory.setItem(slot, filler);
            }
        }
    }

    /** @return l'objet de remplissage gris, dans la langue du joueur. */
    protected final ItemStack fillerItem(Player viewer) {
        return Menus.filler(plugin.getLang().component(viewer, "gui.common.filler"));
    }

    /**
     * Place la navigation standard : indicateur de page en {pageSlot},
     * page precedente en 48, fermer en 49, page suivante en 50.
     */
    protected final void navigation(Player viewer, int page, int pages) {
        navigation(viewer, page, pages, 45);
    }

    protected final void navigation(Player viewer, int page, int pages, int pageSlot) {
        Lang lang = plugin.getLang();
        set(pageSlot, Menus.item(Material.PAPER, lang.component(viewer, "gui.common.page",
                "page", String.valueOf(page + 1), "pages", String.valueOf(pages)), List.of()));
        if (page > 0) {
            set(48, Menus.item(Material.ARROW, lang.component(viewer, "gui.common.previous"), List.of()),
                    this::previousPage);
        }
        set(49, Menus.item(Material.BARRIER, lang.component(viewer, "gui.common.close"), List.of()),
                Player::closeInventory);
        if (page + 1 < pages) {
            set(50, Menus.item(Material.ARROW, lang.component(viewer, "gui.common.next"), List.of()),
                    this::nextPage);
        }
    }

    private void previousPage(Player viewer) {
        if (page > 0) {
            page--;
            refresh(viewer);
        }
    }

    private void nextPage(Player viewer) {
        page++;
        refresh(viewer);
    }

    /** Ferme le menu puis execute l'action au tick suivant (ouverture d'un autre menu, saisie). */
    protected final void closeThen(Player viewer, Runnable action) {
        viewer.closeInventory();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (viewer.isOnline()) {
                action.run();
            }
        });
    }

    /** Traite un clic : l'ecouteur annule toujours l'evenement. */
    public final void click(Player player, int slot) {
        Consumer<Player> action = actions.get(slot);
        if (action != null) {
            action.accept(player);
        }
    }

    @Override
    public final Inventory getInventory() {
        return inventory;
    }

    /** @return la taille du menu en cases. */
    protected final int size() {
        return size;
    }
}

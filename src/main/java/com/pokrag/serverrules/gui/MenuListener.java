package com.pokrag.serverrules.gui;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/**
 * Ecouteur des menus d'inventaire : annule tous les clics, glissers et depots
 * dans un {@link GuiMenu}, puis transmet le clic a l'action enregistree.
 */
public final class MenuListener implements Listener {

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof GuiMenu menu)) {
            return;
        }
        // Aucun objet ne peut etre pris, deplace ou depose, y compris shift-clic.
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player) || event.getClickedInventory() == null) {
            return;
        }
        if (event.getClickedInventory().equals(event.getInventory())) {
            menu.click(player, event.getRawSlot());
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof GuiMenu) {
            event.setCancelled(true);
        }
    }
}

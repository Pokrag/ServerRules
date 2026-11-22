package com.pokrag.serverrules.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.List;

/** Petits utilitaires partages par les menus d'inventaire. */
public final class Menus {

    private Menus() {
    }

    /** Construit un objet avec un nom et une description (MiniMessage deja converti). */
    public static ItemStack item(Material material, Component name, List<Component> lore) {
        return item(material, name, lore, false);
    }

    /**
     * Construit un objet avec un nom, une description et, si demande, l'effet de brillance.
     *
     * @param glint true pour mettre l'objet en evidence (regles ajoutees ou modifiees).
     */
    public static ItemStack item(Material material, Component name, List<Component> lore, boolean glint) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(name);
            if (lore != null && !lore.isEmpty()) {
                meta.lore(lore);
            }
            if (glint) {
                meta.setEnchantmentGlintOverride(Boolean.TRUE);
            }
            stack.setItemMeta(meta);
        }
        return stack;
    }

    /** Construit une tete de joueur (utilisee pour l'accuse d'une plainte). */
    public static ItemStack head(OfflinePlayer owner, Component name, List<Component> lore) {
        ItemStack stack = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = stack.getItemMeta();
        if (meta instanceof SkullMeta skull) {
            skull.setOwningPlayer(owner);
            skull.displayName(name);
            if (lore != null && !lore.isEmpty()) {
                skull.lore(lore);
            }
            stack.setItemMeta(skull);
        } else if (meta != null) {
            meta.displayName(name);
            if (lore != null && !lore.isEmpty()) {
                meta.lore(lore);
            }
            stack.setItemMeta(meta);
        }
        return stack;
    }

    /** Objet decoratif neutre pour remplir les cases vides. */
    public static ItemStack filler(Component name) {
        return item(Material.GRAY_STAINED_GLASS_PANE, name, List.of());
    }

    /** @return le joueur en ligne ou en cache portant ce nom, ou null s'il est inconnu. */
    public static OfflinePlayer resolvePlayer(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online;
        }
        return Bukkit.getOfflinePlayerIfCached(name);
    }
}

package com.pokrag.serverrules.gui;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.pokrag.serverrules.ServerRulesPlugin;
import com.pokrag.serverrules.util.Sync;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Saisie de texte dans le chat (Phase 11).
 *
 * - Le message saisi n'est diffuse a personne.
 * - Le joueur ecrit "annuler" (ou clique le bouton dans le chat) pour annuler.
 * - Passe le delai {@code gui.input-timeout-seconds}, la saisie est abandonnee.
 */
public final class ChatInput implements Listener {

    /** Nom de la commande interne permettant d'annuler une saisie d'un clic. */
    private static final String CANCEL_COMMAND = "serverrulesgui";

    private record Pending(Consumer<String> onInput, Runnable onCancel, long expiresAt) {
    }

    private final ServerRulesPlugin plugin;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
    private BukkitTask ticker;

    public ChatInput(ServerRulesPlugin plugin) {
        this.plugin = plugin;
    }

    /** Enregistre la commande interne d'annulation. */
    public void register() {
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            LiteralCommandNode<CommandSourceStack> node = LiteralArgumentBuilder
                    .<CommandSourceStack>literal(CANCEL_COMMAND)
                    .then(LiteralArgumentBuilder.<CommandSourceStack>literal("cancel")
                            .executes(context -> {
                                if (context.getSource().getSender() instanceof Player player) {
                                    cancel(player);
                                }
                                return Command.SINGLE_SUCCESS;
                            }))
                    .build();
            event.registrar().register(node);
        });
    }

    /** Demarre la tache qui fait expirer les saisies abandonnees. */
    public void startTicker() {
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::purge, 20L, 20L);
    }

    /** Arrete la tache d'expiration. */
    public void stopTicker() {
        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }
    }

    /** Demande une saisie dans le chat. */
    public void request(Player player, Consumer<String> onInput) {
        request(player, onInput, null);
    }

    /**
     * Demande une saisie dans le chat.
     *
     * @param onCancel action executee si le joueur annule (peut etre null).
     */
    public void request(Player player, Consumer<String> onInput, Runnable onCancel) {
        long timeout = Math.max(1, plugin.getConfig().getLong("gui.input-timeout-seconds", 60));
        pending.put(player.getUniqueId(), new Pending(onInput, onCancel, System.currentTimeMillis() + timeout * 1000L));
        player.sendMessage(plugin.getLang().prefixed(player, "gui.common.input",
                "timeout", String.valueOf(timeout),
                "cancel", "/" + CANCEL_COMMAND + " cancel"));
    }

    /** Annule la saisie en attente d'un joueur. */
    public void cancel(Player player) {
        Pending current = pending.remove(player.getUniqueId());
        if (current == null) {
            return;
        }
        player.sendMessage(plugin.getLang().prefixed(player, "gui.common.input-cancelled"));
        if (current.onCancel() != null) {
            current.onCancel().run();
        }
    }

    /** Oublie toute saisie en attente d'un joueur (deconnexion). */
    public void forget(UUID uuid) {
        pending.remove(uuid);
    }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        Pending current = pending.get(event.getPlayer().getUniqueId());
        if (current == null) {
            return;
        }
        event.setCancelled(true);
        // Le message saisi n'est diffuse a personne.
        event.viewers().clear();
        String text = PlainTextComponentSerializer.plainText().serialize(event.message());
        Sync.run(plugin, () -> handle(event.getPlayer(), text));
    }

    private void handle(Player player, String text) {
        Pending current = pending.get(player.getUniqueId());
        if (current == null) {
            return;
        }
        if (System.currentTimeMillis() > current.expiresAt()) {
            pending.remove(player.getUniqueId());
            player.sendMessage(plugin.getLang().prefixed(player, "gui.common.input-timeout"));
            return;
        }
        pending.remove(player.getUniqueId());
        if (text.trim().equalsIgnoreCase("annuler")) {
            player.sendMessage(plugin.getLang().prefixed(player, "gui.common.input-cancelled"));
            if (current.onCancel() != null) {
                current.onCancel().run();
            }
            return;
        }
        current.onInput().accept(text);
    }

    private void purge() {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Pending> entry : new ArrayList<>(pending.entrySet())) {
            if (now > entry.getValue().expiresAt()) {
                pending.remove(entry.getKey());
                Player player = Bukkit.getPlayer(entry.getKey());
                if (player != null) {
                    player.sendMessage(plugin.getLang().prefixed(player, "gui.common.input-timeout"));
                    if (entry.getValue().onCancel() != null) {
                        entry.getValue().onCancel().run();
                    }
                }
            }
        }
    }
}

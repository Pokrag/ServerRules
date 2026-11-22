package com.pokrag.serverrules.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.pokrag.serverrules.ServerRulesPlugin;
import com.pokrag.serverrules.complaint.Citation;
import com.pokrag.serverrules.gui.PlayerComplaintsGui;
import com.pokrag.serverrules.oath.Oath;
import com.pokrag.serverrules.oath.OathManager;
import com.pokrag.serverrules.rule.Rule;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Commande de depot d'une plainte (Phase 5) :
 *   /plaint                                     ouvre la GUI (Phase 11) ;
 *   /plaint &lt;joueur&gt; &lt;n&deg; de regle&gt; &lt;details&gt;   depose une plainte visant une regle ;
 *   /plaint &lt;joueur&gt; oath:&lt;id&gt; &lt;details&gt;          depose une plainte visant un serment.
 */
public final class PlaintCommand {

    private static final String OATH_PREFIX = "oath:";

    private final ServerRulesPlugin plugin;

    public PlaintCommand(ServerRulesPlugin plugin) {
        this.plugin = plugin;
    }

    /** Enregistre la commande (nom et alias configurables) aupres de Paper. */
    public void register() {
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            String name = plugin.getConfig().getString("commands.plaint.name", "plaint");
            List<String> aliases = plugin.getConfig().getStringList("commands.plaint.aliases");

            LiteralCommandNode<CommandSourceStack> node = LiteralArgumentBuilder
                    .<CommandSourceStack>literal(name)
                    .requires(source -> source.getSender().hasPermission("serverrules.complaint"))
                    .executes(context -> {
                        CommandSender sender = context.getSource().getSender();
                        if (sender instanceof Player player) {
                            PlayerComplaintsGui.open(plugin, player);
                        } else {
                            sender.sendMessage(plugin.getLang().prefixed(sender, "gui.players-only"));
                        }
                        return Command.SINGLE_SUCCESS;
                    })
                    .then(Commands.argument("player", StringArgumentType.word())
                            .suggests(this::suggestPlayers)
                            .then(Commands.argument("reference", StringArgumentType.word())
                                    .suggests(this::suggestReferences)
                                    .then(Commands.argument("details", StringArgumentType.greedyString())
                                            .executes(this::execute))))
                    .build();

            event.registrar().register(node, aliases);
        });
    }

    private int execute(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        if (!(sender instanceof Player plaintiff)) {
            sender.sendMessage(plugin.getLang().prefixed(sender, "complaint.players-only"));
            return Command.SINGLE_SUCCESS;
        }

        String playerName = StringArgumentType.getString(context, "player");
        String reference = StringArgumentType.getString(context, "reference");
        String details = StringArgumentType.getString(context, "details");

        OfflinePlayer accused = resolve(playerName);
        if (accused == null) {
            sender.sendMessage(plugin.getLang().prefixed(sender, "complaint.player-not-found",
                    "player", playerName));
            return Command.SINGLE_SUCCESS;
        }
        if (accused.getUniqueId().equals(plaintiff.getUniqueId())) {
            sender.sendMessage(plugin.getLang().prefixed(sender, "complaint.self"));
            return Command.SINGLE_SUCCESS;
        }

        if (reference.toLowerCase(Locale.ROOT).startsWith(OATH_PREFIX)) {
            depositAgainstOath(plaintiff, accused, reference, details);
        } else {
            depositAgainstRule(plaintiff, accused, reference, details);
        }
        return Command.SINGLE_SUCCESS;
    }

    private void depositAgainstRule(Player plaintiff, OfflinePlayer accused, String reference, String details) {
        int number;
        try {
            number = Integer.parseInt(reference);
        } catch (NumberFormatException e) {
            plaintiff.sendMessage(plugin.getLang().prefixed(plaintiff, "complaint.rule-not-found",
                    "number", reference));
            return;
        }
        Rule rule = plugin.getRulesManager().byNumber(number);
        if (rule == null) {
            plaintiff.sendMessage(plugin.getLang().prefixed(plaintiff, "complaint.rule-not-found",
                    "number", String.valueOf(number)));
            return;
        }
        plugin.getComplaintManager().deposit(plaintiff, accused, Citation.ofRule(rule, number), details);
    }

    private void depositAgainstOath(Player plaintiff, OfflinePlayer accused, String reference, String details) {
        long oathId;
        try {
            oathId = Long.parseLong(reference.substring(OATH_PREFIX.length()));
        } catch (NumberFormatException e) {
            plaintiff.sendMessage(plugin.getLang().prefixed(plaintiff, "complaint.oath-invalid",
                    "reference", reference));
            return;
        }
        Oath oath = plugin.getOathManager().oath(oathId);
        if (oath == null) {
            plaintiff.sendMessage(plugin.getLang().prefixed(plaintiff, "oath.not-found",
                    "id", String.valueOf(oathId)));
            return;
        }
        OathManager.ComplaintCheck check = plugin.getOathManager().canComplain(
                oathId, plaintiff.getUniqueId(), accused.getUniqueId());
        switch (check) {
            case OK -> plugin.getComplaintManager().deposit(plaintiff, accused, Citation.ofOath(oath), details);
            case NOT_PARTY -> plaintiff.sendMessage(plugin.getLang().prefixed(plaintiff, "oath.complaint.not-party"));
            default -> plaintiff.sendMessage(plugin.getLang().prefixed(plaintiff, "oath.complaint.not-allowed"));
        }
    }

    private CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestPlayers(
            CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        String remaining = builder.getRemainingLowerCase();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getName().toLowerCase(Locale.ROOT).startsWith(remaining)) {
                builder.suggest(online.getName());
            }
        }
        return builder.buildFuture();
    }

    private CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestReferences(
            CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        int count = plugin.getRulesManager().rules().size();
        for (int i = 1; i <= count; i++) {
            builder.suggest(i);
        }
        CommandSender sender = context.getSource().getSender();
        if (sender instanceof Player player) {
            for (Oath oath : plugin.getOathManager().oathsOf(player.getUniqueId())) {
                builder.suggest(OATH_PREFIX + oath.id());
            }
        }
        return builder.buildFuture();
    }

    /** @return le joueur en ligne ou en cache portant ce nom, ou null s'il est inconnu. */
    private OfflinePlayer resolve(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online;
        }
        return Bukkit.getOfflinePlayerIfCached(name);
    }
}

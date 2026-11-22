package com.pokrag.serverrules.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.pokrag.serverrules.ServerRulesPlugin;
import com.pokrag.serverrules.complaint.Citation;
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

/**
 * Commande de depot d'une plainte (Phase 5) :
 *   /plaint                                    ouvre la GUI (Phase 11) ;
 *   /plaint &lt;joueur&gt; &lt;n&deg; de regle&gt; &lt;details&gt;   depose une plainte.
 */
public final class PlaintCommand {

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
                        // Phase 11 : ouvrira la GUI des plaintes du joueur.
                        CommandSender sender = context.getSource().getSender();
                        sender.sendMessage(plugin.getLang().prefixed(sender, "complaint.usage"));
                        return Command.SINGLE_SUCCESS;
                    })
                    .then(Commands.argument("player", StringArgumentType.word())
                            .suggests((context, builder) -> {
                                String remaining = builder.getRemainingLowerCase();
                                for (Player online : Bukkit.getOnlinePlayers()) {
                                    if (online.getName().toLowerCase(Locale.ROOT).startsWith(remaining)) {
                                        builder.suggest(online.getName());
                                    }
                                }
                                return builder.buildFuture();
                            })
                            .then(Commands.argument("rule", IntegerArgumentType.integer(1))
                                    .suggests((context, builder) -> {
                                        int count = plugin.getRulesManager().rules().size();
                                        for (int i = 1; i <= count; i++) {
                                            builder.suggest(i);
                                        }
                                        return builder.buildFuture();
                                    })
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
        int number = IntegerArgumentType.getInteger(context, "rule");
        String details = StringArgumentType.getString(context, "details");

        Rule rule = plugin.getRulesManager().byNumber(number);
        if (rule == null) {
            sender.sendMessage(plugin.getLang().prefixed(sender, "complaint.rule-not-found",
                    "number", String.valueOf(number)));
            return Command.SINGLE_SUCCESS;
        }

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

        plugin.getComplaintManager().deposit(plaintiff, accused, Citation.ofRule(rule, number), details);
        return Command.SINGLE_SUCCESS;
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

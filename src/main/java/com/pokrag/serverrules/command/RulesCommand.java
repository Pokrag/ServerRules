package com.pokrag.serverrules.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.pokrag.serverrules.ServerRulesPlugin;
import com.pokrag.serverrules.rule.Rule;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.command.CommandSender;

import java.util.List;

/**
 * Commande des regles (Phase 4) :
 *   /rules            affiche toutes les regles
 *   /rules &lt;n&gt;        affiche une regle precise
 *   /rules new        revoit les nouveautes
 * Les sous-commandes d'administration sont ajoutees en Phase 8.
 */
public final class RulesCommand {

    private final ServerRulesPlugin plugin;

    public RulesCommand(ServerRulesPlugin plugin) {
        this.plugin = plugin;
    }

    /** Enregistre la commande (nom et alias configurables) aupres de Paper. */
    public void register() {
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            String name = plugin.getConfig().getString("commands.rules.name", "rules");
            List<String> aliases = plugin.getConfig().getStringList("commands.rules.aliases");

            LiteralCommandNode<CommandSourceStack> node = LiteralArgumentBuilder
                    .<CommandSourceStack>literal(name)
                    .executes(context -> {
                        plugin.getDisplay().showTable(context.getSource().getSender());
                        return Command.SINGLE_SUCCESS;
                    })
                    .then(Commands.argument("number", IntegerArgumentType.integer(1))
                            .executes(context -> {
                                int number = IntegerArgumentType.getInteger(context, "number");
                                CommandSender sender = context.getSource().getSender();
                                Rule rule = plugin.getRulesManager().byNumber(number);
                                if (rule == null) {
                                    sender.sendMessage(plugin.getLang().prefixed(sender, "rules.not-found",
                                            "number", String.valueOf(number)));
                                } else {
                                    plugin.getDisplay().showRule(sender, number, rule);
                                }
                                return Command.SINGLE_SUCCESS;
                            }))
                    .then(LiteralArgumentBuilder.<CommandSourceStack>literal("new")
                            .executes(context -> {
                                plugin.getChangeTracker().showChangesFor(context.getSource().getSender());
                                return Command.SINGLE_SUCCESS;
                            }))
                    .build();

            event.registrar().register(node, aliases);
        });
    }
}

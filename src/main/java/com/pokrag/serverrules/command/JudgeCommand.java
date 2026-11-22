package com.pokrag.serverrules.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.pokrag.serverrules.ServerRulesPlugin;
import com.pokrag.serverrules.complaint.ComplaintManager;
import com.pokrag.serverrules.complaint.ComplaintStatus;
import com.pokrag.serverrules.gui.ComplaintsGui;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

/**
 * Commande de traitement des plaintes (Phase 6) :
 *   /judge                      ouvre la GUI (Phase 11) ;
 *   /judge list [statut]        liste les plaintes ;
 *   /judge view &lt;id&gt;           detail d'une plainte ;
 *   /judge sanction &lt;id&gt; [val] applique la sanction (valeur modulable) ;
 *   /judge dismiss &lt;id&gt;        classe sans suite ;
 *   /judge done &lt;id&gt;           marque une sanction personnalisee comme executee ;
 *   /judge rule &lt;id&gt; &lt;n&deg;&gt;     change la regle citee (superjuge uniquement).
 */
public final class JudgeCommand {

    private final ServerRulesPlugin plugin;

    public JudgeCommand(ServerRulesPlugin plugin) {
        this.plugin = plugin;
    }

    /** Enregistre la commande (nom et alias configurables) aupres de Paper. */
    public void register() {
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            String name = plugin.getConfig().getString("commands.judge.name", "judge");
            List<String> aliases = plugin.getConfig().getStringList("commands.judge.aliases");

            LiteralCommandNode<CommandSourceStack> node = LiteralArgumentBuilder
                    .<CommandSourceStack>literal(name)
                    .requires(source -> ComplaintManager.isJudge(source.getSender()))
                    .executes(this::openGui)
                    .then(LiteralArgumentBuilder.<CommandSourceStack>literal("list")
                            .executes(context -> list(context, null))
                            .then(Commands.argument("status", StringArgumentType.word())
                                    .suggests((context, builder) -> {
                                        for (ComplaintStatus status : ComplaintStatus.values()) {
                                            builder.suggest(status.langKey());
                                        }
                                        return builder.buildFuture();
                                    })
                                    .executes(context -> {
                                        CommandSender sender = context.getSource().getSender();
                                        ComplaintStatus status = statusOf(StringArgumentType.getString(context, "status"));
                                        if (status == null) {
                                            sender.sendMessage(plugin.getLang().prefixed(sender, "judge.invalid-status"));
                                            return Command.SINGLE_SUCCESS;
                                        }
                                        plugin.getComplaintManager().list(sender, status);
                                        return Command.SINGLE_SUCCESS;
                                    })))
                    .then(LiteralArgumentBuilder.<CommandSourceStack>literal("view")
                            .then(Commands.argument("id", IntegerArgumentType.integer(1))
                                    .executes(context -> {
                                        plugin.getComplaintManager().view(context.getSource().getSender(),
                                                IntegerArgumentType.getInteger(context, "id"));
                                        return Command.SINGLE_SUCCESS;
                                    })))
                    .then(LiteralArgumentBuilder.<CommandSourceStack>literal("sanction")
                            .then(Commands.argument("id", IntegerArgumentType.integer(1))
                                    .executes(context -> sanction(context, null))
                                    .then(Commands.argument("value", StringArgumentType.greedyString())
                                            .executes(context -> sanction(context,
                                                    StringArgumentType.getString(context, "value"))))))
                    .then(LiteralArgumentBuilder.<CommandSourceStack>literal("dismiss")
                            .then(Commands.argument("id", IntegerArgumentType.integer(1))
                                    .executes(context -> {
                                        plugin.getComplaintManager().dismiss(context.getSource().getSender(),
                                                IntegerArgumentType.getInteger(context, "id"));
                                        return Command.SINGLE_SUCCESS;
                                    })))
                    .then(LiteralArgumentBuilder.<CommandSourceStack>literal("done")
                            .then(Commands.argument("id", IntegerArgumentType.integer(1))
                                    .executes(context -> {
                                        plugin.getComplaintManager().markDone(context.getSource().getSender(),
                                                IntegerArgumentType.getInteger(context, "id"));
                                        return Command.SINGLE_SUCCESS;
                                    })))
                    .then(LiteralArgumentBuilder.<CommandSourceStack>literal("rule")
                            .then(Commands.argument("id", IntegerArgumentType.integer(1))
                                    .then(Commands.argument("number", IntegerArgumentType.integer(1))
                                            .executes(context -> {
                                                plugin.getComplaintManager().changeCitation(
                                                        context.getSource().getSender(),
                                                        IntegerArgumentType.getInteger(context, "id"),
                                                        IntegerArgumentType.getInteger(context, "number"));
                                                return Command.SINGLE_SUCCESS;
                                            }))))
                    .build();

            event.registrar().register(node, aliases);
        });
    }

    private int openGui(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        if (sender instanceof Player player) {
            ComplaintsGui.open(plugin, player);
        } else {
            sender.sendMessage(plugin.getLang().prefixed(sender, "gui.players-only"));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int list(CommandContext<CommandSourceStack> context, ComplaintStatus status) {
        plugin.getComplaintManager().list(context.getSource().getSender(), status);
        return Command.SINGLE_SUCCESS;
    }

    private int sanction(CommandContext<CommandSourceStack> context, String value) {
        plugin.getComplaintManager().applySanction(context.getSource().getSender(),
                IntegerArgumentType.getInteger(context, "id"), value);
        return Command.SINGLE_SUCCESS;
    }

    /** @return le statut correspondant au texte saisi, ou null s'il est inconnu. */
    private ComplaintStatus statusOf(String input) {
        String key = input.trim().toLowerCase(Locale.ROOT);
        for (ComplaintStatus status : ComplaintStatus.values()) {
            if (status.name().toLowerCase(Locale.ROOT).equals(key) || status.langKey().equals(key)) {
                return status;
            }
        }
        return null;
    }
}

package com.pokrag.serverrules.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.pokrag.serverrules.ServerRulesPlugin;
import com.pokrag.serverrules.complaint.SanctionKind;
import com.pokrag.serverrules.oath.Oath;
import com.pokrag.serverrules.util.TimeUtil;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.List;
import java.util.function.Predicate;

/**
 * Commande des serments (Phase 9) :
 *   /oath                                      ouvre la GUI (Phase 11) ;
 *   /oath create                               ouvre la GUI de creation (Phase 11) ;
 *   /oath create &lt;duree&gt; fine &lt;montant&gt; &lt;promesse&gt;   creation en une ligne, amende ;
 *   /oath create &lt;duree&gt; ban &lt;duree&gt; &lt;promesse&gt;     creation en une ligne, ban temporaire ;
 *   /oath create &lt;duree&gt; custom "&lt;sanction&gt;" &lt;promesse&gt;  creation en une ligne, sanction libre ;
 *   /oath join &lt;code&gt;                          rejoint un serment par son code ;
 *   /oath accept / /oath refuse                repond a une demande d'adhesion ;
 *   /oath info &lt;id&gt;                            detail d'un serment ;
 *   /oath cancel &lt;id&gt;                          demande ou confirme l'annulation.
 */
public final class OathCommand {

    private final ServerRulesPlugin plugin;

    public OathCommand(ServerRulesPlugin plugin) {
        this.plugin = plugin;
    }

    /** Enregistre la commande (nom et alias configurables) aupres de Paper. */
    public void register() {
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            String name = plugin.getConfig().getString("commands.oath.name", "oath");
            List<String> aliases = plugin.getConfig().getStringList("commands.oath.aliases");

            LiteralCommandNode<CommandSourceStack> node = LiteralArgumentBuilder
                    .<CommandSourceStack>literal(name)
                    .requires(permission("serverrules.oath"))
                    .executes(usage(null))
                    .then(LiteralArgumentBuilder.<CommandSourceStack>literal("create")
                            .executes(usage("create"))
                            .then(Commands.argument("duration", StringArgumentType.word())
                                    .then(Commands.literal("fine")
                                            .then(Commands.argument("amount", StringArgumentType.word())
                                                    .then(Commands.argument("promise", StringArgumentType.greedyString())
                                                            .executes(context -> create(context, SanctionKind.FINE)))))
                                    .then(Commands.literal("ban")
                                            .then(Commands.argument("ban-duration", StringArgumentType.word())
                                                    .then(Commands.argument("promise", StringArgumentType.greedyString())
                                                            .executes(context -> create(context, SanctionKind.TEMP_BAN)))))
                                    .then(Commands.literal("custom")
                                            .then(Commands.argument("sanction", StringArgumentType.string())
                                                    .then(Commands.argument("promise", StringArgumentType.greedyString())
                                                            .executes(context -> create(context, SanctionKind.CUSTOM)))))))
                    .then(LiteralArgumentBuilder.<CommandSourceStack>literal("join")
                            .executes(usage("join"))
                            .then(Commands.argument("code", StringArgumentType.word())
                                    .executes(this::join)))
                    .then(LiteralArgumentBuilder.<CommandSourceStack>literal("accept")
                            .executes(this::accept))
                    .then(LiteralArgumentBuilder.<CommandSourceStack>literal("refuse")
                            .executes(this::refuse))
                    .then(LiteralArgumentBuilder.<CommandSourceStack>literal("info")
                            .executes(usage("info"))
                            .then(Commands.argument("id", IntegerArgumentType.integer(1))
                                    .executes(this::info)))
                    .then(LiteralArgumentBuilder.<CommandSourceStack>literal("cancel")
                            .executes(usage("cancel"))
                            .then(Commands.argument("id", IntegerArgumentType.integer(1))
                                    .executes(this::cancel)))
                    .build();

            event.registrar().register(node, aliases);
        });
    }

    private int create(CommandContext<CommandSourceStack> context, SanctionKind kind) {
        CommandSender sender = context.getSource().getSender();
        if (!(sender instanceof Player founder)) {
            sender.sendMessage(plugin.getLang().prefixed(sender, "oath.players-only"));
            return Command.SINGLE_SUCCESS;
        }

        Duration duration = TimeUtil.parse(StringArgumentType.getString(context, "duration"));
        if (duration == null) {
            founder.sendMessage(plugin.getLang().prefixed(founder, "oath.create.invalid-duration"));
            return Command.SINGLE_SUCCESS;
        }

        double amount = 0;
        long banSeconds = 0;
        String customText = "";
        switch (kind) {
            case FINE -> {
                amount = parseAmount(StringArgumentType.getString(context, "amount"));
                if (amount <= 0) {
                    founder.sendMessage(plugin.getLang().prefixed(founder, "oath.create.invalid-value"));
                    return Command.SINGLE_SUCCESS;
                }
            }
            case TEMP_BAN -> {
                Duration ban = TimeUtil.parse(StringArgumentType.getString(context, "ban-duration"));
                if (ban == null) {
                    founder.sendMessage(plugin.getLang().prefixed(founder, "oath.create.invalid-value"));
                    return Command.SINGLE_SUCCESS;
                }
                banSeconds = ban.toSeconds();
            }
            case CUSTOM -> {
                customText = StringArgumentType.getString(context, "sanction");
                if (customText.isBlank()) {
                    founder.sendMessage(plugin.getLang().prefixed(founder, "oath.create.invalid-value"));
                    return Command.SINGLE_SUCCESS;
                }
            }
        }

        String promise = StringArgumentType.getString(context, "promise");
        plugin.getOathManager().create(founder, duration.toSeconds(), kind, amount, banSeconds, customText, promise);
        return Command.SINGLE_SUCCESS;
    }

    private int join(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getLang().prefixed(sender, "oath.players-only"));
            return Command.SINGLE_SUCCESS;
        }
        plugin.getOathManager().join(player, StringArgumentType.getString(context, "code"));
        return Command.SINGLE_SUCCESS;
    }

    private int accept(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getLang().prefixed(sender, "oath.players-only"));
            return Command.SINGLE_SUCCESS;
        }
        plugin.getOathManager().accept(player);
        return Command.SINGLE_SUCCESS;
    }

    private int refuse(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getLang().prefixed(sender, "oath.players-only"));
            return Command.SINGLE_SUCCESS;
        }
        plugin.getOathManager().refuse(player);
        return Command.SINGLE_SUCCESS;
    }

    private int info(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        long id = IntegerArgumentType.getInteger(context, "id");
        Oath oath = plugin.getOathManager().oath(id);
        if (oath == null) {
            sender.sendMessage(plugin.getLang().prefixed(sender, "oath.not-found", "id", String.valueOf(id)));
            return Command.SINGLE_SUCCESS;
        }
        if (sender instanceof Player player && !plugin.getOathManager().isParty(id, player.getUniqueId())) {
            sender.sendMessage(plugin.getLang().prefixed(sender, "oath.info.not-party", "id", String.valueOf(id)));
            return Command.SINGLE_SUCCESS;
        }
        plugin.getOathManager().getDisplay().recap(sender, oath, plugin.getOathManager().membersOf(id));
        return Command.SINGLE_SUCCESS;
    }

    private int cancel(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getLang().prefixed(sender, "oath.players-only"));
            return Command.SINGLE_SUCCESS;
        }
        plugin.getOathManager().requestCancel(player, IntegerArgumentType.getInteger(context, "id"));
        return Command.SINGLE_SUCCESS;
    }

    /** Message d'utilisation d'une sous-commande ; key null pour l'usage general. */
    private Command<CommandSourceStack> usage(String key) {
        return context -> {
            CommandSender sender = context.getSource().getSender();
            String path = key == null ? "oath.usage.general" : "oath.usage." + key;
            sender.sendMessage(plugin.getLang().prefixed(sender, path, "oath-command", commandName()));
            return Command.SINGLE_SUCCESS;
        };
    }

    /** @return le montant saisi, ou -1 s'il est invalide ou negatif. */
    private double parseAmount(String text) {
        try {
            double value = Double.parseDouble(text.replace(',', '.'));
            return Double.isFinite(value) && value >= 0 ? value : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private String commandName() {
        return "/" + plugin.getConfig().getString("commands.oath.name", "oath");
    }

    private static Predicate<CommandSourceStack> permission(String node) {
        return source -> source.getSender().hasPermission(node);
    }
}

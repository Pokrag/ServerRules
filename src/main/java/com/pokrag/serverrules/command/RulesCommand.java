package com.pokrag.serverrules.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.pokrag.serverrules.ServerRulesPlugin;
import com.pokrag.serverrules.gui.RulesGui;
import com.pokrag.serverrules.gui.RulesManageGui;
import com.pokrag.serverrules.rule.PendingRuleChange;
import com.pokrag.serverrules.rule.Rule;
import com.pokrag.serverrules.rule.Sanction;
import com.pokrag.serverrules.rule.SanctionType;
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
 * Commande des regles.
 *
 * Joueurs (Phases 2 et 4) :
 *   /rules            ouvre la GUI des regles (tableau en console)
 *   /rules &lt;n&gt;        affiche une regle precise
 *   /rules new        revoit les nouveautes
 *
 * Administration (Phase 8), une permission par commande :
 *   /rules manage                                               (serverrules.manage)
 *   /rules add &lt;nom&gt; &lt;fine|ban&gt; &lt;valeur&gt; [description]          (serverrules.add)
 *   /rules edit &lt;n&gt; &lt;nom&gt; &lt;fine|ban&gt; &lt;valeur&gt; [description]  (serverrules.edit)
 *   /rules remove &lt;n&gt;                                            (serverrules.remove)
 *   /rules move &lt;n&gt; &lt;position&gt;                                   (serverrules.move)
 *   /rules reload                                               (serverrules.reload)
 *   /rules confirm / /rules cancel                              confirme ou annule la modification en attente
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
                    .executes(context -> openRules(context.getSource().getSender()))
                    .then(LiteralArgumentBuilder.<CommandSourceStack>literal("gui")
                            .executes(context -> openRules(context.getSource().getSender())))
                    .then(LiteralArgumentBuilder.<CommandSourceStack>literal("manage")
                            .requires(anyPermission("serverrules.manage", "serverrules.superjudge"))
                            .executes(context -> openManageGui(context.getSource().getSender())))
                    .then(Commands.argument("number", IntegerArgumentType.integer(1))
                            .executes(this::showRule))
                    .then(LiteralArgumentBuilder.<CommandSourceStack>literal("new")
                            .executes(context -> {
                                plugin.getChangeTracker().showChangesFor(context.getSource().getSender());
                                return Command.SINGLE_SUCCESS;
                            }))
                    .then(LiteralArgumentBuilder.<CommandSourceStack>literal("confirm")
                            .executes(context -> {
                                plugin.getRulesAdminManager().confirm(context.getSource().getSender());
                                return Command.SINGLE_SUCCESS;
                            }))
                    .then(LiteralArgumentBuilder.<CommandSourceStack>literal("cancel")
                            .executes(context -> {
                                plugin.getRulesAdminManager().cancel(context.getSource().getSender());
                                return Command.SINGLE_SUCCESS;
                            }))
                    .then(LiteralArgumentBuilder.<CommandSourceStack>literal("reload")
                            .requires(permission("serverrules.reload"))
                            .executes(context -> {
                                plugin.getRulesAdminManager().reload(context.getSource().getSender());
                                return Command.SINGLE_SUCCESS;
                            }))
                    .then(LiteralArgumentBuilder.<CommandSourceStack>literal("remove")
                            .requires(permission("serverrules.remove"))
                            .executes(usage("remove"))
                            .then(Commands.argument("number", IntegerArgumentType.integer(1))
                                    .executes(this::remove)))
                    .then(LiteralArgumentBuilder.<CommandSourceStack>literal("move")
                            .requires(permission("serverrules.move"))
                            .executes(usage("move"))
                            .then(Commands.argument("number", IntegerArgumentType.integer(1))
                                    .then(Commands.argument("position", IntegerArgumentType.integer(1))
                                            .executes(context -> {
                                                plugin.getRulesAdminManager().move(
                                                        context.getSource().getSender(),
                                                        IntegerArgumentType.getInteger(context, "number"),
                                                        IntegerArgumentType.getInteger(context, "position"));
                                                return Command.SINGLE_SUCCESS;
                                            }))))
                    .then(LiteralArgumentBuilder.<CommandSourceStack>literal("add")
                            .requires(permission("serverrules.add"))
                            .executes(usage("add"))
                            .then(content(false)))
                    .then(LiteralArgumentBuilder.<CommandSourceStack>literal("edit")
                            .requires(permission("serverrules.edit"))
                            .executes(usage("edit"))
                            .then(Commands.argument("number", IntegerArgumentType.integer(1))
                                    .executes(usage("edit"))
                                    .then(content(true))))
                    .build();

            event.registrar().register(node, aliases);
        });
    }

    /** `/rules` : ouvre la GUI des regles pour un joueur, affiche le tableau pour tout autre emetteur. */
    private int openRules(CommandSender sender) {
        if (sender instanceof Player player) {
            RulesGui.open(plugin, player);
        } else {
            plugin.getDisplay().showTable(sender);
        }
        return Command.SINGLE_SUCCESS;
    }

    /** Ouvre la GUI de gestion des regles (`/rules manage`). */
    private int openManageGui(CommandSender sender) {
        if (sender instanceof Player player) {
            RulesManageGui.open(plugin, player);
        } else {
            sender.sendMessage(plugin.getLang().prefixed(sender, "gui.players-only"));
        }
        return Command.SINGLE_SUCCESS;
    }

    /** Affiche une regle precise (`/rules &lt;n&gt;`). */
    private int showRule(CommandContext<CommandSourceStack> context) {
        int number = IntegerArgumentType.getInteger(context, "number");
        CommandSender sender = context.getSource().getSender();
        Rule rule = plugin.getRulesManager().byNumber(number);
        if (rule == null) {
            notFound(sender, number);
        } else {
            plugin.getDisplay().showRule(sender, number, rule);
        }
        return Command.SINGLE_SUCCESS;
    }

    /** Prepare la suppression d'une regle (confirmation demandee). */
    private int remove(CommandContext<CommandSourceStack> context) {
        int number = IntegerArgumentType.getInteger(context, "number");
        CommandSender sender = context.getSource().getSender();
        Rule rule = plugin.getRulesManager().byNumber(number);
        if (rule == null) {
            notFound(sender, number);
            return 0;
        }
        plugin.getRulesAdminManager().request(sender,
                new PendingRuleChange(PendingRuleChange.Kind.REMOVE, number, rule.name(), "", List.of()));
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Construit l'arbre de contenu d'une regle : &lt;nom&gt; (fine|ban|custom) &lt;valeur&gt; [description].
     *
     * @param edit true pour `/rules edit` (le numero de regle est lu dans le contexte).
     */
    private RequiredArgumentBuilder<CommandSourceStack, String> content(boolean edit) {
        return Commands.argument("name", StringArgumentType.string())
                .then(sanctionNode("fine", SanctionType.FINE, edit))
                .then(sanctionNode("ban", SanctionType.TEMP_BAN, edit))
                .then(customNode(edit));
    }

    /** Construit le choix du type de sanction et la saisie de sa valeur. */
    private LiteralArgumentBuilder<CommandSourceStack> sanctionNode(String word, SanctionType type, boolean edit) {
        return LiteralArgumentBuilder.<CommandSourceStack>literal(word)
                .executes(usage(edit ? "edit" : "add"))
                .then(Commands.argument("value", StringArgumentType.word())
                        .executes(context -> submit(context, edit, type, ""))
                        .then(Commands.argument("description", StringArgumentType.greedyString())
                                .executes(context -> submit(context, edit, type,
                                        StringArgumentType.getString(context, "description")))));
    }

    /** Sanction personnalisee : un texte libre decrivant ce que le joueur devra faire. */
    private LiteralArgumentBuilder<CommandSourceStack> customNode(boolean edit) {
        return LiteralArgumentBuilder.<CommandSourceStack>literal("custom")
                .executes(usage(edit ? "edit" : "add"))
                .then(Commands.argument("text", StringArgumentType.greedyString())
                        .executes(context -> submitCustom(context, edit,
                                StringArgumentType.getString(context, "text"))));
    }

    /** Valide la valeur saisie puis memorise la modification en attente de confirmation. */
    private int submit(CommandContext<CommandSourceStack> context, boolean edit, SanctionType type, String description) {
        CommandSender sender = context.getSource().getSender();
        String name = StringArgumentType.getString(context, "name");
        String value = StringArgumentType.getString(context, "value");

        Sanction sanction;
        if (type == SanctionType.FINE) {
            double amount = parseAmount(value);
            if (amount < 0) {
                sender.sendMessage(plugin.getLang().prefixed(sender, "error.invalid-amount"));
                return 0;
            }
            sanction = Sanction.fine(amount);
        } else {
            Duration duration = TimeUtil.parse(value);
            if (duration == null) {
                sender.sendMessage(plugin.getLang().prefixed(sender, "error.invalid-duration"));
                return 0;
            }
            sanction = Sanction.tempBan(duration);
        }

        int number = edit ? IntegerArgumentType.getInteger(context, "number") : -1;
        PendingRuleChange.Kind kind = edit ? PendingRuleChange.Kind.EDIT : PendingRuleChange.Kind.ADD;
        plugin.getRulesAdminManager().request(sender,
                new PendingRuleChange(kind, number, name, description, List.of(sanction)));
        return Command.SINGLE_SUCCESS;
    }

    /** Memorise une regle dont la sanction est un texte libre (type CUSTOM). */
    private int submitCustom(CommandContext<CommandSourceStack> context, boolean edit, String text) {
        CommandSender sender = context.getSource().getSender();
        if (text.isBlank()) {
            sender.sendMessage(plugin.getLang().prefixed(sender, "error.invalid-sanction"));
            return 0;
        }
        String name = StringArgumentType.getString(context, "name");
        int number = edit ? IntegerArgumentType.getInteger(context, "number") : -1;
        PendingRuleChange.Kind kind = edit ? PendingRuleChange.Kind.EDIT : PendingRuleChange.Kind.ADD;
        plugin.getRulesAdminManager().request(sender,
                new PendingRuleChange(kind, number, name, "", List.of(Sanction.custom(text))));
        return Command.SINGLE_SUCCESS;
    }

    /** Message d'utilisation d'une sous-commande d'administration. */
    private Command<CommandSourceStack> usage(String key) {
        return context -> {
            CommandSender sender = context.getSource().getSender();
            sender.sendMessage(plugin.getLang().prefixed(sender, "rules.admin.usage." + key,
                    "rules-command", commandName()));
            return Command.SINGLE_SUCCESS;
        };
    }

    private void notFound(CommandSender sender, int number) {
        sender.sendMessage(plugin.getLang().prefixed(sender, "rules.not-found",
                "number", String.valueOf(number)));
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
        return "/" + plugin.getConfig().getString("commands.rules.name", "rules");
    }

    private static Predicate<CommandSourceStack> permission(String node) {
        return source -> source.getSender().hasPermission(node);
    }

    /** @return une condition vraie si l'emetteur possede au moins une des permissions indiquees. */
    private static Predicate<CommandSourceStack> anyPermission(String... nodes) {
        return source -> {
            for (String node : nodes) {
                if (source.getSender().hasPermission(node)) {
                    return true;
                }
            }
            return false;
        };
    }
}

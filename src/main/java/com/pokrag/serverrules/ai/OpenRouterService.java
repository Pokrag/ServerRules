package com.pokrag.serverrules.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pokrag.serverrules.ServerRulesPlugin;
import com.pokrag.serverrules.util.Http;
import com.pokrag.serverrules.util.Sync;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Traduit une sanction ecrite librement en commande serveur, via l'API OpenRouter.
 *
 * - Desactive par defaut : sans cle API ({@code openrouter.api-key}), {@link #configured()}
 *   vaut false et la sanction libre reste a la charge de l'administrateur/juge.
 * - Le modele est choisi dans la configuration ({@code openrouter.model}) ; un modele
 *   gratuit performant est utilise par defaut.
 * - L'IA repond soit une commande (executable en console), soit l'abandon ({@code PASS})
 *   qui laisse le juge decider.
 * - Si l'API n'a plus de credit, les joueurs disposant de la permission dediee
 *   ({@code serverrules.ai.notify}) sont prevenus.
 *
 * Aucune commande n'est exposee aux joueurs : le service n'est appele qu'en interne,
 * lors du traitement d'une plainte par un juge.
 */
public final class OpenRouterService {

    /** Modele gratuit performant propose par defaut. */
    public static final String DEFAULT_MODEL = "google/gemma-4-31b-it:free";

    private static final String DEFAULT_ENDPOINT = "https://openrouter.ai/api/v1/chat/completions";
    private static final String NOTIFY_PERMISSION = "serverrules.ai.notify";

    /** Resultat possible d'une demande a l'IA. */
    public enum VerdictKind {
        /** L'IA a produit une commande executable. */
        COMMAND,
        /** L'IA laisse la main au juge. */
        PASS,
        /** L'API n'a plus de credit. */
        NO_CREDIT,
        /** Erreur technique (reseau, statut inattendu...). */
        ERROR
    }

    /** Verdict rendu par l'IA : le type, et la commande s'il y en a une. */
    public record Verdict(VerdictKind kind, String command) {

        static Verdict command(String command) {
            return new Verdict(VerdictKind.COMMAND, command);
        }

        static Verdict pass() {
            return new Verdict(VerdictKind.PASS, "");
        }

        static Verdict noCredit() {
            return new Verdict(VerdictKind.NO_CREDIT, "");
        }

        static Verdict error() {
            return new Verdict(VerdictKind.ERROR, "");
        }
    }

    private final ServerRulesPlugin plugin;

    public OpenRouterService(ServerRulesPlugin plugin) {
        this.plugin = plugin;
    }

    /** @return true si une cle API et un modele sont renseignes (sinon la fonctionnalite est inactive). */
    public boolean configured() {
        String key = apiKey();
        return key != null && !key.isBlank() && !model().isBlank();
    }

    /**
     * Demande a l'IA de traduire une sanction libre en commande.
     *
     * @param text     la sanction telle qu'ecrite par le juge
     * @param accused  le pseudo du joueur concerne
     * @param ruleName le nom de la regle citee (contexte)
     * @return un futur rendant le verdict (jamais null).
     */
    public CompletableFuture<Verdict> toCommand(String text, String accused, String ruleName) {
        if (!configured()) {
            return CompletableFuture.completedFuture(Verdict.pass());
        }
        return CompletableFuture.supplyAsync(() -> request(text, accused, ruleName));
    }

    private Verdict request(String text, String accused, String ruleName) {
        try {
            Http.Response response = Http.post(endpoint(), headers(), body(text, accused, ruleName), timeoutSeconds());

            if (response.status() == 402 || mentionsInsufficientCredit(response.body())) {
                notifyNoCredit();
                return Verdict.noCredit();
            }
            if (response.status() >= 300) {
                plugin.getLogger().warning("OpenRouter : statut " + response.status() + " - " + summarize(response.body()));
                return Verdict.error();
            }
            return interpret(response.body());
        } catch (Exception error) {
            plugin.getLogger().warning("Requete OpenRouter impossible : " + error.getMessage());
            return Verdict.error();
        }
    }

    /** Lit la reponse de l'API et en deduit une commande, ou l'abandon. */
    private Verdict interpret(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonArray choices = root.getAsJsonArray("choices");
            if (choices == null || choices.isEmpty()) {
                return Verdict.error();
            }
            JsonElement message = choices.get(0).getAsJsonObject().get("message");
            if (message == null || !message.isJsonObject()) {
                return Verdict.error();
            }
            JsonElement content = message.getAsJsonObject().get("content");
            if (content == null || content.isJsonNull()) {
                return Verdict.pass();
            }
            return parseContent(content.getAsString());
        } catch (Exception error) {
            plugin.getLogger().warning("Reponse OpenRouter illisible : " + error.getMessage());
            return Verdict.error();
        }
    }

    /** Analyse le texte rendu par l'IA : "COMMAND: ..." produit une commande, sinon abandon. */
    private Verdict parseContent(String content) {
        String cleaned = content.replace("`", "").trim();
        for (String rawLine : cleaned.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            String upper = line.toUpperCase(Locale.ROOT);
            if (upper.startsWith("COMMAND:")) {
                String command = line.substring("COMMAND:".length()).trim();
                while (command.startsWith("/")) {
                    command = command.substring(1).trim();
                }
                return command.isEmpty() ? Verdict.pass() : Verdict.command(command);
            }
            // Toute autre reponse (y compris "PASS") laisse la main au juge.
            return Verdict.pass();
        }
        return Verdict.pass();
    }

    /** Construit le corps JSON de la requete de chat. */
    private String body(String text, String accused, String ruleName) {
        String system = "Tu es un assistant technique d'un serveur Minecraft Paper. On te decrit une sanction "
                + "en langage naturel et tu la traduis en UNE commande executee par la console qui l'applique au joueur.\n"
                + "Reponds uniquement par une seule ligne, sans explication ni balise de code :\n"
                + "- \"COMMAND: <commande>\" avec une commande valide (sans slash initial) utilisant le pseudo du joueur ; ou\n"
                + "- \"PASS\" si aucune commande fiable ne permet de l'appliquer.\n"
                + "Utilise le pseudo exact du joueur. N'invente jamais de commande : en cas de doute, reponds PASS.";
        String user = "Regle concernee : " + safe(ruleName) + "\n"
                + "Joueur : " + safe(accused) + "\n"
                + "Sanction a appliquer : " + safe(text);

        JsonObject systemMessage = new JsonObject();
        systemMessage.addProperty("role", "system");
        systemMessage.addProperty("content", system);
        JsonObject userMessage = new JsonObject();
        userMessage.addProperty("role", "user");
        userMessage.addProperty("content", user);

        JsonArray messages = new JsonArray();
        messages.add(systemMessage);
        messages.add(userMessage);

        JsonObject payload = new JsonObject();
        payload.addProperty("model", model());
        payload.addProperty("temperature", 0);
        payload.addProperty("max_tokens", 100);
        payload.add("messages", messages);
        return payload.toString();
    }

    private Map<String, String> headers() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + apiKey());
        headers.put("Content-Type", "application/json");
        // Recommande par OpenRouter pour identifier l'application appelante.
        headers.put("HTTP-Referer", "https://github.com/Pokrag/ServerRules");
        headers.put("X-Title", "ServerRules");
        return headers;
    }

    /** Previent les joueurs autorises (et la console) que l'API n'a plus de credit. */
    private void notifyNoCredit() {
        plugin.getLogger().warning("OpenRouter n'a plus de credit : les sanctions libres restent a traiter manuellement.");
        Sync.run(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.hasPermission(NOTIFY_PERMISSION)) {
                    player.sendMessage(plugin.getLang().prefixed(player, "ai.no-credit-notify"));
                }
            }
        });
    }

    private boolean mentionsInsufficientCredit(String body) {
        if (body == null) {
            return false;
        }
        String lower = body.toLowerCase(Locale.ROOT);
        return lower.contains("insufficient") || lower.contains("not enough credit")
                || lower.contains("no credits") || lower.contains("quota");
    }

    private String apiKey() {
        return plugin.getConfig().getString("openrouter.api-key", "");
    }

    private String model() {
        return plugin.getConfig().getString("openrouter.model", DEFAULT_MODEL).trim();
    }

    private String endpoint() {
        return plugin.getConfig().getString("openrouter.endpoint", DEFAULT_ENDPOINT).trim();
    }

    private int timeoutSeconds() {
        return (int) Math.max(5, plugin.getConfig().getLong("openrouter.timeout-seconds", 30));
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String summarize(String body) {
        if (body == null || body.isBlank()) {
            return "(reponse vide)";
        }
        String flat = body.replaceAll("\\s+", " ").trim();
        return flat.length() > 200 ? flat.substring(0, 200) + "..." : flat;
    }
}

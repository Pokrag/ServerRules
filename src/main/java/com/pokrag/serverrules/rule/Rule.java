package com.pokrag.serverrules.rule;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Une regle du serveur.
 *
 * - id : identifiant interne stable, jamais reutilise.
 * - name / description / sanction : contenu modifiable par l'admin.
 * L'ordre (numero) n'appartient pas a la regle : il est gere par {@link RulesManager}.
 */
public final class Rule {

    private final int id;
    private final String name;
    private final String description;
    private final Sanction sanction;

    public Rule(int id, String name, String description, Sanction sanction) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.sanction = sanction;
    }

    public int id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    public Sanction sanction() {
        return sanction;
    }

    /**
     * Revision de la regle : un hash qui ne depend ni de l'ordre ni de l'id.
     * Deplacer une regle ne change donc pas sa revision.
     */
    public String revision() {
        String raw = name + "\u0001" + description + "\u0001" + sanction.signature();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", bytes[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(raw.hashCode());
        }
    }
}

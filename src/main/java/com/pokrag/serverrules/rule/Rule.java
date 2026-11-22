package com.pokrag.serverrules.rule;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * Une regle du serveur.
 *
 * - id : identifiant interne stable, jamais reutilise.
 * - name / description / sanctions : contenu modifiable par l'admin.
 *   Une regle peut prevoir plusieurs sanctions (amende, ban, sanction libre...).
 * L'ordre (numero) n'appartient pas a la regle : il est gere par {@link RulesManager}.
 */
public final class Rule {

    private final int id;
    private final String name;
    private final String description;
    private final List<Sanction> sanctions;

    public Rule(int id, String name, String description, List<Sanction> sanctions) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.sanctions = List.copyOf(sanctions);
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

    /** @return les sanctions prevues par la regle, dans l'ordre. */
    public List<Sanction> sanctions() {
        return sanctions;
    }

    /**
     * Revision de la regle : un hash qui ne depend ni de l'ordre ni de l'id.
     * Deplacer une regle ne change donc pas sa revision.
     */
    public String revision() {
        StringBuilder raw = new StringBuilder(name).append('\u0001').append(description);
        for (Sanction sanction : sanctions) {
            raw.append('\u0001').append(sanction.signature());
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(raw.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", bytes[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(raw.toString().hashCode());
        }
    }
}

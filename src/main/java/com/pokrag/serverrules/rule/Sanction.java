package com.pokrag.serverrules.rule;

import java.time.Duration;

/**
 * Sanction prevue par une regle : amende (FINE), bannissement temporaire
 * (TEMP_BAN) ou sanction personnalisee ecrite librement par un juge (CUSTOM).
 */
public final class Sanction {

    private final SanctionType type;
    private final double amount;
    private final Duration duration;
    private final String text;

    private Sanction(SanctionType type, double amount, Duration duration, String text) {
        this.type = type;
        this.amount = amount;
        this.duration = duration;
        this.text = text;
    }

    public static Sanction fine(double amount) {
        return new Sanction(SanctionType.FINE, amount, null, "");
    }

    public static Sanction tempBan(Duration duration) {
        return new Sanction(SanctionType.TEMP_BAN, 0, duration, "");
    }

    /** Sanction personnalisee : le texte decrit ce que le joueur devra faire. */
    public static Sanction custom(String text) {
        return new Sanction(SanctionType.CUSTOM, 0, null, text == null ? "" : text.trim());
    }

    public SanctionType type() {
        return type;
    }

    public double amount() {
        return amount;
    }

    public Duration duration() {
        return duration;
    }

    /** @return le texte d'une sanction personnalisee (vide pour les autres types). */
    public String text() {
        return text;
    }

    /** Signature brute, utilisee pour detecter une modification de la regle. */
    public String signature() {
        return switch (type) {
            case FINE -> "FINE:" + amount;
            case TEMP_BAN -> "TEMP_BAN:" + duration.toSeconds();
            case CUSTOM -> "CUSTOM:" + text;
        };
    }
}

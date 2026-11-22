package com.pokrag.serverrules.rule;

import java.time.Duration;

/** Sanction prevue par une regle : amende (FINE) ou bannissement temporaire (TEMP_BAN). */
public final class Sanction {

    private final SanctionType type;
    private final double amount;
    private final Duration duration;

    private Sanction(SanctionType type, double amount, Duration duration) {
        this.type = type;
        this.amount = amount;
        this.duration = duration;
    }

    public static Sanction fine(double amount) {
        return new Sanction(SanctionType.FINE, amount, null);
    }

    public static Sanction tempBan(Duration duration) {
        return new Sanction(SanctionType.TEMP_BAN, 0, duration);
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

    /** Signature brute, utilisee pour detecter une modification de la regle. */
    public String signature() {
        return type == SanctionType.FINE
                ? "FINE:" + amount
                : "TEMP_BAN:" + duration.toSeconds();
    }
}

package com.pokrag.serverrules.complaint;

import com.pokrag.serverrules.rule.Sanction;
import com.pokrag.serverrules.rule.SanctionType;

/**
 * Une sanction figee, telle que citee par une plainte (copie d'une sanction de regle
 * ou de serment au moment du depot).
 *
 * @param kind            type de sanction
 * @param amount          montant (amendes)
 * @param durationSeconds duree (bannissement temporaire)
 * @param customText      texte libre (sanction personnalisee)
 */
public record CitedSanction(SanctionKind kind, double amount, long durationSeconds, String customText) {

    /** Construit la version figee d'une sanction de regle. */
    public static CitedSanction of(Sanction sanction) {
        SanctionKind kind = switch (sanction.type()) {
            case FINE -> SanctionKind.FINE;
            case TEMP_BAN -> SanctionKind.TEMP_BAN;
            case CUSTOM -> SanctionKind.CUSTOM;
        };
        long seconds = sanction.duration() == null ? 0 : sanction.duration().toSeconds();
        return new CitedSanction(kind, sanction.amount(), seconds, sanction.text());
    }

    /** @return true si cette sanction est modulable (amende ou duree de ban). */
    public boolean modulable() {
        return kind == SanctionKind.FINE || kind == SanctionKind.TEMP_BAN;
    }

    /** Signature brute, utilisee pour comparer deux sanctions figees. */
    public String signature() {
        return switch (kind) {
            case FINE -> "FINE:" + amount;
            case TEMP_BAN -> "TEMP_BAN:" + durationSeconds;
            case CUSTOM -> "CUSTOM:" + customText;
        };
    }

    /** @return la sanction de regle correspondante (pour les messages). */
    public SanctionType ruleType() {
        return switch (kind) {
            case FINE -> SanctionType.FINE;
            case TEMP_BAN -> SanctionType.TEMP_BAN;
            case CUSTOM -> SanctionType.CUSTOM;
        };
    }
}

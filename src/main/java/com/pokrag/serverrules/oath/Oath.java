package com.pokrag.serverrules.oath;

import com.pokrag.serverrules.complaint.SanctionKind;

import java.util.UUID;

/**
 * Un serment : une promesse entre deux joueurs ou plus, avec une sanction prevue.
 *
 * - durationSeconds : duree de la promesse, a partir de l'activation (0 ou negatif = infinie) ;
 * - sanctionKind / amount / sanctionDurationSeconds / customText : sanction prevue ;
 * - code / codeExpiresAt : code d'invitation et sa date d'expiration ;
 * - activatedAt : date d'activation (0 tant que le serment n'est pas actif).
 */
public record Oath(long id, UUID founder, String founderName, String promise,
                   long durationSeconds, SanctionKind sanctionKind, double amount,
                   long sanctionDurationSeconds, String customText, String code,
                   long createdAt, long codeExpiresAt, long activatedAt, OathStatus status) {

    /** @return true si le serment est actif. */
    public boolean isActive() {
        return status == OathStatus.ACTIF;
    }

    /** @return true si la promesse n'a pas de duree limite (0 ou negatif = infinie). */
    public boolean isInfinite() {
        return durationSeconds <= 0;
    }

    /** @return date d'expiration de la promesse (0 si le serment n'est pas actif ou est infini). */
    public long expiresAt() {
        return activatedAt <= 0 || isInfinite() ? 0 : activatedAt + durationSeconds * 1000L;
    }

    /** @return true si la promesse est encore dans sa duree (ou infinie). */
    public boolean isRunning(long now) {
        return isActive() && (activatedAt <= 0 || isInfinite() || now < expiresAt());
    }

    /** @return true si le delai de plainte apres expiration (en jours) est encore ouvert. */
    public boolean isWithinGrace(long now, int graceDays) {
        if (isRunning(now)) {
            return true;
        }
        if (status != OathStatus.EXPIRE || activatedAt <= 0) {
            return false;
        }
        return now < expiresAt() + graceDays * 86400_000L;
    }
}

package com.pokrag.serverrules.sanction;

import com.pokrag.serverrules.complaint.SanctionKind;

/**
 * Une sanction appliquee, enregistree dans l'historique SQLite.
 *
 * @param complaintId     plainte liee
 * @param ruleRefId       identifiant interne de la reference citee
 * @param ruleNumber      numero de la reference citee au moment de la sanction
 * @param ruleName        nom de la reference citee au moment de la sanction
 * @param kind            type de sanction (amende, ban, personnalisee)
 * @param amount          montant applique (amendes)
 * @param durationSeconds duree appliquee (ban temporaire)
 * @param judgeName       juge ayant applique la sanction
 * @param date            date d'application (epoch millis)
 */
public record AppliedSanction(long complaintId, int ruleRefId, int ruleNumber, String ruleName,
                              SanctionKind kind, double amount, long durationSeconds,
                              String judgeName, long date) {
}

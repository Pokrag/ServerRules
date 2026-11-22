package com.pokrag.serverrules.complaint;

/**
 * Type de la sanction citee par une plainte.
 *
 * - FINE et TEMP_BAN viennent des regles ;
 * - CUSTOM n'existe que pour les serments (texte libre, Phase 9).
 */
public enum SanctionKind {

    FINE,
    TEMP_BAN,
    CUSTOM
}

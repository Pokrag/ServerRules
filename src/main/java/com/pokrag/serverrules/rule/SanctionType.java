package com.pokrag.serverrules.rule;

/** Type de sanction prevue par une regle. */
public enum SanctionType {
    FINE,
    TEMP_BAN,

    /** Sanction ecrite librement par un juge : elle peut etre traitee par l'IA (Phase 13). */
    CUSTOM
}

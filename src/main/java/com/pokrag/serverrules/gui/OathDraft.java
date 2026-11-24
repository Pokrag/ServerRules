package com.pokrag.serverrules.gui;

import com.pokrag.serverrules.complaint.SanctionKind;

/**
 * Brouillon de serment partage par les menus de creation (Phase 11).
 *
 * Il permet a {@link OathCreateGui} et {@link OathSanctionGui} de modifier en
 * commun la duree, la sanction et la promesse avant confirmation.
 */
final class OathDraft {

    /** Duree en secondes ; null tant qu'elle n'a pas ete choisie, 0 ou negatif = illimite. */
    Long durationSeconds;
    String promise = "";
    /** Type de sanction ; null tant qu'il n'a pas ete choisi. */
    SanctionKind kind;
    double amount;
    long banSeconds;
    String customText = "";
}

package com.pokrag.serverrules.complaint;

/** Statut d'une plainte. */
public enum ComplaintStatus {

    EN_ATTENTE("pending"),
    TRAITEE("handled"),
    CLASSEE_SANS_SUITE("dismissed"),

    /** Sanction personnalisee d'un serment : le juge doit suivre son execution (Phase 9). */
    SANCTION_A_EXECUTER("sanction-to-execute");

    private final String langKey;

    ComplaintStatus(String langKey) {
        this.langKey = langKey;
    }

    /** @return la cle de langue courte du statut (voir {@code complaint.status.*}). */
    public String langKey() {
        return langKey;
    }
}

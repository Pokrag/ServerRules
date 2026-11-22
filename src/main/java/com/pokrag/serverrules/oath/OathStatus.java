package com.pokrag.serverrules.oath;

/** Statut d'un serment. */
public enum OathStatus {

    /** Code emis, personne n'a encore rejoint. */
    PROPOSE("proposed"),

    /** Personne n'a rejoint avant l'expiration du code. */
    PROPOSITION_EXPIREE("proposal-expired"),

    /** Au moins deux parties liees, duree en cours. */
    ACTIF("active"),

    /** Duree ecoulee. */
    EXPIRE("expired"),

    /** Annule d'un commun accord : plus aucune plainte possible. */
    ANNULE("cancelled"),

    /** Un manquement a ete sanctionne. */
    NON_RESPECTE("broken"),

    /** Le dossier lie au serment a ete clos. */
    TRAITE("handled");

    private final String langKey;

    OathStatus(String langKey) {
        this.langKey = langKey;
    }

    /** @return la cle de langue courte du statut (voir {@code oath.status.*}). */
    public String langKey() {
        return langKey;
    }
}

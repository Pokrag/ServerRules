package com.pokrag.serverrules.sanction;

/** Resultat de l'application d'une sanction : succes, ou cle de langue expliquant l'echec. */
public record SanctionResult(boolean success, String errorKey) {

    public static SanctionResult ok() {
        return new SanctionResult(true, null);
    }

    public static SanctionResult error(String key) {
        return new SanctionResult(false, key);
    }
}

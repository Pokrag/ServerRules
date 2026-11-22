package com.pokrag.serverrules.complaint;

/**
 * Resultat d'une operation sur une plainte : succes, ou cle de langue expliquant l'echec.
 *
 * @param success         true si l'operation a reussi
 * @param errorKey        cle de langue de l'erreur, ou null en cas de succes
 * @param cooldownSeconds temps restant avant de pouvoir recommencer (anti-spam), 0 sinon
 */
public record ComplaintResult(boolean success, String errorKey, long cooldownSeconds) {

    public static ComplaintResult ok() {
        return new ComplaintResult(true, null, 0);
    }

    public static ComplaintResult error(String key) {
        return new ComplaintResult(false, key, 0);
    }

    public static ComplaintResult cooldown(String key, long seconds) {
        return new ComplaintResult(false, key, seconds);
    }
}

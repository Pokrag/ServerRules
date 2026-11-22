package com.pokrag.serverrules.oath;

import java.security.SecureRandom;
import java.util.function.Predicate;

/**
 * Generation des codes d'invitation d'un serment.
 *
 * L'alphabet exclut les caracteres ambigus (0/O et 1/I) pour eviter les erreurs
 * de saisie lorsque le fondateur transmet le code de vive voix.
 */
final class OathCode {

    /** 32 caracteres sans ambiguite : ni 0/O, ni 1/I. */
    private static final char[] ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private OathCode() {
    }

    /**
     * @param length longueur demandee (au moins 2)
     * @param taken  predicat vrai si le code est deja utilise
     * @return un code unique vis-a-vis du predicat fourni
     */
    static String generate(int length, Predicate<String> taken) {
        int size = Math.max(2, length);
        for (int attempt = 0; attempt < 200; attempt++) {
            StringBuilder sb = new StringBuilder(size);
            for (int i = 0; i < size; i++) {
                sb.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
            }
            String code = sb.toString();
            if (!taken.test(code)) {
                return code;
            }
        }
        // Tres improbable : on allonge d'un caractere pour garantir l'unicite.
        return generate(size + 1, taken);
    }
}

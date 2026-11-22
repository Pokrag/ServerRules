package com.pokrag.serverrules.rule;

import java.util.List;

/**
 * Modification de regle en attente de confirmation par son auteur.
 *
 * - ADD    : nouvelle regle (le numero est celui qu'elle prendra a l'application) ;
 * - EDIT   : remplacement du contenu de la regle "number" (identifiant conserve) ;
 * - REMOVE : suppression de la regle "number".
 */
public record PendingRuleChange(Kind kind, int number, String name, String description, List<Sanction> sanctions) {

    public enum Kind {
        ADD,
        EDIT,
        REMOVE
    }
}

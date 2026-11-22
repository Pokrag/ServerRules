package com.pokrag.serverrules.rule;

/**
 * Une entree du journal des changements de regles.
 *
 * @param seq        numero de sequence global (croissant)
 * @param type       ajout, modification ou suppression
 * @param ruleNumber numero de la regle au moment du changement
 * @param ruleName   nom de la regle au moment du changement
 * @param date       date du changement (epoch millis)
 */
public record RuleChange(long seq, ChangeType type, int ruleNumber, String ruleName, long date) {
}

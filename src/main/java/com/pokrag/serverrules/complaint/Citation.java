package com.pokrag.serverrules.complaint;

import com.pokrag.serverrules.oath.Oath;
import com.pokrag.serverrules.rule.Rule;
import com.pokrag.serverrules.rule.SanctionType;

/**
 * Reference figee citee par une plainte.
 *
 * Le numero, le nom et la sanction sont copies au moment du depot : si la regle
 * est modifiee ou supprimee ensuite, la plainte reste traitable.
 */
public record Citation(CitationType type, int refId, int number, String name,
                       SanctionKind sanctionKind, double amount, long durationSeconds, String customText) {

    /** Construit la citation d'une regle (numero, nom et sanction figes). */
    public static Citation ofRule(Rule rule, int number) {
        SanctionType sanctionType = rule.sanction().type();
        SanctionKind kind = sanctionType == SanctionType.FINE ? SanctionKind.FINE : SanctionKind.TEMP_BAN;
        long seconds = rule.sanction().duration() == null ? 0 : rule.sanction().duration().toSeconds();
        return new Citation(CitationType.RULE, rule.id(), number, rule.name(), kind,
                rule.sanction().amount(), seconds, "");
    }

    /** Construit la citation d'un serment (promesse et sanction figees). */
    public static Citation ofOath(Oath oath) {
        return new Citation(CitationType.OATH, (int) oath.id(), 0, oath.promise(),
                oath.sanctionKind(), oath.amount(), oath.sanctionDurationSeconds(), oath.customText());
    }
}

package com.pokrag.serverrules.complaint;

import com.pokrag.serverrules.oath.Oath;
import com.pokrag.serverrules.rule.Rule;

import java.util.List;

/**
 * Reference figee citee par une plainte.
 *
 * Le numero, le nom et les sanctions sont copies au moment du depot : si la regle
 * est modifiee ou supprimee ensuite, la plainte reste traitable.
 *
 * Une regle peut prevoir plusieurs sanctions : la citation en conserve la liste.
 */
public record Citation(CitationType type, int refId, int number, String name, List<CitedSanction> sanctions) {

    public Citation {
        sanctions = List.copyOf(sanctions);
    }

    /** Construit la citation d'une regle (numero, nom et sanctions figes). */
    public static Citation ofRule(Rule rule, int number) {
        return new Citation(CitationType.RULE, rule.id(), number, rule.name(),
                rule.sanctions().stream().map(CitedSanction::of).toList());
    }

    /** Construit la citation d'un serment (promesse et sanction figees). */
    public static Citation ofOath(Oath oath) {
        CitedSanction sanction = new CitedSanction(oath.sanctionKind(), oath.amount(),
                oath.sanctionDurationSeconds(), oath.customText());
        return new Citation(CitationType.OATH, (int) oath.id(), 0, oath.promise(), List.of(sanction));
    }

    /** @return true si la citation prevoit au moins une sanction personnalisee (texte libre). */
    public boolean hasCustom() {
        for (CitedSanction sanction : sanctions) {
            if (sanction.kind() == SanctionKind.CUSTOM) {
                return true;
            }
        }
        return false;
    }

    /** @return true si la citation prevoit au moins une sanction modulable (amende ou duree). */
    public boolean hasModulable() {
        for (CitedSanction sanction : sanctions) {
            if (sanction.modulable()) {
                return true;
            }
        }
        return false;
    }

    /** @return la sanction unique si la citation n'en prevoit qu'une, sinon null. */
    public CitedSanction single() {
        return sanctions.size() == 1 ? sanctions.get(0) : null;
    }

    /** Remplace les sanctions de la citation. */
    public Citation withSanctions(List<CitedSanction> updated) {
        return new Citation(type, refId, number, name, updated);
    }
}

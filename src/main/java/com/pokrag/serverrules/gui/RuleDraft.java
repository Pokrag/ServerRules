package com.pokrag.serverrules.gui;

import com.pokrag.serverrules.rule.Sanction;

import java.util.ArrayList;
import java.util.List;

/**
 * Brouillon de regle partage par les menus d'edition (Phase 11, section 11.7).
 *
 * Il permet a {@link RuleEditGui} et {@link SanctionListGui} de modifier en commun
 * le nom, la description et la liste des sanctions avant confirmation.
 */
final class RuleDraft {

    final boolean creating;
    final int number;
    final List<Sanction> sanctions;
    String name;
    String description;

    RuleDraft(boolean creating, int number, String name, String description, List<Sanction> sanctions) {
        this.creating = creating;
        this.number = number;
        this.name = name;
        this.description = description;
        this.sanctions = new ArrayList<>(sanctions);
    }
}

package com.pokrag.serverrules.complaint;

import java.util.UUID;

/** Une plainte enregistree. */
public record Complaint(long id, UUID plaintiff, String plaintiffName, UUID accused, String accusedName,
                        Citation citation, String details, long date, ComplaintStatus status) {

    /** @return true si le joueur donne est directement implique (plaignant ou accuse). */
    public boolean involves(UUID uuid) {
        return plaintiff.equals(uuid) || accused.equals(uuid);
    }
}

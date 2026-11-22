package com.pokrag.serverrules.complaint;

/**
 * Trace du changement de la reference citee par une plainte (superjuge).
 *
 * @param complaintId plainte concernee
 * @param oldNumber   ancien numero de la reference
 * @param oldName     ancien nom de la reference
 * @param newNumber   nouveau numero de la reference
 * @param newName     nouveau nom de la reference
 * @param judgeName   superjuge ayant effectue le changement
 * @param date        date du changement (epoch millis)
 */
public record CitationChange(long complaintId, int oldNumber, String oldName,
                             int newNumber, String newName, String judgeName, long date) {
}

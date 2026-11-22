package com.pokrag.serverrules.oath;

import java.util.UUID;

/** Une partie d'un serment (le fondateur en est une aussi). */
public record OathMember(UUID uuid, String name, long joinedAt) {
}

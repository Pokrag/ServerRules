package com.pokrag.serverrules.util;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Lecture et affichage des durees sous la forme compacte "3d", "2h30m", "90s". */
public final class TimeUtil {

    private static final Pattern PART = Pattern.compile("(\\d+)\\s*([wdhms])");

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private TimeUtil() {
    }

    /**
     * Analyse une duree compacte.
     *
     * @param input texte a analyser (ex. "1w2d3h4m5s")
     * @return la duree, ou null si le texte est invalide ou nul.
     */
    public static Duration parse(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        Matcher matcher = PART.matcher(input.toLowerCase().replace(" ", ""));
        Duration total = Duration.ZERO;
        boolean matched = false;
        int consumed = 0;
        while (matcher.find()) {
            matched = true;
            consumed += matcher.group().length();
            long value = Long.parseLong(matcher.group(1));
            total = switch (matcher.group(2)) {
                case "w" -> total.plusDays(value * 7);
                case "d" -> total.plusDays(value);
                case "h" -> total.plusHours(value);
                case "m" -> total.plusMinutes(value);
                case "s" -> total.plusSeconds(value);
                default -> total;
            };
        }
        if (!matched || consumed == 0 || total.isZero() || total.isNegative()) {
            return null;
        }
        return total;
    }

    /**
     * Affiche une duree sous forme compacte et lisible (ex. "3d 2h").
     * Les lettres (d/h/m/s) sont volontairement neutres : elles ne dependent
     * d'aucune langue et restent lisibles partout.
     */
    public static String format(Duration duration) {
        if (duration == null) {
            return "0s";
        }
        long seconds = Math.max(0, duration.toSeconds());
        long days = seconds / 86400;
        long hours = (seconds % 86400) / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;

        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append("d ");
        if (hours > 0) sb.append(hours).append("h ");
        if (minutes > 0) sb.append(minutes).append("m ");
        if (secs > 0 || sb.isEmpty()) sb.append(secs).append("s");
        return sb.toString().trim();
    }

    /** Affiche une date (epoch millis) sous la forme "2026-11-22 14:30". */
    public static String formatDate(long epochMillis) {
        return DATE.format(Instant.ofEpochMilli(epochMillis));
    }
}

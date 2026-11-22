package com.pokrag.serverrules.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Petit utilitaire HTTP (GET / POST) partage par les services externes du plugin
 * (recherche de mise a jour, API OpenRouter). Appele uniquement depuis des threads
 * asynchrones : les exceptions d'entree/sortie remontent a l'appelant.
 */
public final class Http {

    /** Reponse d'une requete : code de statut HTTP et corps (texte). */
    public record Response(int status, String body) {
    }

    private Http() {
    }

    /** Requete GET ; le corps est lu depuis le flux d'erreur si le statut est >= 400. */
    public static Response get(String url, Map<String, String> headers, int timeoutSeconds) throws IOException {
        HttpURLConnection connection = open(url, "GET", headers, timeoutSeconds, false);
        return read(connection);
    }

    /** Requete POST avec un corps (JSON) ; le corps est lu depuis le flux d'erreur si le statut est >= 400. */
    public static Response post(String url, Map<String, String> headers, String body, int timeoutSeconds)
            throws IOException {
        HttpURLConnection connection = open(url, "POST", headers, timeoutSeconds, true);
        try (OutputStream out = connection.getOutputStream()) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }
        return read(connection);
    }

    /**
     * Requete GET binaire : renvoie le corps brut (telechargement d'un fichier, ex. un JAR).
     * Les redirections (cas des liens de release GitHub) sont suivies automatiquement.
     */
    public static byte[] getBytes(String url, Map<String, String> headers, int timeoutSeconds) throws IOException {
        HttpURLConnection connection = open(url, "GET", headers, timeoutSeconds, false);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "application/octet-stream");
        try {
            int status = connection.getResponseCode();
            if (status >= 400) {
                throw new IOException("HTTP " + status);
            }
            try (InputStream in = connection.getInputStream()) {
                return in.readAllBytes();
            }
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection open(String url, String method, Map<String, String> headers,
                                          int timeoutSeconds, boolean output) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(timeoutSeconds * 1000);
        connection.setReadTimeout(timeoutSeconds * 1000);
        connection.setDoOutput(output);
        connection.setRequestProperty("Accept", "application/json");
        if (headers != null) {
            for (Map.Entry<String, String> header : headers.entrySet()) {
                connection.setRequestProperty(header.getKey(), header.getValue());
            }
        }
        return connection;
    }

    private static Response read(HttpURLConnection connection) throws IOException {
        int status = connection.getResponseCode();
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String body = stream == null ? "" : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        connection.disconnect();
        return new Response(status, body);
    }
}

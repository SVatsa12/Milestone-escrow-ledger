package com.svatsa.escrow.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tiny HTTP helper for integration tests that drive the real API on the random server port.
 */
public final class ApiClient {

    private final int port;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    public ApiClient(int port) {
        this.port = port;
    }

    public static String[] actor(long id, String role) {
        return new String[]{"X-Actor-Id", String.valueOf(id), "X-Actor-Role", role};
    }

    public static String[] admin() {
        return actor(1, "ADMIN");
    }

    public HttpRequest.Builder postRequest(String path, String idempotencyKey, String[] headers, String body) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
        if (headers.length > 0) {
            b.headers(headers);
        }
        if (idempotencyKey != null) {
            b.header("Idempotency-Key", idempotencyKey);
        }
        return b;
    }

    public HttpResponse<String> post(String path, String idempotencyKey, String[] headers, String body) throws Exception {
        return http.send(postRequest(path, idempotencyKey, headers, body).build(), HttpResponse.BodyHandlers.ofString());
    }

    public HttpResponse<String> get(String path, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (headers.length > 0) {
            b.headers(headers);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** A POST with no Idempotency-Key and no actor headers at all. */
    public HttpResponse<String> postWithoutActor(String path, String idempotencyKey, String body) throws Exception {
        return post(path, idempotencyKey, new String[0], body);
    }

    public HttpClient http() {
        return http;
    }

    /** Assert the status (showing the body on failure) and parse the body. */
    public JsonNode expect(HttpResponse<String> response, int status) throws Exception {
        assertThat(response.statusCode())
            .as(response.request().method() + " " + response.request().uri().getPath() + " -> " + response.body())
            .isEqualTo(status);
        return response.body().isBlank() ? json.nullNode() : json.readTree(response.body());
    }

    /** POST /api/v1/transfers as ADMIN (the only role allowed to call it). */
    public HttpResponse<String> transfer(String idempotencyKey, long fromAccountId, long toAccountId, long amountPaise)
            throws Exception {
        return post("/api/v1/transfers", idempotencyKey, admin(), "{\"fromAccountId\":" + fromAccountId
            + ",\"toAccountId\":" + toAccountId + ",\"amountPaise\":" + amountPaise + ",\"memo\":\"test\"}");
    }

    public long createUser(String name, String role) throws Exception {
        return expect(post("/api/escrow/users", null, admin(), "{\"name\":\"" + name + "\",\"role\":\"" + role + "\"}"), 201)
            .path("id").asLong();
    }

    /** JSON body for a project with one milestone per amount, all due in 30 days. */
    public static String projectBody(long clientId, long freelancerId, String title, long... amounts) {
        String due = Instant.now().plus(30, ChronoUnit.DAYS).toString();
        StringBuilder ms = new StringBuilder();
        for (int i = 0; i < amounts.length; i++) {
            if (i > 0) ms.append(',');
            ms.append("{\"title\":\"m").append(i + 1).append("\",\"amountPaise\":").append(amounts[i])
                .append(",\"dueAt\":\"").append(due).append("\"}");
        }
        return "{\"clientId\":" + clientId + ",\"freelancerId\":" + freelancerId + ",\"title\":\"" + title + "\","
            + "\"reviewWindow\":\"P5D\",\"milestones\":[" + ms + "]}";
    }
}

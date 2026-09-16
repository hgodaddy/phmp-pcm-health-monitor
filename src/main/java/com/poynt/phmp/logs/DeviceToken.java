package com.poynt.phmp.logs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The access token PCM carries as a query parameter on its WebSocket URL, together with the identifiers
 * on that URL and the JWT claims needed to tell a valid token from a wrong-environment one.
 *
 * <p>PCM logs the full URL at debug level, so the token is recoverable from logcat. {@link #raw()} is
 * therefore a live credential: reports, console output and commit-tracked files must carry
 * {@link #fingerprint()} instead.
 */
public record DeviceToken(
        String raw,
        String fingerprint,
        String host,
        String businessId,
        String storeId,
        String deviceId,
        String subject,
        String issuer,
        String audience,
        long issuedAtEpochMs,
        long expiresAtEpochMs
) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Matches {@code Attempting to connect websocket: wss://host/streams/{biz}/{store}/{deviceId}?token=JWT}.
     */
    private static final Pattern CONNECT_URL = Pattern.compile(
            "(wss://[A-Za-z0-9._-]+)/streams/([^/\\s]+)/([^/\\s]+)/([^/?\\s]+)\\?token=([A-Za-z0-9._\\-]+)");

    public static Optional<DeviceToken> fromConnectLine(String line) {
        if (line == null || line.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = CONNECT_URL.matcher(line);
        if (!matcher.find()) {
            return Optional.empty();
        }
        String jwt = matcher.group(5);
        JsonNode claims = decodeClaims(jwt);
        return Optional.of(new DeviceToken(
                jwt,
                fingerprintOf(jwt),
                matcher.group(1),
                matcher.group(2),
                matcher.group(3),
                matcher.group(4),
                text(claims, "sub"),
                text(claims, "iss"),
                text(claims, "aud"),
                seconds(claims, "iat"),
                seconds(claims, "exp")
        ));
    }

    public boolean expiredAt(long nowEpochMs) {
        return expiresAtEpochMs > 0 && nowEpochMs >= expiresAtEpochMs;
    }

    public long remainingMs(long nowEpochMs) {
        return expiresAtEpochMs <= 0 ? -1 : expiresAtEpochMs - nowEpochMs;
    }

    public String issuerHost() {
        return ScenarioLogAnalyzer.hostOf(issuer);
    }

    /** Safe one-line description for reports. Never contains token material. */
    public String summary() {
        return "fingerprint=" + fingerprint + " issuer=" + (issuer.isBlank() ? "<unknown>" : issuer)
                + " audience=" + (audience.isBlank() ? "<unknown>" : audience);
    }

    private static JsonNode decodeClaims(String jwt) {
        String[] parts = jwt.split("\\.");
        if (parts.length < 2) {
            return MAPPER.createObjectNode();
        }
        try {
            byte[] payload = Base64.getUrlDecoder().decode(padded(parts[1]));
            return MAPPER.readTree(payload);
        } catch (Exception ignored) {
            return MAPPER.createObjectNode();
        }
    }

    private static String padded(String base64Url) {
        int remainder = base64Url.length() % 4;
        return remainder == 0 ? base64Url : base64Url + "=".repeat(4 - remainder);
    }

    private static String text(JsonNode claims, String field) {
        JsonNode node = claims.get(field);
        return node == null || node.isNull() ? "" : node.asText("");
    }

    private static long seconds(JsonNode claims, String field) {
        JsonNode node = claims.get(field);
        return node == null || !node.canConvertToLong() ? 0L : node.asLong() * 1000L;
    }

    private static String fingerprintOf(String jwt) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(jwt.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return "sha256:" + hex;
        } catch (NoSuchAlgorithmException e) {
            return "sha256:unavailable";
        }
    }
}

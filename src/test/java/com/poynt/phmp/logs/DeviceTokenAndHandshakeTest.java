package com.poynt.phmp.logs;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * Covers the trust properties around the token PCM presents: it must be recoverable from logcat, its
 * claims must be readable, and a terminal being rejected with 401 must never report as authenticated.
 */
class DeviceTokenAndHandshakeTest {

    private static final String CONNECT_LINE_TEMPLATE =
            "%s  4322  4322 D /SourceFile:38: main Attempting to connect websocket: "
                    + "wss://pcm-ci.poynt.net/streams/biz-1/store-2/urn:tid:device-3?token=%s";

    @Test
    @DisplayName("token, identifiers and claims are recovered from the connect URL")
    void parsesConnectUrl() {
        String jwt = jwt("{\"iss\":\"https://services-ote.poynt.net\","
                + "\"aud\":\"urn:aid:cloudmessaging.poynt.co\",\"sub\":\"tester\","
                + "\"iat\":1786603945,\"exp\":1786690345}");

        Optional<DeviceToken> parsed = DeviceToken.fromConnectLine(
                String.format(CONNECT_LINE_TEMPLATE, stamp(LocalTime.of(10, 0)), jwt));

        Assertions.assertTrue(parsed.isPresent(), "connect URL should yield a token");
        DeviceToken token = parsed.get();
        Assertions.assertEquals(jwt, token.raw());
        Assertions.assertEquals("wss://pcm-ci.poynt.net", token.host());
        Assertions.assertEquals("biz-1", token.businessId());
        Assertions.assertEquals("store-2", token.storeId());
        Assertions.assertEquals("urn:tid:device-3", token.deviceId());
        Assertions.assertEquals("services-ote.poynt.net", token.issuerHost());
        Assertions.assertEquals(1786690345_000L, token.expiresAtEpochMs());
        Assertions.assertTrue(token.expiredAt(1786690346_000L));
        Assertions.assertFalse(token.expiredAt(1786603946_000L));
    }

    @Test
    @DisplayName("summary carries a fingerprint and never the token itself")
    void summaryNeverLeaksToken() {
        String jwt = jwt("{\"iss\":\"https://services-ci.poynt.net\"}");
        DeviceToken token = DeviceToken.fromConnectLine(
                String.format(CONNECT_LINE_TEMPLATE, stamp(LocalTime.of(10, 0)), jwt)).orElseThrow();

        Assertions.assertFalse(token.summary().contains(jwt));
        Assertions.assertTrue(token.summary().startsWith("fingerprint=sha256:"));
    }

    @Test
    @DisplayName("a 401 handshake after an earlier open socket is not authenticated")
    void rejectionAfterOpenSocketFailsAuth() {
        String log = String.join("\n",
                line(LocalTime.of(9, 0), "I /null:43: ReadingThread WEBSOCKET connected: 84652584"),
                line(LocalTime.of(9, 1), "D PcmService Is Websocket open: true"),
                line(LocalTime.of(10, 0), "E /null:5 : ConnectThread WEBSOCKET connect error."),
                line(LocalTime.of(10, 0), "E /null:74: ConnectThread PcmService: FAILED HANDSHAKE status 401"));

        ScenarioLogAnalyzer analyzer = new ScenarioLogAnalyzer(log, epoch(LocalTime.of(8, 0)));

        Assertions.assertFalse(analyzer.authenticatedBySocketHandshake(),
                "a terminal currently rejected with 401 must not report as authenticated");
        Assertions.assertEquals(401, analyzer.latestHandshakeRejectionStatus());
    }

    @Test
    @DisplayName("an open socket after an earlier rejection is authenticated again")
    void recoveryAfterRejectionPassesAuth() {
        String log = String.join("\n",
                line(LocalTime.of(9, 0), "E /null:74: ConnectThread PcmService: FAILED HANDSHAKE status 401"),
                line(LocalTime.of(9, 5), "D PcmService Is Websocket open: true"));

        ScenarioLogAnalyzer analyzer = new ScenarioLogAnalyzer(log, epoch(LocalTime.of(8, 0)));

        Assertions.assertTrue(analyzer.authenticatedBySocketHandshake());
    }

    @Test
    @DisplayName("a rejection before the run window is ignored")
    void staleRejectionExcludedByWindow() {
        String log = String.join("\n",
                line(LocalTime.of(7, 0), "E /null:74: ConnectThread PcmService: FAILED HANDSHAKE status 401"),
                line(LocalTime.of(9, 0), "D PcmService Is Websocket open: true"));

        ScenarioLogAnalyzer analyzer = new ScenarioLogAnalyzer(log, epoch(LocalTime.of(8, 0)));

        Assertions.assertTrue(analyzer.authenticatedBySocketHandshake());
        Assertions.assertEquals(0, analyzer.latestHandshakeRejectionStatus());
    }

    @Test
    @DisplayName("discovered host comes from the newest attempt and excludes trailing JSON punctuation")
    void latestDiscoveredHostIsSanitised() {
        String log = String.join("\n",
                line(LocalTime.of(9, 0), "I *** PCM-SERVICE Discovered PCM HOST:wss://pcm-ote.poynt.net"),
                line(LocalTime.of(9, 30), "D CLOUD-API: {\"cloudMessageService\":{\"address\":"
                        + "\"wss://pcm-ci.poynt.net\"}}"),
                line(LocalTime.of(10, 0), "I *** PCM-SERVICE Discovered PCM HOST:wss://pcm-ci.poynt.net"));

        ScenarioLogAnalyzer analyzer = new ScenarioLogAnalyzer(log, epoch(LocalTime.of(8, 0)));

        Assertions.assertEquals("wss://pcm-ci.poynt.net", analyzer.latestDiscoveredHost().orElseThrow());
        Assertions.assertEquals("pcm-ci.poynt.net",
                ScenarioLogAnalyzer.hostOf(analyzer.latestDiscoveredHost().orElseThrow()));
    }

    @Test
    @DisplayName("the newest token in the buffer is the one reported")
    void newestTokenWins() {
        String older = jwt("{\"iss\":\"https://services-ote.poynt.net\"}");
        String newer = jwt("{\"iss\":\"https://services-ci.poynt.net\"}");
        String log = String.join("\n",
                String.format(CONNECT_LINE_TEMPLATE, stamp(LocalTime.of(9, 0)), older),
                String.format(CONNECT_LINE_TEMPLATE, stamp(LocalTime.of(10, 0)), newer));

        DeviceToken token = new ScenarioLogAnalyzer(log).deviceToken().orElseThrow();

        Assertions.assertEquals("services-ci.poynt.net", token.issuerHost());
    }

    @Test
    @DisplayName("boot-time shared-uid ABI warnings are not install failures")
    void benignAbiWarningIsIgnored() {
        String log = line(LocalTime.of(9, 0),
                "W PackageManager: Instruction set mismatch, PackageSetting{e63550 co.poynt.accessory.manager/1000}"
                        + " requires arm64 whereas PackageSetting{fc9df1 co.poynt.cloudmessaging/2500} requires arm");

        Assertions.assertFalse(new ScenarioLogAnalyzer(log).hasInstallAbiFailure(),
                "a boot warning naming cloudmessaging as the compared package is not an install failure");
    }

    @Test
    @DisplayName("an error-level ABI mismatch naming cloudmessaging is an install failure")
    void realAbiFailureIsDetected() {
        String log = line(LocalTime.of(9, 0),
                "E PackageManager: Instruction set mismatch, PackageSetting{e63550 co.poynt.cloudmessaging/2500}"
                        + " requires arm64 whereas PackageSetting{fc9df1 android/1000} requires arm");

        Assertions.assertTrue(new ScenarioLogAnalyzer(log).hasInstallAbiFailure());
    }

    @Test
    @DisplayName("unsecure fallback to the production host is reported as a dialled host")
    void fallbackHostIsReported() {
        String log = String.join("\n",
                line(LocalTime.of(9, 0), "D PcmService *** PCM-SERVICE fallback to un secure mode"),
                line(LocalTime.of(9, 0), "D /SourceFile:38: main Attempting to connect websocket: "
                        + "wss://pcm.poynt.net/streams/ST3SL512NY000341"));

        ScenarioLogAnalyzer analyzer = new ScenarioLogAnalyzer(log, epoch(LocalTime.of(8, 0)));

        Assertions.assertTrue(analyzer.hasUnsecureFallback());
        Assertions.assertTrue(analyzer.hasTokenlessConnectAttempt());
        Assertions.assertEquals(List.of("wss://pcm.poynt.net"), analyzer.dialedHosts());
    }

    @Test
    @DisplayName("activation error code is recovered from the backend response")
    void activationErrorIsRecovered() {
        String log = line(LocalTime.of(9, 0), "E CO.POYNT.SERVICES/LoadBusinessTask.java:43: Exception received "
                + "while obtaining buiness info:PoyntAPIException{HTTP statusCode=403 "
                + "apiErrorCode=STORE_DEVICE_NOT_ACTIVATED Network Error? false");

        Assertions.assertEquals("STORE_DEVICE_NOT_ACTIVATED",
                new ScenarioLogAnalyzer(log).activationErrorCode().orElseThrow());
    }

    private static String jwt(String claimsJson) {
        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"RS256\"}".getBytes());
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(claimsJson.getBytes());
        return header + "." + payload + ".c2lnbmF0dXJl";
    }

    private static String line(LocalTime time, String body) {
        return stamp(time) + "  4322  4322 " + body;
    }

    private static String stamp(LocalTime time) {
        LocalDate today = LocalDate.now();
        return String.format("%02d-%02d %02d:%02d:%02d.000",
                today.getMonthValue(), today.getDayOfMonth(), time.getHour(), time.getMinute(), time.getSecond());
    }

    private static long epoch(LocalTime time) {
        return LocalDate.now().atTime(time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}

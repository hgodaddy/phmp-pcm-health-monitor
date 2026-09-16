package com.poynt.phmp.logs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the device-mode trust properties: a disconnected socket must never read as connected, and
 * connectivity must be scored from the current run rather than from an earlier boot.
 */
class ScenarioLogAnalyzerWindowTest {

    private static final DateTimeFormatter LOG_STAMP = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS");
    private static final LocalDateTime BASE = LocalDate.now().atTime(10, 0, 0);

    private static String at(int minuteOffset) {
        return BASE.plusMinutes(minuteOffset).format(LOG_STAMP);
    }

    private static long epochAt(int minuteOffset) {
        return BASE.plusMinutes(minuteOffset).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private static String line(int minuteOffset, String body) {
        return at(minuteOffset) + "  1234  1234 I PCM     : " + body;
    }

    @Test
    @DisplayName("A disconnected socket is not reported as connected")
    void disconnectedIsNotConnected() {
        ScenarioLogAnalyzer analyzer = new ScenarioLogAnalyzer(String.join("\n",
                line(0, "*** PCM-Service WebSocket connected: wss://pcm.example.com/v1/socket"),
                line(5, "*** PCM-Service WebSocket disconnected: code=1006")));

        assertFalse(analyzer.connectedAt(Long.MAX_VALUE),
                "trailing 'WebSocket disconnected:' must not satisfy a connected check");
    }

    @Test
    @DisplayName("Disconnects from before the run window do not count against the run")
    void staleDisconnectsAreExcluded() {
        String logcat = String.join("\n",
                line(0, "*** PCM-Service WebSocket disconnected: code=1006"),
                line(1, "*** PCM-Service Schedule reconnect attempt in: 30s"),
                line(2, "*** PCM-Service WebSocket connected: wss://pcm.example.com/v1/socket"),
                line(30, "*** PCM-Service Ping: sent"),
                line(31, "*** PCM-Service Pong: received"));

        ScenarioLogAnalyzer wholeBuffer = new ScenarioLogAnalyzer(logcat);
        assertEquals(1, wholeBuffer.connectivityStats().disconnects(),
                "unwindowed analysis still sees the historical disconnect");

        ScenarioLogAnalyzer windowed = new ScenarioLogAnalyzer(logcat, epochAt(20));
        assertEquals(0, windowed.connectivityStats().disconnects(),
                "a disconnect from before the run must not be attributed to this run");
        assertTrue(windowed.connectivityStats().mostlyConnected(),
                "socket was connected entering the window and never dropped inside it");
    }

    @Test
    @DisplayName("A quiet window inherits the connection state it started with")
    void quietWindowTrustsCarriedState() {
        String logcat = line(0, "*** PCM-Service WebSocket connected: wss://pcm.example.com/v1/socket");

        ScenarioLogAnalyzer windowed = new ScenarioLogAnalyzer(logcat, epochAt(10));
        assertTrue(windowed.connectivityStats().mostlyConnected(),
                "no new transitions must not be read as a dead socket");
    }

    @Test
    @DisplayName("Reconnect evidence is only credited after the forced disconnect")
    void reconnectEvidenceIsTimestampGated() {
        String logcat = String.join("\n",
                line(0, "*** PCM-Service WebSocket disconnected: code=1006"),
                line(1, "*** PCM-Service Schedule reconnect attempt in: 30s"),
                line(2, "*** PCM-Service WebSocket connected: wss://pcm.example.com/v1/socket"));

        ScenarioLogAnalyzer analyzer = new ScenarioLogAnalyzer(logcat);
        long broadcastAt = epochAt(10);

        assertFalse(analyzer.hasDisconnectAfter(broadcastAt),
                "history must not satisfy the controlled-disconnect assertion");
        assertFalse(analyzer.hasReconnectScheduledAfter(broadcastAt));
        assertFalse(analyzer.hasConnectedAfter(broadcastAt));
        assertTrue(analyzer.hasDisconnectAfter(epochAt(0)),
                "evidence at or after the broadcast still counts");
    }

    @Test
    @DisplayName("Authentication is driven by the token-path marker, not the word 'authenticated'")
    void tokenPathDrivesAuthentication() {
        ScenarioLogAnalyzer failed = new ScenarioLogAnalyzer(
                line(0, "*** PCM-Service device is unauthenticated, retrying"));
        assertFalse(failed.tokenPath().tokenFetchSuccess());

        ScenarioLogAnalyzer succeeded = new ScenarioLogAnalyzer(
                line(0, "*** PCM-Service Successfully retrieved access token!"));
        assertTrue(succeeded.tokenPath().tokenFetchSuccess());
    }

    @Test
    @DisplayName("Device clock stamps parse onto the same basis as log lines")
    void deviceClockSharesLogBasis() {
        assertEquals(epochAt(0), ScenarioLogAnalyzer.epochMsOfDeviceTimestamp(at(0)));
    }

    @Test
    @DisplayName("PST3 build wording 'Is Websocket open' is understood as connect and disconnect")
    void pst3SocketVocabularyIsRecognised() {
        ScenarioLogAnalyzer open = new ScenarioLogAnalyzer(String.join("\n",
                line(0, "main PcmService: onStartCommand: ACTION_WEBSOCKET_PING"),
                line(0, "main Is Websocket open: true")));
        assertTrue(open.connectedAt(Long.MAX_VALUE));
        assertTrue(open.hasWebsocketInitSignals());
        assertTrue(open.authenticatedBySocketHandshake(),
                "the token is validated during the handshake, so an open socket proves authentication");

        ScenarioLogAnalyzer closed = new ScenarioLogAnalyzer(String.join("\n",
                line(0, "main Is Websocket open: true"),
                line(5, "main Is Websocket open: false")));
        assertFalse(closed.connectedAt(Long.MAX_VALUE));
        assertTrue(closed.hasDisconnectAfter(epochAt(4)));
    }

    @Test
    @DisplayName("Token failure markers override an open socket")
    void tokenFailureBeatsOpenSocket() {
        ScenarioLogAnalyzer analyzer = new ScenarioLogAnalyzer(String.join("\n",
                line(0, "main Is Websocket open: true"),
                line(1, "*** PCM-Service Failed to obtain token!")));
        assertFalse(analyzer.authenticatedBySocketHandshake());
    }

    @Test
    @DisplayName("Ping to pong latency is measured from log timestamps")
    void pingPongLatencyIsMeasured() {
        ScenarioLogAnalyzer analyzer = new ScenarioLogAnalyzer(String.join("\n",
                at(0) + "  4250  4250 D main Ping: urn:tid:abc",
                at(0).substring(0, at(0).length() - 4) + ".700  4250  5809 D ReadingThread Pong: urn:tid:abc"));
        assertEquals(700, analyzer.pingPongLatencyMs());
    }
}

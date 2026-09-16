package com.poynt.phmp.logs;

import java.net.URI;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses PCM-related adb logcat output and computes scenario signals for A-H checks.
 */
public class ScenarioLogAnalyzer {

    private static final Pattern THREADTIME_PREFIX = Pattern.compile("^(\\d\\d-\\d\\d\\s+\\d\\d:\\d\\d:\\d\\d\\.\\d{1,6}).*");
    private static final Pattern WSS_PATTERN = Pattern.compile("(wss://\\S+)", Pattern.CASE_INSENSITIVE);
    private static final DateTimeFormatter THREADTIME_FORMATTER = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd HH:mm:ss.")
            .appendFraction(ChronoField.MILLI_OF_SECOND, 1, 6, false)
            .toFormatter(Locale.ROOT);

    /** Connect / disconnect / reconnect markers emitted by PCM. Kept public so runners can pre-qualify a device. */
    public static final List<String> TRANSITION_MARKERS = List.of(
            "websocket connected:",
            "websocket disconnected:",
            "is websocket open: true",
            "is websocket open: false",
            "pcmservice onfailedconnection",
            "pcm connected",
            "poynt_websocket_connectivity_changed",
            "attempting to connect websocket:",
            "start web socket connect task",
            "pcmservice: onstartcommand",
            "schedule reconnect attempt in:",
            "poynt.intent.action.websocket_reconnect",
            "failed handshake status",
            "websocket connect error"
    );

    private static final String CONNECTED_MARKER = "websocket connected:";
    private static final String DISCONNECTED_MARKER = "websocket disconnected:";
    /**
     * Observed on PST3 build develop-1.26.02.78: this PCM emits "Is Websocket open: true|false"
     * rather than the "WebSocket connected:" wording, so both vocabularies are accepted.
     */
    private static final String SOCKET_OPEN_MARKER = "is websocket open: true";
    private static final String SOCKET_CLOSED_MARKER = "is websocket open: false";
    private static final String FAILED_CONNECTION_MARKER = "pcmservice onfailedconnection";
    private static final String RECONNECT_SCHEDULED_MARKER = "schedule reconnect attempt in:";
    private static final Pattern LATENCY_PATTERN = Pattern.compile("latency[=:]\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern HANDSHAKE_STATUS = Pattern.compile(
            "failed handshake status\\s+(\\d{3})", Pattern.CASE_INSENSITIVE);
    private static final Pattern ABI_MISMATCH_SUBJECT = Pattern.compile(
            "instruction set mismatch,\\s*PackageSetting\\{\\S+\\s+(\\S+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern THREADTIME_LEVEL = Pattern.compile(
            "^\\d\\d-\\d\\d\\s+\\d\\d:\\d\\d:\\d\\d\\.\\d+\\s+\\d+\\s+\\d+\\s+([VDIWEF])\\s");
    private static final Pattern ACTIVATION_ERROR = Pattern.compile(
            "apiErrorCode=([A-Z_]*NOT_ACTIVATED[A-Z_]*|[A-Z_]*UNAUTHORIZED[A-Z_]*)");
    private static final Pattern DISCOVERED_HOST = Pattern.compile(
            "(?:discovered pcm host:|attempting to connect websocket:)\\s*(wss://[A-Za-z0-9._-]+)",
            Pattern.CASE_INSENSITIVE);

    private final List<LogEvent> events;

    /**
     * Epoch millis marking the start of the current run. Connectivity is scored only from this point
     * onward so that disconnects from earlier boots cannot fail (or silently pass) the current run.
     * Zero disables windowing and preserves the original whole-buffer behaviour.
     */
    private final long windowStartMs;

    public ScenarioLogAnalyzer(String rawLogcat) {
        this(rawLogcat, 0L);
    }

    public ScenarioLogAnalyzer(String rawLogcat, long windowStartMs) {
        this.events = parse(rawLogcat == null ? "" : rawLogcat);
        this.windowStartMs = Math.max(0L, windowStartMs);
    }

    public long windowStartMs() {
        return windowStartMs;
    }

    public boolean hasInstallCertificateFailure() {
        return containsAll("failed to collect certificates", "poyntcloudmessaging");
    }

    /**
     * True only for an error-level instruction-set mismatch naming cloudmessaging as its subject.
     *
     * <p>At boot PackageManager emits warning-level "Instruction set mismatch" lines for every pair in a
     * shared-uid group; those name cloudmessaging incidentally as the compared package and are not
     * install failures. Matching them turned a healthy terminal's install gate red.
     */
    public boolean hasInstallAbiFailure() {
        for (LogEvent event : events) {
            String lower = event.lower();
            if (!lower.contains("instruction set mismatch")) {
                continue;
            }
            Matcher subject = ABI_MISMATCH_SUBJECT.matcher(event.line());
            boolean aboutCloudMessaging = subject.find() && subject.group(1).contains("cloudmessaging");
            if (aboutCloudMessaging && (isErrorLevel(event.line()) || lower.contains("failed"))) {
                return true;
            }
        }
        return false;
    }

    /** Distinct wss hosts PCM actually dialled inside the run window. */
    public List<String> dialedHosts() {
        List<String> hosts = new ArrayList<>();
        for (LogEvent event : events) {
            if (!inWindow(event) || !event.lower().contains("attempting to connect websocket:")) {
                continue;
            }
            Matcher matcher = DISCOVERED_HOST.matcher(event.line());
            if (matcher.find() && !hosts.contains(matcher.group(1))) {
                hosts.add(matcher.group(1));
            }
        }
        return hosts;
    }

    private static boolean isErrorLevel(String line) {
        Matcher matcher = THREADTIME_LEVEL.matcher(line);
        return matcher.find() && ("E".equals(matcher.group(1)) || "F".equals(matcher.group(1)));
    }

    public boolean hasStartProcCloudMessaging() {
        return containsAll("start proc", "co.poynt.cloudmessaging");
    }

    public boolean hasStartupMarker() {
        return containsAny(
                "pcm-startup",
                "pcmstartupprovider oncreate",
                "boot completed",
                "network available",
                "starting pcm service",
                "registered pcm network connectivity callback"
        );
    }

    public boolean hasServiceOnCreate() {
        return containsAny("*** pcm-service pcmservice: oncreate()", "pcmservice: oncreate()");
    }

    public boolean hasServiceOnStartCommand() {
        return containsAny("pcmservice: onstartcommand:");
    }

    public boolean hasConnectTaskStart() {
        return containsAny("start web socket connect task...");
    }

    public boolean hasConnectAttempt() {
        return containsAny("attempting to connect websocket:");
    }

    public boolean hasOnlyManualStartService() {
        boolean manual = containsAny("adb startservice");
        boolean auto = hasStartProcCloudMessaging() || hasStartupMarker();
        return manual && !auto;
    }

    public Optional<String> firstResolvedHost() {
        for (LogEvent event : events) {
            if (event.lower().contains("*** pcm-service discovered pcm host:wss://")
                    || event.lower().contains("attempting to connect websocket:")
                    || event.lower().contains("wss://")) {
                Matcher matcher = WSS_PATTERN.matcher(event.line());
                if (matcher.find()) {
                    return Optional.of(matcher.group(1));
                }
            }
        }
        return Optional.empty();
    }

    public boolean hasSyspropPcmUrlLog() {
        return containsAny("persist.poynt.srvc.url.pcm");
    }

    /** True when the log shows PCM attempting to bring a socket up at all. */
    public boolean hasWebsocketInitSignals() {
        return hasConnectTaskStart()
                || hasConnectAttempt()
                || hasServiceOnCreate()
                || hasServiceOnStartCommand()
                || containsAny(SOCKET_OPEN_MARKER, SOCKET_CLOSED_MARKER);
    }

    /**
     * On this PCM build the access token is carried as a query parameter on the WebSocket URL and is
     * validated during the handshake, so an open socket is itself proof of a successful token exchange.
     *
     * <p>Only the newest verdict inside the run window counts. A terminal that authenticated hours ago
     * and is now being rejected with {@code FAILED HANDSHAKE status 401} must not pass this check, and
     * the buffer holds both outcomes at once whenever an environment switch leaves a stale token behind.
     */
    public boolean authenticatedBySocketHandshake() {
        TokenPath path = tokenPath();
        if (path.tokenFailure() || path.maxRetriesReached() || hasUnsecureFallback()) {
            return false;
        }
        Boolean latest = null;
        for (LogEvent event : events) {
            if (!inWindow(event)) {
                continue;
            }
            String lower = event.lower();
            if (isHandshakeRejection(lower)) {
                latest = false;
            } else if (lower.contains(SOCKET_OPEN_MARKER)
                    || lower.contains(CONNECTED_MARKER)
                    || lower.contains("successfully retrieved access token!")) {
                latest = true;
            }
        }
        return latest != null && latest;
    }

    /**
     * HTTP status of the most recent rejected WebSocket handshake in the window, or 0 when none. 401 and
     * 403 mean the token was refused, which is the signal an expired or wrong-environment token produces.
     */
    public int latestHandshakeRejectionStatus() {
        int status = 0;
        for (LogEvent event : events) {
            if (!inWindow(event)) {
                continue;
            }
            Matcher matcher = HANDSHAKE_STATUS.matcher(event.line());
            if (matcher.find()) {
                try {
                    status = Integer.parseInt(matcher.group(1));
                } catch (NumberFormatException ignored) {
                    // keep previous value
                }
            }
        }
        return status;
    }

    /**
     * The token PCM is currently presenting, taken from the newest connect attempt. The whole buffer is
     * searched rather than the run window: the token in force may have been logged before the run opened.
     */
    public Optional<DeviceToken> deviceToken() {
        for (int i = events.size() - 1; i >= 0; i--) {
            Optional<DeviceToken> token = DeviceToken.fromConnectLine(events.get(i).line());
            if (token.isPresent()) {
                return token;
            }
        }
        return Optional.empty();
    }

    /**
     * Host from the newest discovery result or connect attempt. This is what PCM actually dials, and it
     * can differ from {@code persist.poynt.srvc.url.pcm}, which is not consulted once discovery answers.
     */
    public Optional<String> latestDiscoveredHost() {
        for (int i = events.size() - 1; i >= 0; i--) {
            Matcher matcher = DISCOVERED_HOST.matcher(events.get(i).line());
            if (matcher.find()) {
                return Optional.of(matcher.group(1));
            }
        }
        return Optional.empty();
    }

    /**
     * True when PCM gave up on authentication and dialled the socket without a token
     * ({@code *** PCM-SERVICE fallback to un secure mode}). The socket can still reach OPEN in this
     * state, so without this check an unauthenticated terminal looks like a healthy one.
     */
    public boolean hasUnsecureFallback() {
        return containsAnyInWindow("fallback to un secure mode");
    }

    /**
     * True when the newest connect attempt carried no {@code ?token=} parameter, which is how the
     * unsecure fallback URL differs from a normal authenticated stream URL.
     */
    public boolean hasTokenlessConnectAttempt() {
        for (int i = events.size() - 1; i >= 0; i--) {
            LogEvent event = events.get(i);
            if (!event.lower().contains("attempting to connect websocket:")) {
                continue;
            }
            return !event.lower().contains("?token=");
        }
        return false;
    }

    /**
     * Backend activation error code, when the cloud told the terminal why it was refused. A terminal
     * pointed at an environment it was never activated in reports
     * {@code apiErrorCode=STORE_DEVICE_NOT_ACTIVATED}, which is the upstream cause of PCM being unable
     * to obtain a token at all.
     */
    public Optional<String> activationErrorCode() {
        Matcher matcher;
        for (int i = events.size() - 1; i >= 0; i--) {
            matcher = ACTIVATION_ERROR.matcher(events.get(i).line());
            if (matcher.find()) {
                return Optional.of(matcher.group(1));
            }
        }
        return Optional.empty();
    }

    private boolean containsAnyInWindow(String... tokens) {
        for (LogEvent event : events) {
            if (!inWindow(event)) {
                continue;
            }
            for (String token : tokens) {
                if (event.lower().contains(token.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isHandshakeRejection(String lower) {
        if (!lower.contains("failed handshake status")) {
            return false;
        }
        return lower.contains(" 401") || lower.contains(" 403");
    }

    /**
     * True when {@code token} appears on a line timestamped at or after {@code startMs}. Used to prove a
     * signal was produced by the action we just took rather than by an earlier boot.
     */
    public boolean existsAfter(String token, long startMs) {
        String needle = token.toLowerCase(Locale.ROOT);
        for (LogEvent event : events) {
            if (event.epochMs() <= 0 || event.epochMs() < startMs) {
                continue;
            }
            if (event.lower().contains(needle)) {
                return true;
            }
        }
        return false;
    }

    public boolean hasDisconnectAfter(long startMs) {
        return existsAfter(DISCONNECTED_MARKER, startMs)
                || existsAfter(SOCKET_CLOSED_MARKER, startMs)
                || existsAfter(FAILED_CONNECTION_MARKER, startMs);
    }

    public boolean hasReconnectScheduledAfter(long startMs) {
        return existsAfter(RECONNECT_SCHEDULED_MARKER, startMs);
    }

    public boolean hasConnectedAfter(long startMs) {
        return existsAfter(CONNECTED_MARKER, startMs) || existsAfter(SOCKET_OPEN_MARKER, startMs);
    }

    private static boolean isConnectSignal(String lower) {
        return lower.contains(CONNECTED_MARKER)
                || lower.contains(SOCKET_OPEN_MARKER)
                || lower.contains("pcm connected")
                || lower.contains("poynt_websocket_connectivity_changed");
    }

    private static boolean isDisconnectSignal(String lower) {
        return lower.contains(DISCONNECTED_MARKER)
                || lower.contains(SOCKET_CLOSED_MARKER)
                || lower.contains(FAILED_CONNECTION_MARKER);
    }

    /**
     * Round trip between a {@code Ping:} and the following {@code Pong:}. This build does not log a
     * latency value, so it is measured from the log timestamps instead.
     */
    public long pingPongLatencyMs() {
        long pendingPing = 0;
        long latest = 0;
        for (LogEvent event : events) {
            if (event.epochMs() <= 0) {
                continue;
            }
            String lower = event.lower();
            if (lower.contains("ping:")) {
                pendingPing = event.epochMs();
            } else if (lower.contains("pong:") && pendingPing > 0) {
                long delta = event.epochMs() - pendingPing;
                if (delta >= 0) {
                    latest = delta;
                }
                pendingPing = 0;
            }
        }
        return latest;
    }

    /** Most recent reported ping latency, or 0 when PCM logged none. */
    public long latestLatencyMs() {
        long latency = 0;
        for (LogEvent event : events) {
            Matcher matcher = LATENCY_PATTERN.matcher(event.line());
            if (matcher.find()) {
                try {
                    latency = Long.parseLong(matcher.group(1));
                } catch (NumberFormatException ignored) {
                    // keep previous value
                }
            }
        }
        return latency;
    }

    /** Converts a device-local {@code MM-DD HH:MM:SS.mmm} stamp into the same epoch basis as parsed log lines. */
    public static long epochMsOfDeviceTimestamp(String deviceTimestamp) {
        if (deviceTimestamp == null || deviceTimestamp.isBlank()) {
            return 0;
        }
        long parsed = parseEpochMs(LocalDate.now(), deviceTimestamp.trim());
        return parsed > 0 ? parsed : 0;
    }

    public long detectedPingProfileMs(long fallbackMs) {
        if (containsAny("custom ping frequency found: + 30000")) {
            return 30_000L;
        }
        if (containsAny("profile ping frequency: 300000")) {
            return 300_000L;
        }
        return fallbackMs;
    }

    /**
     * Ping/pong timestamps across the whole buffer. Deliberately not restricted to the run window:
     * profiles as slow as 300s need more history than a single run provides to score cadence.
     */
    public List<Long> pingEventTimes() {
        List<Long> times = new ArrayList<>();
        for (LogEvent event : events) {
            if (event.epochMs() <= 0) {
                continue;
            }
            if (event.lower().contains("ping:")
                    || event.lower().contains("pong:")
                    || event.lower().contains("pcmservice: onstartcommand: action_websocket_ping")) {
                times.add(event.epochMs());
            }
        }
        return times;
    }

    public long maxGapMs(List<Long> sortedEventTimes) {
        if (sortedEventTimes.size() < 2) {
            return -1;
        }
        long max = 0;
        for (int i = 1; i < sortedEventTimes.size(); i++) {
            long gap = sortedEventTimes.get(i) - sortedEventTimes.get(i - 1);
            if (gap > max) {
                max = gap;
            }
        }
        return max;
    }

    public long medianGapMs(List<Long> sortedEventTimes) {
        if (sortedEventTimes.size() < 2) {
            return -1;
        }
        List<Long> gaps = new ArrayList<>();
        for (int i = 1; i < sortedEventTimes.size(); i++) {
            gaps.add(sortedEventTimes.get(i) - sortedEventTimes.get(i - 1));
        }
        gaps.sort(Comparator.naturalOrder());
        return gaps.get(gaps.size() / 2);
    }

    public ConnectivityStats connectivityStats() {
        boolean windowed = windowStartMs > 0;
        long observedStart = windowed ? windowStartMs : firstTimestamp();
        long observedEnd = lastTimestamp();
        boolean carriedConnected = windowed && connectedAt(windowStartMs);

        if (observedStart <= 0 || observedEnd <= 0 || observedEnd <= observedStart) {
            // No evidence inside the window. Trust the state carried into it rather than reporting a dead
            // socket, so that a healthy long-lived connection with no new transitions does not fail.
            return new ConnectivityStats(0, 0, 0, 0, 0, carriedConnected, false, false);
        }

        List<TimedFlag> transitions = new ArrayList<>();
        int handshakeFailures = 0;
        int connectErrors = 0;
        int failedConnectionMarkers = 0;

        for (LogEvent event : events) {
            if (!inWindow(event)) {
                continue;
            }
            if (isConnectSignal(event.lower())) {
                transitions.add(new TimedFlag(event.epochMs(), true));
            }
            if (isDisconnectSignal(event.lower())) {
                transitions.add(new TimedFlag(event.epochMs(), false));
            }
            if (event.lower().contains("failed handshake status")) {
                handshakeFailures++;
            }
            if (event.lower().contains("websocket connect error")) {
                connectErrors++;
            }
            if (event.lower().contains("pcmservice onfailedconnection")) {
                failedConnectionMarkers++;
            }
        }

        transitions.sort(Comparator.comparingLong(TimedFlag::epochMs));
        boolean connected = carriedConnected;
        long currentStart = observedStart;
        long connectedDuration = 0;
        int disconnects = 0;
        int reconnects = 0;

        for (TimedFlag transition : transitions) {
            if (connected) {
                connectedDuration += Math.max(0, transition.epochMs() - currentStart);
            }
            if (transition.connected()) {
                reconnects++;
            } else {
                disconnects++;
            }
            connected = transition.connected();
            currentStart = transition.epochMs();
        }
        if (connected) {
            connectedDuration += Math.max(0, observedEnd - currentStart);
        }

        long window = observedEnd - observedStart;
        double ratio = window <= 0 ? 0 : (double) connectedDuration / window;

        return new ConnectivityStats(
                connectedDuration,
                window,
                disconnects,
                reconnects,
                handshakeFailures + connectErrors + failedConnectionMarkers,
                ratio >= 0.90,
                hasReconnectChainViolations(),
                hasDeadSocketOverTenMinutes()
        );
    }

    public boolean hasReconnectChainViolations() {
        List<LogEvent> disconnects = findEvents(DISCONNECTED_MARKER, SOCKET_CLOSED_MARKER, FAILED_CONNECTION_MARKER);
        for (LogEvent disconnect : disconnects) {
            if (disconnect.epochMs() <= 0) {
                continue;
            }
            long t = disconnect.epochMs();
            boolean scheduled = existsWithin(RECONNECT_SCHEDULED_MARKER, t, 5 * 60_000L);
            boolean fired = existsWithin("poynt.intent.action.websocket_reconnect", t, 5 * 60_000L);
            boolean recovered = existsWithin(CONNECTED_MARKER, t, 5 * 60_000L)
                    || existsWithin(SOCKET_OPEN_MARKER, t, 5 * 60_000L);
            if (!(scheduled && fired && recovered)) {
                return true;
            }
        }
        return false;
    }

    public boolean hasDeadSocketOverTenMinutes() {
        List<LogEvent> disconnects = findEvents(DISCONNECTED_MARKER, SOCKET_CLOSED_MARKER, FAILED_CONNECTION_MARKER);
        for (LogEvent disconnect : disconnects) {
            if (disconnect.epochMs() <= 0) {
                continue;
            }
            boolean recovered = existsWithin(CONNECTED_MARKER, disconnect.epochMs(), 10 * 60_000L)
                    || existsWithin(SOCKET_OPEN_MARKER, disconnect.epochMs(), 10 * 60_000L);
            if (!recovered) {
                return true;
            }
        }
        return false;
    }

    public TokenPath tokenPath() {
        return new TokenPath(
                containsAny("*** pcm-service getting access token"),
                containsAny("successfully retrieved access token!"),
                containsAny("*** pcm-service starting pcm connection..."),
                containsAny("*** pcm-service failed to obtain token!"),
                containsAny("maximum retries reached! try again in 2 hours")
        );
    }

    public CloudPath cloudPath(String correlationId) {
        boolean rawSocket = false;
        boolean received = false;
        boolean eventlog = false;
        boolean paymentBridge = false;
        boolean outboundBroadcast = false;
        boolean processingError = false;
        long receivedAt = 0;

        for (LogEvent event : events) {
            String lower = event.lower();
            boolean lineMatchesCorrelation = correlationId == null || correlationId.isBlank()
                    || lower.contains(correlationId.toLowerCase(Locale.ROOT));
            if (!lineMatchesCorrelation
                    && !lower.contains("cloud message")
                    && !lower.contains("poynt_cloud_message_received")
                    && !lower.contains("websocket got:")
                    && !lower.contains("pcmmessage")
                    && !lower.contains("action_cloud_message_received")
                    && !lower.contains("intent_extra_cloud_message_body")) {
                continue;
            }
            if (lower.contains("websocket got:")) {
                rawSocket = true;
            }
            if (lower.contains("cloud message received")) {
                received = true;
                if (receivedAt == 0 && event.epochMs() > 0) {
                    receivedAt = event.epochMs();
                }
            }
            if (lower.contains("poynt_cloud_message_received")) {
                eventlog = true;
            }
            if (lower.contains("pcmmessage : payment message processing further")) {
                paymentBridge = true;
            }
            if (lower.contains("action_cloud_message_received")
                    || lower.contains("intent_extra_cloud_message_body")) {
                outboundBroadcast = true;
            }
            if (lower.contains("error while processing message")) {
                processingError = true;
            }
        }
        return new CloudPath(rawSocket, received, eventlog, paymentBridge, outboundBroadcast, processingError, receivedAt);
    }

    public boolean connectedAt(long epochMs) {
        if (epochMs <= 0) {
            return true;
        }
        boolean connected = false;
        for (LogEvent event : events) {
            if (event.epochMs() <= 0 || event.epochMs() > epochMs) {
                continue;
            }
            if (isConnectSignal(event.lower())) {
                connected = true;
            } else if (isDisconnectSignal(event.lower())) {
                connected = false;
            }
        }
        return connected;
    }

    public long firstTimestamp() {
        for (LogEvent event : events) {
            if (event.epochMs() > 0) {
                return event.epochMs();
            }
        }
        return 0;
    }

    public long lastTimestamp() {
        for (int i = events.size() - 1; i >= 0; i--) {
            if (events.get(i).epochMs() > 0) {
                return events.get(i).epochMs();
            }
        }
        return 0;
    }

    private boolean existsWithin(String token, long startMs, long windowMs) {
        for (LogEvent event : events) {
            if (event.epochMs() <= 0 || event.epochMs() < startMs) {
                continue;
            }
            if (event.epochMs() - startMs > windowMs) {
                break;
            }
            if (event.lower().contains(token)) {
                return true;
            }
        }
        return false;
    }

    private boolean inWindow(LogEvent event) {
        return event.epochMs() > 0 && event.epochMs() >= windowStartMs;
    }

    private List<LogEvent> findEvents(String... tokens) {
        List<LogEvent> out = new ArrayList<>();
        for (LogEvent event : events) {
            if (!inWindow(event)) {
                continue;
            }
            for (String token : tokens) {
                if (event.lower().contains(token)) {
                    out.add(event);
                    break;
                }
            }
        }
        return out;
    }

    public static String hostOf(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            return "";
        }
        try {
            return URI.create(endpoint).getHost();
        } catch (Exception ignored) {
            return endpoint;
        }
    }

    private boolean containsAny(String... tokens) {
        for (String token : tokens) {
            String match = token.toLowerCase(Locale.ROOT);
            for (LogEvent event : events) {
                if (event.lower().contains(match)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean containsAll(String... tokens) {
        for (LogEvent event : events) {
            boolean all = true;
            for (String token : tokens) {
                if (!event.lower().contains(token.toLowerCase(Locale.ROOT))) {
                    all = false;
                    break;
                }
            }
            if (all) {
                return true;
            }
        }
        return false;
    }

    private static List<LogEvent> parse(String rawLogcat) {
        List<LogEvent> out = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (String line : rawLogcat.split("\\R")) {
            String trimmed = line == null ? "" : line;
            long ts = parseEpochMs(today, trimmed);
            out.add(new LogEvent(ts, trimmed, trimmed.toLowerCase(Locale.ROOT)));
        }
        return out;
    }

    private static long parseEpochMs(LocalDate today, String line) {
        Matcher matcher = THREADTIME_PREFIX.matcher(line);
        if (!matcher.find()) {
            return -1;
        }
        String datePart = today.getYear() + "-" + matcher.group(1).replace("  ", " ");
        try {
            LocalDateTime ldt = LocalDateTime.parse(datePart, THREADTIME_FORMATTER);
            return ldt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (Exception ignored) {
            return -1;
        }
    }

    private record LogEvent(long epochMs, String line, String lower) {
    }

    private record TimedFlag(long epochMs, boolean connected) {
    }

    public record ConnectivityStats(
            long connectedDurationMs,
            long observedWindowMs,
            int disconnects,
            int reconnects,
            int errorSignals,
            boolean mostlyConnected,
            boolean reconnectChainViolation,
            boolean deadSocketOverTenMinutes
    ) {
    }

    public record TokenPath(
            boolean tokenFetchStarted,
            boolean tokenFetchSuccess,
            boolean connectionStartedAfterToken,
            boolean tokenFailure,
            boolean maxRetriesReached
    ) {
    }

    public record CloudPath(
            boolean rawSocketSignal,
            boolean messageReceived,
            boolean eventLogReceived,
            boolean paymentBridgePath,
            boolean outboundBroadcastPath,
            boolean processingError,
            long receivedAtEpochMs
    ) {
    }
}

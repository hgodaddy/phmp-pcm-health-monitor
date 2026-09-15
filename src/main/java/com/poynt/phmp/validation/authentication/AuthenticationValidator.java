package com.poynt.phmp.validation.authentication;

import com.poynt.phmp.config.PhmpConfig;
import com.poynt.phmp.device.DeviceClient;
import com.poynt.phmp.logs.DeviceToken;
import com.poynt.phmp.logs.ScenarioLogAnalyzer;
import com.poynt.phmp.model.ValidationResult;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Day 2 – validates token acquisition and authentication readiness for messaging flows.
 */
public class AuthenticationValidator {

    private final PhmpConfig config;
    private final DeviceClient device;

    public AuthenticationValidator(PhmpConfig config, DeviceClient device) {
        this.config = config;
        this.device = device;
    }

    public ValidationResult validate() {
        List<String> details = new ArrayList<>();
        List<String> failures = new ArrayList<>();

        boolean authenticated = device.isAuthenticated();
        String tokenHint = device.authTokenHint();
        details.add("Authenticated=" + authenticated);
        details.add("Token hint present=" + (tokenHint != null && !tokenHint.isBlank()));
        details.add("Region=" + device.region());
        details.add("Mode=" + config.phmp.mode);

        if (!authenticated) {
            failures.add("Authentication did not succeed prior to messaging flows");
        }
        if (tokenHint == null || tokenHint.isBlank()) {
            failures.add("Auth token acquisition not observed");
        }

        if (!config.isSimulate()) {
            inspectDeviceToken(details, failures);
        }

        if (!failures.isEmpty()) {
            return ValidationResult.fail("Authentication Validation", String.join("; ", failures), failures);
        }
        return ValidationResult.pass(
                "Authentication Validation",
                "Token acquisition and authentication readiness confirmed",
                details,
                Map.of("authenticated", true, "region", device.region())
        );
    }

    /**
     * Reads the token PCM is presenting and checks it against the environment the terminal is pointed at.
     * A syntactically valid, unexpired token still fails the handshake with 401 when it was minted by a
     * different environment's issuer, which is what an incomplete environment switch leaves behind.
     */
    private void inspectDeviceToken(List<String> details, List<String> failures) {
        ScenarioLogAnalyzer analyzer = device.logAnalyzer();

        Optional<String> activationError = analyzer.activationErrorCode();
        if (activationError.isPresent()) {
            failures.add("Terminal is not activated in this environment (backend returned "
                    + activationError.get() + "), so no token can be issued to PCM. Activate the terminal "
                    + "against " + config.region(device.region()).cloud().baseUrl + " and re-run.");
        }

        if (analyzer.hasUnsecureFallback() || analyzer.hasTokenlessConnectAttempt()) {
            failures.add("PCM fell back to an unauthenticated socket: it could not obtain a token and "
                    + "dialled the stream URL without one");
        }

        int rejection = analyzer.latestHandshakeRejectionStatus();
        if (rejection == 401 || rejection == 403) {
            failures.add("PCM WebSocket handshake rejected with HTTP " + rejection
                    + ": the cloud refused the access token the terminal presented");
        }

        Optional<DeviceToken> maybeToken = analyzer.deviceToken();
        if (maybeToken.isEmpty()) {
            details.add("No token-bearing connect URL found in logcat");
            return;
        }
        DeviceToken token = maybeToken.get();
        details.add("Token " + token.summary());
        details.add("Token identity business=" + token.businessId() + " store=" + token.storeId()
                + " device=" + token.deviceId());

        long now = device.deviceEpochMs();
        if (token.expiredAt(now)) {
            failures.add("Access token expired at " + Instant.ofEpochMilli(token.expiresAtEpochMs())
                    + "; the terminal must re-authenticate");
        } else if (token.expiresAtEpochMs() > 0) {
            details.add("Token valid for a further " + Duration.ofMillis(token.remainingMs(now)).toMinutes() + "m");
        }

        String targetHost = ScenarioLogAnalyzer.hostOf(config.region(device.region()).cloud().baseUrl);
        String issuerTag = environmentTagOf(token.issuerHost());
        String targetTag = environmentTagOf(targetHost);
        if (!issuerTag.isBlank() && !targetTag.isBlank() && !issuerTag.equals(targetTag)) {
            failures.add("Token was issued by " + token.issuerHost() + " (" + issuerTag
                    + ") but the terminal is configured against " + targetHost + " (" + targetTag
                    + "); the cloud will reject it. Re-activate the terminal in " + targetTag + ".");
        } else if (!issuerTag.isBlank()) {
            details.add("Token issuer environment matches target: " + issuerTag);
        }
    }

    /**
     * Environment tag carried in a Poynt hostname, e.g. {@code services-ote} -> {@code ote} and
     * {@code pcm-ci} -> {@code ci}. Blank when the hostname does not encode one, in which case no
     * issuer/target comparison is attempted.
     */
    private static String environmentTagOf(String host) {
        if (host == null || host.isBlank() || !host.contains(".")) {
            return "";
        }
        String firstLabel = host.substring(0, host.indexOf('.'));
        int dash = firstLabel.lastIndexOf('-');
        if (dash < 0) {
            return "";
        }
        String tag = firstLabel.substring(dash + 1).toLowerCase(Locale.ROOT);
        return KNOWN_ENVIRONMENT_TAGS.contains(tag) ? tag : "";
    }

    private static final Set<String> KNOWN_ENVIRONMENT_TAGS = Set.of("ci", "ote", "dev", "stage", "prod");
}

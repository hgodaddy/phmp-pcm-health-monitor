package com.poynt.phmp.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class CommandRunner {

    private static final Logger log = LoggerFactory.getLogger(CommandRunner.class);
    private static final long DRAIN_GRACE_MS = 2000L;
    /**
     * With -Dphmp.trace=true every device command is logged at INFO, so a live run shows exactly what
     * is being asked of the terminal instead of only the resulting verdicts.
     */
    private static final boolean TRACE = Boolean.parseBoolean(System.getProperty("phmp.trace", "false"));

    private CommandRunner() {
    }

    /**
     * Runs a command, draining stdout/stderr on a separate thread so that the timeout also bounds
     * commands that stall while producing output (for example a wedged {@code adb logcat -d} read).
     */
    public static CommandResult run(List<String> command, int timeoutSeconds) {
        String rendered = String.join(" ", command);
        if (TRACE) {
            log.info("[device] $ {}", abbreviate(rendered));
        } else {
            log.debug("Executing command: {}", rendered);
        }
        long startedAt = System.currentTimeMillis();
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        try {
            Process process = pb.start();
            StringBuilder output = new StringBuilder();
            Thread pump = new Thread(() -> drain(process, output), "phmp-cmd-output");
            pump.setDaemon(true);
            pump.start();

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                pump.join(DRAIN_GRACE_MS);
                log.warn("Command timed out after {}s: {}", timeoutSeconds, String.join(" ", command));
                return new CommandResult(-1, snapshot(output), true);
            }
            pump.join(DRAIN_GRACE_MS);
            String result = snapshot(output).trim();
            if (TRACE) {
                log.info("[device] -> exit={} in {}ms, {} bytes",
                        process.exitValue(), System.currentTimeMillis() - startedAt, result.length());
            }
            return new CommandResult(process.exitValue(), result, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CommandResult(-1, "interrupted", true);
        } catch (Exception e) {
            return new CommandResult(-1, e.getMessage() == null ? e.toString() : e.getMessage(), false);
        }
    }

    private static void drain(Process process, StringBuilder sink) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                synchronized (sink) {
                    sink.append(line).append(System.lineSeparator());
                }
            }
        } catch (Exception ignored) {
            // Stream closed by destroyForcibly or process exit; partial output is still usable.
        }
    }

    private static String abbreviate(String command) {
        return command.length() <= 160 ? command : command.substring(0, 157) + "...";
    }

    private static String snapshot(StringBuilder sink) {
        synchronized (sink) {
            return sink.toString();
        }
    }

    public record CommandResult(int exitCode, String output, boolean timedOut) {
        public boolean isSuccess() {
            return !timedOut && exitCode == 0;
        }
    }
}

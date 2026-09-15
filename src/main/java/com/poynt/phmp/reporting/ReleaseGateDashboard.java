package com.poynt.phmp.reporting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.poynt.phmp.config.PhmpConfig;
import com.poynt.phmp.model.ExecutionSummary;
import com.poynt.phmp.model.ValidationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes per-region latest snapshots and a combined US/EU release-gate dashboard.
 */
public class ReleaseGateDashboard {

    private static final Logger log = LoggerFactory.getLogger(ReleaseGateDashboard.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final PhmpConfig config;

    public ReleaseGateDashboard(PhmpConfig config) {
        this.config = config;
    }

    public String update(ExecutionSummary summary) {
        try {
            Path latestDir = Path.of(config.phmp.reporting.outputDir, "latest");
            Files.createDirectories(latestDir);

            Map<String, Object> snapshot = toSnapshot(summary);
            Path regionFile = latestDir.resolve(summary.getRegion().toUpperCase() + ".json");
            Files.writeString(regionFile, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(snapshot));

            Path dashboard = Path.of(config.phmp.reporting.outputDir, "phmp-release-gate.html");
            Files.writeString(dashboard, renderDashboard(latestDir));
            log.info("Updated release gate dashboard at {}", dashboard.toAbsolutePath());
            return dashboard.toAbsolutePath().toString();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to update release gate dashboard", e);
        }
    }

    private Map<String, Object> toSnapshot(ExecutionSummary summary) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("region", summary.getRegion());
        snapshot.put("deviceSerial", summary.getDeviceSerial());
        snapshot.put("mode", summary.getMode());
        snapshot.put("buildId", summary.getBuildId());
        snapshot.put("passed", summary.isPassed());
        snapshot.put("releaseReady", summary.isReleaseReady());
        snapshot.put("passCount", summary.passCount());
        snapshot.put("failCount", summary.failCount());
        snapshot.put("durationMs", summary.duration().toMillis());
        snapshot.put("startedAt", summary.getStartedAt().toString());
        snapshot.put("finishedAt", summary.getFinishedAt() == null ? null : summary.getFinishedAt().toString());
        snapshot.put("reportPath", summary.getReportPath());
        snapshot.put("logPath", summary.getLogPath());

        List<Map<String, Object>> checks = new ArrayList<>();
        for (ValidationResult result : summary.getResults()) {
            Map<String, Object> check = new LinkedHashMap<>();
            check.put("name", result.getName());
            check.put("passed", result.isPassed());
            check.put("message", result.getMessage());
            checks.add(check);
        }
        snapshot.put("checks", checks);
        return snapshot;
    }

    private String renderDashboard(Path latestDir) throws IOException {
        Map<String, Object> us = readOptional(latestDir.resolve("US.json"));
        Map<String, Object> eu = readOptional(latestDir.resolve("EU.json"));

        boolean usReady = us != null && Boolean.TRUE.equals(us.get("releaseReady"));
        boolean euReady = eu != null && Boolean.TRUE.equals(eu.get("releaseReady"));
        boolean bothPresent = us != null && eu != null;
        boolean gatePass = bothPresent && usReady && euReady;

        String overall = !bothPresent
                ? "PARTIAL — awaiting both US and EU legs"
                : (gatePass ? "PASS — nightly PCM build is release-ready" : "FAIL — release blocked");
        String overallClass = !bothPresent ? "partial" : (gatePass ? "ready" : "blocked");

        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                  <meta charset="UTF-8"/>
                  <meta name="viewport" content="width=device-width, initial-scale=1"/>
                  <title>PHMP Combined Release Gate</title>
                  <style>
                    :root { --ink:#10233f; --muted:#4a5d78; --line:#d5e0ec; --pass:#0b6e4f; --fail:#9b2226; --navy:#16324f; }
                    body {
                      margin:0; font-family:"Iowan Old Style","Palatino Linotype",Palatino,Georgia,serif; color:var(--ink);
                      background:
                        radial-gradient(ellipse at 80%% 0%%, rgba(11,110,79,0.10), transparent 40%%),
                        linear-gradient(165deg,#f4f7fb 0%%,#e9eef5 50%%,#e2e9f2 100%%);
                    }
                    header, main, footer { max-width:1100px; margin:0 auto; padding:28px 40px; }
                    .brand { letter-spacing:.14em; text-transform:uppercase; color:var(--muted); font-size:.82rem; }
                    h1 { margin:8px 0 10px; font-size:2.2rem; }
                    .overall {
                      display:inline-block; margin-top:10px; padding:10px 14px; border-radius:4px;
                      font-family:"Avenir Next","Segoe UI",sans-serif; font-weight:700; letter-spacing:.04em;
                    }
                    .overall.ready { background:#d8f3e7; color:var(--pass); }
                    .overall.blocked { background:#fde2e1; color:var(--fail); }
                    .overall.partial { background:#e8eef7; color:#31527a; }
                    .grid { display:grid; grid-template-columns:1fr 1fr; gap:18px; margin-top:22px; }
                    .card {
                      background:rgba(255,255,255,.9); border:1px solid var(--line); border-radius:10px; padding:18px 20px;
                    }
                    .card h2 { margin:0 0 10px; font-size:1.25rem; }
                    .status.pass { color:var(--pass); font-weight:700; }
                    .status.fail { color:var(--fail); font-weight:700; }
                    .status.missing { color:#7a8aa0; font-weight:700; }
                    ul { margin:10px 0 0; padding-left:18px; color:var(--muted); }
                    a { color:#1d4f7c; }
                    footer { color:var(--muted); font-size:.9rem; padding-top:0; }
                    @media (max-width:780px) { .grid { grid-template-columns:1fr; } header,main,footer{padding:22px 18px;} }
                  </style>
                </head>
                <body>
                  <header>
                    <div class="brand">PCM Health Monitoring Platform · Phase 1 MVP</div>
                    <h1>Combined Release Gate</h1>
                    <p>Nightly vendor PCM validation across US and EU lab profiles.</p>
                    <div class="overall %s">%s</div>
                  </header>
                  <main>
                    <div class="grid">
                      %s
                      %s
                    </div>
                  </main>
                  <footer>
                    Generated %s · Open region reports for full check details · History: reports/history/summary.jsonl
                  </footer>
                </body>
                </html>
                """.formatted(
                overallClass,
                overall,
                renderRegionCard("US", us),
                renderRegionCard("EU", eu),
                FORMATTER.format(Instant.now())
        );
    }

    private String renderRegionCard(String region, Map<String, Object> data) {
        if (data == null) {
            return """
                    <section class="card">
                      <h2>%s</h2>
                      <div class="status missing">NOT RUN YET</div>
                      <p>Run the %s lifecycle to populate this leg.</p>
                    </section>
                    """.formatted(region, region);
        }
        boolean ready = Boolean.TRUE.equals(data.get("releaseReady"));
        boolean passed = Boolean.TRUE.equals(data.get("passed"));
        String statusClass = ready ? "pass" : "fail";
        String statusText = ready ? "RELEASE READY" : (passed ? "PASSED CHECKS / GATE REVIEW" : "FAILED");
        String report = String.valueOf(data.getOrDefault("reportPath", ""));
        String rel = report.contains("/reports/")
                ? report.substring(report.lastIndexOf("/reports/") + 1)
                : "phmp-execution-report-" + region.toLowerCase() + ".html";

        StringBuilder checks = new StringBuilder();
        Object raw = data.get("checks");
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    boolean ok = Boolean.TRUE.equals(map.get("passed"));
                    checks.append("<li>")
                            .append(ok ? "PASS" : "FAIL")
                            .append(" — ")
                            .append(escape(String.valueOf(map.get("name"))))
                            .append("</li>");
                }
            }
        }

        return """
                <section class="card">
                  <h2>%s</h2>
                  <div class="status %s">%s</div>
                  <p>
                    Device <strong>%s</strong> · Mode <strong>%s</strong> · Build <strong>%s</strong><br/>
                    Passed %s / Failed %s · Duration %sms
                  </p>
                  <p><a href="%s">Open latest %s HTML report</a></p>
                  <ul>%s</ul>
                </section>
                """.formatted(
                region,
                statusClass,
                statusText,
                escape(String.valueOf(data.get("deviceSerial"))),
                escape(String.valueOf(data.get("mode"))),
                escape(String.valueOf(data.get("buildId"))),
                String.valueOf(data.get("passCount")),
                String.valueOf(data.get("failCount")),
                String.valueOf(data.get("durationMs")),
                escape(rel.startsWith("reports/") ? rel.substring("reports/".length()) : rel),
                region,
                checks
        );
    }

    private Map<String, Object> readOptional(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            return null;
        }
        return MAPPER.readValue(
                Files.readString(path),
                MAPPER.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class)
        );
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}

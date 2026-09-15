package com.poynt.phmp.reporting;

import com.poynt.phmp.config.PhmpConfig;
import com.poynt.phmp.model.ExecutionSummary;
import com.poynt.phmp.model.ValidationResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class HtmlReportGenerator {

    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final PhmpConfig config;

    public HtmlReportGenerator(PhmpConfig config) {
        this.config = config;
    }

    public String write(ExecutionSummary summary) {
        try {
            Path dir = Path.of(config.phmp.reporting.outputDir);
            Files.createDirectories(dir);
            String fileName = "phmp-" + summary.getRegion().toLowerCase() + "-"
                    + summary.getStartedAt().toEpochMilli() + ".html";
            Path file = dir.resolve(fileName);
            String html = render(summary);
            Files.writeString(file, html);

            Path latest = dir.resolve(config.phmp.reporting.htmlReportName.replace(
                    ".html", "-" + summary.getRegion().toLowerCase() + ".html"));
            Files.writeString(latest, html);
            return file.toAbsolutePath().toString();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write HTML report", e);
        }
    }

    private String render(ExecutionSummary summary) {
        String statusColor = summary.isPassed() ? "#0b6e4f" : "#9b2226";
        String readyBadge = summary.isReleaseReady()
                ? "<span class='badge ready'>RELEASE READY</span>"
                : "<span class='badge blocked'>RELEASE BLOCKED</span>";

        StringBuilder pipeline = new StringBuilder();
        List<ValidationResult> results = summary.getResults();
        for (int i = 0; i < results.size(); i++) {
            ValidationResult result = results.get(i);
            String cls = result.isSkipped() ? "step skip" : (result.isPassed() ? "step pass" : "step fail");
            pipeline.append("<div class='").append(cls).append("'>")
                    .append("<span class='n'>").append(i + 1).append("</span>")
                    .append("<span class='t'>").append(escape(shortName(result.getName()))).append("</span>")
                    .append("</div>");
            if (i < results.size() - 1) {
                pipeline.append("<div class='arrow'>→</div>");
            }
        }

        StringBuilder rows = new StringBuilder();
        for (ValidationResult result : results) {
            String badge = result.isSkipped()
                    ? "<span class='skip'>SKIP</span>"
                    : result.isPassed()
                    ? "<span class='pass'>PASS</span>"
                    : "<span class='fail'>FAIL</span>";
            rows.append("<tr>")
                    .append("<td>").append(badge).append("</td>")
                    .append("<td>").append(escape(result.getName())).append("</td>")
                    .append("<td>").append(escape(result.getMessage())).append("</td>")
                    .append("<td><ul>");
            for (String detail : result.getDetails()) {
                rows.append("<li>").append(escape(detail)).append("</li>");
            }
            rows.append("</ul></td></tr>");
        }

        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                  <meta charset="UTF-8"/>
                  <meta name="viewport" content="width=device-width, initial-scale=1"/>
                  <title>PHMP Execution Report - %s</title>
                  <style>
                    :root {
                      --ink: #10233f;
                      --muted: #4a5d78;
                      --line: #d5e0ec;
                      --panel: rgba(255,255,255,0.88);
                      --pass: #0b6e4f;
                      --fail: #9b2226;
                      --navy: #16324f;
                    }
                    * { box-sizing: border-box; }
                    body {
                      margin: 0;
                      font-family: "Iowan Old Style", "Palatino Linotype", Palatino, Georgia, serif;
                      color: var(--ink);
                      background:
                        radial-gradient(ellipse at 12%% 0%%, rgba(22, 90, 145, 0.12), transparent 42%%),
                        linear-gradient(165deg, #f4f7fb 0%%, #e9eef5 48%%, #e2e9f2 100%%);
                    }
                    header, main, footer { max-width: 1180px; margin: 0 auto; padding-left: 40px; padding-right: 40px; }
                    header { padding-top: 36px; padding-bottom: 24px; }
                    .brand { font-size: 0.85rem; letter-spacing: 0.14em; text-transform: uppercase; color: var(--muted); }
                    h1 { margin: 8px 0 14px; font-size: 2.15rem; letter-spacing: -0.01em; }
                    .meta { display: flex; flex-wrap: wrap; gap: 14px 22px; color: var(--muted); font-size: 0.98rem; }
                    .badge {
                      display: inline-block; margin-top: 16px; padding: 8px 14px; border-radius: 4px;
                      font-family: "Avenir Next", "Segoe UI", sans-serif; font-size: 0.82rem;
                      letter-spacing: 0.06em; font-weight: 700;
                    }
                    .badge.ready { background: #d8f3e7; color: var(--pass); }
                    .badge.blocked { background: #fde2e1; color: var(--fail); }
                    .panel {
                      background: var(--panel); border: 1px solid var(--line); border-radius: 10px;
                      padding: 18px 20px; margin-bottom: 22px; backdrop-filter: blur(6px);
                    }
                    .pipeline {
                      display: flex; flex-wrap: wrap; align-items: center; gap: 8px;
                    }
                    .step {
                      display: flex; align-items: center; gap: 8px;
                      padding: 8px 10px; border-radius: 6px; border: 1px solid var(--line);
                      background: #fff; font-family: "Avenir Next", "Segoe UI", sans-serif; font-size: 0.78rem;
                    }
                    .step .n {
                      width: 20px; height: 20px; border-radius: 50%%; display: inline-flex; align-items: center;
                      justify-content: center; font-weight: 700; color: #fff; background: #7a8aa0;
                    }
                    .step.pass { border-color: #9ed9c0; }
                    .step.pass .n { background: var(--pass); }
                    .step.fail { border-color: #f0b4b2; }
                    .step.fail .n { background: var(--fail); }
                    .step.skip { border-color: #e0c98a; }
                    .step.skip .n { background: #b8860b; }
                    .arrow { color: #9aabbf; font-family: sans-serif; }
                    table { width: 100%%; border-collapse: collapse; background: var(--panel); border: 1px solid var(--line); }
                    th, td { text-align: left; padding: 12px 14px; border-bottom: 1px solid var(--line); vertical-align: top; }
                    th {
                      background: var(--navy); color: #f4f8fc;
                      font-family: "Avenir Next", "Segoe UI", sans-serif; font-weight: 600; font-size: 0.86rem;
                    }
                    .pass { color: var(--pass); font-weight: 700; font-family: "Avenir Next", sans-serif; }
                    .fail { color: var(--fail); font-weight: 700; font-family: "Avenir Next", sans-serif; }
                    .skip { color: #b8860b; font-weight: 700; font-family: "Avenir Next", sans-serif; }
                    ul { margin: 0; padding-left: 18px; }
                    footer { padding: 8px 40px 40px; color: var(--muted); font-size: 0.9rem; }
                    @media (max-width: 720px) {
                      header, main, footer { padding-left: 18px; padding-right: 18px; }
                      h1 { font-size: 1.6rem; }
                    }
                  </style>
                </head>
                <body>
                  <header>
                    <div class="brand">PCM Health Monitoring Platform · Phase 1 MVP</div>
                    <h1>Execution Report — %s</h1>
                    <div class="meta">
                      <div>Device: <strong>%s</strong></div>
                      <div>Mode: <strong>%s</strong></div>
                      <div>Build: <strong>%s</strong></div>
                      <div>Result: <strong style="color:%s">%s</strong></div>
                      <div>Passed %d / Failed %d</div>
                      <div>%s → %s · %s</div>
                    </div>
                    %s
                  </header>
                  <main>
                    <div class="panel">
                      <strong>Validation pipeline</strong>
                      <div class="pipeline" style="margin-top:12px">%s</div>
                    </div>
                    <table>
                      <thead>
                        <tr><th>Status</th><th>Check</th><th>Summary</th><th>Details</th></tr>
                      </thead>
                      <tbody>
                        %s
                      </tbody>
                    </table>
                  </main>
                  <footer>
                    Log: %s<br/>
                    Report generated by PHMP Phase 1 MVP · Nightly PCM release gate
                  </footer>
                </body>
                </html>
                """.formatted(
                summary.getRegion(),
                escape(summary.getRegion()),
                escape(summary.getDeviceSerial()),
                escape(summary.getMode()),
                escape(summary.getBuildId()),
                statusColor,
                summary.isPassed() ? "PASS" : "FAIL",
                summary.passCount(),
                summary.failCount(),
                FORMATTER.format(summary.getStartedAt()),
                summary.getFinishedAt() == null ? "-" : FORMATTER.format(summary.getFinishedAt()),
                summary.duration().toSeconds() + "s",
                readyBadge,
                pipeline,
                rows,
                escape(summary.getLogPath() == null ? "n/a" : summary.getLogPath())
        );
    }

    private static String shortName(String name) {
        return name
                .replace(" Validation", "")
                .replace(" Monitoring", "")
                .replace(" Decision", "")
                .replace("Disconnect / Reconnect", "Reconnect")
                .replace("Flash / OTA Deployment", "Flash/OTA")
                .replace("Cloud Messaging", "Cloud Msg")
                .replace("Release Gate", "Gate")
                .replace("Ping/Pong", "Ping")
                .replace("Socket Health", "Health")
                .replace("Authentication", "Auth")
                .replace("Environment", "Env")
                .replace("Installation", "Install")
                .replace("WebSocket", "WS");
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

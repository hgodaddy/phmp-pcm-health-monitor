package com.poynt.phmp.reporting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.poynt.phmp.config.PhmpConfig;
import com.poynt.phmp.model.ExecutionSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Day 4 – appends lightweight historical execution records for trend visibility.
 */
public class HistoryStore {

    private static final Logger log = LoggerFactory.getLogger(HistoryStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PhmpConfig config;

    public HistoryStore(PhmpConfig config) {
        this.config = config;
    }

    public String append(ExecutionSummary summary) {
        try {
            Path dir = Path.of(config.phmp.reporting.historyDir);
            Files.createDirectories(dir);
            Path file = dir.resolve("summary.jsonl");

            Map<String, Object> record = new LinkedHashMap<>();
            record.put("timestamp", summary.getStartedAt().toString());
            record.put("finishedAt", summary.getFinishedAt() == null ? null : summary.getFinishedAt().toString());
            record.put("buildId", summary.getBuildId());
            record.put("region", summary.getRegion());
            record.put("mode", summary.getMode());
            record.put("deviceSerial", summary.getDeviceSerial());
            record.put("passed", summary.isPassed());
            record.put("passCount", summary.passCount());
            record.put("failCount", summary.failCount());
            record.put("durationMs", summary.duration().toMillis());
            record.put("releaseReady", summary.isReleaseReady());
            record.put("reportPath", summary.getReportPath());

            Files.writeString(
                    file,
                    MAPPER.writeValueAsString(record) + System.lineSeparator(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
            log.info("Appended historical result to {}", file.toAbsolutePath());
            return file.toAbsolutePath().toString();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to append history record", e);
        }
    }
}

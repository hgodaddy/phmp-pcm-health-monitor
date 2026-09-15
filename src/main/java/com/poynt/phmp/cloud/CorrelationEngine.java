package com.poynt.phmp.cloud;

import com.poynt.phmp.device.DeviceClient;

import java.util.ArrayList;
import java.util.List;

public class CorrelationEngine {

    public CorrelationReport correlate(
            CloudMessageClient.InjectedMessage injected,
            DeviceClient.MessageDeliveryResult delivery
    ) {
        List<String> details = new ArrayList<>();
        long deliveryLatency = delivery.deliveredAtEpochMs() > 0
                ? delivery.deliveredAtEpochMs() - injected.injectedAtEpochMs()
                : -1;
        long processingLatency = delivery.processedAtEpochMs() > 0 && delivery.deliveredAtEpochMs() > 0
                ? delivery.processedAtEpochMs() - delivery.deliveredAtEpochMs()
                : -1;

        details.add("InjectedAt=" + injected.injectedAtEpochMs());
        details.add("DeliveredAt=" + delivery.deliveredAtEpochMs());
        details.add("ProcessedAt=" + delivery.processedAtEpochMs());
        details.add("DeliveryLatencyMs=" + deliveryLatency);
        details.add("ProcessingLatencyMs=" + processingLatency);

        boolean aligned = delivery.delivered()
                && delivery.processed()
                && deliveryLatency >= 0
                && processingLatency >= 0
                && injected.correlationId().equals(delivery.correlationId());

        String summary = aligned
                ? "Correlation aligned for " + injected.correlationId()
                : "Correlation misaligned for " + injected.correlationId();

        return new CorrelationReport(aligned, summary, details, deliveryLatency, processingLatency);
    }

    public record CorrelationReport(
            boolean aligned,
            String summary,
            List<String> details,
            long deliveryLatencyMs,
            long processingLatencyMs
    ) {
    }
}

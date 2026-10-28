package io.github.snktibor.vdr.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.snktibor.vdr.e2e.support.TransactionOutcome;
import io.github.snktibor.vdr.e2e.support.TransactionResult;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScalabilityBenchTest {

    @Test
    void percentileUsesNearestRank() {
        final List<Long> sorted = List.of(10L, 20L, 30L, 40L);
        assertEquals(20L, ScalabilityBench.percentile(sorted, 50));
        assertEquals(40L, ScalabilityBench.percentile(sorted, 95));
        assertEquals(-1L, ScalabilityBench.percentile(List.of(), 50));
    }

    @Test
    void summaryCountsClientsAndWriterSeparately() {
        final TransactionResult committed = TransactionResult.committed("c0", "tx", 1);
        final TransactionResult stale =
                TransactionResult.failed(
                        "c0",
                        "tx",
                        null,
                        TransactionOutcome.ENDORSEMENT_REJECTED,
                        null,
                        new IllegalStateException(
                                "guard registry 'h' is at version 3, expected 2: it has changed"),
                        1);
        final String summary =
                ScalabilityBench.summarize(
                        List.of(
                                new ScalabilityBench.Row("c0", 0L, 100L, committed),
                                new ScalabilityBench.Row("c1", 0L, 300L, committed),
                                new ScalabilityBench.Row("c0", 0L, 50L, stale),
                                new ScalabilityBench.Row("writer", 0L, 10L, committed)),
                        2);
        // tps, p50, p95, p99, then one count per outcome, failed_precondition, writer.
        assertEquals("1.000,100,300,300,2,0,1,0,0,0,0,1,1,1", summary);
        assertEquals(
                ScalabilityBench.SUMMARY_HEADER.split(",").length,
                7 + 1 + summary.split(",").length);
    }
}

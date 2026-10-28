package com.example.vdr.bench;

import static com.example.vdr.bench.ScalabilityBench.ACCUM_VALUE;
import static com.example.vdr.bench.ScalabilityBench.GUARDED_TX;
import static com.example.vdr.bench.ScalabilityBench.TX_HEADER;

import com.example.vdr.bench.ScalabilityBench.Registry;
import com.example.vdr.bench.ScalabilityBench.Row;
import com.example.vdr.e2e.support.ConcurrentSubmission;
import com.example.vdr.e2e.support.CryptoUtils;
import com.example.vdr.e2e.support.FabricConnectionManager;
import com.example.vdr.e2e.support.GuardsJson;
import com.example.vdr.e2e.support.TransactionExecutor;
import com.example.vdr.e2e.support.TransactionResult;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.hyperledger.fabric.client.Status;
import org.junit.jupiter.api.Test;

/**
 * Fault injection for the robustness measurement on {@code 3org-raft}. Each run drives the W2 load
 * ({@code k} guards, {@code c} closed-loop clients) and, next to it, a stream of mutually guarded
 * pairs, while {@code bench/fault.sh} kills part of the network and restarts it. The pairs check
 * the at-most-one guarantee on the ledger itself: a pair where both registries advanced is a
 * violation. Fault {@code solo} instead submits guarded updates endorsed by one organisation only.
 *
 * <p>Runs like {@link ScalabilityBench}, only when named: {@code mvn test -Dtest=RobustnessBench}.
 */
class RobustnessBench {

    static final String PAIR_HEADER =
            "run_id,fault,repetition,pair,start_ms,end_ms,a_outcome,a_code,b_outcome,b_code,"
                    + "a_advanced,b_advanced";
    static final String FAULT_HEADER =
            "run_id,fault,repetition,measure_from_ms,down_ms,up_ms,end_ms,touched,"
                    + "a_initial,b_initial,a_final,b_final";
    static final String SOLO_HEADER = "run_id,endorsing_orgs,attempt,validation_code,advanced";

    @Test
    void run() throws Exception {
        final List<String> faults =
                Arrays.asList(
                        ScalabilityBench.param("bench.faults", "none,peer,org,leader,quorum,solo")
                                .split(","));
        final int k = Integer.parseInt(ScalabilityBench.param("bench.k", "2"));
        final int c = Integer.parseInt(ScalabilityBench.param("bench.c", "4"));
        final int warmup = Integer.parseInt(ScalabilityBench.param("bench.warmup", "10"));
        final int duration = Integer.parseInt(ScalabilityBench.param("bench.duration", "120"));
        final int faultAt = Integer.parseInt(ScalabilityBench.param("bench.faultAt", "30"));
        final int faultFor = Integer.parseInt(ScalabilityBench.param("bench.faultFor", "30"));
        final int reps = Integer.parseInt(ScalabilityBench.param("bench.reps", "3"));
        final int soloAttempts = Integer.parseInt(ScalabilityBench.param("bench.solo", "20"));
        final String topology = ScalabilityBench.param("bench.topology", "3org-raft");
        final String batchTimeoutMs = ScalabilityBench.param("bench.batchTimeoutMs", "250");
        final Path out = Path.of(ScalabilityBench.param("bench.out", "target/bench"));
        Files.createDirectories(out);

        final FabricConnectionManager connection = new FabricConnectionManager();
        connection.connect("bench");
        try (PrintWriter txOut = ScalabilityBench.appender(out.resolve("transactions.csv"), TX_HEADER);
                PrintWriter pairOut = ScalabilityBench.appender(out.resolve("pairs.csv"), PAIR_HEADER);
                PrintWriter faultOut =
                        ScalabilityBench.appender(out.resolve("faults.csv"), FAULT_HEADER);
                PrintWriter soloOut = ScalabilityBench.appender(out.resolve("solo.csv"), SOLO_HEADER)) {
            final List<Registry> registries = ScalabilityBench.provision(connection, c + k + 2);
            final List<Registry> targets = registries.subList(0, c);
            final List<Registry> guarded = registries.subList(c, c + k);
            final Registry a = registries.get(c + k);
            final Registry b = registries.get(c + k + 1);
            final TransactionExecutor reader = ScalabilityBench.executor(connection);

            for (int rep = 1; rep <= reps; rep++) {
                for (final String fault : faults) {
                    final String runId = "F-" + fault + "-rep" + rep;
                    System.out.printf("bench: %s started%n", runId);
                    if (fault.equals("solo")) {
                        for (final String row : solo(connection, a, b, soloAttempts)) {
                            soloOut.println(runId + "," + row);
                        }
                        soloOut.flush();
                        continue;
                    }
                    final long a0 = ScalabilityBench.version(reader, a);
                    final long b0 = ScalabilityBench.version(reader, b);
                    final long measureFrom = System.currentTimeMillis() + warmup * 1000L;
                    final long end = measureFrom + duration * 1000L;
                    final ExecutorService pool = Executors.newFixedThreadPool(2);
                    final List<Row> rows;
                    final List<String> pairs;
                    final long downMs;
                    final long upMs;
                    final String touched;
                    try {
                        final Future<List<Row>> load =
                                pool.submit(
                                        () ->
                                                ScalabilityBench.runPoint(
                                                        connection, targets, guarded, null, 0.0,
                                                        warmup, duration, 0L, 0L));
                        final Future<List<String>> race =
                                pool.submit(() -> race(connection, a, b, end));
                        sleepUntil(measureFrom + faultAt * 1000L);
                        downMs = System.currentTimeMillis();
                        touched = faultScript("down", fault);
                        sleepUntil(measureFrom + (faultAt + faultFor) * 1000L);
                        upMs = System.currentTimeMillis();
                        faultScript("up", fault);
                        rows = load.get();
                        pairs = race.get();
                    } finally {
                        pool.shutdownNow();
                    }
                    final String prefix =
                            String.join(",", runId, topology, batchTimeoutMs, "F-" + fault, "" + k, "" + c, "0.0", "" + rep);
                    rows.forEach(row -> txOut.println(prefix + "," + ScalabilityBench.csv(row)));
                    final String pairPrefix = runId + "," + fault + "," + rep + ",";
                    pairs.forEach(p -> pairOut.println(pairPrefix + p));
                    faultOut.println(
                            String.join(
                                    ",",
                                    runId,
                                    fault,
                                    "" + rep,
                                    "" + measureFrom,
                                    "" + downMs,
                                    "" + upMs,
                                    "" + end,
                                    touched.isBlank() ? "-" : touched.trim().replace('\n', ' '),
                                    "" + a0,
                                    "" + b0,
                                    "" + ScalabilityBench.version(reader, a),
                                    "" + ScalabilityBench.version(reader, b)));
                    txOut.flush();
                    pairOut.flush();
                    faultOut.flush();
                    System.out.printf(
                            "bench: %s done: %d transactions, %d pairs%n", runId, rows.size(), pairs.size());
                }
            }
        } finally {
            connection.close();
        }
    }

    /**
     * Mutually guarded pairs, back to back until {@code end}: A revokes in {@code a} guarding
     * {@code b}, B revokes in {@code b} guarding {@code a}, both endorsed before either is
     * submitted. Whether each registry advanced is read back from the ledger, not taken from the
     * client's status, so a lost or late status cannot hide a double commit. Warm-up pairs are
     * kept too, so the flags add up to the version change of {@code a} and {@code b} over the run.
     */
    static List<String> race(
            final FabricConnectionManager connection,
            final Registry a,
            final Registry b,
            final long end) {
        final TransactionExecutor executor = ScalabilityBench.executor(connection);
        final List<String> rows = new ArrayList<>();
        for (int pair = 0; System.currentTimeMillis() < end; pair++) {
            try {
                final long va = ScalabilityBench.version(executor, a);
                final long vb = ScalabilityBench.version(executor, b);
                final long start = System.currentTimeMillis();
                final List<TransactionResult> results =
                        executor.submitTogether(
                                List.of(
                                        guardedSubmission("A", a, b, vb),
                                        guardedSubmission("B", b, a, va)));
                final long finish = System.currentTimeMillis();
                final boolean aAdvanced = ScalabilityBench.version(executor, a) > va;
                final boolean bAdvanced = ScalabilityBench.version(executor, b) > vb;
                rows.add(
                        String.join(
                                ",",
                                "" + pair,
                                "" + start,
                                "" + finish,
                                outcome(results.get(0)),
                                outcome(results.get(1)),
                                "" + aAdvanced,
                                "" + bAdvanced));
            } catch (Exception e) {
                // Only the version reads throw; peer0.org1 stays up in every fault, so retry.
                System.err.println("bench: pair " + pair + " skipped: " + e.getMessage());
            }
        }
        return rows;
    }

    private static ConcurrentSubmission guardedSubmission(
            final String label, final Registry target, final Registry guard, final long version) {
        return ConcurrentSubmission.signed(
                label,
                GUARDED_TX,
                target.owner(),
                target.keyPair(),
                target.id(),
                ACCUM_VALUE,
                GuardsJson.builder().guard(guard.id(), version).build().text());
    }

    private static String outcome(final TransactionResult result) {
        return result.outcome().name()
                + ","
                + (result.validationCode() == null ? "" : result.validationCode().name());
    }

    /**
     * Guarded updates of {@code target} (guarding {@code guard} at its current version) endorsed by
     * Org1 alone, then by Org1 and Org2 as the control. Under the 2-of-3 policy the first must
     * never change the ledger, whatever Org1's peers sign.
     */
    static List<String> solo(
            final FabricConnectionManager connection,
            final Registry target,
            final Registry guard,
            final int attempts)
            throws Exception {
        final TransactionExecutor reader = ScalabilityBench.executor(connection);
        final List<String> rows = new ArrayList<>();
        for (final String[] orgs : List.of(new String[] {"Org1MSP"}, new String[] {"Org1MSP", "Org2MSP"})) {
            for (int attempt = 1; attempt <= attempts; attempt++) {
                final long before = ScalabilityBench.version(reader, target);
                final List<String> payload =
                        List.of(
                                target.id(),
                                ACCUM_VALUE,
                                GuardsJson.builder()
                                        .guard(guard.id(), ScalabilityBench.version(reader, guard))
                                        .build()
                                        .text());
                final List<String> args = new ArrayList<>(payload);
                args.add(CryptoUtils.buildDidJwt(target.owner(), target.keyPair(), GUARDED_TX, payload));
                String code;
                try {
                    final Status status =
                            connection
                                    .contract()
                                    .newProposal(GUARDED_TX)
                                    .addArguments(args.toArray(String[]::new))
                                    .setEndorsingOrganizations(orgs)
                                    .build()
                                    .endorse()
                                    .submitAsync()
                                    .getStatus();
                    code = status.getCode().name();
                } catch (Exception e) {
                    code = e.getClass().getSimpleName();
                }
                final boolean advanced = ScalabilityBench.version(reader, target) > before;
                rows.add(String.join(",", String.join("+", orgs), "" + attempt, code, "" + advanced));
            }
        }
        return rows;
    }

    /** Runs {@code bench/fault.sh <action> <fault>} and returns the containers it touched. */
    private static String faultScript(final String action, final String fault) throws Exception {
        final Process process =
                new ProcessBuilder(
                                "bash",
                                ScalabilityBench.param("bench.faultScript", "bench/fault.sh"),
                                action,
                                fault)
                        .redirectErrorStream(true)
                        .start();
        final String output =
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            // A run without its fault would be reported as if it had one, so stop here.
            throw new IllegalStateException("fault.sh " + action + " " + fault + " failed: " + output);
        }
        System.out.printf("bench: fault %s %s: %s%n", action, fault, output.trim());
        return output;
    }

    private static void sleepUntil(final long epochMs) throws InterruptedException {
        final long wait = epochMs - System.currentTimeMillis();
        if (wait > 0) {
            Thread.sleep(wait);
        }
    }
}

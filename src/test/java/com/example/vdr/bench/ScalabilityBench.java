package com.example.vdr.bench;

import com.example.vdr.TestVectors;
import com.example.vdr.e2e.entity.DidEntityHandler;
import com.example.vdr.e2e.entity.IndependentRegistryFixture;
import com.example.vdr.e2e.support.FabricConnectionManager;
import com.example.vdr.e2e.support.GuardsJson;
import com.example.vdr.e2e.support.RetryPolicy;
import com.example.vdr.e2e.support.ScenarioContext;
import com.example.vdr.e2e.support.TransactionExecutor;
import com.example.vdr.e2e.support.TransactionOutcome;
import com.example.vdr.e2e.support.TransactionResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Closed-loop load generator for the scalability measurement: W1 (cost of guards), W2 (load) and W3
 * (contention on one hot registry).
 *
 * <p>The class name matches neither the surefire ({@code *Test}) nor the failsafe ({@code *IT})
 * include pattern, so {@code mvn test} and {@code mvn -Pe2e verify} never run it. It runs only when
 * named, {@code mvn test -Dtest=ScalabilityBench}, which reuses the E2E classpath and support
 * classes without a new plugin, profile or main class. {@code bench/run-matrix.sh} sets the
 * parameters read in {@link #run()}.
 */
class ScalabilityBench {

    private static final String UNGUARDED_TX = "updateRevRegEntry";
    private static final String GUARDED_TX = "updateRevRegEntryIfGuardsUnchanged";
    private static final String ACCUM_VALUE = TestVectors.accumValue("accum-after-revocation");
    private static final int PROVISIONING_THREADS = 16;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final String TX_HEADER =
            "run_id,topology,workload,k,c,r,repetition,client,submit_ms,final_ms,outcome,"
                    + "validation_code,error_code";
    static final String SUMMARY_HEADER =
            "run_id,topology,workload,k,c,r,repetition,duration_s,committed_tps,p50_ms,p95_ms,"
                    + "p99_ms,"
                    + Arrays.stream(TransactionOutcome.values())
                            .map(o -> o.name().toLowerCase())
                            .collect(Collectors.joining(","))
                    + ",failed_precondition,writer_committed,writer_total";

    record Registry(String id, String owner, KeyPair keyPair) {}

    record Row(String client, long submitMs, long finalMs, TransactionResult result) {}

    @Test
    void run() throws Exception {
        final String workload = param("bench.workload", "W1");
        final boolean contention = workload.equals("W3");
        if (!List.of("W1", "W2", "W3").contains(workload)) {
            throw new IllegalArgumentException("bench.workload must be W1, W2 or W3: " + workload);
        }
        // W3 always guards exactly the hot registry; W1 and W2 have no background writer.
        final List<Integer> ks = contention ? List.of(1) : ints(param("bench.k", "0"));
        final List<Integer> cs = ints(param("bench.c", "4"));
        final List<Double> rs =
                contention
                        ? Arrays.stream(param("bench.r", "0").split(","))
                                .map(String::trim)
                                .map(Double::valueOf)
                                .toList()
                        : List.of(0.0);
        final int warmup = Integer.parseInt(param("bench.warmup", "10"));
        final int duration = Integer.parseInt(param("bench.duration", "60"));
        final int reps = Integer.parseInt(param("bench.reps", "3"));
        final String topology = param("bench.topology", "1org-solo");
        final Path out = Path.of(param("bench.out", "target/bench"));
        Files.createDirectories(out);

        final FabricConnectionManager connection = new FabricConnectionManager();
        connection.connect("bench");
        try (PrintWriter txOut = appender(out.resolve("transactions.csv"), TX_HEADER);
                PrintWriter summaryOut = appender(out.resolve("summary.csv"), SUMMARY_HEADER)) {
            final int maxC = Collections.max(cs);
            final int maxK = contention ? 0 : Collections.max(ks);
            final long provisionStart = System.currentTimeMillis();
            final List<Registry> registries =
                    provision(connection, maxC + maxK + (contention ? 1 : 0));
            System.out.printf(
                    "bench: provisioned %d registries in %d s%n",
                    registries.size(), (System.currentTimeMillis() - provisionStart) / 1000);
            final List<Registry> targets = registries.subList(0, maxC);
            final List<Registry> guarded = registries.subList(maxC, maxC + maxK);
            final Registry hot = contention ? registries.get(registries.size() - 1) : null;

            // Repetitions outermost, so slow drift of the network spreads over every point.
            for (int rep = 1; rep <= reps; rep++) {
                for (final int k : ks) {
                    for (final int c : cs) {
                        for (final double r : rs) {
                            final String runId =
                                    String.format("%s-k%d-c%d-r%s-rep%d", workload, k, c, r, rep);
                            final String prefix =
                                    String.join(
                                            ",",
                                            runId,
                                            topology,
                                            workload,
                                            Integer.toString(k),
                                            Integer.toString(c),
                                            Double.toString(r),
                                            Integer.toString(rep));
                            System.out.printf("bench: %s started%n", runId);
                            final List<Row> rows =
                                    runPoint(
                                            connection,
                                            targets.subList(0, c),
                                            guarded.subList(0, Math.min(k, guarded.size())),
                                            hot,
                                            r,
                                            warmup,
                                            duration);
                            for (final Row row : rows) {
                                txOut.println(prefix + "," + csv(row));
                            }
                            final String summary = summarize(rows, duration);
                            summaryOut.println(prefix + "," + duration + "," + summary);
                            txOut.flush();
                            summaryOut.flush();
                            System.out.printf("bench: %s done: %s%n", runId, summary);
                        }
                    }
                }
            }
        } finally {
            connection.close();
        }
    }

    /**
     * Runs one point: {@code targets.size()} closed-loop clients for warm-up plus measured time,
     * each revoking in its own target registry, and, under contention, a background writer on the
     * hot registry. Only transactions submitted inside the measured window are returned.
     */
    private static List<Row> runPoint(
            final FabricConnectionManager connection,
            final List<Registry> targets,
            final List<Registry> guarded,
            final Registry hot,
            final double writesPerSecond,
            final int warmupSeconds,
            final int durationSeconds)
            throws Exception {
        final long measureFrom = System.currentTimeMillis() + warmupSeconds * 1000L;
        final long end = measureFrom + durationSeconds * 1000L;
        final List<Row> rows = Collections.synchronizedList(new ArrayList<>());

        // Nobody writes the W1/W2 guarded registries, so they stay at their genesis version 0.
        final GuardsJson.Builder fixedGuards = GuardsJson.builder();
        guarded.forEach(g -> fixedGuards.guard(g.id(), 0L));
        final String fixedGuardsText = guarded.isEmpty() ? null : fixedGuards.build().text();

        final ExecutorService clients = Executors.newFixedThreadPool(targets.size());
        final ExecutorService writes = Executors.newCachedThreadPool();
        final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor();
        try {
            if (hot != null && writesPerSecond > 0) {
                // Open loop: a tick starts a write even if the previous one is still in flight.
                ticker.scheduleAtFixedRate(
                        () -> writes.submit(() -> write(connection, hot, measureFrom, rows)),
                        0L,
                        Math.round(1_000_000L / writesPerSecond),
                        TimeUnit.MICROSECONDS);
            }
            final List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < targets.size(); i++) {
                final Registry target = targets.get(i);
                final String client = "c" + i;
                futures.add(
                        clients.submit(
                                () -> {
                                    final TransactionExecutor executor = executor(connection);
                                    while (System.currentTimeMillis() < end) {
                                        final String guards =
                                                hot != null
                                                        ? GuardsJson.builder()
                                                                .guard(
                                                                        hot.id(),
                                                                        version(executor, hot))
                                                                .build()
                                                                .text()
                                                        : fixedGuardsText;
                                        final long submit = System.currentTimeMillis();
                                        final TransactionResult result =
                                                guards == null
                                                        ? executor.submitSignedForOutcome(
                                                                client,
                                                                UNGUARDED_TX,
                                                                target.owner(),
                                                                target.keyPair(),
                                                                target.id(),
                                                                ACCUM_VALUE)
                                                        : executor.submitSignedForOutcome(
                                                                client,
                                                                GUARDED_TX,
                                                                target.owner(),
                                                                target.keyPair(),
                                                                target.id(),
                                                                ACCUM_VALUE,
                                                                guards);
                                        if (submit >= measureFrom) {
                                            rows.add(
                                                    new Row(
                                                            client,
                                                            submit,
                                                            System.currentTimeMillis(),
                                                            result));
                                        }
                                    }
                                    return null;
                                }));
            }
            for (final Future<?> future : futures) {
                future.get();
            }
        } finally {
            ticker.shutdownNow();
            clients.shutdownNow();
            writes.shutdown();
            writes.awaitTermination(2, TimeUnit.MINUTES);
        }
        final List<Row> measured = new ArrayList<>(rows);
        measured.removeIf(row -> row.submitMs() >= end);
        return measured;
    }

    /** One unguarded background write on the hot registry, by its owner. */
    private static void write(
            final FabricConnectionManager connection,
            final Registry hot,
            final long measureFrom,
            final List<Row> rows) {
        final long submit = System.currentTimeMillis();
        final TransactionResult result =
                executor(connection)
                        .submitSignedForOutcome(
                                "writer",
                                UNGUARDED_TX,
                                hot.owner(),
                                hot.keyPair(),
                                hot.id(),
                                ACCUM_VALUE);
        if (submit >= measureFrom) {
            rows.add(new Row("writer", submit, System.currentTimeMillis(), result));
        }
    }

    /** The version a client observes the hot registry at, right before it guards on it. */
    private static long version(final TransactionExecutor executor, final Registry registry)
            throws Exception {
        return MAPPER.readTree(executor.evaluate("getRevRegEntry", registry.id()))
                .get("version")
                .asLong();
    }

    /**
     * A fresh executor with retries off: a retried loser would hide the very conflict W3 measures.
     * Each thread gets its own, since {@link ScenarioContext} is not thread-safe.
     */
    private static TransactionExecutor executor(final FabricConnectionManager connection) {
        final TransactionExecutor executor =
                new TransactionExecutor(connection, new ScenarioContext());
        executor.setRetryPolicy(RetryPolicy.none());
        return executor;
    }

    /**
     * Creates {@code count} independent registries (issuer DID, schema, credential definition,
     * definition, genesis entry) in parallel, outside any timed window. They share no ledger key,
     * so parallel provisioning cannot conflict.
     */
    private static List<Registry> provision(
            final FabricConnectionManager connection, final int count) throws Exception {
        final ExecutorService pool =
                Executors.newFixedThreadPool(Math.max(1, Math.min(PROVISIONING_THREADS, count)));
        try {
            final List<Future<Registry>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                final String handle = "bench-" + i;
                futures.add(
                        pool.submit(
                                () -> {
                                    final ScenarioContext context = new ScenarioContext();
                                    final TransactionExecutor executor =
                                            new TransactionExecutor(connection, context);
                                    final IndependentRegistryFixture fixture =
                                            new IndependentRegistryFixture(
                                                    executor,
                                                    context,
                                                    new DidEntityHandler(executor, context));
                                    fixture.provision(handle);
                                    return new Registry(
                                            fixture.revRegDefId(handle),
                                            fixture.issuerDid(handle),
                                            fixture.issuerKeyPair(handle));
                                }));
            }
            final List<Registry> registries = new ArrayList<>();
            for (final Future<Registry> future : futures) {
                registries.add(future.get());
            }
            return registries;
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Summary columns after the shared prefix: committed client tx/s, latency percentiles of the
     * committed client transactions, client counts by outcome, then the background writer.
     */
    static String summarize(final List<Row> rows, final int durationSeconds) {
        final Map<TransactionOutcome, Integer> counts = new EnumMap<>(TransactionOutcome.class);
        final List<Long> latencies = new ArrayList<>();
        int failedPrecondition = 0;
        int writerCommitted = 0;
        int writerTotal = 0;
        for (final Row row : rows) {
            final boolean committed = row.result().isCommitted();
            if (row.client().equals("writer")) {
                writerTotal++;
                writerCommitted += committed ? 1 : 0;
                continue;
            }
            counts.merge(row.result().outcome(), 1, Integer::sum);
            failedPrecondition += errorCode(row.result()).equals("FAILED_PRECONDITION") ? 1 : 0;
            if (committed) {
                latencies.add(row.finalMs() - row.submitMs());
            }
        }
        Collections.sort(latencies);
        final List<String> columns = new ArrayList<>();
        columns.add(
                String.format(
                        Locale.ROOT,
                        "%.3f",
                        counts.getOrDefault(TransactionOutcome.COMMITTED, 0)
                                / (double) durationSeconds));
        columns.add(Long.toString(percentile(latencies, 50)));
        columns.add(Long.toString(percentile(latencies, 95)));
        columns.add(Long.toString(percentile(latencies, 99)));
        for (final TransactionOutcome outcome : TransactionOutcome.values()) {
            columns.add(Integer.toString(counts.getOrDefault(outcome, 0)));
        }
        columns.add(Integer.toString(failedPrecondition));
        columns.add(Integer.toString(writerCommitted));
        columns.add(Integer.toString(writerTotal));
        return String.join(",", columns);
    }

    /** Nearest-rank percentile of a sorted list; -1 when there is no committed transaction. */
    static long percentile(final List<Long> sorted, final int p) {
        if (sorted.isEmpty()) {
            return -1L;
        }
        final int rank = (int) Math.ceil(p / 100.0 * sorted.size());
        return sorted.get(Math.max(0, rank - 1));
    }

    private static String csv(final Row row) {
        final TransactionResult result = row.result();
        return String.join(
                ",",
                row.client(),
                Long.toString(row.submitMs()),
                Long.toString(row.finalMs()),
                result.outcome().name(),
                result.validationCode() == null ? "" : result.validationCode().name(),
                errorCode(result));
    }

    /**
     * The chaincode's error code for an endorsement refusal. The gateway hands back only the
     * message, so the code is recognised by the stale-guard message the chaincode writes.
     */
    static String errorCode(final TransactionResult result) {
        if (result.outcome() != TransactionOutcome.ENDORSEMENT_REJECTED) {
            return "";
        }
        final String message = String.valueOf(result.failure().getMessage());
        if (message.contains("guard registry '") && message.contains("' is at version ")) {
            return "FAILED_PRECONDITION";
        }
        System.err.println("bench: unexpected endorsement refusal: " + message);
        return "OTHER";
    }

    private static PrintWriter appender(final Path file, final String header) throws IOException {
        final boolean fresh = !Files.exists(file);
        final PrintWriter writer =
                new PrintWriter(
                        Files.newBufferedWriter(
                                file, StandardOpenOption.CREATE, StandardOpenOption.APPEND));
        if (fresh) {
            writer.println(header);
        }
        return writer;
    }

    private static List<Integer> ints(final String list) {
        return Arrays.stream(list.split(",")).map(String::trim).map(Integer::valueOf).toList();
    }

    /** System property, then environment variable ({@code bench.k} → {@code BENCH_K}). */
    private static String param(final String name, final String defaultValue) {
        final String fromProperty = System.getProperty(name);
        if (fromProperty != null && !fromProperty.isBlank()) {
            return fromProperty;
        }
        final String fromEnv = System.getenv(name.toUpperCase().replace('.', '_'));
        return fromEnv != null && !fromEnv.isBlank() ? fromEnv : defaultValue;
    }
}

package com.example.vdr.e2e.demo;

import com.example.vdr.e2e.support.CryptoUtils;
import com.example.vdr.e2e.support.FabricConnectionManager;
import com.example.vdr.e2e.support.ScenarioContext;
import com.example.vdr.e2e.support.TransactionExecutor;
import com.example.vdr.logic.AnonCredsObjectTypes;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.f4b6a3.uuid.UuidCreator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyPair;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Stage 3 harness: publishes one real {@code credx} credential definition and its revocation
 * lifecycle to the VDR, then records what the ledger gave back.
 *
 * <p>This is the Java half of a two-language demo. The Rust side ({@code anoncreds-vdr-demo})
 * writes {@code bundle.json} — genuine BBS/VB20 material in the {@code docs/anoncreds-method.md}
 * §8.1.6 wire shape — and this class turns it into transactions. Everything it reads back goes to
 * {@code vdr-report.json}, which the Rust side then feeds through the §8.1.5 reassembly recipe to
 * verify presentations against a ledger-sourced {@code IssuerPublic}.
 *
 * <p>It deliberately reuses only the framework-free transport layer ({@link
 * FabricConnectionManager}, {@link TransactionExecutor}, {@link CryptoUtils}) and not the Cucumber
 * entity handlers: those hard-code the fixture schema, an empty {@code publicMetadata} and
 * generator-multiple accumulators, which are exactly the four slots real credx material has to
 * fill.
 *
 * <p>Run it on its own. {@code -Dit.test} overrides the failsafe includes so {@code
 * RunCucumberIT}'s scenarios do not also run; the surefire pair silences the 155 unit tests. Do
 * <em>not</em> reach for {@code -DskipTests} here — failsafe binds its own {@code skipTests} to the
 * same {@code ${skipTests}} property, so it would skip this integration test too:
 *
 * <pre>{@code
 * cd anoncreds-vdr-demo && cargo run -- emit
 * cd fabric-vdr-core && mvn verify -Dit.test=AnonCredsDemoIT \
 *     -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false
 * cd .. && cargo run -- verify
 * }</pre>
 */
public class AnonCredsDemoIT {

    /**
     * Where the Rust side wrote its artifacts, relative to this module, which is checked out inside
     * anoncreds-vdr-demo.
     */
    private static final String DEFAULT_STAGE3_DIR = "../target/stage3";

    private static final String BUNDLE_FILE = "bundle.json";
    private static final String REPORT_FILE = "vdr-report.json";

    // Plain mapper on purpose: the ledger's own canonical serialization is what we are
    // observing, so nothing here should re-order or re-render the payloads it returns.
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void publishesTheCredxRevocationLifecycleToTheLedger() throws Exception {
        // A missing bundle is the ordinary state of a clean checkout: the Rust half of the demo
        // has not run, so there is nothing for this harness to publish. Skip, so a plain `mvn
        // verify` stays green. A bundle that is present but unusable is a real problem, and still
        // fails inside run().
        final Path bundlePath = bundlePath();
        Assumptions.assumeTrue(
                Files.isRegularFile(bundlePath) && Files.isReadable(bundlePath),
                "Skipping AnonCredsDemoIT: no bundle at "
                        + bundlePath.toAbsolutePath()
                        + ". Run the demo via ./run-anoncreds-demo.sh, or `cargo run -- emit` in"
                        + " anoncreds-vdr-demo, to generate it.");
        run();
    }

    /** CLI entry point, so the harness can also be driven outside failsafe. */
    public static void main(final String[] args) throws Exception {
        run();
    }

    /**
     * Drives the whole lifecycle: bootstrap the four AnonCreds objects, write the genesis
     * accumulator, then one update per revocation event, reading the entry back after every write.
     */
    static void run() throws Exception {
        final Path directory = stage3Directory();
        final Path bundlePath = bundlePath();
        require(
                Files.isRegularFile(bundlePath),
                "No bundle at "
                        + bundlePath.toAbsolutePath()
                        + " — run `cargo run -- emit` in anoncreds-vdr-demo first.");
        final JsonNode bundle = MAPPER.readTree(Files.readString(bundlePath));
        System.out.println(
                "[harness 1/6] read "
                        + bundlePath
                        + " (credx "
                        + text(bundle, "credxCommit")
                        + ")");

        final FabricConnectionManager connection = new FabricConnectionManager();
        final ScenarioContext context = new ScenarioContext();
        final TransactionExecutor executor = new TransactionExecutor(connection, context);
        connection.connect("anoncreds-v2-demo");
        try {
            publish(bundle, directory, executor, context);
        } finally {
            connection.close();
        }
    }

    private static void publish(
            final JsonNode bundle,
            final Path directory,
            final TransactionExecutor executor,
            final ScenarioContext context)
            throws Exception {

        // The bundle carries no DID key material: a P-256 DID key is unrelated to the credx
        // BLS material, so the issuer identity is minted fresh for every run.
        final String issuerDid = "did:vdr:" + uuid7();
        final KeyPair issuerKeys = CryptoUtils.generateP256KeyPair();
        executor.submit(
                "createDid", issuerDid, CryptoUtils.buildDidJson(issuerDid, issuerKeys, null));
        requireCommitted(context, "createDid");
        System.out.println("[harness 2/6] issuer DID published: " + issuerDid);

        final JsonNode schema = child(bundle, "schema");
        final String schemaId = mintObjectId(issuerDid, AnonCredsObjectTypes.SCHEMA);
        executor.submitSigned(
                "createSchema",
                issuerDid,
                issuerKeys,
                schemaId,
                text(schema, "name"),
                text(schema, "version"),
                child(schema, "attrNames").toString());
        requireCommitted(context, "createSchema");
        System.out.println("[harness 3/6] schema published: " + schemaId);

        final JsonNode credDef = child(bundle, "credDef");
        final String publicMetadataJson = child(credDef, "publicMetadata").toString();
        final String credDefId = mintObjectId(issuerDid, AnonCredsObjectTypes.PUBLIC_CRED_DEF);
        executor.submitSigned(
                "createCredDef",
                issuerDid,
                issuerKeys,
                credDefId,
                schemaId,
                text(credDef, "type"),
                text(credDef, "tag"),
                publicMetadataJson);
        requireCommitted(context, "createCredDef");
        System.out.println(
                "[harness 4/6] credential definition published: "
                        + credDefId
                        + " ("
                        + publicMetadataJson.length()
                        + " bytes of publicMetadata)");

        final JsonNode revRegDef = child(bundle, "revRegDef");
        final String publicKeysJson = child(revRegDef, "publicKeys").toString();
        final String revRegDefId = mintObjectId(issuerDid, AnonCredsObjectTypes.REV_REG_DEF);
        executor.submitSigned(
                "createRevRegDef",
                issuerDid,
                issuerKeys,
                revRegDefId,
                credDefId,
                text(revRegDef, "type"),
                publicKeysJson,
                String.valueOf(child(revRegDef, "maxCredNum").asLong()));
        requireCommitted(context, "createRevRegDef");
        System.out.println(
                "[harness 5/6] revocation registry definition published: " + revRegDefId);

        // Resolve the two immutable objects that carry crypto material back off the ledger.
        final JsonNode storedCredDef = MAPPER.readTree(executor.evaluate("getCredDef", credDefId));
        final JsonNode returnedMetadata = child(storedCredDef, "publicMetadata");
        final JsonNode storedRevRegDef =
                MAPPER.readTree(executor.evaluate("getRevRegDef", revRegDefId));
        final String returnedVerifyingKey =
                text(child(storedRevRegDef, "publicKeys"), "revocationVerifyingKey");

        require(
                returnedMetadata.equals(child(credDef, "publicMetadata")),
                "getCredDef returned a publicMetadata tree that differs from the published one");
        require(
                returnedVerifyingKey.equals(
                        text(child(revRegDef, "publicKeys"), "revocationVerifyingKey")),
                "getRevRegDef returned a different revocationVerifyingKey than was published");
        System.out.println("          publicMetadata semantically unchanged by the ledger: true");
        System.out.println(
                "          publicMetadata byte-identical to the submitted string: "
                        + returnedMetadata.toString().equals(publicMetadataJson));

        // The whole of the VDR's share of the revocation lifecycle: one create, then one
        // update per revocation event. Revoking and refreshing witnesses stay off ledger.
        final ArrayNode entries = MAPPER.createArrayNode();
        final JsonNode accumulators = child(bundle, "accumulators");
        require(
                accumulators.isArray() && !accumulators.isEmpty(),
                "bundle.accumulators must be a non-empty array");
        System.out.println(
                "[harness 6/6] writing " + accumulators.size() + " accumulator value(s)");
        for (int index = 0; index < accumulators.size(); index++) {
            final JsonNode step = accumulators.get(index);
            final String stepName = text(step, "step");
            final String accumValue = text(step, "accumValue");
            final String txName = index == 0 ? "createRevRegEntry" : "updateRevRegEntry";

            executor.submitSigned(txName, issuerDid, issuerKeys, revRegDefId, accumValue);
            requireCommitted(context, txName);

            final JsonNode entry =
                    MAPPER.readTree(executor.evaluate("getRevRegEntry", revRegDefId));
            final long version = child(entry, "version").asLong();
            final String storedValue = text(entry, "accumValue");
            require(
                    storedValue.equals(accumValue),
                    txName + " stored a different accumulator than was submitted");
            require(
                    version == index,
                    "expected entry version "
                            + index
                            + " after "
                            + txName
                            + " but read "
                            + version);

            final ObjectNode reported = entries.addObject();
            reported.put("step", stepName);
            reported.put("version", version);
            reported.put("accumValue", storedValue);
            System.out.println(
                    "          " + txName + " -> version " + version + " (" + stepName + ")");
        }

        final JsonNode history =
                MAPPER.readTree(executor.evaluate("getRevRegEntryHistory", revRegDefId));

        final ObjectNode report = MAPPER.createObjectNode();
        report.put("credxCommit", text(bundle, "credxCommit"));
        report.put("issuerDid", issuerDid);
        report.put("schemaId", schemaId);
        report.put("credDefId", credDefId);
        report.put("revRegDefId", revRegDefId);
        report.set("credDefPublicMetadata", returnedMetadata);
        report.put("revocationVerifyingKey", returnedVerifyingKey);
        report.set("revRegEntries", entries);
        report.set("revRegEntryHistory", history);

        final Path reportPath = directory.resolve(REPORT_FILE);
        Files.writeString(
                reportPath,
                MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n");
        System.out.println("          report written: " + reportPath.toAbsolutePath());
        System.out.println("          next: `cargo run -- verify` in anoncreds-vdr-demo");
    }

    /**
     * Fails loudly on a swallowed write.
     *
     * <p>{@link TransactionExecutor#submit} captures failures into the {@link ScenarioContext}
     * instead of throwing, so without this check the harness would happily continue past a
     * transaction that never committed and "pass" while having written nothing.
     */
    private static void requireCommitted(final ScenarioContext context, final String txName) {
        final Exception failure = context.lastTransactionException();
        if (failure != null) {
            throw new IllegalStateException(
                    txName + " did not commit: " + failure.getMessage(), failure);
        }
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static JsonNode child(final JsonNode parent, final String field) {
        final JsonNode value = parent.get(field);
        if (value == null || value.isNull()) {
            throw new IllegalStateException("Missing JSON field '" + field + "'");
        }
        return value;
    }

    private static String text(final JsonNode parent, final String field) {
        final JsonNode value = child(parent, field);
        if (!value.isTextual()) {
            throw new IllegalStateException("JSON field '" + field + "' is not a string");
        }
        return value.asText();
    }

    private static String uuid7() {
        return UuidCreator.getTimeOrderedEpoch().toString();
    }

    /** Same recipe as the Cucumber handlers: a DID URL rooted at the issuer's own DID. */
    private static String mintObjectId(final String issuerDid, final String objectType) {
        return issuerDid + "/anoncreds/v2/" + objectType + "/" + uuid7();
    }

    private static Path stage3Directory() {
        final String fromProperty = System.getProperty("vdr.demo.stage3Dir");
        if (fromProperty != null && !fromProperty.isBlank()) {
            return Paths.get(fromProperty);
        }
        final String fromEnv = System.getenv("VDR_DEMO_STAGE3_DIR");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return Paths.get(fromEnv);
        }
        return Paths.get(DEFAULT_STAGE3_DIR);
    }

    /**
     * Where the Rust side's bundle lives. Composed from {@link #stage3Directory()} so the skip
     * guard in the test and the check in {@link #run()} always look at the same file, whichever way
     * the stage 3 directory was overridden.
     */
    private static Path bundlePath() {
        return stage3Directory().resolve(BUNDLE_FILE);
    }
}

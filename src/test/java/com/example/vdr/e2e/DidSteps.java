package com.example.vdr.e2e;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.cucumber.java.After;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.grpc.ChannelCredentials;
import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.TlsChannelCredentials;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.hyperledger.fabric.client.Contract;
import org.hyperledger.fabric.client.EndorseException;
import org.hyperledger.fabric.client.Gateway;
import org.hyperledger.fabric.client.Network;
import org.hyperledger.fabric.client.identity.Identities;
import org.hyperledger.fabric.client.identity.Identity;
import org.hyperledger.fabric.client.identity.Signer;
import org.hyperledger.fabric.client.identity.Signers;
import org.hyperledger.fabric.client.identity.X509Identity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DidSteps {

    private static final Logger LOGGER = LoggerFactory.getLogger(DidSteps.class);

    private static final Path CRYPTO_PATH =
            Paths.get(
                    "/home/sinti/fablo-target/fabric-config/crypto-config/peerOrganizations/org1.example.com");
    private static final String MSP_ID = "Org1MSP";
    private static final String PEER_ENDPOINT = "localhost:7041";
    private static final String PEER_AUTHORITY = "peer0.org1.example.com";
    private static final String CHANNEL_NAME = "my-channel1";
    private static final String CHAINCODE_NAME = "vdrchaincode";
    private static final String CONTRACT_NAME = "VdrContract";

    private Gateway gateway;
    private Contract contract;
    private Exception transactionException;
    private String effectiveDid;
    private String queryResponse;
    private Exception queryException;

    // -------------------------------------------------------------------------
    // Given steps
    // -------------------------------------------------------------------------

    // TODO: Refactor credential resolution to use clientType when multiple client
    // types are
    // supported
    @Given("a connected Fabric Gateway as an {string} client")
    public void aConnectedFabricGateway(final String clientType) throws Exception {
        final Path certPath =
                requireFile(
                        CRYPTO_PATH.resolve(
                                "users/Admin@org1.example.com/msp/signcerts/Admin@org1.example.com-cert.pem"));
        final Identity identity =
                new X509Identity(
                        MSP_ID, Identities.readX509Certificate(Files.newBufferedReader(certPath)));

        final Path keyPath = CRYPTO_PATH.resolve("users/Admin@org1.example.com/msp/keystore/");
        final Signer signer =
                Signers.newPrivateKeySigner(
                        Identities.readPrivateKey(
                                Files.newBufferedReader(findPrivateKey(keyPath))));

        final Path peerTlsCaPath =
                requireFile(CRYPTO_PATH.resolve("peers/peer0.org1.example.com/tls/ca.crt"));
        final Path clientTlsCertPath =
                requireFile(CRYPTO_PATH.resolve("users/Admin@org1.example.com/tls/client.crt"));
        final Path clientTlsKeyPath =
                requireFile(CRYPTO_PATH.resolve("users/Admin@org1.example.com/tls/client.key"));

        final ChannelCredentials tlsCredentials =
                TlsChannelCredentials.newBuilder()
                        .trustManager(peerTlsCaPath.toFile())
                        .keyManager(clientTlsCertPath.toFile(), clientTlsKeyPath.toFile())
                        .build();

        final ManagedChannel channel =
                Grpc.newChannelBuilder(PEER_ENDPOINT, tlsCredentials)
                        .overrideAuthority(PEER_AUTHORITY)
                        .build();

        gateway =
                Gateway.newInstance()
                        .identity(identity)
                        .signer(signer)
                        .connection(channel)
                        .connect();

        final Network network = gateway.getNetwork(CHANNEL_NAME);
        contract = network.getContract(CHAINCODE_NAME, CONTRACT_NAME);

        LOGGER.info(
                "Connected to Fabric Gateway at {} for channel {} as client type '{}'.",
                PEER_ENDPOINT,
                CHANNEL_NAME,
                clientType);
    }

    @Given("the ledger contains the {string} for {string}")
    public void theLedgerContainsThe(final String entityType, final String id) {
        switch (entityType) {
            case "DID document":
                setupDid(id);
                break;
            default:
                throw new IllegalArgumentException(
                        "Unsupported entity type for ledger setup: " + entityType);
        }
    }

    // -------------------------------------------------------------------------
    // When steps
    // -------------------------------------------------------------------------

    @When("I submit a transaction to create a {string} with ID {string}")
    public void submitCreationTransaction(final String entityType, final String id) {
        switch (entityType) {
            case "DID document":
                submitCreateDid(id);
                break;
            default:
                throw new IllegalArgumentException(
                        "Unsupported entity type for creation: " + entityType);
        }
    }

    @When("I query the ledger for the {string} with ID {string}")
    public void iQueryTheLedgerFor(final String entityType, final String id) {
        final String resolvedId = resolveId(id);
        queryResponse = null;
        queryException = null;
        try {
            final byte[] result = contract.evaluateTransaction("getDid", resolvedId);
            queryResponse = new String(result, StandardCharsets.UTF_8);
            LOGGER.info(
                    "Ledger query successful for {} '{}'. Response: {}",
                    entityType,
                    resolvedId,
                    queryResponse);
        } catch (Exception e) {
            queryException = e;
            LOGGER.error(
                    "Ledger query failed for {} '{}'. Error: {}",
                    entityType,
                    resolvedId,
                    e.getMessage(),
                    e);
        }
    }

    @When("I submit a transaction to update the {string} with ID {string}")
    public void submitUpdateTransaction(final String entityType, final String id) {
        switch (entityType) {
            case "DID document":
                submitUpdateDid(id);
                break;
            default:
                throw new IllegalArgumentException(
                        "Unsupported entity type for update: " + entityType);
        }
    }

    @When("I submit a transaction to deactivate the {string} with ID {string}")
    public void submitDeactivationTransaction(final String entityType, final String id) {
        switch (entityType) {
            case "DID document":
                submitDeactivateDid(id);
                break;
            default:
                throw new IllegalArgumentException(
                        "Unsupported entity type for deactivation: " + entityType);
        }
    }

    // -------------------------------------------------------------------------
    // Then steps
    // -------------------------------------------------------------------------

    @Then("the transaction should be successful")
    public void theTransactionShouldBeSuccessful() {
        assertNull(
                transactionException,
                () -> "Transaction failed: " + transactionException.getMessage());
    }

    @Then("the transaction should fail with an {string} error")
    public void theTransactionShouldFailWithAnError(final String expectedMessage) {
        assertNotNull(
                transactionException,
                () ->
                        "Expected transaction to fail with message '"
                                + expectedMessage
                                + "', but it succeeded.");
        assertTrue(
                transactionException.getMessage().contains(expectedMessage),
                () ->
                        "Transaction failed with unexpected message: "
                                + transactionException.getMessage());
    }

    @Then("the ledger should contain the {string} for {string}")
    public void theLedgerShouldContainThe(final String entityType, final String id) {
        switch (entityType) {
            case "DID document":
                assertLedgerContainsDid(id);
                break;
            default:
                throw new IllegalArgumentException(
                        "Unsupported entity type for ledger assertion: " + entityType);
        }
    }

    @Then("the {string} for {string} should be marked as {string}")
    public void theEntityShouldBeMarkedAs(
            final String entityType, final String id, final String status) {
        switch (entityType) {
            case "DID document":
                assertDidStatus(id, status);
                break;
            default:
                throw new IllegalArgumentException(
                        "Unsupported entity type for status assertion: " + entityType);
        }
    }

    // -------------------------------------------------------------------------
    // DID-specific business logic
    // -------------------------------------------------------------------------

    private void setupDid(final String did) {
        effectiveDid = uniqueId(did);
        try {
            contract.submitTransaction("createDid", effectiveDid, buildDidJson(effectiveDid));
        } catch (Exception e) {
            fail(
                    "Setup failed: could not create prerequisite DID document. Error: "
                            + e.getMessage());
        }
    }

    private void submitCreateDid(final String did) {
        effectiveDid = uniqueId(did);
        transactionException = null;
        try {
            contract.submitTransaction("createDid", effectiveDid, buildDidJson(effectiveDid));
        } catch (EndorseException e) {
            transactionException = toEndorseFailure(e);
            LOGGER.error(
                    "DID creation endorsement failed. Details: {}",
                    transactionException.getMessage(),
                    e);
        } catch (Exception e) {
            transactionException = e;
            LOGGER.error("DID creation submission failed.", e);
        }
    }

    private void submitUpdateDid(final String did) {
        final String resolvedId = resolveId(did);
        transactionException = null;
        final String updatedJson = "{\"id\":\"" + resolvedId + "\", \"updated\":true}";
        try {
            contract.submitTransaction("updateDid", resolvedId, updatedJson);
            LOGGER.info("Update transaction submitted for DID {}.", resolvedId);
        } catch (EndorseException e) {
            transactionException = toEndorseFailure(e);
            LOGGER.error(
                    "DID update endorsement failed. Details: {}",
                    transactionException.getMessage(),
                    e);
        } catch (Exception e) {
            transactionException = e;
            LOGGER.error("DID update submission failed for '{}'.", resolvedId, e);
        }
    }

    private void submitDeactivateDid(final String did) {
        final String resolvedId = resolveId(did);
        transactionException = null;
        try {
            contract.submitTransaction("deactivateDid", resolvedId);
            LOGGER.info("Deactivation transaction submitted for DID {}.", resolvedId);
        } catch (EndorseException e) {
            transactionException = toEndorseFailure(e);
            LOGGER.error(
                    "DID deactivation endorsement failed. Details: {}",
                    transactionException.getMessage(),
                    e);
        } catch (Exception e) {
            transactionException = e;
            LOGGER.error("DID deactivation submission failed for '{}'.", resolvedId, e);
        }
    }

    private void assertLedgerContainsDid(final String did) {
        final String resolvedId = resolveId(did);
        try {
            final byte[] result = contract.evaluateTransaction("getDid", resolvedId);
            final String response = new String(result, StandardCharsets.UTF_8);
            assertTrue(
                    response.contains(resolvedId),
                    "Ledger response does not contain the expected DID: " + resolvedId);
            LOGGER.info("Ledger query succeeded for DID '{}'.", resolvedId);
        } catch (Exception e) {
            fail("Ledger query failed for DID '" + resolvedId + "': " + e.getMessage());
        }
    }

    private void assertDidStatus(final String did, final String expectedStatus) {
        final String resolvedId = resolveId(did);
        try {
            final byte[] result = contract.evaluateTransaction("getDid", resolvedId);
            final String response = new String(result, StandardCharsets.UTF_8);
            // Only the status value is uppercased to match chaincode convention;
            // the JSON key "status" is case-sensitive and must remain lowercase.
            final String expectedStatusField =
                    "\"status\":\"" + expectedStatus.toUpperCase() + "\"";
            assertTrue(
                    response.contains(expectedStatusField),
                    String.format(
                            "Expected DID '%s' to have status '%s', but response was: %s",
                            resolvedId, expectedStatus.toUpperCase(), response));
            LOGGER.info("DID '{}' is correctly marked as '{}'.", resolvedId, expectedStatus);
        } catch (Exception e) {
            fail("Failed to query DID status for '" + resolvedId + "': " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    /**
     * Returns {@code effectiveDid} when a previous step has already established it (e.g. a "Given"
     * setup step that appended a timestamp), otherwise falls back to the raw {@code id} from the
     * feature file.
     */
    private String resolveId(final String id) {
        return effectiveDid != null ? effectiveDid : id;
    }

    /**
     * Appends the current epoch millis to {@code base} to guarantee a collision-safe identifier
     * across parallel or repeated scenario runs.
     */
    private static String uniqueId(final String base) {
        return base + "-" + System.currentTimeMillis();
    }

    /** Builds a minimal, well-formed DID JSON document for test purposes. */
    private static String buildDidJson(final String did) {
        return "{\"id\":\"" + did + "\"}";
    }

    /**
     * Flattens the peer-level endorsement details from an {@link EndorseException} into a single,
     * human-readable {@link IllegalStateException}. The original exception is preserved as the
     * cause for full stack-trace fidelity.
     */
    private static IllegalStateException toEndorseFailure(final EndorseException e) {
        final String details =
                e.getDetails().stream()
                        .map(detail -> detail.getMessage())
                        .collect(Collectors.joining("; "));
        return new IllegalStateException(details, e);
    }

    private static Path findPrivateKey(final Path keyPath) throws IOException {
        try (Stream<Path> files = Files.list(keyPath)) {
            return files.filter(Files::isRegularFile)
                    .findFirst()
                    .orElseThrow(
                            () ->
                                    new IllegalStateException(
                                            "No private key file found in: " + keyPath));
        }
    }

    private static Path requireFile(final Path path) {
        if (!Files.exists(path)) {
            throw new IllegalStateException("Required Fabric crypto file is missing: " + path);
        }
        return path;
    }

    private void closeGateway() {
        if (gateway == null) {
            return;
        }
        try {
            gateway.close();
        } catch (Exception e) {
            LOGGER.warn("Gateway close operation reported an error.", e);
        } finally {
            gateway = null;
        }
    }

    @After
    public void tearDown() {
        LOGGER.info("Scenario finished, closing Gateway...");
        closeGateway();
    }
}

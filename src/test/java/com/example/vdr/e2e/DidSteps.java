package com.example.vdr.e2e;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

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

    @Given("a connected Fabric Gateway")
    public void aConnectedFabricGateway() throws Exception {
        final Path certPath =
                requireFile(
                        CRYPTO_PATH.resolve(
                                "users/Admin@org1.example.com/msp/signcerts/Admin@org1.example.com-cert.pem"));
        final Identity identity =
                new X509Identity(
                        MSP_ID, Identities.readX509Certificate(Files.newBufferedReader(certPath)));

        final Path keyPath = CRYPTO_PATH.resolve("users/Admin@org1.example.com/msp/keystore/");
        final Path privateKeyFile = findPrivateKey(keyPath);
        final Signer signer =
                Signers.newPrivateKeySigner(
                        Identities.readPrivateKey(Files.newBufferedReader(privateKeyFile)));

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
                "Connected to Fabric Gateway at {} for channel {}.", PEER_ENDPOINT, CHANNEL_NAME);
    }

    @When("I submit a transaction to create DID {string}")
    public void iSubmitATransactionToCreateDid(final String did) {
        effectiveDid = did + "-" + System.currentTimeMillis();
        final String didDocumentJson = "{\"id\":\"" + effectiveDid + "\"}";
        transactionException = null;
        try {
            contract.submitTransaction("createDid", effectiveDid, didDocumentJson);
        } catch (EndorseException e) {
            transactionException = e;
            final String details =
                    e.getDetails().stream()
                            .map(detail -> detail.getMessage())
                            .collect(Collectors.joining("; "));
            LOGGER.error("Transaction endorsement failed. Details: {}", details, e);
        } catch (Exception e) {
            transactionException = e;
            LOGGER.error("Transaction submission failed.", e);
        }
    }

    @Then("the transaction should be successful")
    public void theTransactionShouldBeSuccessful() {
        assertNull(
                transactionException,
                () ->
                        "Transaction failed: "
                                + (transactionException != null
                                        ? transactionException.getMessage()
                                        : "Unknown error"));
    }

    @Then("the ledger should contain the DID document for {string}")
    public void theLedgerShouldContainTheDidDocumentFor(final String did) {
        final String didToCheck = effectiveDid != null ? effectiveDid : did;
        try {
            final byte[] queryResult = contract.evaluateTransaction("getDid", didToCheck);
            final String response = new String(queryResult, StandardCharsets.UTF_8);

            assertTrue(
                    response.contains(didToCheck),
                    "Ledger response does not contain the expected DID.");
            LOGGER.info("Ledger query succeeded for DID {}.", didToCheck);
        } catch (Exception e) {
            fail("Ledger query failed: " + e.getMessage());
        } finally {
            closeGateway();
        }
    }

    private static Path findPrivateKey(final Path keyPath) throws IOException {
        try (Stream<Path> keyFiles = Files.list(keyPath)) {
            return keyFiles.filter(Files::isRegularFile)
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
}

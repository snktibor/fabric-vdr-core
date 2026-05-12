package com.example.vdr.e2e.support;

import com.example.vdr.e2e.config.E2EConfig;
import io.grpc.ChannelCredentials;
import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.TlsChannelCredentials;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.stream.Stream;
import org.hyperledger.fabric.client.Contract;
import org.hyperledger.fabric.client.Gateway;
import org.hyperledger.fabric.client.Network;
import org.hyperledger.fabric.client.identity.Identities;
import org.hyperledger.fabric.client.identity.Identity;
import org.hyperledger.fabric.client.identity.Signer;
import org.hyperledger.fabric.client.identity.Signers;
import org.hyperledger.fabric.client.identity.X509Identity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns the lifecycle of the Fabric Gateway and exposes the configured {@link Contract}. One
 * instance is created per Cucumber scenario via PicoContainer.
 */
public class FabricConnectionManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(FabricConnectionManager.class);

    private Gateway gateway;
    private Contract contract;

    /**
     * Establishes the gRPC channel and Gateway identity. The {@code clientType} parameter is kept
     * for future use when multiple identities are supported.
     */
    public void connect(final String clientType) throws IOException, GeneralSecurityException {
        final Path crypto = E2EConfig.CRYPTO_PATH;
        final Path certPath =
                requireFile(
                        crypto.resolve(
                                "users/Admin@org1.example.com/msp/signcerts/Admin@org1.example.com-cert.pem"));
        final Identity identity =
                new X509Identity(
                        E2EConfig.MSP_ID,
                        Identities.readX509Certificate(Files.newBufferedReader(certPath)));

        final Path keyDir = crypto.resolve("users/Admin@org1.example.com/msp/keystore/");
        final Signer signer =
                Signers.newPrivateKeySigner(
                        Identities.readPrivateKey(Files.newBufferedReader(findPrivateKey(keyDir))));

        final ChannelCredentials tlsCredentials =
                TlsChannelCredentials.newBuilder()
                        .trustManager(
                                requireFile(
                                                crypto.resolve(
                                                        "peers/peer0.org1.example.com/tls/ca.crt"))
                                        .toFile())
                        .keyManager(
                                requireFile(
                                                crypto.resolve(
                                                        "users/Admin@org1.example.com/tls/client.crt"))
                                        .toFile(),
                                requireFile(
                                                crypto.resolve(
                                                        "users/Admin@org1.example.com/tls/client.key"))
                                        .toFile())
                        .build();

        final ManagedChannel channel =
                Grpc.newChannelBuilder(E2EConfig.PEER_ENDPOINT, tlsCredentials)
                        .overrideAuthority(E2EConfig.PEER_AUTHORITY)
                        .build();

        gateway =
                Gateway.newInstance()
                        .identity(identity)
                        .signer(signer)
                        .connection(channel)
                        .connect();

        final Network network = gateway.getNetwork(E2EConfig.CHANNEL_NAME);
        contract = network.getContract(E2EConfig.CHAINCODE_NAME, E2EConfig.CONTRACT_NAME);

        LOGGER.info(
                "Connected to Fabric Gateway at {} on channel {} as '{}'.",
                E2EConfig.PEER_ENDPOINT,
                E2EConfig.CHANNEL_NAME,
                clientType);
    }

    public Contract contract() {
        if (contract == null) {
            throw new IllegalStateException(
                    "Fabric Gateway is not connected; ensure the Background step ran.");
        }
        return contract;
    }

    public void close() {
        if (gateway == null) {
            return;
        }
        try {
            gateway.close();
        } catch (Exception e) {
            LOGGER.warn("Gateway close reported an error.", e);
        } finally {
            gateway = null;
            contract = null;
        }
    }

    private static Path requireFile(final Path path) {
        if (!Files.exists(path)) {
            throw new IllegalStateException("Required Fabric crypto file is missing: " + path);
        }
        return path;
    }

    private static Path findPrivateKey(final Path keyDir) throws IOException {
        try (Stream<Path> files = Files.list(keyDir)) {
            return files.filter(Files::isRegularFile)
                    .findFirst()
                    .orElseThrow(
                            () ->
                                    new IllegalStateException(
                                            "No private key file found in: " + keyDir));
        }
    }
}

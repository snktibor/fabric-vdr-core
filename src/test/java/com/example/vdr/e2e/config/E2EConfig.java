package com.example.vdr.e2e.config;

import java.nio.file.Path;
import java.nio.file.Paths;

/** Immutable end-to-end test configuration. Centralises every environment-specific constant. */
public final class E2EConfig {

    public static final Path CRYPTO_PATH =
            Paths.get(
                    "../fablo-target/fabric-config/crypto-config/peerOrganizations/org1.example.com");
    public static final String MSP_ID = "Org1MSP";
    public static final String PEER_ENDPOINT = "localhost:7041";
    public static final String PEER_AUTHORITY = "peer0.org1.example.com";
    public static final String CHANNEL_NAME = "my-channel1";
    public static final String CHAINCODE_NAME = "vdrchaincode";
    public static final String CONTRACT_NAME = "VdrContract";
    public static final int JWT_VALIDITY_SECONDS = 300;

    private E2EConfig() {}
}

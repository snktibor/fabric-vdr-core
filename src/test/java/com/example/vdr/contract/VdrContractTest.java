package com.example.vdr.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.vdr.TestVectors;
import com.example.vdr.context.SsiContext;
import com.example.vdr.context.SsiServices;
import com.example.vdr.exception.VdrException;
import com.example.vdr.ledger.KeyRevision;
import com.example.vdr.ledger.RegistryEntry;
import com.example.vdr.logic.CredentialDefinitionService;
import com.example.vdr.logic.DidService;
import com.example.vdr.logic.JwtPrincipal;
import com.example.vdr.logic.JwtValidator;
import com.example.vdr.logic.RevRegDefService;
import com.example.vdr.logic.RevRegEntryService;
import com.example.vdr.logic.SchemaService;
import com.example.vdr.logic.VdrTypes;
import com.example.vdr.model.CredentialDefinitionRecord;
import com.example.vdr.model.DidDocument;
import com.example.vdr.model.RecordStatus;
import com.example.vdr.model.RevRegDefRecord;
import com.example.vdr.model.RevRegEntryRecord;
import com.example.vdr.model.SchemaRecord;
import com.example.vdr.util.JsonUtil;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.hyperledger.fabric.shim.ChaincodeException;
import org.hyperledger.fabric.shim.ChaincodeStub;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link VdrContract} API-level orchestration behavior.
 *
 * <p>Verifies contract methods delegate correctly to service layer components, return canonical
 * JSON payloads, and map domain exceptions to chaincode exceptions where required.
 */
@SuppressWarnings("java:S1192")
@ExtendWith(MockitoExtension.class)
class VdrContractTest {

    private static final Instant TX_TIME = Instant.ofEpochMilli(123L);

    private static final String ISSUER_DID = "did:vdr:018f6c02-3a5e-7cbb-9d3e-5f2a4b6c8d0e";

    private static final String SCHEMA_ID =
            ISSUER_DID + "/anoncreds/v2/SCHEMA/018f6c05-1b22-7aa0-8c31-9e77d0f6a4b1";

    private static final String CRED_DEF_ID =
            ISSUER_DID + "/anoncreds/v2/PUBLIC_CRED_DEF/018f6c06-4d13-7b58-b0e2-3a91c4d7e2f0";

    private static final String REV_REG_DEF_ID =
            ISSUER_DID + "/anoncreds/v2/REV_REG_DEF/018f6c07-9e04-7ff1-a3c6-71b28e05d9a4";

    @Mock private SsiContext ctx;

    @Mock private SsiServices services;

    @Mock private DidService didService;

    @Mock private SchemaService schemaService;

    @Mock private CredentialDefinitionService credentialDefinitionService;

    @Mock private RevRegDefService revRegDefService;

    @Mock private RevRegEntryService revRegEntryService;

    @Mock private JwtValidator jwtValidator;

    @Mock private ChaincodeStub stub;

    /**
     * Wires the JWT validation path for one protected invocation: the stub reports the submitted
     * wire arguments (payload plus trailing jwt) and the validator expects the matching method name
     * and jwt-less scope arguments.
     */
    private void stubSignerDid(
            final String jwt,
            final String signerDid,
            final String method,
            final String... payloadArgs) {
        final List<String> wireParameters = new ArrayList<>(Arrays.asList(payloadArgs));
        wireParameters.add(jwt);
        when(ctx.getStub()).thenReturn(stub);
        when(stub.getParameters()).thenReturn(wireParameters);
        when(ctx.getServices()).thenReturn(services);
        when(ctx.getTxTimestamp()).thenReturn(TX_TIME);
        when(services.getJwtValidator()).thenReturn(jwtValidator);
        when(jwtValidator.validate(jwt, TX_TIME, method, Arrays.asList(payloadArgs)))
                .thenReturn(new JwtPrincipal(signerDid, signerDid + "#key-1"));
    }

    /** Verifies contract context factory returns SSI-specific context type. */
    @Test
    void createContextReturnsSsiContext() {
        final VdrContract contract = new VdrContract();

        assertNotNull(contract.createContext(stub));
    }

    /** Verifies DID creation endpoint returns canonical JSON response. */
    @Test
    void createDidReturnsCanonicalJson() {
        final VdrContract contract = new VdrContract();
        final String didJson = "{\"id\":\"did:example:123\",\"controller\":\"did:example:123\"}";
        final DidDocument document = new DidDocument();
        document.setId("did:example:123");
        document.setOwnerId("did:example:123");
        document.setStatus(RecordStatus.ACTIVE);

        when(ctx.getServices()).thenReturn(services);
        when(services.getDidService()).thenReturn(didService);
        when(didService.createDid("did:example:123", didJson)).thenReturn(document);

        final String result = contract.createDid(ctx, "did:example:123", didJson);

        assertEquals(JsonUtil.toCanonicalJson(document), result);
        verify(didService).createDid("did:example:123", didJson);
    }

    /** Verifies DID lookup endpoint returns canonical JSON response. */
    @Test
    void getDidReturnsCanonicalJson() {
        final VdrContract contract = new VdrContract();
        final DidDocument document = new DidDocument();
        document.setId("did:example:456");
        document.setOwnerId("caller");
        document.setStatus(RecordStatus.ACTIVE);

        when(ctx.getServices()).thenReturn(services);
        when(services.getDidService()).thenReturn(didService);
        when(didService.getDid("did:example:456")).thenReturn(document);

        final String result = contract.getDid(ctx, "did:example:456");

        assertEquals(JsonUtil.toCanonicalJson(document), result);
    }

    /** Verifies DID deactivation endpoint returns canonical JSON response. */
    @Test
    void deactivateDidReturnsCanonicalJson() {
        final VdrContract contract = new VdrContract();
        final String jwt = "jwt";
        final DidDocument document = new DidDocument();
        document.setId("did:example:789");
        document.setOwnerId("caller");
        document.setStatus(RecordStatus.INACTIVE);

        stubSignerDid(jwt, "caller", "deactivateDid", "did:example:789");
        when(services.getDidService()).thenReturn(didService);
        when(didService.deactivateDid("caller", "did:example:789")).thenReturn(document);

        final String result = contract.deactivateDid(ctx, "did:example:789", jwt);

        assertEquals(JsonUtil.toCanonicalJson(document), result);
    }

    /** Verifies DID update endpoint returns canonical JSON response. */
    @Test
    void updateDidReturnsCanonicalJson() {
        final VdrContract contract = new VdrContract();
        final String jwt = "jwt";
        final String didJson =
                "{\"id\":\"did:example:999\",\"controller\":\"did:example:controller\"}";
        final DidDocument document = new DidDocument();
        document.setId("did:example:999");
        document.setOwnerId("caller");
        document.setStatus(RecordStatus.ACTIVE);

        stubSignerDid(jwt, "caller", "updateDid", "did:example:999", didJson);
        when(services.getDidService()).thenReturn(didService);
        when(didService.updateDid("caller", "did:example:999", didJson)).thenReturn(document);

        final String result = contract.updateDid(ctx, "did:example:999", didJson, jwt);

        assertEquals(JsonUtil.toCanonicalJson(document), result);
        verify(didService).updateDid("caller", "did:example:999", didJson);
    }

    /** Verifies DID listing endpoint serializes registry entries as array envelope. */
    @Test
    void listDidsReturnsEntryArray() {
        final VdrContract contract = new VdrContract();
        final DidDocument document = new DidDocument();
        document.setId("did:example:123");
        document.setOwnerId("caller");
        document.setStatus(RecordStatus.ACTIVE);

        when(ctx.getServices()).thenReturn(services);
        when(services.getDidService()).thenReturn(didService);
        when(didService.listDids())
                .thenReturn(List.of(new RegistryEntry<>("did:example:123", document)));

        final String result = contract.listDids(ctx);

        final ArrayNode expected = JsonUtil.arrayNode();
        final ObjectNode item = JsonUtil.objectNode();
        item.put("id", "did:example:123");
        item.set("value", JsonUtil.readTree(JsonUtil.toCanonicalJson(document)));
        expected.add(item);
        assertEquals(JsonUtil.toCanonicalJson(expected), result);
    }

    /** Verifies schema creation endpoint returns canonical JSON response. */
    @Test
    void createSchemaReturnsCanonicalJson() {
        final VdrContract contract = new VdrContract();
        final String jwt = "jwt";
        final SchemaRecord schema = new SchemaRecord();
        schema.setId(SCHEMA_ID);
        schema.setIssuerId(ISSUER_DID);
        schema.setName("Degree");
        schema.setVersion("1.0");
        schema.setAttrNames(List.of("name", "degree"));
        schema.setOwnerId("caller");

        stubSignerDid(
                jwt,
                "caller",
                "createSchema",
                SCHEMA_ID,
                ISSUER_DID,
                "Degree",
                "1.0",
                "[\"name\",\"degree\"]");
        when(services.getSchemaService()).thenReturn(schemaService);
        when(schemaService.createSchema(
                        "caller", SCHEMA_ID, ISSUER_DID, "Degree", "1.0", "[\"name\",\"degree\"]"))
                .thenReturn(schema);

        final String result =
                contract.createSchema(
                        ctx, SCHEMA_ID, ISSUER_DID, "Degree", "1.0", "[\"name\",\"degree\"]", jwt);

        assertEquals(JsonUtil.toCanonicalJson(schema), result);
    }

    /** Verifies schema lookup endpoint returns canonical JSON response. */
    @Test
    void getSchemaReturnsCanonicalJson() {
        final VdrContract contract = new VdrContract();
        final SchemaRecord schema = new SchemaRecord();
        schema.setId(SCHEMA_ID);
        schema.setIssuerId(ISSUER_DID);
        schema.setName("Profile");
        schema.setVersion("1.0");
        schema.setAttrNames(List.of("name"));

        when(ctx.getServices()).thenReturn(services);
        when(services.getSchemaService()).thenReturn(schemaService);
        when(schemaService.getSchema(SCHEMA_ID)).thenReturn(schema);

        final String result = contract.getSchema(ctx, SCHEMA_ID);

        assertEquals(JsonUtil.toCanonicalJson(schema), result);
    }

    /** Verifies schema listing endpoint serializes registry entries as array envelope. */
    @Test
    void listSchemasReturnsEntryArray() {
        final VdrContract contract = new VdrContract();
        final SchemaRecord schema = new SchemaRecord();
        schema.setId(SCHEMA_ID);
        schema.setIssuerId(ISSUER_DID);
        schema.setName("Identity");
        schema.setVersion("1.0");
        schema.setAttrNames(List.of("name"));

        when(ctx.getServices()).thenReturn(services);
        when(services.getSchemaService()).thenReturn(schemaService);
        when(schemaService.listSchemas())
                .thenReturn(List.of(new RegistryEntry<>(SCHEMA_ID, schema)));

        final String result = contract.listSchemas(ctx);

        final ArrayNode expected = JsonUtil.arrayNode();
        final ObjectNode item = JsonUtil.objectNode();
        item.put("id", SCHEMA_ID);
        item.set("value", JsonUtil.readTree(JsonUtil.toCanonicalJson(schema)));
        expected.add(item);
        assertEquals(JsonUtil.toCanonicalJson(expected), result);
    }

    /** Verifies service-layer domain errors are mapped to chaincode exceptions. */
    @Test
    void getSchemaWrapsVdrException() {
        final VdrContract contract = new VdrContract();

        when(ctx.getServices()).thenReturn(services);
        when(services.getSchemaService()).thenReturn(schemaService);
        when(schemaService.getSchema("schema-missing"))
                .thenThrow(VdrException.notFound("schema", "schema-missing"));

        assertThrows(ChaincodeException.class, () -> contract.getSchema(ctx, "schema-missing"));
    }

    /** Verifies credential definition creation returns canonical JSON response. */
    @Test
    void createCredDefReturnsCanonicalJson() {
        final VdrContract contract = new VdrContract();
        final String jwt = "jwt";
        final ObjectNode metadata = JsonUtil.objectNode();
        metadata.put("type", "BBS");
        final CredentialDefinitionRecord crecord = new CredentialDefinitionRecord();
        crecord.setId(CRED_DEF_ID);
        crecord.setIssuerId(ISSUER_DID);
        crecord.setSchemaId(SCHEMA_ID);
        crecord.setType(VdrTypes.CRED_DEF_BBS_V1);
        crecord.setTag("TAG");
        crecord.setPublicMetadata(metadata);
        crecord.setOwnerId("caller");

        stubSignerDid(
                jwt,
                "caller",
                "createCredDef",
                CRED_DEF_ID,
                ISSUER_DID,
                SCHEMA_ID,
                VdrTypes.CRED_DEF_BBS_V1,
                "TAG",
                "{\"type\":\"BBS\"}");
        when(services.getCredentialDefinitionService()).thenReturn(credentialDefinitionService);
        when(credentialDefinitionService.createCredDef(
                        "caller",
                        CRED_DEF_ID,
                        ISSUER_DID,
                        SCHEMA_ID,
                        VdrTypes.CRED_DEF_BBS_V1,
                        "TAG",
                        "{\"type\":\"BBS\"}"))
                .thenReturn(crecord);

        final String result =
                contract.createCredDef(
                        ctx,
                        CRED_DEF_ID,
                        ISSUER_DID,
                        SCHEMA_ID,
                        VdrTypes.CRED_DEF_BBS_V1,
                        "TAG",
                        "{\"type\":\"BBS\"}",
                        jwt);

        assertEquals(JsonUtil.toCanonicalJson(crecord), result);
    }

    /** Verifies credential definition lookup returns canonical JSON response. */
    @Test
    void getCredDefReturnsCanonicalJson() {
        final VdrContract contract = new VdrContract();
        final CredentialDefinitionRecord crecord = new CredentialDefinitionRecord();
        crecord.setId(CRED_DEF_ID);
        crecord.setIssuerId(ISSUER_DID);
        crecord.setSchemaId(SCHEMA_ID);
        crecord.setType(VdrTypes.CRED_DEF_BBS_V1);
        crecord.setTag("TAG");
        crecord.setPublicMetadata(JsonUtil.objectNode());

        when(ctx.getServices()).thenReturn(services);
        when(services.getCredentialDefinitionService()).thenReturn(credentialDefinitionService);
        when(credentialDefinitionService.getCredDef(CRED_DEF_ID)).thenReturn(crecord);

        final String result = contract.getCredDef(ctx, CRED_DEF_ID);

        assertEquals(JsonUtil.toCanonicalJson(crecord), result);
    }

    /** Verifies credential definition listing serializes registry entries correctly. */
    @Test
    void listCredDefsReturnsEntryArray() {
        final VdrContract contract = new VdrContract();
        final CredentialDefinitionRecord crecord = new CredentialDefinitionRecord();
        crecord.setId(CRED_DEF_ID);
        crecord.setIssuerId(ISSUER_DID);
        crecord.setSchemaId(SCHEMA_ID);
        crecord.setType(VdrTypes.CRED_DEF_BBS_V1);
        crecord.setTag("TAG");
        crecord.setPublicMetadata(JsonUtil.objectNode());

        when(ctx.getServices()).thenReturn(services);
        when(services.getCredentialDefinitionService()).thenReturn(credentialDefinitionService);
        when(credentialDefinitionService.listCredDefs())
                .thenReturn(List.of(new RegistryEntry<>(CRED_DEF_ID, crecord)));

        final String result = contract.listCredDefs(ctx);

        final ArrayNode expected = JsonUtil.arrayNode();
        final ObjectNode item = JsonUtil.objectNode();
        item.put("id", CRED_DEF_ID);
        item.set("value", JsonUtil.readTree(JsonUtil.toCanonicalJson(crecord)));
        expected.add(item);
        assertEquals(JsonUtil.toCanonicalJson(expected), result);
    }

    /** Verifies revocation registry definition creation returns canonical JSON response. */
    @Test
    void createRevRegDefReturnsCanonicalJson() {
        final VdrContract contract = new VdrContract();
        final String jwt = "jwt";
        final ObjectNode publicKeys = JsonUtil.objectNode();
        publicKeys.put("revocationVerifyingKey", TestVectors.G2_GENERATOR);
        final RevRegDefRecord rrecord = new RevRegDefRecord();
        rrecord.setId(REV_REG_DEF_ID);
        rrecord.setIssuerId(ISSUER_DID);
        rrecord.setCredDefId(CRED_DEF_ID);
        rrecord.setType(VdrTypes.REV_REG_VB20_V1);
        rrecord.setPublicKeys(publicKeys);
        rrecord.setMaxCredNum(100L);
        rrecord.setOwnerId("caller");

        stubSignerDid(
                jwt,
                "caller",
                "createRevRegDef",
                REV_REG_DEF_ID,
                ISSUER_DID,
                CRED_DEF_ID,
                VdrTypes.REV_REG_VB20_V1,
                TestVectors.REVOCATION_PUBLIC_KEYS_JSON,
                "100");
        when(services.getRevRegDefService()).thenReturn(revRegDefService);
        when(revRegDefService.createRevRegDef(
                        "caller",
                        REV_REG_DEF_ID,
                        ISSUER_DID,
                        CRED_DEF_ID,
                        VdrTypes.REV_REG_VB20_V1,
                        TestVectors.REVOCATION_PUBLIC_KEYS_JSON,
                        100L))
                .thenReturn(rrecord);

        final String result =
                contract.createRevRegDef(
                        ctx,
                        REV_REG_DEF_ID,
                        ISSUER_DID,
                        CRED_DEF_ID,
                        VdrTypes.REV_REG_VB20_V1,
                        TestVectors.REVOCATION_PUBLIC_KEYS_JSON,
                        100L,
                        jwt);

        assertEquals(JsonUtil.toCanonicalJson(rrecord), result);
    }

    /** Verifies revocation registry definition lookup returns canonical JSON response. */
    @Test
    void getRevRegDefReturnsCanonicalJson() {
        final VdrContract contract = new VdrContract();
        final RevRegDefRecord rrecord = new RevRegDefRecord();
        rrecord.setId(REV_REG_DEF_ID);
        rrecord.setIssuerId(ISSUER_DID);
        rrecord.setCredDefId(CRED_DEF_ID);
        rrecord.setType(VdrTypes.REV_REG_VB20_V1);
        rrecord.setPublicKeys(JsonUtil.objectNode());
        rrecord.setMaxCredNum(100L);

        when(ctx.getServices()).thenReturn(services);
        when(services.getRevRegDefService()).thenReturn(revRegDefService);
        when(revRegDefService.getRevRegDef(REV_REG_DEF_ID)).thenReturn(rrecord);

        final String result = contract.getRevRegDef(ctx, REV_REG_DEF_ID);

        assertEquals(JsonUtil.toCanonicalJson(rrecord), result);
    }

    /** Verifies revocation registry definition listing serializes registry entries correctly. */
    @Test
    void listRevRegDefsReturnsEntryArray() {
        final VdrContract contract = new VdrContract();
        final RevRegDefRecord rrecord = new RevRegDefRecord();
        rrecord.setId(REV_REG_DEF_ID);
        rrecord.setIssuerId(ISSUER_DID);
        rrecord.setCredDefId(CRED_DEF_ID);
        rrecord.setType(VdrTypes.REV_REG_VB20_V1);
        rrecord.setPublicKeys(JsonUtil.objectNode());
        rrecord.setMaxCredNum(100L);

        when(ctx.getServices()).thenReturn(services);
        when(services.getRevRegDefService()).thenReturn(revRegDefService);
        when(revRegDefService.listRevRegDefs())
                .thenReturn(List.of(new RegistryEntry<>(REV_REG_DEF_ID, rrecord)));

        final String result = contract.listRevRegDefs(ctx);

        final ArrayNode expected = JsonUtil.arrayNode();
        final ObjectNode item = JsonUtil.objectNode();
        item.put("id", REV_REG_DEF_ID);
        item.set("value", JsonUtil.readTree(JsonUtil.toCanonicalJson(rrecord)));
        expected.add(item);
        assertEquals(JsonUtil.toCanonicalJson(expected), result);
    }

    /** Verifies revocation registry entry creation returns canonical JSON response. */
    @Test
    void createRevRegEntryReturnsCanonicalJson() {
        final VdrContract contract = new VdrContract();
        final String jwt = "jwt";
        final RevRegEntryRecord erecord = new RevRegEntryRecord();
        erecord.setRevRegDefId(REV_REG_DEF_ID);
        erecord.setAccumValue(TestVectors.G1_GENERATOR);
        erecord.setVersion(0L);

        stubSignerDid(jwt, "caller", "createRevRegEntry", REV_REG_DEF_ID, TestVectors.G1_GENERATOR);
        when(services.getRevRegEntryService()).thenReturn(revRegEntryService);
        when(revRegEntryService.createRevRegEntry(
                        "caller", REV_REG_DEF_ID, TestVectors.G1_GENERATOR))
                .thenReturn(erecord);

        final String result =
                contract.createRevRegEntry(ctx, REV_REG_DEF_ID, TestVectors.G1_GENERATOR, jwt);

        assertEquals(JsonUtil.toCanonicalJson(erecord), result);
    }

    /** Verifies revocation registry entry update returns canonical JSON response. */
    @Test
    void updateRevRegEntryReturnsCanonicalJson() {
        final VdrContract contract = new VdrContract();
        final String jwt = "jwt";
        final RevRegEntryRecord erecord = new RevRegEntryRecord();
        erecord.setRevRegDefId(REV_REG_DEF_ID);
        erecord.setAccumValue(TestVectors.G1_TIMES_2);
        erecord.setVersion(1L);

        stubSignerDid(jwt, "caller", "updateRevRegEntry", REV_REG_DEF_ID, TestVectors.G1_TIMES_2);
        when(services.getRevRegEntryService()).thenReturn(revRegEntryService);
        when(revRegEntryService.updateRevRegEntry("caller", REV_REG_DEF_ID, TestVectors.G1_TIMES_2))
                .thenReturn(erecord);

        final String result =
                contract.updateRevRegEntry(ctx, REV_REG_DEF_ID, TestVectors.G1_TIMES_2, jwt);

        assertEquals(JsonUtil.toCanonicalJson(erecord), result);
        verify(revRegEntryService)
                .updateRevRegEntry("caller", REV_REG_DEF_ID, TestVectors.G1_TIMES_2);
    }

    /** Verifies entry history serializes revisions oldest-first in one uniform revision shape. */
    @Test
    void getRevRegEntryHistoryReturnsRevisionArray() {
        final VdrContract contract = new VdrContract();
        final RevRegEntryRecord genesis = new RevRegEntryRecord();
        genesis.setAccumValue(TestVectors.G1_GENERATOR);
        genesis.setVersion(0L);
        final RevRegEntryRecord updated = new RevRegEntryRecord();
        updated.setAccumValue(TestVectors.G1_TIMES_2);
        updated.setVersion(1L);
        final Instant firstTime = Instant.parse("2026-07-12T10:00:00Z");
        final Instant secondTime = Instant.parse("2026-07-12T11:00:00Z");

        when(ctx.getServices()).thenReturn(services);
        when(services.getRevRegEntryService()).thenReturn(revRegEntryService);
        when(revRegEntryService.getRevRegEntryHistory(REV_REG_DEF_ID))
                .thenReturn(
                        List.of(
                                new KeyRevision<>("tx-1", firstTime, false, genesis),
                                new KeyRevision<>("tx-2", secondTime, false, updated),
                                new KeyRevision<>("tx-3", secondTime, true, null)));

        final String result = contract.getRevRegEntryHistory(ctx, REV_REG_DEF_ID);

        final ArrayNode expected = JsonUtil.arrayNode();
        final ObjectNode first = JsonUtil.objectNode();
        first.put("txId", "tx-1");
        first.put("timestamp", firstTime.toString());
        first.put("deleted", false);
        first.put("accumValue", TestVectors.G1_GENERATOR);
        first.put("version", 0L);
        expected.add(first);
        final ObjectNode second = JsonUtil.objectNode();
        second.put("txId", "tx-2");
        second.put("timestamp", secondTime.toString());
        second.put("deleted", false);
        second.put("accumValue", TestVectors.G1_TIMES_2);
        second.put("version", 1L);
        expected.add(second);
        // A tombstone keeps the same field set: deleted flips, the record fields go JSON null.
        final ObjectNode third = JsonUtil.objectNode();
        third.put("txId", "tx-3");
        third.put("timestamp", secondTime.toString());
        third.put("deleted", true);
        third.putNull("accumValue");
        third.putNull("version");
        expected.add(third);
        assertEquals(JsonUtil.toCanonicalJson(expected), result);
    }

    /** Verifies the jwt-is-final-argument convention is enforced before validation. */
    @Test
    void protectedTransactionRejectsJwtNotInFinalPosition() {
        final VdrContract contract = new VdrContract();

        when(ctx.getServices()).thenReturn(services);
        when(services.getDidService()).thenReturn(didService);
        when(ctx.getStub()).thenReturn(stub);
        // The submitted wire arguments do not end with the jwt parameter value.
        when(stub.getParameters()).thenReturn(List.of("did:example:789", "unexpected-extra"));

        final ChaincodeException exception =
                assertThrows(
                        ChaincodeException.class,
                        () -> contract.deactivateDid(ctx, "did:example:789", "jwt"));
        assertEquals(
                "authorization failed: jwt must be the final transaction argument",
                exception.getMessage());
    }

    /** Verifies revocation registry entry lookup returns canonical JSON response. */
    @Test
    void getRevRegEntryReturnsCanonicalJson() {
        final VdrContract contract = new VdrContract();
        final RevRegEntryRecord erecord = new RevRegEntryRecord();
        erecord.setRevRegDefId(REV_REG_DEF_ID);
        erecord.setAccumValue(TestVectors.G1_GENERATOR);
        erecord.setVersion(0L);

        when(ctx.getServices()).thenReturn(services);
        when(services.getRevRegEntryService()).thenReturn(revRegEntryService);
        when(revRegEntryService.getRevRegEntry(REV_REG_DEF_ID)).thenReturn(erecord);

        final String result = contract.getRevRegEntry(ctx, REV_REG_DEF_ID);

        assertEquals(JsonUtil.toCanonicalJson(erecord), result);
    }
}

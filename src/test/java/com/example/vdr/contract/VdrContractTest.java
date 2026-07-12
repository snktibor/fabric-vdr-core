package com.example.vdr.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.vdr.context.SsiContext;
import com.example.vdr.context.SsiServices;
import com.example.vdr.exception.VdrException;
import com.example.vdr.ledger.RegistryEntry;
import com.example.vdr.logic.CredentialDefinitionService;
import com.example.vdr.logic.DidService;
import com.example.vdr.logic.JwtPrincipal;
import com.example.vdr.logic.JwtValidator;
import com.example.vdr.logic.RevRegDefService;
import com.example.vdr.logic.RevRegEntryService;
import com.example.vdr.logic.SchemaService;
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

    @Mock private SsiContext ctx;

    @Mock private SsiServices services;

    @Mock private DidService didService;

    @Mock private SchemaService schemaService;

    @Mock private CredentialDefinitionService credentialDefinitionService;

    @Mock private RevRegDefService revRegDefService;

    @Mock private RevRegEntryService revRegEntryService;

    @Mock private JwtValidator jwtValidator;

    @Mock private ChaincodeStub stub;

    private void stubSignerDid(final String jwt, final String signerDid) {
        when(ctx.getServices()).thenReturn(services);
        when(ctx.getTxTimestamp()).thenReturn(TX_TIME);
        when(services.getJwtValidator()).thenReturn(jwtValidator);
        when(jwtValidator.validate(jwt, TX_TIME))
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

        stubSignerDid(jwt, "caller");
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

        stubSignerDid(jwt, "caller");
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
        schema.setId("schema-1");
        schema.setIssuerId("did:issuer");
        schema.setName("Degree");
        schema.setVersion("1.0");
        schema.setAttrNames(List.of("name", "degree"));
        schema.setOwnerId("caller");

        stubSignerDid(jwt, "caller");
        when(services.getSchemaService()).thenReturn(schemaService);
        when(schemaService.createSchema(
                        "caller",
                        "schema-1",
                        "did:issuer",
                        "Degree",
                        "1.0",
                        "[\"name\",\"degree\"]"))
                .thenReturn(schema);

        final String result =
                contract.createSchema(
                        ctx,
                        "schema-1",
                        "did:issuer",
                        "Degree",
                        "1.0",
                        "[\"name\",\"degree\"]",
                        jwt);

        assertEquals(JsonUtil.toCanonicalJson(schema), result);
    }

    /** Verifies schema lookup endpoint returns canonical JSON response. */
    @Test
    void getSchemaReturnsCanonicalJson() {
        final VdrContract contract = new VdrContract();
        final SchemaRecord schema = new SchemaRecord();
        schema.setId("schema-2");
        schema.setIssuerId("did:issuer");
        schema.setName("Profile");
        schema.setVersion("1.0");
        schema.setAttrNames(List.of("name"));

        when(ctx.getServices()).thenReturn(services);
        when(services.getSchemaService()).thenReturn(schemaService);
        when(schemaService.getSchema("schema-2")).thenReturn(schema);

        final String result = contract.getSchema(ctx, "schema-2");

        assertEquals(JsonUtil.toCanonicalJson(schema), result);
    }

    /** Verifies schema listing endpoint serializes registry entries as array envelope. */
    @Test
    void listSchemasReturnsEntryArray() {
        final VdrContract contract = new VdrContract();
        final SchemaRecord schema = new SchemaRecord();
        schema.setId("schema-3");
        schema.setIssuerId("did:issuer");
        schema.setName("Identity");
        schema.setVersion("1.0");
        schema.setAttrNames(List.of("name"));

        when(ctx.getServices()).thenReturn(services);
        when(services.getSchemaService()).thenReturn(schemaService);
        when(schemaService.listSchemas())
                .thenReturn(List.of(new RegistryEntry<>("schema-3", schema)));

        final String result = contract.listSchemas(ctx);

        final ArrayNode expected = JsonUtil.arrayNode();
        final ObjectNode item = JsonUtil.objectNode();
        item.put("id", "schema-3");
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
        crecord.setId("creddef-1");
        crecord.setIssuerId("did:issuer");
        crecord.setSchemaId("schema-1");
        crecord.setType("BBS");
        crecord.setTag("TAG");
        crecord.setPublicMetadata(metadata);
        crecord.setOwnerId("caller");

        stubSignerDid(jwt, "caller");
        when(services.getCredentialDefinitionService()).thenReturn(credentialDefinitionService);
        when(credentialDefinitionService.createCredDef(
                        "caller",
                        "creddef-1",
                        "did:issuer",
                        "schema-1",
                        "BBS",
                        "TAG",
                        "{\"type\":\"BBS\"}"))
                .thenReturn(crecord);

        final String result =
                contract.createCredDef(
                        ctx,
                        "creddef-1",
                        "did:issuer",
                        "schema-1",
                        "BBS",
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
        crecord.setId("creddef-2");
        crecord.setIssuerId("did:issuer");
        crecord.setSchemaId("schema-1");
        crecord.setType("BBS");
        crecord.setTag("TAG");
        crecord.setPublicMetadata(JsonUtil.objectNode());

        when(ctx.getServices()).thenReturn(services);
        when(services.getCredentialDefinitionService()).thenReturn(credentialDefinitionService);
        when(credentialDefinitionService.getCredDef("creddef-2")).thenReturn(crecord);

        final String result = contract.getCredDef(ctx, "creddef-2");

        assertEquals(JsonUtil.toCanonicalJson(crecord), result);
    }

    /** Verifies credential definition listing serializes registry entries correctly. */
    @Test
    void listCredDefsReturnsEntryArray() {
        final VdrContract contract = new VdrContract();
        final CredentialDefinitionRecord crecord = new CredentialDefinitionRecord();
        crecord.setId("creddef-3");
        crecord.setIssuerId("did:issuer");
        crecord.setSchemaId("schema-1");
        crecord.setType("BBS");
        crecord.setTag("TAG");
        crecord.setPublicMetadata(JsonUtil.objectNode());

        when(ctx.getServices()).thenReturn(services);
        when(services.getCredentialDefinitionService()).thenReturn(credentialDefinitionService);
        when(credentialDefinitionService.listCredDefs())
                .thenReturn(List.of(new RegistryEntry<>("creddef-3", crecord)));

        final String result = contract.listCredDefs(ctx);

        final ArrayNode expected = JsonUtil.arrayNode();
        final ObjectNode item = JsonUtil.objectNode();
        item.put("id", "creddef-3");
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
        publicKeys.put("revocationVerifyingKey", "key-1");
        final RevRegDefRecord rrecord = new RevRegDefRecord();
        rrecord.setId("revregdef-1");
        rrecord.setIssuerId("did:issuer");
        rrecord.setCredDefId("creddef-1");
        rrecord.setType("ALLOSAUR");
        rrecord.setPublicKeys(publicKeys);
        rrecord.setMaxCredNum(100L);
        rrecord.setOwnerId("caller");

        stubSignerDid(jwt, "caller");
        when(services.getRevRegDefService()).thenReturn(revRegDefService);
        when(revRegDefService.createRevRegDef(
                        "caller",
                        "revregdef-1",
                        "did:issuer",
                        "creddef-1",
                        "ALLOSAUR",
                        "{\"revocationVerifyingKey\":\"key-1\"}",
                        100L))
                .thenReturn(rrecord);

        final String result =
                contract.createRevRegDef(
                        ctx,
                        "revregdef-1",
                        "did:issuer",
                        "creddef-1",
                        "ALLOSAUR",
                        "{\"revocationVerifyingKey\":\"key-1\"}",
                        100L,
                        jwt);

        assertEquals(JsonUtil.toCanonicalJson(rrecord), result);
    }

    /** Verifies revocation registry definition lookup returns canonical JSON response. */
    @Test
    void getRevRegDefReturnsCanonicalJson() {
        final VdrContract contract = new VdrContract();
        final RevRegDefRecord rrecord = new RevRegDefRecord();
        rrecord.setId("revregdef-2");
        rrecord.setIssuerId("did:issuer");
        rrecord.setCredDefId("creddef-1");
        rrecord.setType("ALLOSAUR");
        rrecord.setPublicKeys(JsonUtil.objectNode());
        rrecord.setMaxCredNum(100L);

        when(ctx.getServices()).thenReturn(services);
        when(services.getRevRegDefService()).thenReturn(revRegDefService);
        when(revRegDefService.getRevRegDef("revregdef-2")).thenReturn(rrecord);

        final String result = contract.getRevRegDef(ctx, "revregdef-2");

        assertEquals(JsonUtil.toCanonicalJson(rrecord), result);
    }

    /** Verifies revocation registry definition listing serializes registry entries correctly. */
    @Test
    void listRevRegDefsReturnsEntryArray() {
        final VdrContract contract = new VdrContract();
        final RevRegDefRecord rrecord = new RevRegDefRecord();
        rrecord.setId("revregdef-3");
        rrecord.setIssuerId("did:issuer");
        rrecord.setCredDefId("creddef-1");
        rrecord.setType("ALLOSAUR");
        rrecord.setPublicKeys(JsonUtil.objectNode());
        rrecord.setMaxCredNum(100L);

        when(ctx.getServices()).thenReturn(services);
        when(services.getRevRegDefService()).thenReturn(revRegDefService);
        when(revRegDefService.listRevRegDefs())
                .thenReturn(List.of(new RegistryEntry<>("revregdef-3", rrecord)));

        final String result = contract.listRevRegDefs(ctx);

        final ArrayNode expected = JsonUtil.arrayNode();
        final ObjectNode item = JsonUtil.objectNode();
        item.put("id", "revregdef-3");
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
        erecord.setId("revregentry-1");
        erecord.setRevRegDefId("revregdef-1");
        erecord.setAccumValue("accum-genesis");
        erecord.setVersion(0L);
        erecord.setOwnerId("caller");

        stubSignerDid(jwt, "caller");
        when(services.getRevRegEntryService()).thenReturn(revRegEntryService);
        when(revRegEntryService.createRevRegEntry(
                        "caller", "revregentry-1", "revregdef-1", "accum-genesis"))
                .thenReturn(erecord);

        final String result =
                contract.createRevRegEntry(
                        ctx, "revregentry-1", "revregdef-1", "accum-genesis", jwt);

        assertEquals(JsonUtil.toCanonicalJson(erecord), result);
    }

    /** Verifies revocation registry entry update returns canonical JSON response. */
    @Test
    void updateRevRegEntryReturnsCanonicalJson() {
        final VdrContract contract = new VdrContract();
        final String jwt = "jwt";
        final RevRegEntryRecord erecord = new RevRegEntryRecord();
        erecord.setId("revregentry-1");
        erecord.setRevRegDefId("revregdef-1");
        erecord.setAccumValue("accum-next");
        erecord.setVersion(1L);
        erecord.setOwnerId("caller");

        stubSignerDid(jwt, "caller");
        when(services.getRevRegEntryService()).thenReturn(revRegEntryService);
        when(revRegEntryService.updateRevRegEntry("caller", "revregdef-1", "accum-next"))
                .thenReturn(erecord);

        final String result = contract.updateRevRegEntry(ctx, "revregdef-1", "accum-next", jwt);

        assertEquals(JsonUtil.toCanonicalJson(erecord), result);
        verify(revRegEntryService).updateRevRegEntry("caller", "revregdef-1", "accum-next");
    }

    /** Verifies revocation registry entry lookup returns canonical JSON response. */
    @Test
    void getRevRegEntryReturnsCanonicalJson() {
        final VdrContract contract = new VdrContract();
        final RevRegEntryRecord erecord = new RevRegEntryRecord();
        erecord.setId("revregentry-2");
        erecord.setRevRegDefId("revregdef-1");
        erecord.setAccumValue("accum-genesis");
        erecord.setVersion(0L);

        when(ctx.getServices()).thenReturn(services);
        when(services.getRevRegEntryService()).thenReturn(revRegEntryService);
        when(revRegEntryService.getRevRegEntry("revregdef-1")).thenReturn(erecord);

        final String result = contract.getRevRegEntry(ctx, "revregdef-1");

        assertEquals(JsonUtil.toCanonicalJson(erecord), result);
    }
}

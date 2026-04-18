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
import com.example.vdr.logic.CredentialService;
import com.example.vdr.logic.DidService;
import com.example.vdr.logic.SchemaService;
import com.example.vdr.model.CredentialDefinitionRecord;
import com.example.vdr.model.CredentialRecord;
import com.example.vdr.model.DidDocument;
import com.example.vdr.model.RecordStatus;
import com.example.vdr.model.SchemaRecord;
import com.example.vdr.util.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;
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

    @Mock private SsiContext ctx;

    @Mock private SsiServices services;

    @Mock private DidService didService;

    @Mock private SchemaService schemaService;

    @Mock private CredentialDefinitionService credentialDefinitionService;

    @Mock private CredentialService credentialService;

    @Mock private ChaincodeStub stub;

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
        document.setOwnerId("caller");
        document.setStatus(RecordStatus.ACTIVE);

        when(ctx.getServices()).thenReturn(services);
        when(ctx.getCallerId()).thenReturn("caller");
        when(services.getDidService()).thenReturn(didService);
        when(didService.createDid("caller", "did:example:123", didJson)).thenReturn(document);

        final String result = contract.createDid(ctx, "did:example:123", didJson);

        assertEquals(JsonUtil.toCanonicalJson(document), result);
        verify(didService).createDid("caller", "did:example:123", didJson);
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
        final DidDocument document = new DidDocument();
        document.setId("did:example:789");
        document.setOwnerId("caller");
        document.setStatus(RecordStatus.INACTIVE);

        when(ctx.getServices()).thenReturn(services);
        when(ctx.getCallerId()).thenReturn("caller");
        when(services.getDidService()).thenReturn(didService);
        when(didService.deactivateDid("caller", "did:example:789")).thenReturn(document);

        final String result = contract.deactivateDid(ctx, "did:example:789");

        assertEquals(JsonUtil.toCanonicalJson(document), result);
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
        final SchemaRecord schema = new SchemaRecord();
        schema.setId("schema-1");
        schema.setIssuerId("did:issuer");
        schema.setName("Degree");
        schema.setVersion("1.0");
        schema.setAttrNames(List.of("name", "degree"));
        schema.setOwnerId("caller");

        when(ctx.getServices()).thenReturn(services);
        when(ctx.getCallerId()).thenReturn("caller");
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
                        ctx, "schema-1", "did:issuer", "Degree", "1.0", "[\"name\",\"degree\"]");

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

    /** Verifies schema deactivation succeeds for correct owner and returns JSON. */
    @Test
    void deactivateSchemaCorrectOwnerSucceeds() {
        final VdrContract contract = new VdrContract();
        final SchemaRecord schema = new SchemaRecord();
        schema.setId("schema-4");
        schema.setOwnerId("user1");
        schema.setStatus(RecordStatus.INACTIVE);

        when(ctx.getServices()).thenReturn(services);
        when(ctx.getCallerId()).thenReturn("user1");
        when(services.getSchemaService()).thenReturn(schemaService);
        when(schemaService.deactivateSchema("user1", "schema-4")).thenReturn(schema);

        final String result = contract.deactivateSchema(ctx, "schema-4");

        assertEquals(JsonUtil.toCanonicalJson(schema), result);
    }

    /** Verifies schema deactivation maps unauthorized service errors to chaincode errors. */
    @Test
    void deactivateSchemaWrongOwnerUnauthorized() {
        final VdrContract contract = new VdrContract();

        when(ctx.getServices()).thenReturn(services);
        when(ctx.getCallerId()).thenReturn("user2");
        when(services.getSchemaService()).thenReturn(schemaService);
        when(schemaService.deactivateSchema("user2", "schema-4"))
                .thenThrow(VdrException.unauthorized("Unauthorized"));

        assertThrows(ChaincodeException.class, () -> contract.deactivateSchema(ctx, "schema-4"));
    }

    /** Verifies credential definition creation returns canonical JSON response. */
    @Test
    void createCredDefReturnsCanonicalJson() {
        final VdrContract contract = new VdrContract();
        final ObjectNode metadata = JsonUtil.objectNode();
        metadata.put("type", "CL");
        final CredentialDefinitionRecord crecord = new CredentialDefinitionRecord();
        crecord.setId("creddef-1");
        crecord.setIssuerId("did:issuer");
        crecord.setSchemaId("schema-1");
        crecord.setType("CL");
        crecord.setTag("TAG");
        crecord.setPublicMetadata(metadata);
        crecord.setOwnerId("caller");

        when(ctx.getServices()).thenReturn(services);
        when(ctx.getCallerId()).thenReturn("caller");
        when(services.getCredentialDefinitionService()).thenReturn(credentialDefinitionService);
        when(credentialDefinitionService.createCredDef(
                        "caller",
                        "creddef-1",
                        "did:issuer",
                        "schema-1",
                        "CL",
                        "TAG",
                        "{\"type\":\"CL\"}"))
                .thenReturn(crecord);

        final String result =
                contract.createCredDef(
                        ctx,
                        "creddef-1",
                        "did:issuer",
                        "schema-1",
                        "CL",
                        "TAG",
                        "{\"type\":\"CL\"}");

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
        crecord.setType("CL");
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
        crecord.setType("CL");
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

    /** Verifies credential definition deactivation succeeds for correct owner. */
    @Test
    void deactivateCredDefCorrectOwnerSucceeds() {
        final VdrContract contract = new VdrContract();
        final CredentialDefinitionRecord crecord = new CredentialDefinitionRecord();
        crecord.setId("creddef-4");
        crecord.setOwnerId("user1");
        crecord.setStatus(RecordStatus.INACTIVE);

        when(ctx.getServices()).thenReturn(services);
        when(ctx.getCallerId()).thenReturn("user1");
        when(services.getCredentialDefinitionService()).thenReturn(credentialDefinitionService);
        when(credentialDefinitionService.deactivateCredDef("user1", "creddef-4"))
                .thenReturn(crecord);

        final String result = contract.deactivateCredDef(ctx, "creddef-4");

        assertEquals(JsonUtil.toCanonicalJson(crecord), result);
    }

    /** Verifies credential definition deactivation maps unauthorized errors. */
    @Test
    void deactivateCredDefWrongOwnerUnauthorized() {
        final VdrContract contract = new VdrContract();

        when(ctx.getServices()).thenReturn(services);
        when(ctx.getCallerId()).thenReturn("user2");
        when(services.getCredentialDefinitionService()).thenReturn(credentialDefinitionService);
        when(credentialDefinitionService.deactivateCredDef("user2", "creddef-4"))
                .thenThrow(VdrException.unauthorized("Unauthorized"));

        assertThrows(ChaincodeException.class, () -> contract.deactivateCredDef(ctx, "creddef-4"));
    }

    /** Verifies credential listing serializes registry entries as JSON array envelope. */
    @Test
    void listCredentialsReturnsEntryArray() {
        final VdrContract contract = new VdrContract();
        final CredentialRecord crecord = new CredentialRecord();
        crecord.setId("cred-1");
        crecord.setCredentialHash("hash");

        when(ctx.getServices()).thenReturn(services);
        when(services.getCredentialService()).thenReturn(credentialService);
        when(credentialService.listCredentials())
                .thenReturn(List.of(new RegistryEntry<>("cred-1", crecord)));

        final String result = contract.listCredentials(ctx);

        final ArrayNode expected = JsonUtil.arrayNode();
        final ObjectNode item = JsonUtil.objectNode();
        item.put("id", "cred-1");
        item.set("value", JsonUtil.readTree(JsonUtil.toCanonicalJson(crecord)));
        expected.add(item);
        assertEquals(JsonUtil.toCanonicalJson(expected), result);
    }

    /** Verifies credential issuance endpoint returns expected response envelope fields. */
    @Test
    void issueCredentialReturnsEnvelope() {
        final VdrContract contract = new VdrContract();
        final CredentialRecord crecord = new CredentialRecord();
        crecord.setId("cred-1");
        crecord.setCredentialHash("hash");
        crecord.setIssuanceDate(123L);
        crecord.setOwnerId("caller");
        crecord.setStatus(RecordStatus.ACTIVE);

        when(ctx.getServices()).thenReturn(services);
        when(ctx.getCallerId()).thenReturn("caller");
        when(ctx.getTxTimestamp()).thenReturn(Instant.ofEpochMilli(123L));
        when(services.getCredentialService()).thenReturn(credentialService);
        when(credentialService.issueCredential(
                        "caller",
                        Instant.ofEpochMilli(123L),
                        "cred-1",
                        "creddef-1",
                        "did:subject",
                        "{}"))
                .thenReturn(crecord);

        final String result =
                contract.issueCredential(ctx, "cred-1", "creddef-1", "did:subject", "{}");
        final JsonNode json = JsonUtil.readTree(result);

        assertEquals("cred-1", json.get("credentialId").asText());
        assertEquals("hash", json.get("credentialHash").asText());
        assertEquals(123L, json.get("issuanceDate").asLong());
    }

    /** Verifies issuance failure for inactive schema is mapped to chaincode error. */
    @Test
    void issueCredentialInactiveSchemaFails() {
        final VdrContract contract = new VdrContract();

        when(ctx.getServices()).thenReturn(services);
        when(ctx.getCallerId()).thenReturn("caller");
        when(ctx.getTxTimestamp()).thenReturn(Instant.ofEpochMilli(123L));
        when(services.getCredentialService()).thenReturn(credentialService);
        when(credentialService.issueCredential(
                        "caller",
                        Instant.ofEpochMilli(123L),
                        "cred-2",
                        "creddef-1",
                        "did:subject",
                        "{}"))
                .thenThrow(VdrException.invalid("schema 'schema-1' is not active"));

        assertThrows(
                ChaincodeException.class,
                () -> contract.issueCredential(ctx, "cred-2", "creddef-1", "did:subject", "{}"));
    }

    /** Verifies issuance failure for inactive credential definition maps correctly. */
    @Test
    void issueCredentialInactiveCredDefFails() {
        final VdrContract contract = new VdrContract();

        when(ctx.getServices()).thenReturn(services);
        when(ctx.getCallerId()).thenReturn("caller");
        when(ctx.getTxTimestamp()).thenReturn(Instant.ofEpochMilli(123L));
        when(services.getCredentialService()).thenReturn(credentialService);
        when(credentialService.issueCredential(
                        "caller",
                        Instant.ofEpochMilli(123L),
                        "cred-3",
                        "creddef-2",
                        "did:subject",
                        "{}"))
                .thenThrow(VdrException.invalid("creddef 'creddef-2' is not active"));

        assertThrows(
                ChaincodeException.class,
                () -> contract.issueCredential(ctx, "cred-3", "creddef-2", "did:subject", "{}"));
    }

    /** Verifies credential verification endpoint returns expected response envelope. */
    @Test
    void verifyCredentialReturnsEnvelope() {
        final VdrContract contract = new VdrContract();
        final CredentialRecord crecord = new CredentialRecord();
        crecord.setId("cred-2");
        crecord.setCredentialHash("hash");
        crecord.setIssuanceDate(456L);
        crecord.setSubjectDid("did:subject");
        crecord.setStatus(RecordStatus.ACTIVE);

        when(ctx.getServices()).thenReturn(services);
        when(services.getCredentialService()).thenReturn(credentialService);
        when(credentialService.verifyCredential("cred-2", "{}")).thenReturn(crecord);

        final String result = contract.verifyCredential(ctx, "cred-2", "{}");
        final JsonNode json = JsonUtil.readTree(result);

        assertEquals("cred-2", json.get("credentialId").asText());
        assertEquals(true, json.get("verified").asBoolean());
        assertEquals(456L, json.get("issuanceDate").asLong());
        assertEquals("did:subject", json.get("subjectDid").asText());
    }

    /** Verifies verification errors from service layer are mapped to chaincode errors. */
    @Test
    void verifyCredentialWrapsVdrException() {
        final VdrContract contract = new VdrContract();

        when(ctx.getServices()).thenReturn(services);
        when(services.getCredentialService()).thenReturn(credentialService);
        when(credentialService.verifyCredential("cred-1", "{}"))
                .thenThrow(VdrException.invalid("bad credential"));

        assertThrows(
                ChaincodeException.class, () -> contract.verifyCredential(ctx, "cred-1", "{}"));
    }

    /** Verifies credential revocation endpoint returns canonical JSON response. */
    @Test
    void revokeCredentialReturnsCanonicalJson() {
        final VdrContract contract = new VdrContract();
        final CredentialRecord crecord = new CredentialRecord();
        crecord.setId("cred-3");
        crecord.setStatus(RecordStatus.REVOKED);

        when(ctx.getServices()).thenReturn(services);
        when(ctx.getCallerId()).thenReturn("caller");
        when(services.getCredentialService()).thenReturn(credentialService);
        when(credentialService.revokeCredential("caller", "cred-3")).thenReturn(crecord);

        final String result = contract.revokeCredential(ctx, "cred-3");

        assertEquals(JsonUtil.toCanonicalJson(crecord), result);
    }

    /** Verifies credential lookup endpoint returns canonical JSON response. */
    @Test
    void getCredentialReturnsCanonicalJson() {
        final VdrContract contract = new VdrContract();
        final CredentialRecord crecord = new CredentialRecord();
        crecord.setId("cred-4");
        crecord.setCredentialHash("hash");

        when(ctx.getServices()).thenReturn(services);
        when(services.getCredentialService()).thenReturn(credentialService);
        when(credentialService.getCredential("cred-4")).thenReturn(crecord);

        final String result = contract.getCredential(ctx, "cred-4");

        assertEquals(JsonUtil.toCanonicalJson(crecord), result);
    }
}

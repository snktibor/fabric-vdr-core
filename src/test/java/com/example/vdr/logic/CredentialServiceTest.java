package com.example.vdr.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.vdr.exception.VdrException;
import com.example.vdr.ledger.CredentialDefinitionRegistry;
import com.example.vdr.ledger.CredentialRegistry;
import com.example.vdr.ledger.DidRegistry;
import com.example.vdr.ledger.SchemaRegistry;
import com.example.vdr.model.CredentialDefinitionRecord;
import com.example.vdr.model.CredentialRecord;
import com.example.vdr.model.DidDocument;
import com.example.vdr.model.RecordStatus;
import com.example.vdr.model.SchemaRecord;
import com.example.vdr.util.JsonUtil;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link CredentialService} issuance, verification, and revoke flows.
 *
 * <p>Exercises hash generation, dependency validation, ownership checks, and revoked-state
 * enforcement.
 */
@SuppressWarnings("java:S1192")
@ExtendWith(MockitoExtension.class)
class CredentialServiceTest {

    @Mock private CredentialRegistry credentialRegistry;

    @Mock private CredentialDefinitionRegistry credDefRegistry;

    @Mock private SchemaRegistry schemaRegistry;

    @Mock private DidRegistry didRegistry;

    private CredentialService credentialService;

    /** Initializes service under test with mocked registries and real validators. */
    @BeforeEach
    void setUp() {
        credentialService =
                new CredentialService(
                        credentialRegistry,
                        credDefRegistry,
                        schemaRegistry,
                        didRegistry,
                        new AuthorizationService(),
                        new ValidationService());
    }

    /** Verifies issuance persists canonical hash metadata and ownership fields. */
    @Test
    void issueCredentialPersistsRecord() {
        when(credentialRegistry.exists("cred-1")).thenReturn(false);

        final CredentialDefinitionRecord credDef = new CredentialDefinitionRecord();
        credDef.setId("creddef-1");
        credDef.setSchemaId("schema-1");
        credDef.setStatus(RecordStatus.ACTIVE);
        credDef.setOwnerId("did:issuer");
        when(credDefRegistry.get("creddef-1")).thenReturn(credDef);

        final SchemaRecord schemaRecord = new SchemaRecord();
        schemaRecord.setAttrNames(List.of("name", "degree"));
        schemaRecord.setStatus(RecordStatus.ACTIVE);
        when(schemaRegistry.get("schema-1")).thenReturn(schemaRecord);

        final DidDocument subject = new DidDocument();
        subject.setId("did:subject");
        subject.setStatus(RecordStatus.ACTIVE);
        when(didRegistry.get("did:subject")).thenReturn(subject);

        final String credentialJson = "{\"name\":\"Alice\",\"degree\":\"CS\"}";
        final Instant issuedAt = Instant.ofEpochMilli(123456789L);

        credentialService.issueCredential(
                "did:issuer", issuedAt, "cred-1", "creddef-1", "did:subject", credentialJson);

        final ArgumentCaptor<CredentialRecord> captor =
                ArgumentCaptor.forClass(CredentialRecord.class);
        verify(credentialRegistry).put(anyString(), captor.capture());

        final CredentialRecord stored = captor.getValue();
        assertEquals("cred-1", stored.getId());
        assertEquals("creddef-1", stored.getCredDefId());
        assertEquals("did:subject", stored.getSubjectDid());
        assertEquals(RecordStatus.ACTIVE, stored.getStatus());
        assertEquals("did:issuer", stored.getOwnerId());
        assertEquals(
                JsonUtil.sha256(JsonUtil.toCanonicalJson(JsonUtil.readTree(credentialJson))),
                stored.getCredentialHash());
    }

    /** Verifies verification rejects provided credentials with mismatched hashes. */
    @Test
    void verifyCredentialRejectsHashMismatch() {
        final CredentialRecord stored = new CredentialRecord();
        stored.setId("cred-1");
        stored.setCredDefId("creddef-1");
        stored.setCredentialHash("abc");
        stored.setStatus(RecordStatus.ACTIVE);
        when(credentialRegistry.get("cred-1")).thenReturn(stored);

        final String provided = "{\"name\":\"Bob\"}";
        assertThrows(
                VdrException.class, () -> credentialService.verifyCredential("cred-1", provided));
    }

    /** Verifies revoke operation enforces ownership checks. */
    @Test
    void revokeCredentialRequiresOwner() {
        final CredentialRecord stored = new CredentialRecord();
        stored.setId("cred-1");
        stored.setOwnerId("did:owner");
        stored.setStatus(RecordStatus.ACTIVE);
        when(credentialRegistry.get("cred-1")).thenReturn(stored);

        assertThrows(
                VdrException.class,
                () -> credentialService.revokeCredential("did:other", "cred-1"));
    }

    /** Verifies issuance rejects signer DID not owning credential definition. */
    @Test
    void issueCredentialRejectsSignerNotCredDefOwner() {
        when(credentialRegistry.exists("cred-1")).thenReturn(false);

        final CredentialDefinitionRecord credDef = new CredentialDefinitionRecord();
        credDef.setId("creddef-1");
        credDef.setSchemaId("schema-1");
        credDef.setStatus(RecordStatus.ACTIVE);
        credDef.setOwnerId("did:issuer");
        when(credDefRegistry.get("creddef-1")).thenReturn(credDef);

        assertThrows(
                VdrException.class,
                () ->
                        credentialService.issueCredential(
                                "did:other",
                                Instant.ofEpochMilli(10L),
                                "cred-1",
                                "creddef-1",
                                "did:subject",
                                "{\"name\":\"Alice\"}"));
    }

    /** Verifies verification fails for revoked credential records. */
    @Test
    void verifyCredentialRejectsRevoked() {
        final CredentialRecord stored = new CredentialRecord();
        stored.setId("cred-1");
        stored.setCredDefId("creddef-1");
        stored.setCredentialHash("hash");
        stored.setStatus(RecordStatus.REVOKED);
        when(credentialRegistry.get("cred-1")).thenReturn(stored);

        assertThrows(VdrException.class, () -> credentialService.verifyCredential("cred-1", "{}"));
    }

    /** Verifies verification succeeds when hash and schema constraints match. */
    @Test
    void verifyCredentialReturnsRecordWhenValid() {
        final String provided = "{\"name\":\"Alice\"}";
        final String canonical = JsonUtil.toCanonicalJson(JsonUtil.readTree(provided));
        final String hash = JsonUtil.sha256(canonical);

        final CredentialRecord stored = new CredentialRecord();
        stored.setId("cred-1");
        stored.setCredDefId("creddef-1");
        stored.setCredentialHash(hash);
        stored.setStatus(RecordStatus.ACTIVE);
        when(credentialRegistry.get("cred-1")).thenReturn(stored);

        final CredentialDefinitionRecord credDef = new CredentialDefinitionRecord();
        credDef.setId("creddef-1");
        credDef.setSchemaId("schema-1");
        when(credDefRegistry.get("creddef-1")).thenReturn(credDef);

        final SchemaRecord schemaRecord = new SchemaRecord();
        schemaRecord.setAttrNames(List.of("name"));
        when(schemaRegistry.get("schema-1")).thenReturn(schemaRecord);

        final CredentialRecord result = credentialService.verifyCredential("cred-1", provided);

        assertEquals("cred-1", result.getId());
    }
}

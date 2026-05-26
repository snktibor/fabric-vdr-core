Feature: Credential Lifecycle Management
    As a Credential Issuer
    I want to issue, verify, and revoke verifiable credentials anchored to the ledger
    So that holders can prove ownership of trust-rooted claims and relying parties can detect tampering or revocation

    Background:
        Given a connected Fabric Gateway as an "authorized" client

    Scenario: 1. Issue a new credential against an existing credential definition
        When I create a "credential" with id "cred:vdr:test-1"
        Then the transaction should be successful
        And a "credential" with id "cred:vdr:test-1" should exist on the ledger

    Scenario: 2. Query an issued credential by ID
        Given a "credential" with id "cred:vdr:test-2" exists on the ledger
        When I query the "credential" with id "cred:vdr:test-2"
        Then the transaction should be successful
        And a "credential" with id "cred:vdr:test-2" should exist on the ledger

    Scenario: 3. List credentials returns previously issued entries
        Given a "credential" with id "cred:vdr:test-3" exists on the ledger
        When I list all "credential" entries
        Then the transaction should be successful
        And the list should contain the "credential" with id "cred:vdr:test-3"

    Scenario: 4. Verify a valid, unmodified credential
        Given a "credential" with id "cred:vdr:test-4" exists on the ledger
        When I verify the "credential" with id "cred:vdr:test-4"
        Then the transaction should be successful

    Scenario: 5. Verifying a credential with a tampered payload fails the hash check
        Given a "credential" with id "cred:vdr:test-5" exists on the ledger
        When I verify the "credential" with id "cred:vdr:test-5" with a tampered payload
        Then the transaction should fail with an "hash mismatch" error

    Scenario: 6. Revoke an issued credential using the Issuer's JWT
        Given a "credential" with id "cred:vdr:test-6" exists on the ledger
        When I revoke the "credential" with id "cred:vdr:test-6"
        Then the transaction should be successful
        And the "credential" with id "cred:vdr:test-6" is marked as "REVOKED"

    Scenario: 7. Verifying a revoked credential should fail
        Given a "credential" with id "cred:vdr:test-7" exists on the ledger
        When I revoke the "credential" with id "cred:vdr:test-7"
        Then the transaction should be successful
        When I verify the "credential" with id "cred:vdr:test-7"
        Then the transaction should fail with an "revoked" error

    Scenario: 8. Reject revocation with an invalid JWT
        Given a "credential" with id "cred:vdr:test-8" exists on the ledger
        When I revoke the "credential" with id "cred:vdr:test-8" using an invalid JWT
        Then the transaction should fail with an "authorization" error

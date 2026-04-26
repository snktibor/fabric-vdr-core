Feature: DID Lifecycle Management
    As an SSI Administrator
    I want to create and manage DIDs on the ledger
    So that users can have verifiable identities

    Background:
        Given a connected Fabric Gateway as an "authorized" client

    Scenario: 1. Register a new DID document and retrieve it
        When I submit a transaction to create a "DID document" with ID "did:vdr:test-1"
        Then the transaction should be successful
        And the ledger should contain the "DID document" for "did:vdr:test-1"

    Scenario: 2. Query an existing DID to get the document with public keys
        Given the ledger contains the "DID document" for "did:vdr:test-2"
        When I query the ledger for the "DID document" with ID "did:vdr:test-2"
        Then the transaction should be successful
        And the ledger should contain the "DID document" for "did:vdr:test-2"

    Scenario: 3. Deactivate a DID and prevent modifications
        Given the ledger contains the "DID document" for "did:vdr:test-3"
        When I submit a transaction to deactivate the "DID document" with ID "did:vdr:test-3"
        Then the transaction should be successful
        And the "DID document" for "did:vdr:test-3" should be marked as "INACTIVE"
        When I submit a transaction to update the "DID document" with ID "did:vdr:test-3"
        Then the transaction should fail with an "authorization" error
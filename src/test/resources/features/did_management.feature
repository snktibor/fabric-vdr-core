Feature: DID Lifecycle Management
    As an SSI Administrator
    I want to create and manage DIDs on the ledger
    So that users can have verifiable identities

    Background:
        Given a connected Fabric Gateway as an "authorized" client

    Scenario: 1. Register a new DID document and retrieve it
        When I create a "DID document" with id "did:vdr:test-1"
        Then the transaction should be successful
        And a "DID document" with id "did:vdr:test-1" should exist on the ledger

    Scenario: 2. Query an existing DID to get the document with public keys
        Given a "DID document" with id "did:vdr:test-2" exists on the ledger
        When I query the "DID document" with id "did:vdr:test-2"
        Then the transaction should be successful
        And a "DID document" with id "did:vdr:test-2" should exist on the ledger

    Scenario: 3. Deactivate a DID and prevent modifications
        Given a "DID document" with id "did:vdr:test-3" exists on the ledger
        When I deactivate the "DID document" with id "did:vdr:test-3"
        Then the transaction should be successful
        And the "DID document" with id "did:vdr:test-3" is marked as "INACTIVE"
        When I update the "DID document" with id "did:vdr:test-3"
        Then the transaction should fail with an "authorization" error

    Scenario: 4. Reject a protected DID transaction with an invalid JWT
        Given a "DID document" with id "did:vdr:test-4" exists on the ledger
        When I deactivate the "DID document" with id "did:vdr:test-4" using an invalid JWT
        Then the transaction should fail with an "authorization" error

    Scenario: 5. List all registered DIDs includes a newly created one
        Given a "DID document" with id "did:vdr:test-5" exists on the ledger
        When I list all "DID document" entries
        Then the transaction should be successful
        And the list should contain the "DID document" with id "did:vdr:test-5"

    Scenario: 6. Reject a DID document payload that is the JSON null literal
        When I create a DID document with id "did:vdr:test-6" and the raw document payload "null"
        Then the transaction should fail with an "must be a JSON object" error

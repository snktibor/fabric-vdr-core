Feature: DID Lifecycle Management
    As an SSI Administrator
    I want to create and manage DIDs on the ledger
    So that users can have verifiable identities

    Scenario: Successfully creating a new DID Document
        Given a connected Fabric Gateway
        When I submit a transaction to create DID "did:vdr:test-123"
        Then the transaction should be successful
        And the ledger should contain the DID document for "did:vdr:test-123"
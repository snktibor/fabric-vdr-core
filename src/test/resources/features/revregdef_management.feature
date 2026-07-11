Feature: Revocation Registry Definition Lifecycle Management
    As a Credential Issuer
    I want to publish revocation registry definitions on the ledger
    So that I can revoke issued credentials using the tails-free AnonCreds v2 scheme

    Background:
        Given a connected Fabric Gateway as an "authorized" client

    Scenario: 1. Publish a new revocation registry definition
        When I create a "revocation registry definition" with id "revregdef:vdr:test-1"
        Then the transaction should be successful
        And a "revocation registry definition" with id "revregdef:vdr:test-1" should exist on the ledger

    Scenario: 2. Query an existing revocation registry definition by ID
        Given a "revocation registry definition" with id "revregdef:vdr:test-2" exists on the ledger
        When I query the "revocation registry definition" with id "revregdef:vdr:test-2"
        Then the transaction should be successful
        And a "revocation registry definition" with id "revregdef:vdr:test-2" should exist on the ledger

    Scenario: 3. List revocation registry definitions returns previously created entries
        Given a "revocation registry definition" with id "revregdef:vdr:test-3" exists on the ledger
        When I list all "revocation registry definition" entries
        Then the transaction should be successful
        And the list should contain the "revocation registry definition" with id "revregdef:vdr:test-3"

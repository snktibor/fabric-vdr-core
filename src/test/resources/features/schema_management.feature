Feature: Schema Lifecycle Management

    Background:
        Given a connected Fabric Gateway as an "authorized" client

    Scenario: 1. Publish a new verifiable credential schema
        When I create a "schema" with id "schema:vdr:test-1"
        Then the transaction should be successful
        And a "schema" with id "schema:vdr:test-1" should exist on the ledger

    Scenario: 2. Query and fetch a published schema by ID
        Given a "schema" with id "schema:vdr:test-2" exists on the ledger
        When I query the "schema" with id "schema:vdr:test-2"
        Then the transaction should be successful
        And a "schema" with id "schema:vdr:test-2" should exist on the ledger

    Scenario: 3. Publish a schema signed with an explicitly held issuer key
        Given I hold the signing key for the DID "did:vdr:issuer-1"
        When I create a "schema" with id "schema:vdr:test-3" signed with my held key
        Then the transaction should be successful
        And a "schema" with id "schema:vdr:test-3" should exist on the ledger

    Scenario: 4. List schemas returns previously created entries
        Given a "schema" with id "schema:vdr:test-4" exists on the ledger
        When I list all "schema" entries
        Then the transaction should be successful
        And the list should contain the "schema" with id "schema:vdr:test-4"

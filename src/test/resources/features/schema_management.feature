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

    Scenario: 3. Publish a schema using a pre-existing JWT
        Given I hold a valid JWT for the DID "did:vdr:issuer-1"
        When I create a "schema" with id "schema:vdr:test-3" using my held JWT
        Then the transaction should be successful
        And a "schema" with id "schema:vdr:test-3" should exist on the ledger

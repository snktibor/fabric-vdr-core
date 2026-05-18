Feature: Credential Definition Lifecycle Management
    As a Credential Issuer
    I want to publish and manage credential definitions on the ledger
    So that I can issue verifiable credentials anchored to a known schema

    Background:
        Given a connected Fabric Gateway as an "authorized" client

    Scenario: 1. Publish a new credential definition
        When I create a "credential definition" with id "creddef:vdr:test-1"
        Then the transaction should be successful
        And a "credential definition" with id "creddef:vdr:test-1" should exist on the ledger

    Scenario: 2. Query an existing credential definition by ID
        Given a "credential definition" with id "creddef:vdr:test-2" exists on the ledger
        When I query the "credential definition" with id "creddef:vdr:test-2"
        Then the transaction should be successful
        And a "credential definition" with id "creddef:vdr:test-2" should exist on the ledger

    Scenario: 3. List credential definitions returns previously created entries
        Given a "credential definition" with id "creddef:vdr:test-3" exists on the ledger
        When I list all "credential definition" entries
        Then the transaction should be successful
        And the list should contain the "credential definition" with id "creddef:vdr:test-3"

    Scenario: 4. Deactivate a credential definition
        Given a "credential definition" with id "creddef:vdr:test-4" exists on the ledger
        When I deactivate the "credential definition" with id "creddef:vdr:test-4"
        Then the transaction should be successful
        And the "credential definition" with id "creddef:vdr:test-4" is marked as "INACTIVE"

    Scenario: 5. Reject credential definition deactivation with an invalid JWT
        Given a "credential definition" with id "creddef:vdr:test-5" exists on the ledger
        When I deactivate the "credential definition" with id "creddef:vdr:test-5" using an invalid JWT
        Then the transaction should fail with an "authorization" error

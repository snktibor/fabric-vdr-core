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

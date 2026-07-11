Feature: Revocation Registry Entry Lifecycle Management
    As a Credential Issuer
    I want to publish and maintain the accumulator value for a revocation registry
    So that verifiers can check revocation status without a correlatable status list

    Background:
        Given a connected Fabric Gateway as an "authorized" client

    Scenario: 1. Initialize the genesis accumulator for a revocation registry definition
        When I create a "revocation registry entry" with id "revregentry:vdr:test-1"
        Then the transaction should be successful
        And a "revocation registry entry" with id "revregentry:vdr:test-1" should exist on the ledger
        And the "revocation registry entry" with id "revregentry:vdr:test-1" should have accumulator value "accum-genesis" and version 0

    Scenario: 2. Owner updates the accumulator value after a revocation
        Given a "revocation registry entry" with id "revregentry:vdr:test-2" exists on the ledger
        When I update the accumulator of the "revocation registry entry" with id "revregentry:vdr:test-2" to "accum-after-revocation"
        Then the transaction should be successful
        And the "revocation registry entry" with id "revregentry:vdr:test-2" should have accumulator value "accum-after-revocation" and version 1

    Scenario: 3. Reject an accumulator update from a registered non-owner issuer
        Given a "revocation registry entry" with id "revregentry:vdr:test-3" exists on the ledger
        When I update the accumulator of the "revocation registry entry" with id "revregentry:vdr:test-3" as an unauthorized actor
        Then the transaction should fail with an "Unauthorized" error
        And the "revocation registry entry" with id "revregentry:vdr:test-3" should have accumulator value "accum-genesis" and version 0

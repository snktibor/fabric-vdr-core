Feature: Revocation Registry Entry Lifecycle Management
    As a Credential Issuer
    I want to publish and maintain the accumulator value for a revocation registry
    So that verifiers can check revocation status without a correlatable status list

    Background:
        Given a connected Fabric Gateway as an "authorized" client

    Scenario: 1. Initialize the genesis accumulator for a revocation registry definition
        When I create a "revocation registry entry" with id "revregentry-1"
        Then the transaction should be successful
        And a "revocation registry entry" with id "revregentry-1" should exist on the ledger
        And the "revocation registry entry" with id "revregentry-1" should have accumulator value "accum-genesis" and version 0

    Scenario: 2. Owner updates the accumulator value after a revocation
        Given a "revocation registry entry" with id "revregentry-2" exists on the ledger
        When I update the accumulator of the "revocation registry entry" with id "revregentry-2" to "accum-after-revocation"
        Then the transaction should be successful
        And the "revocation registry entry" with id "revregentry-2" should have accumulator value "accum-after-revocation" and version 1

    Scenario: 3. Reject an accumulator update from a registered non-owner issuer
        Given a "revocation registry entry" with id "revregentry-3" exists on the ledger
        When I update the accumulator of the "revocation registry entry" with id "revregentry-3" as an unauthorized actor
        Then the transaction should fail with an "Unauthorized" error
        And the "revocation registry entry" with id "revregentry-3" should have accumulator value "accum-genesis" and version 0

    Scenario: 4. An accumulator update emits a chaincode event carrying the new value
        Given a "revocation registry entry" with id "revregentry-4" exists on the ledger
        When I update the accumulator of the "revocation registry entry" with id "revregentry-4" to "accum-after-event" while listening for chaincode events
        Then the transaction should be successful
        And a "RevRegEntryUpdated" chaincode event should be received with accumulator value "accum-after-event"

    Scenario: 5. Accumulator history preserves overwritten values oldest first
        Given a "revocation registry entry" with id "revregentry-5" exists on the ledger
        When I update the accumulator of the "revocation registry entry" with id "revregentry-5" to "accum-second"
        And I fetch the accumulator history of the "revocation registry entry" with id "revregentry-5"
        Then the transaction should be successful
        And the history should list accumulator value "accum-genesis" with version 0 before accumulator value "accum-second" with version 1

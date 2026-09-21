@concurrency
Feature: Concurrent revocation registry writes
    As the operator of a verifiable data registry
    I want to know what the ledger actually guarantees when two revocations race
    So that conditional revocation is designed against measured behaviour, not against assumption

    Background:
        Given a connected Fabric Gateway as an "authorized" client

    @control
    Scenario: A0. Control — two updates to one registry, applied one after the other
        Given a "revocation registry entry" with id "race-entry" exists on the ledger
        When I update the accumulator of the "revocation registry entry" with id "race-entry" to "accum-race-first"
        Then the transaction should be successful
        And the last transaction outcome should be "COMMITTED"
        When I update the accumulator of the "revocation registry entry" with id "race-entry" to "accum-race-second"
        Then the transaction should be successful
        And the last transaction outcome should be "COMMITTED"
        And the "revocation registry entry" with id "race-entry" should have accumulator value "accum-race-second" and version 2

    @race
    Scenario: A1. Two updates to one registry, endorsed against the same state and released together
        Given a "revocation registry entry" with id "race-entry" exists on the ledger
        And retrying is disabled for this scenario
        When the following accumulator updates are endorsed against the same state and submitted together:
            | label  | registry   | accumulator       |
            | first  | race-entry | accum-race-first  |
            | second | race-entry | accum-race-second |
        Then no concurrent submission should have failed on endorsement or on the network
        And exactly 1 concurrent submission should be committed
        And 1 concurrent submission should have outcome "MVCC_CONFLICT"
        And the registry "race-entry" should be at version 1

    @control
    Scenario: B0. Control — two independent registries, each issuer re-checks the other before acting
        Given an independent revocation registry "registry-x" exists with its own issuer
        And an independent revocation registry "registry-y" exists with its own issuer
        When the issuer of "registry-x" revokes it with "accum-x-revoked" only if "registry-y" is still unrevoked
        And the issuer of "registry-y" revokes it with "accum-y-revoked" only if "registry-x" is still unrevoked
        Then at most 1 of the revocations should have taken effect
        And the registry "registry-x" should be at version 1
        And the registry "registry-y" should be at version 0

    @toctou
    # Characterisation of the unconditional update path. The "is the other registry still
    # unrevoked?" check is a client-side query, so the two submissions carry disjoint read and
    # write sets and commit-time validation has nothing to compare. The assertion "at most 1 of
    # the revocations should have taken effect" belongs to the conditional update, which reads the
    # guarded registry inside the submitted transaction.
    Scenario: B1u. Characterisation — without a guard, both issuers observe the other unrevoked and both revocations commit
        Given an independent revocation registry "registry-x" exists with its own issuer
        And an independent revocation registry "registry-y" exists with its own issuer
        And retrying is disabled for this scenario
        When the issuer of "registry-x" observes that "registry-y" is still unrevoked
        And the issuer of "registry-y" observes that "registry-x" is still unrevoked
        And the following accumulator updates are endorsed against the same state and submitted together:
            | label | registry   | accumulator     |
            | x     | registry-x | accum-x-revoked |
            | y     | registry-y | accum-y-revoked |
        Then no concurrent submission should have failed on endorsement or on the network
        And exactly 2 concurrent submissions should be committed
        And the registry "registry-x" should be at version 1
        And the registry "registry-y" should be at version 1

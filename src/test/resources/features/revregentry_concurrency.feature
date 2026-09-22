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

    @race
    Scenario: A2. Two updates to one registry, each guarded by that same registry, released together
        Given a "revocation registry entry" with id "race-entry" exists on the ledger
        And retrying is disabled for this scenario
        When the issuer of "race-entry" observes that "race-entry" is still unrevoked
        # A guard on the registry being written is a compare-and-swap on it. Both updates are
        # computed from the same version, so the expectation is A1's: the guarded path neither
        # helps nor hinders a race that the write set already decides.
        And the following accumulator updates are endorsed against the same state and submitted together:
            | label  | registry   | accumulator       | guard      |
            | first  | race-entry | accum-race-first  | race-entry |
            | second | race-entry | accum-race-second | race-entry |
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

    @toctou
    # The conditional counterpart of B1u, and the pair is the evidence: same fixture, same
    # observations, same release machinery, and the only difference is that each update names the
    # other registry as a guard. The guarded transaction reads that entry itself, so the key joins
    # its read set, the two transactions now overlap where B1u's did not, and commit-time
    # validation has something to compare.
    #
    # A guarded loser can end in two correct ways. Here it is invalidated at commit
    # (MVCC_READ_CONFLICT), because every proposal is simulated before any of them is submitted and
    # the guarded registry therefore cannot have moved yet. A guard whose registry moves before the
    # simulation is refused at endorsement instead, which is what the sequential scenarios measure.
    Scenario: B1. With mutual guards, both issuers observe the other unrevoked and at most one revocation commits
        Given an independent revocation registry "registry-x" exists with its own issuer
        And an independent revocation registry "registry-y" exists with its own issuer
        And retrying is disabled for this scenario
        When the issuer of "registry-x" observes that "registry-y" is still unrevoked
        And the issuer of "registry-y" observes that "registry-x" is still unrevoked
        And the following accumulator updates are endorsed against the same state and submitted together:
            | label | registry   | accumulator     | guard      |
            | x     | registry-x | accum-x-revoked | registry-y |
            | y     | registry-y | accum-y-revoked | registry-x |
        Then at most 1 of the revocations should have taken effect
        And no concurrent submission should have failed for a reason other than its guard
        And exactly 1 concurrent submission should be committed
        And 1 concurrent submission should have been refused by its guard
        And 1 concurrent submission should have outcome "MVCC_CONFLICT"
        And exactly 1 of the independent registries should be at version 1 and the other at version 0

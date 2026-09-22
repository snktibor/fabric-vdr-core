@guard
Feature: Guarded revocation registry updates
    As a Credential Issuer whose revocation depends on another registry standing still
    I want an update that commits only while every registry I name is unchanged
    So that the window between checking that state and writing mine is closed by the ledger

    Background:
        Given a connected Fabric Gateway as an "authorized" client

    Scenario: G1. A guarded update commits against an observed guard version and writes only its own registry
        Given an independent revocation registry "registry-x" exists with its own issuer
        And an independent revocation registry "registry-y" exists with its own issuer
        # Moving the guard registry twice first keeps its version clear of every version the
        # target passes through, so neither reported number could be the other one misread.
        When I update the accumulator of the "revocation registry entry" with id "registry-y" to "accum-bump"
        And I update the accumulator of the "revocation registry entry" with id "registry-y" to "accum-bump-again"
        Then the registry "registry-y" should be at version 2
        When the version of "registry-y" is observed
        And the issuer of "registry-x" updates its accumulator to "accum-guarded-write" while listening for chaincode events, guarded by:
            | registry   | expected version |
            | registry-y | observed         |
        Then the transaction should be successful
        And the guarded response should report entry version 1
        And the guarded response should report checked version 2 for "registry-y"
        And the "revocation registry entry" with id "registry-x" should have accumulator value "accum-guarded-write" and version 1
        And the registry "registry-y" should be at version 2
        And a "RevRegEntryUpdated" chaincode event should be received with accumulator value "accum-guarded-write"

    Scenario: G2. A guarded update is refused when the guard registry moved after it was observed
        Given an independent revocation registry "registry-x" exists with its own issuer
        And an independent revocation registry "registry-y" exists with its own issuer
        When the version of "registry-y" is observed
        And I update the accumulator of the "revocation registry entry" with id "registry-y" to "accum-bump"
        And the issuer of "registry-x" updates its accumulator to "accum-guarded-write" guarded by:
            | registry   | expected version |
            | registry-y | observed         |
        Then the transaction should fail because "registry-y" is at version 1 and 0 was expected
        And the last transaction outcome should be "ENDORSEMENT_REJECTED"
        And the "revocation registry entry" with id "registry-x" should have accumulator value "accum-genesis" and version 0
        And the registry "registry-y" should be at version 1

    Scenario: G3a. A guard on the target itself commits while the target is still where the caller read it
        Given an independent revocation registry "registry-x" exists with its own issuer
        When the version of "registry-x" is observed
        And the issuer of "registry-x" updates its accumulator to "accum-guarded-write" guarded by:
            | registry   | expected version |
            | registry-x | observed         |
        Then the transaction should be successful
        # A guard on the target is tested against the version before the write, so it stays
        # exactly one behind the version the same response reports for the entry.
        And the guarded response should report entry version 1
        And the guarded response should report checked version 0 for "registry-x"
        And the "revocation registry entry" with id "registry-x" should have accumulator value "accum-guarded-write" and version 1

    Scenario: G3b. A guard on the target refuses a write computed from a version it has already left
        Given an independent revocation registry "registry-x" exists with its own issuer
        When the version of "registry-x" is observed
        And I update the accumulator of the "revocation registry entry" with id "registry-x" to "accum-bump"
        And the issuer of "registry-x" updates its accumulator to "accum-guarded-write" guarded by:
            | registry   | expected version |
            | registry-x | observed         |
        Then the transaction should fail because "registry-x" is at version 1 and 0 was expected
        # Without the guard the later write would win and the earlier one would be gone; here the
        # registry keeps the newer accumulator and the stale write is the one that is lost.
        And the "revocation registry entry" with id "registry-x" should have accumulator value "accum-bump" and version 1

    Scenario: G3c-i. A malformed guard list is refused before anything is written — an empty list
        Given an independent revocation registry "registry-x" exists with its own issuer
        When the issuer of "registry-x" updates its accumulator to "accum-guarded-write" guarded by the raw payload:
            """
            []
            """
        Then the transaction should fail with an "guards must not be empty" error
        And the "revocation registry entry" with id "registry-x" should have accumulator value "accum-genesis" and version 0

    Scenario: G3c-ii. A malformed guard list is refused before anything is written — the same registry twice
        Given an independent revocation registry "registry-x" exists with its own issuer
        And an independent revocation registry "registry-y" exists with its own issuer
        When the issuer of "registry-x" updates its accumulator to "accum-guarded-write" guarded by the raw payload:
            """
            [{"revRegDefId":"<registry-y>","expectedVersion":0},{"revRegDefId":"<registry-y>","expectedVersion":0}]
            """
        Then the transaction should fail with an "guards must not guard the same registry twice" error
        And the "revocation registry entry" with id "registry-x" should have accumulator value "accum-genesis" and version 0

    Scenario: G3c-iii. A malformed guard list is refused before anything is written — a duplicated JSON key
        Given an independent revocation registry "registry-x" exists with its own issuer
        And an independent revocation registry "registry-y" exists with its own issuer
        # Read last-wins, this payload is a guard on the target at version 0 and would commit.
        # The state assertion below therefore also measures that the text is read only one way.
        When the issuer of "registry-x" updates its accumulator to "accum-guarded-write" guarded by the raw payload:
            """
            [{"revRegDefId":"<registry-y>","revRegDefId":"<registry-x>","expectedVersion":0}]
            """
        Then the transaction should fail with an "Invalid JSON payload" error
        And the "revocation registry entry" with id "registry-x" should have accumulator value "accum-genesis" and version 0

    Scenario: G4. A registered non-owner is refused on the guarded path by the ownership check
        Given an independent revocation registry "registry-x" exists with its own issuer
        And an independent revocation registry "registry-y" exists with its own issuer
        When the version of "registry-y" is observed
        And a registered non-owner issuer updates the accumulator of "registry-x" to "accum-guarded-write" guarded by:
            | registry   | expected version |
            | registry-y | observed         |
        # The ownership failure and a token that was not scoped to this invocation share one error
        # code, so the message is what tells them apart: this has to be the ownership check.
        Then the transaction should fail with an "is not the owner" error
        And the "revocation registry entry" with id "registry-x" should have accumulator value "accum-genesis" and version 0

    Scenario: G5a. A guard naming an identifier that never existed is refused, not read as version zero
        Given an independent revocation registry "registry-x" exists with its own issuer
        When the issuer of "registry-x" updates its accumulator to "accum-guarded-write" guarded by:
            | registry                          | expected version |
            | guard-registry-that-never-existed | 0                |
        Then the transaction should fail with a not-found error naming "guard-registry-that-never-existed"
        And the "revocation registry entry" with id "registry-x" should have accumulator value "accum-genesis" and version 0

    Scenario: G5b. A guard naming a definition whose genesis entry was never created is refused
        Given an independent revocation registry "registry-x" exists with its own issuer
        And a "revocation registry definition" with id "guard-definition-without-entry" exists on the ledger
        When the issuer of "registry-x" updates its accumulator to "accum-guarded-write" guarded by:
            | registry                       | expected version |
            | guard-definition-without-entry | 0                |
        Then the transaction should fail with a not-found error naming "guard-definition-without-entry"
        And the "revocation registry entry" with id "registry-x" should have accumulator value "accum-genesis" and version 0

    Scenario: G5c. For a caller that does not own the target, the authorization failure wins over a missing guard
        Given an independent revocation registry "registry-x" exists with its own issuer
        When a registered non-owner issuer updates the accumulator of "registry-x" to "accum-guarded-write" guarded by:
            | registry                          | expected version |
            | guard-registry-that-never-existed | 0                |
        Then the transaction should fail with an "is not the owner" error
        And the transaction failure message should not contain "was not found"
        And the "revocation registry entry" with id "registry-x" should have accumulator value "accum-genesis" and version 0

    Scenario: G7. Several guards at their observed versions commit, and no guarded registry is written
        Given an independent revocation registry "registry-x" exists with its own issuer
        And an independent revocation registry "registry-y" exists with its own issuer
        And an independent revocation registry "registry-z" exists with its own issuer
        When I update the accumulator of the "revocation registry entry" with id "registry-y" to "accum-bump"
        And I update the accumulator of the "revocation registry entry" with id "registry-z" to "accum-bump"
        And I update the accumulator of the "revocation registry entry" with id "registry-z" to "accum-bump-again"
        And the version of "registry-y" is observed
        And the version of "registry-z" is observed
        And the version of "registry-x" is observed
        And the issuer of "registry-x" updates its accumulator to "accum-guarded-write" guarded by:
            | registry   | expected version |
            | registry-y | observed         |
            | registry-z | observed         |
            | registry-x | observed         |
        Then the transaction should be successful
        And the guarded response should report entry version 1
        And the guarded response should report checked version 1 for "registry-y"
        And the guarded response should report checked version 2 for "registry-z"
        And the guarded response should report checked version 0 for "registry-x"
        And the "revocation registry entry" with id "registry-x" should have accumulator value "accum-guarded-write" and version 1
        And the registry "registry-y" should be at version 1
        And the registry "registry-z" should be at version 2

    Scenario: G8. Every element of the guard list is checked — only the last one is stale and the update is refused
        Given an independent revocation registry "registry-x" exists with its own issuer
        And an independent revocation registry "registry-y" exists with its own issuer
        And an independent revocation registry "registry-z" exists with its own issuer
        When I update the accumulator of the "revocation registry entry" with id "registry-y" to "accum-bump"
        And the version of "registry-y" is observed
        And the version of "registry-x" is observed
        And the version of "registry-z" is observed
        And I update the accumulator of the "revocation registry entry" with id "registry-z" to "accum-bump"
        And the issuer of "registry-x" updates its accumulator to "accum-guarded-write" guarded by:
            | registry   | expected version |
            | registry-y | observed         |
            | registry-x | observed         |
            | registry-z | observed         |
        Then the transaction should fail because "registry-z" is at version 1 and 0 was expected
        And the "revocation registry entry" with id "registry-x" should have accumulator value "accum-genesis" and version 0
        And the registry "registry-y" should be at version 1
        And the registry "registry-z" should be at version 1

    Scenario: G9. A second guarded update continues from the first response alone
        Given an independent revocation registry "registry-x" exists with its own issuer
        And an independent revocation registry "registry-y" exists with its own issuer
        # The external guard is moved clear of every version the target passes through, so the
        # version each field carries is readable from the numbers alone.
        When I update the accumulator of the "revocation registry entry" with id "registry-y" to "accum-bump"
        And I update the accumulator of the "revocation registry entry" with id "registry-y" to "accum-bump-again"
        And I update the accumulator of the "revocation registry entry" with id "registry-y" to "accum-bump-third"
        Then the registry "registry-y" should be at version 3
        When the version of "registry-x" is observed
        And the version of "registry-y" is observed
        And the issuer of "registry-x" chains two guarded updates to "accum-guarded-write" and "accum-guarded-write-again", guarded by itself and "registry-y", taking the second call's expected versions from the first response
        Then the transaction should be successful
        And the "revocation registry entry" with id "registry-x" should have accumulator value "accum-guarded-write-again" and version 2
        And the registry "registry-y" should be at version 3

    Scenario: G9b. The two version fields are not interchangeable — the checked version is refused for a guard on the target
        Given an independent revocation registry "registry-x" exists with its own issuer
        And an independent revocation registry "registry-y" exists with its own issuer
        When I update the accumulator of the "revocation registry entry" with id "registry-y" to "accum-bump"
        And I update the accumulator of the "revocation registry entry" with id "registry-y" to "accum-bump-again"
        And I update the accumulator of the "revocation registry entry" with id "registry-y" to "accum-bump-third"
        And the version of "registry-x" is observed
        And the version of "registry-y" is observed
        And the issuer of "registry-x" chains two guarded updates to "accum-guarded-write" and "accum-guarded-write-again", guarded by itself and "registry-y", sending the first response's checked version for its own guard on the second call
        Then the transaction should fail because "registry-x" is at version 1 and 0 was expected
        And the "revocation registry entry" with id "registry-x" should have accumulator value "accum-guarded-write" and version 1
        And the registry "registry-y" should be at version 3

    Scenario: G9c. A version carried over from an earlier response is checked again, not assumed to still hold
        Given an independent revocation registry "registry-x" exists with its own issuer
        And an independent revocation registry "registry-y" exists with its own issuer
        When I update the accumulator of the "revocation registry entry" with id "registry-y" to "accum-bump"
        And I update the accumulator of the "revocation registry entry" with id "registry-y" to "accum-bump-again"
        And I update the accumulator of the "revocation registry entry" with id "registry-y" to "accum-bump-third"
        And the version of "registry-x" is observed
        And the version of "registry-y" is observed
        # The first call guarantees the guarded registry was unchanged up to its own commit and
        # nothing after it, so the version it reported has to be re-earned, not inherited.
        And the issuer of "registry-x" chains two guarded updates to "accum-guarded-write" and "accum-guarded-write-again", guarded by itself and "registry-y", whose issuer moves it to "accum-bump" between the two calls
        Then the transaction should fail because "registry-y" is at version 4 and 3 was expected
        And the "revocation registry entry" with id "registry-x" should have accumulator value "accum-guarded-write" and version 1
        And the registry "registry-y" should be at version 4

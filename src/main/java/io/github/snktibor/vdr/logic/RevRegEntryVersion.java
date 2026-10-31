package io.github.snktibor.vdr.logic;

import io.github.snktibor.vdr.exception.VdrException;

/** Checked arithmetic for the revocation entry's nonnegative audit version. */
final class RevRegEntryVersion {

    private RevRegEntryVersion() {}

    /*@ normal_behavior
    @ requires 0 <= version && version < Long.MAX_VALUE;
    @ ensures (\bigint)\result == (\bigint)version + 1;
    @ ensures \result > version && \result > 0;
    @ assignable \nothing;
    @ also
    @ exceptional_behavior
    @ requires version == Long.MAX_VALUE;
    @ signals_only VdrException;
    @ signals (VdrException e) e.errorCode == "FAILED_PRECONDITION";
    @ assignable \nothing;
    @ also
    @ exceptional_behavior
    @ requires version < 0;
    @ signals_only VdrException;
    @ signals (VdrException e) e.errorCode == "FAILED_PRECONDITION";
    @ assignable \nothing;
    @*/
    static long next(final long version) {
        if (version < 0) {
            throw VdrException.failedPrecondition("revocation registry entry version is negative");
        }
        if (version == Long.MAX_VALUE) {
            throw VdrException.failedPrecondition("revocation registry entry version is exhausted");
        }
        return version + 1;
    }
}

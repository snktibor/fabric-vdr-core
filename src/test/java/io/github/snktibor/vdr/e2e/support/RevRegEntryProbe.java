package io.github.snktibor.vdr.e2e.support;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads revocation registry entry state over the EVALUATE path, outside any submitted transaction.
 *
 * <p>A guarded update pins a version the caller claims to have observed, so a scenario has to
 * observe it the way a client would — a plain query against committed state — instead of asserting
 * a literal that the fixture happens to produce today. The same read after a submission is what
 * separates "the transaction was refused" from "the transaction was refused and wrote nothing".
 */
public class RevRegEntryProbe {

    private static final String GETTER_TX = "getRevRegEntry";
    private static final Pattern VERSION_PATTERN = Pattern.compile("\"version\"\\s*:\\s*(\\d+)");

    private final TransactionExecutor executor;

    public RevRegEntryProbe(final TransactionExecutor executor) {
        this.executor = executor;
    }

    /** The entry's current audit version, read straight from the ledger. */
    public long version(final String revRegDefId) {
        final String response = read(revRegDefId);
        final Matcher matcher = VERSION_PATTERN.matcher(response);
        if (!matcher.find()) {
            throw new IllegalStateException(
                    "No version field in the entry of '" + revRegDefId + "': " + response);
        }
        return Long.parseLong(matcher.group(1));
    }

    /** The raw getter response for an entry. */
    public String read(final String revRegDefId) {
        try {
            return executor.evaluate(GETTER_TX, revRegDefId);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Could not read the revocation registry entry of '" + revRegDefId + "'.", e);
        }
    }
}

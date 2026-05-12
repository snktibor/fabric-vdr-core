package com.example.vdr.e2e.support;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.hyperledger.fabric.client.EndorseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Centralises submit/evaluate semantics: error flattening, exception capture into the {@link
 * ScenarioContext}, and JWT-argument appending. Step classes never call {@code
 * contract.submitTransaction} directly.
 */
public class TransactionExecutor {

    private static final Logger LOGGER = LoggerFactory.getLogger(TransactionExecutor.class);

    private final FabricConnectionManager connection;
    private final ScenarioContext context;

    public TransactionExecutor(
            final FabricConnectionManager connection, final ScenarioContext context) {
        this.connection = connection;
        this.context = context;
    }

    /** Submits an unprotected transaction (no JWT). Captures any failure into the context. */
    public void submit(final String txName, final String... args) {
        context.setLastTransactionException(null);
        try {
            connection.contract().submitTransaction(txName, args);
            LOGGER.info("Transaction {} submitted successfully.", txName);
        } catch (EndorseException e) {
            context.setLastTransactionException(flatten(e));
            LOGGER.error("{} endorsement failed: {}", txName, e.getMessage(), e);
        } catch (Exception e) {
            context.setLastTransactionException(e);
            LOGGER.error("{} submission failed.", txName, e);
        }
    }

    /** Submits a JWT-protected transaction, appending {@code jwt} as the final argument. */
    public void submitWithJwt(final String txName, final String jwt, final String... payloadArgs) {
        final String[] finalArgs = Arrays.copyOf(payloadArgs, payloadArgs.length + 1);
        finalArgs[payloadArgs.length] = jwt;
        submit(txName, finalArgs);
    }

    /** Evaluates a read-only transaction and returns the UTF-8 response, throwing on failure. */
    public String evaluate(final String txName, final String... args) throws Exception {
        final byte[] result = connection.contract().evaluateTransaction(txName, args);
        return new String(result, StandardCharsets.UTF_8);
    }

    private static IllegalStateException flatten(final EndorseException e) {
        final String details =
                e.getDetails().stream().map(d -> d.getMessage()).collect(Collectors.joining("; "));
        return new IllegalStateException(details, e);
    }
}

package io.github.snktibor.vdr.e2e.support;

import io.github.snktibor.vdr.e2e.config.E2EConfig;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.hyperledger.fabric.client.ChaincodeEvent;
import org.hyperledger.fabric.client.CloseableIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Subscribes to chaincode events around a submitted transaction and records the first matching
 * committed event into the {@link ScenarioContext}.
 *
 * <p>The subscription is opened BEFORE the action runs (Gateway event streams start at the next
 * committed block), the action submits and waits for commit, and a bounded await then reads the
 * stream until an event with the expected name and payload fragment arrives. On timeout nothing is
 * recorded, letting the asserting step fail with a clear message.
 */
public class ChaincodeEventProbe {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChaincodeEventProbe.class);
    private static final long AWAIT_TIMEOUT_SECONDS = 30L;

    private final FabricConnectionManager connection;
    private final ScenarioContext context;

    public ChaincodeEventProbe(
            final FabricConnectionManager connection, final ScenarioContext context) {
        this.connection = connection;
        this.context = context;
    }

    /**
     * Runs {@code action} with an open event subscription and records the first event matching
     * {@code eventName} whose UTF-8 payload contains {@code payloadFragment}.
     */
    public void runAndAwaitEvent(
            final String eventName, final String payloadFragment, final Runnable action) {
        context.recordChaincodeEvent(null, null);
        try (CloseableIterator<ChaincodeEvent> events =
                connection.network().getChaincodeEvents(E2EConfig.CHAINCODE_NAME)) {
            action.run();
            awaitMatch(events, eventName, payloadFragment);
        }
    }

    private void awaitMatch(
            final CloseableIterator<ChaincodeEvent> events,
            final String eventName,
            final String payloadFragment) {
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            final Future<ChaincodeEvent> match =
                    executor.submit(() -> firstMatch(events, eventName, payloadFragment));
            final ChaincodeEvent event = match.get(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (event != null) {
                context.recordChaincodeEvent(
                        event.getEventName(),
                        new String(event.getPayload(), StandardCharsets.UTF_8));
            }
        } catch (final TimeoutException e) {
            LOGGER.warn(
                    "No '{}' chaincode event matching '{}' within {}s.",
                    eventName,
                    payloadFragment,
                    AWAIT_TIMEOUT_SECONDS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (final Exception e) {
            LOGGER.warn("Chaincode event listening failed.", e);
        } finally {
            // Closing the iterator (try-with-resources in the caller) unblocks the worker.
            executor.shutdownNow();
        }
    }

    private static ChaincodeEvent firstMatch(
            final CloseableIterator<ChaincodeEvent> events,
            final String eventName,
            final String payloadFragment) {
        while (events.hasNext()) {
            final ChaincodeEvent event = events.next();
            final String payload = new String(event.getPayload(), StandardCharsets.UTF_8);
            LOGGER.info("Observed chaincode event '{}': {}", event.getEventName(), payload);
            if (eventName.equals(event.getEventName()) && payload.contains(payloadFragment)) {
                return event;
            }
        }
        return null;
    }
}

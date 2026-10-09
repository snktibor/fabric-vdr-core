package io.github.snktibor.vdr.e2e.steps;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.When;
import io.github.snktibor.vdr.e2e.entity.EntityHandlerRegistry;
import io.github.snktibor.vdr.e2e.support.ScenarioContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Steps unique to the DID lifecycle that cannot be generalised across entity types. */
public class DidManagementSteps {

    private static final Logger LOGGER = LoggerFactory.getLogger(DidManagementSteps.class);

    private final EntityHandlerRegistry registry;
    private final ScenarioContext context;

    public DidManagementSteps(final EntityHandlerRegistry registry, final ScenarioContext context) {
        this.registry = registry;
        this.context = context;
    }

    @When("I create a DID document with id {string} and the raw document payload {string}")
    public void iCreateADidWithRawDocumentPayload(final String rawId, final String documentJson) {
        registry.did().createWithRawDocument(rawId, documentJson);
        LOGGER.info("Submitted createDid for {} with raw payload '{}'.", rawId, documentJson);
    }

    @Given("I hold the signing key for the DID {string}")
    public void iHoldTheSigningKeyForTheDid(final String didSeed) {
        registry.did().existingOnLedger(didSeed);
        final String effectiveDid = context.resolveId(didSeed);
        // Verify the key material exists now so a later signing step cannot fail confusingly.
        context.requireDidKeyPair(effectiveDid);
        context.holdSigningDid(effectiveDid);
        LOGGER.info("Holding signing key for {}.", effectiveDid);
    }
}

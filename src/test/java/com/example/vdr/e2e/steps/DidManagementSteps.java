package com.example.vdr.e2e.steps;

import com.example.vdr.e2e.entity.EntityHandlerRegistry;
import com.example.vdr.e2e.support.CryptoUtils;
import com.example.vdr.e2e.support.ScenarioContext;
import io.cucumber.java.en.Given;
import java.security.KeyPair;
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

    @Given("I hold a valid JWT for the DID {string}")
    public void iHoldAValidJwtForTheDid(final String didSeed) {
        registry.did().existingOnLedger(didSeed);
        final String effectiveDid = context.resolveId(didSeed);
        final KeyPair keyPair = context.requireDidKeyPair(effectiveDid);
        context.holdJwt(effectiveDid, CryptoUtils.buildDidJwt(effectiveDid, keyPair));
        LOGGER.info("Pre-existing JWT generated for {}.", effectiveDid);
    }
}

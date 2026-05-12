package com.example.vdr.e2e.steps;

import com.example.vdr.e2e.support.FabricConnectionManager;
import io.cucumber.java.After;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Scenario-level hooks. Connection setup happens in the explicit Background step. */
public class HooksSteps {

    private static final Logger LOGGER = LoggerFactory.getLogger(HooksSteps.class);

    private final FabricConnectionManager connection;

    public HooksSteps(final FabricConnectionManager connection) {
        this.connection = connection;
    }

    @After
    public void closeGateway() {
        LOGGER.info("Scenario finished, closing Gateway.");
        connection.close();
    }
}

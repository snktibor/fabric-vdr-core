package com.example.vdr.e2e.steps;

import com.example.vdr.e2e.entity.EntityHandlerRegistry;
import io.cucumber.java.en.When;

/** Steps unique to the schema lifecycle. */
public class SchemaManagementSteps {

    private final EntityHandlerRegistry registry;

    public SchemaManagementSteps(final EntityHandlerRegistry registry) {
        this.registry = registry;
    }

    @When("I create a {string} with id {string} using my held JWT")
    public void iCreateUsingHeldJwt(final String entityType, final String id) {
        if (!"schema".equals(entityType)) {
            throw new IllegalArgumentException(
                    "'using my held JWT' is currently only supported for schema; got: "
                            + entityType);
        }
        registry.schema().createWithHeldJwt(id);
    }
}

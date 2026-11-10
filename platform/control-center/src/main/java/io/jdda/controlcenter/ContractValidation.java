package io.jdda.controlcenter;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
class ContractValidation {
    private final Map<String, JsonSchema> schemas;

    ContractValidation() {
        var factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        var config = SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build();
        schemas = Map.of(
                "AgentRegistration", factory.getSchema(SchemaLocation.of(
                        "classpath:contracts/governance.schema.json#/$defs/AgentRegistration"), config),
                "ParentChange", factory.getSchema(SchemaLocation.of(
                        "classpath:contracts/governance.schema.json#/$defs/ParentChange"), config),
                "TaskPackage", factory.getSchema(SchemaLocation.of(
                        "classpath:contracts/execution.schema.json#/$defs/TaskPackage"), config),
                "ResultSubmission", factory.getSchema(SchemaLocation.of(
                        "classpath:contracts/execution.schema.json#/$defs/ResultSubmission"), config),
                "TaskAttempt", factory.getSchema(SchemaLocation.of(
                        "classpath:contracts/execution.schema.json#/$defs/TaskAttempt"), config),
                "TaskLease", factory.getSchema(SchemaLocation.of(
                        "classpath:contracts/execution.schema.json#/$defs/TaskLease"), config),
                "Artifact", factory.getSchema(SchemaLocation.of(
                        "classpath:contracts/execution.schema.json#/$defs/Artifact"), config));
    }

    void validate(String name, JsonNode value) {
        if (!schemas.get(name).validate(value).isEmpty()) {
            throw new ApiFailure(400, "INVALID_PAYLOAD", "Request does not match " + name + ".");
        }
    }

    static void identifier(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new ApiFailure(400, "INVALID_PAYLOAD", "Invalid identifier.");
        }
    }
}

package com.svatsa.escrow.escrow;

import com.fasterxml.jackson.databind.JsonNode;
import com.svatsa.escrow.chaos.AbstractToxiproxyIT;
import com.svatsa.escrow.test.ApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static com.svatsa.escrow.test.ApiClient.actor;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for project creation on real Postgres (chaos/FINDINGS.md #9).
 */
@DisplayName("Project API on Postgres")
class ProjectApiIT extends AbstractToxiproxyIT {

    private ApiClient api;

    @BeforeEach
    void client() {
        api = new ApiClient(serverPort);
    }

    @Test
    @DisplayName("a project is created and read back with its review window and milestones")
    void createAndReadProject() throws Exception {
        String run = UUID.randomUUID().toString().substring(0, 8);
        long client = api.createUser("pc-" + run, "CLIENT");
        long freelancer = api.createUser("pf-" + run, "FREELANCER");

        // review_window (INTERVAL) used to be bound as varchar, so this was always a 500.
        JsonNode created = api.expect(api.post("/api/escrow/projects", null, actor(client, "CLIENT"),
            ApiClient.projectBody(client, freelancer, "p-" + run, 10_000, 20_000)), 201);
        long projectId = created.path("id").asLong();

        // ...and reading it back had no INTERVAL -> Duration converter.
        JsonNode read = api.expect(api.get("/api/escrow/projects/" + projectId), 200);
        assertThat(read.path("project").path("reviewWindow").asText()).isEqualTo("PT120H");
        assertThat(read.path("milestones").size()).isEqualTo(2);
    }
}

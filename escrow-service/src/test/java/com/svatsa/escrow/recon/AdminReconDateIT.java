package com.svatsa.escrow.recon;

import com.svatsa.escrow.test.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/v1/admin/recon/runs without a date means "today" in Asia/Kolkata, taken from the
 * injected Clock. It used to be LocalDate.now() in the server's zone, so between 00:00 and 05:30 IST
 * a UTC server answered with yesterday's runs.
 */
@DisplayName("Admin recon runs: default date")
class AdminReconDateIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;

    @Test
    @DisplayName("at 02:00 IST (20:30 UTC the day before) the default date is the IST date")
    void defaultDateIsTodayInIndiaFromTheClock() throws Exception {
        clock.freezeAt(Instant.parse("2031-03-14T20:30:00Z")); // 2031-03-15 02:00 IST

        mockMvc.perform(get("/api/v1/admin/recon/runs")
                .header("X-Actor-Id", "1")
                .header("X-Actor-Role", "ADMIN"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.date").value("2031-03-15"));
    }

    @Test
    @DisplayName("an explicit date is used as given")
    void explicitDateWins() throws Exception {
        clock.freezeAt(Instant.parse("2031-03-14T20:30:00Z"));

        mockMvc.perform(get("/api/v1/admin/recon/runs").param("date", "2031-03-01")
                .header("X-Actor-Id", "1")
                .header("X-Actor-Role", "ADMIN"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.date").value("2031-03-01"));
    }
}

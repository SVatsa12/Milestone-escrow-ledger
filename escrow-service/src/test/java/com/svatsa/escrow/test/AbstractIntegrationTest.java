package com.svatsa.escrow.test;

import com.svatsa.escrow.common.Actor;
import com.svatsa.escrow.common.Role;
import com.svatsa.escrow.recon.InvariantChecker;
import com.svatsa.escrow.recon.Violation;
import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import eu.rekawek.toxiproxy.model.Toxic;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.lifecycle.Startables;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Base class for every integration test: real Postgres, Kafka and Redis from Testcontainers
 * (AGENTS.md: no H2, no mocks for Postgres). One set of containers per JVM, one Spring context
 * for every test class that doesn't add its own properties.
 *
 * <pre>
 *   escrow-service (this JVM) ──JDBC──▶ toxiproxy:8666 ──▶ postgres:5432
 *   escrow-service (this JVM) ──HTTP──▶ toxiproxy:8667 ──▶ mock-payout-provider (this JVM, own context)
 *   mock-payout-provider      ──JDBC──────────────────────▶ postgres:5432, schema "provider"
 * </pre>
 * The provider is the real mock-payout-provider module, started once per JVM in its own Spring
 * context on a random port ({@link ProviderControl}, which also explains the two-apps-in-one-JVM
 * clashes and how they're avoided). Tests control it only through {@link #PROVIDER}.
 *
 * Toxiproxy is a pass-through unless a chaos scenario adds toxics, so ordinary tests see a plain
 * database and provider; chaos tests reach the same proxies through {@link #PG_PROXY} /
 * {@link #PROVIDER_PROXY}.
 *
 * Before every test the provider is reset (no payouts) and set to SUCCESS. After every test: all
 * toxics are removed, the clock is reset, and {@link InvariantChecker#check()} must be empty. A
 * test that leaves the ledger inconsistent fails.
 *
 * This class used to boot an H2 "test" profile with Flyway off, so the schema never existed and
 * every integration test written against it from P3 on was @Disabled.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(AbstractIntegrationTest.TestClockConfig.class)
@TestPropertySource(properties = {
    // Defaults a test class can override with its own @TestPropertySource.
    "escrow.ratelimit.enabled=false",          // RateLimiterIT turns it on
    // The job bean only exists when enabled; keep it, but tests drive AutoReleaseJob.runOnce() themselves.
    "escrow.auto-release.enabled=true",
    "escrow.auto-release.poll-interval=3600000",
    "payouts.recovery.poll-interval=3600000",  // tests drive PayoutRecoveryJob.runOnce() themselves
})
public abstract class AbstractIntegrationTest {

    private static final int PG_PROXY_PORT = 8666;
    private static final int PROVIDER_PROXY_PORT = 8667;

    private static final Network NETWORK = Network.newNetwork();

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
        .withNetwork(NETWORK)
        .withNetworkAliases("postgres")
        .withDatabaseName("escrow")
        .withUsername("escrow")
        .withPassword("escrow")
        // Every cached Spring test context keeps a 30-connection Hikari pool open (min idle = max),
        // and the in-process payout provider has its own: the default 100 ran out.
        .withCommand("postgres", "-c", "max_connections=300");

    private static final ToxiproxyContainer TOXIPROXY = new ToxiproxyContainer("ghcr.io/shopify/toxiproxy:2.5.0")
        .withNetwork(NETWORK);

    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:latest");

    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7").withExposedPorts(6379);

    /** The real payout provider (in this JVM). */
    protected static final ProviderControl PROVIDER;

    protected static final Proxy PG_PROXY;
    protected static final Proxy PROVIDER_PROXY;

    static {
        // Postgres first: the provider needs it (its own schema, its own Flyway) before it can start.
        POSTGRES.start();
        PROVIDER = ProviderControl.start(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        // Must happen before Toxiproxy starts so it gets the host.testcontainers.internal route.
        Testcontainers.exposeHostPorts(PROVIDER.port());
        Startables.deepStart(TOXIPROXY, KAFKA, REDIS).join();
        try {
            ToxiproxyClient client = new ToxiproxyClient(TOXIPROXY.getHost(), TOXIPROXY.getControlPort());
            PG_PROXY = client.createProxy("postgres", "0.0.0.0:" + PG_PROXY_PORT, "postgres:5432");
            PROVIDER_PROXY = client.createProxy("provider", "0.0.0.0:" + PROVIDER_PROXY_PORT,
                "host.testcontainers.internal:" + PROVIDER.port());
        } catch (IOException e) {
            throw new IllegalStateException("could not create toxiproxy proxies", e);
        }
    }

    /** Where the containers are. Highest precedence: no test can point the app elsewhere. */
    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        String toxiHost = TOXIPROXY.getHost();
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://" + toxiHost + ":"
            + TOXIPROXY.getMappedPort(PG_PROXY_PORT) + "/escrow?connectTimeout=5&socketTimeout=30");
        registry.add("spring.datasource.username", () -> "escrow");
        registry.add("spring.datasource.password", () -> "escrow");
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "30");
        registry.add("spring.datasource.hikari.connection-timeout", () -> "10000");
        registry.add("spring.flyway.enabled", () -> "true");

        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));

        registry.add("payouts.provider-base-url", () -> "http://" + toxiHost + ":"
            + TOXIPROXY.getMappedPort(PROVIDER_PROXY_PORT));
        registry.add("payouts.connect-timeout", () -> "1s");
        registry.add("payouts.read-timeout", () -> "2s");
    }

    @TestConfiguration
    public static class TestClockConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock();
        }
    }

    @Autowired
    protected JdbcClient jdbcClient;

    @Autowired
    protected InvariantChecker invariantChecker;

    @Autowired
    protected MutableClock clock;

    @LocalServerPort
    protected int serverPort;

    /** Every test starts with an empty provider in SUCCESS mode (seed 1). */
    @BeforeEach
    void resetProvider() {
        PROVIDER.reset();
        PROVIDER.setMode(ProviderControl.Mode.SUCCESS);
    }

    /**
     * Runs after every integration test (JUnit runs a superclass @AfterEach after the subclass's).
     */
    @AfterEach
    void healAndCheckInvariants() throws IOException {
        removeAllToxics(PG_PROXY);
        removeAllToxics(PROVIDER_PROXY);
        clock.reset();
        List<Violation> violations = invariantChecker.check();
        assertThat(violations).as("ledger invariants must hold after every test").isEmpty();
    }

    /**
     * Freeze Redis (docker pause) to simulate an outage without stopping the shared container.
     * Always pair with {@link #unpauseRedis()} in a finally block.
     */
    protected static void pauseRedis() {
        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
    }

    protected static void unpauseRedis() {
        REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
    }

    /** Freeze Kafka (docker pause) to simulate an outage. Always unpause in a finally block. */
    protected static void pauseKafka() {
        KAFKA.getDockerClient().pauseContainerCmd(KAFKA.getContainerId()).exec();
    }

    protected static void unpauseKafka() {
        KAFKA.getDockerClient().unpauseContainerCmd(KAFKA.getContainerId()).exec();
    }

    protected static void removeAllToxics(Proxy proxy) throws IOException {
        for (Toxic toxic : proxy.toxics().getAll()) {
            toxic.remove();
        }
    }

    // --- actors for tests that call services directly ------------------------------------------

    /** The client of a project, as the actor that may fund it. */
    protected Actor clientOfProject(long projectId) {
        return Actors.client(jdbcClient.sql("SELECT client_id FROM projects WHERE id = ?")
            .param(projectId).query(Long.class).single());
    }

    /** The client of a milestone's project (approve, cancel, dispute). */
    protected Actor clientOfMilestone(long milestoneId) {
        return Actors.client(jdbcClient.sql(
                "SELECT p.client_id FROM milestones m JOIN projects p ON p.id = m.project_id WHERE m.id = ?")
            .param(milestoneId).query(Long.class).single());
    }

    /** The freelancer of a milestone's project (submit, dispute). */
    protected Actor freelancerOfMilestone(long milestoneId) {
        return Actors.freelancer(jdbcClient.sql(
                "SELECT p.freelancer_id FROM milestones m JOIN projects p ON p.id = m.project_id WHERE m.id = ?")
            .param(milestoneId).query(Long.class).single());
    }

    /** A new ADMIN user (disputes.resolved_by references users, so an admin must be a real user). */
    protected Actor newAdmin() {
        long id = jdbcClient.sql("INSERT INTO users (name, role) VALUES ('admin', 'ADMIN') RETURNING id")
            .query(Long.class).single();
        return new Actor(id, Role.ADMIN);
    }

    public static List<String> describe(List<Violation> violations) {
        return violations.stream().map(String::valueOf).toList();
    }
}

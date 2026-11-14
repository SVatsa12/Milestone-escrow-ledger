package com.svatsa.escrow.chaos;

import com.svatsa.escrow.recon.InvariantChecker;
import com.svatsa.escrow.recon.Violation;
import com.svatsa.escrow.test.MutableClock;
import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import eu.rekawek.toxiproxy.model.Toxic;
import org.junit.jupiter.api.AfterEach;
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
 * Real-infrastructure test base: Postgres and the payout provider sit behind Toxiproxy.
 * Untagged, so regression ITs for bugs the chaos suite found run in the default build;
 * the chaos scenarios extend {@link AbstractChaosIT}, which adds the "chaos" tag.
 * Both share one Spring context and one set of containers.
 *
 * Topology (one set of containers per JVM, shared by every chaos test):
 * <pre>
 *   escrow-service (this JVM) ──JDBC──▶ toxiproxy:8666 ──▶ postgres:5432
 *   escrow-service (this JVM) ──HTTP──▶ toxiproxy:8667 ──▶ FakePayoutProvider (this JVM, via host.testcontainers.internal)
 *   Kafka and Redis are connected directly; they are not the subject of these faults.
 * </pre>
 *
 * Every test ends with all toxics removed and {@link InvariantChecker#check()} empty.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(AbstractToxiproxyIT.ChaosTestConfig.class)
public abstract class AbstractToxiproxyIT {

    private static final int PG_PROXY_PORT = 8666;
    private static final int PROVIDER_PROXY_PORT = 8667;

    protected static final FakePayoutProvider PROVIDER = FakePayoutProvider.start();

    private static final Network NETWORK = Network.newNetwork();

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
        .withNetwork(NETWORK)
        .withNetworkAliases("postgres")
        .withDatabaseName("escrow")
        .withUsername("escrow")
        .withPassword("escrow");

    private static final ToxiproxyContainer TOXIPROXY = new ToxiproxyContainer("ghcr.io/shopify/toxiproxy:2.5.0")
        .withNetwork(NETWORK);

    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:latest");

    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7").withExposedPorts(6379);

    protected static final Proxy PG_PROXY;
    protected static final Proxy PROVIDER_PROXY;

    static {
        // Must happen before Toxiproxy starts so it gets the host.testcontainers.internal route.
        Testcontainers.exposeHostPorts(PROVIDER.port());
        Startables.deepStart(POSTGRES, TOXIPROXY, KAFKA, REDIS).join();
        try {
            ToxiproxyClient client = new ToxiproxyClient(TOXIPROXY.getHost(), TOXIPROXY.getControlPort());
            PG_PROXY = client.createProxy("postgres", "0.0.0.0:" + PG_PROXY_PORT, "postgres:5432");
            PROVIDER_PROXY = client.createProxy("provider", "0.0.0.0:" + PROVIDER_PROXY_PORT,
                "host.testcontainers.internal:" + PROVIDER.port());
        } catch (IOException e) {
            throw new IllegalStateException("could not create toxiproxy proxies", e);
        }
    }

    @DynamicPropertySource
    static void chaosProperties(DynamicPropertyRegistry registry) {
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
        // Tests drive recovery explicitly with runOnce().
        registry.add("payouts.recovery.poll-interval", () -> "3600000");

        registry.add("escrow.auto-release.enabled", () -> "false");
        // Rate limiting is not what these scenarios test; a 429 would hide the fault under test.
        registry.add("escrow.ratelimit.enabled", () -> "false");
    }

    @TestConfiguration
    public static class ChaosTestConfig {
        @Bean
        @Primary
        MutableClock chaosClock() {
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

    @AfterEach
    void healNetworkAndCheckInvariants() throws IOException {
        removeAllToxics(PG_PROXY);
        removeAllToxics(PROVIDER_PROXY);
        clock.reset();
        List<Violation> violations = invariantChecker.check();
        assertThat(violations).as("ledger invariants after chaos").isEmpty();
    }

    protected static void removeAllToxics(Proxy proxy) throws IOException {
        for (Toxic toxic : proxy.toxics().getAll()) {
            toxic.remove();
        }
    }

    public static List<String> describe(List<Violation> violations) {
        return violations.stream().map(String::valueOf).toList();
    }
}

package com.svatsa.escrow.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.svatsa.escrow.chaos.FakePayoutProvider;
import com.svatsa.escrow.escrow.AutoReleaseJob;
import com.svatsa.escrow.escrow.MilestoneRepository;
import com.svatsa.escrow.escrow.MilestoneRepository.Transition;
import com.svatsa.escrow.escrow.MilestoneState;
import com.svatsa.escrow.payouts.PayoutService;
import com.svatsa.escrow.test.AbstractIntegrationTest;
import com.svatsa.escrow.test.Actors;
import com.svatsa.escrow.test.ApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static com.svatsa.escrow.test.ApiClient.actor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Actor and role checks (AGENTS.md: X-Actor-Id / X-Actor-Role, checked in the service layer).
 * For every endpoint the right actor succeeds and every wrong one gets 403 FORBIDDEN, and a
 * rejected request changes nothing. Missing or invalid actor headers are 401 UNAUTHENTICATED.
 *
 * The world: client A and freelancer A share project A; client B and freelancer B share project B.
 */
@DisplayName("Actor and role checks")
class AuthorizationIT extends AbstractIntegrationTest {

    @Autowired private MilestoneRepository milestoneRepository;
    @Autowired private PayoutService payoutService;
    @Autowired private AutoReleaseJob autoReleaseJob;

    private ApiClient api;
    private String run;
    private long clientA;
    private long freelancerA;
    private long clientB;
    private long freelancerB;

    @BeforeEach
    void world() throws Exception {
        api = new ApiClient(serverPort);
        run = UUID.randomUUID().toString().substring(0, 8);
        clientA = api.createUser("ca-" + run, "CLIENT");
        freelancerA = api.createUser("fa-" + run, "FREELANCER");
        clientB = api.createUser("cb-" + run, "CLIENT");
        freelancerB = api.createUser("fb-" + run, "FREELANCER");
        deposit(clientA, 1_000_000);
        deposit(clientB, 1_000_000);
    }

    // --- escrow ----------------------------------------------------------------------------------

    @Test
    @DisplayName("create project: the named client (or an admin); another client or a freelancer is 403")
    void createProject() throws Exception {
        String body = ApiClient.projectBody(clientA, freelancerA, "p-" + run, 10_000);
        forbidden(api.post("/api/escrow/projects", null, actor(clientB, "CLIENT"), body));
        forbidden(api.post("/api/escrow/projects", null, actor(freelancerA, "FREELANCER"), body));
        api.expect(api.post("/api/escrow/projects", null, actor(clientA, "CLIENT"), body), 201);
        api.expect(api.post("/api/escrow/projects", null, ApiClient.admin(), body), 201);
    }

    @Test
    @DisplayName("fund: only the project's client")
    void fund() throws Exception {
        long projectA = project(clientA, freelancerA, 10_000, 20_000);
        String path = "/api/escrow/projects/" + projectA + "/fund";

        forbidden(api.post(path, key("f1"), actor(freelancerA, "FREELANCER"), ""));
        forbidden(api.post(path, key("f2"), actor(clientB, "CLIENT"), ""));
        forbidden(api.post(path, key("f3"), ApiClient.admin(), ""));
        // A user id that is the project's client but claims another role is still not the client.
        forbidden(api.post(path, key("f4"), actor(clientA, "FREELANCER"), ""));
        assertThat(states(projectA)).containsOnly(MilestoneState.DRAFT);
        assertThat(walletBalance("CLIENT_WALLET:" + clientA)).isEqualTo(1_000_000);

        api.expect(api.post(path, key("f5"), actor(clientA, "CLIENT"), ""), 200);
        assertThat(states(projectA)).containsOnly(MilestoneState.FUNDED);
    }

    @Test
    @DisplayName("submit: only the project's freelancer; freelancer of project B can't submit on project A")
    void submit() throws Exception {
        long projectA = fundedProject(clientA, freelancerA, 10_000);
        long projectB = fundedProject(clientB, freelancerB, 10_000);
        long milestoneA = milestones(projectA).get(0);
        String path = "/api/escrow/milestones/" + milestoneA + "/submit";

        forbidden(api.post(path, key("s1"), actor(freelancerB, "FREELANCER"), ""));
        forbidden(api.post(path, key("s2"), actor(clientA, "CLIENT"), ""));
        forbidden(api.post(path, key("s3"), ApiClient.admin(), ""));
        assertThat(state(milestoneA)).isEqualTo(MilestoneState.FUNDED);

        api.expect(api.post(path, key("s4"), actor(freelancerA, "FREELANCER"), ""), 200);
        assertThat(state(milestoneA)).isEqualTo(MilestoneState.SUBMITTED);

        // ...and freelancer A can't submit on project B either.
        long milestoneB = milestones(projectB).get(0);
        forbidden(api.post("/api/escrow/milestones/" + milestoneB + "/submit", key("s5"), actor(freelancerA, "FREELANCER"), ""));
        assertThat(state(milestoneB)).isEqualTo(MilestoneState.FUNDED);
    }

    @Test
    @DisplayName("approve: only the project's client; a rejected approve releases no money")
    void approve() throws Exception {
        long milestoneA = submittedMilestone(clientA, freelancerA, 10_000);
        String path = "/api/escrow/milestones/" + milestoneA + "/approve";

        forbidden(api.post(path, key("a1"), actor(freelancerA, "FREELANCER"), ""));
        forbidden(api.post(path, key("a2"), actor(clientB, "CLIENT"), ""));
        forbidden(api.post(path, key("a3"), ApiClient.admin(), ""));
        assertThat(state(milestoneA)).isEqualTo(MilestoneState.SUBMITTED);
        assertThat(walletBalance("FREELANCER_WALLET:" + freelancerA)).isZero();

        api.expect(api.post(path, key("a4"), actor(clientA, "CLIENT"), ""), 200);
        assertThat(state(milestoneA)).isEqualTo(MilestoneState.RELEASED);
    }

    @Test
    @DisplayName("cancel: only the project's client")
    void cancel() throws Exception {
        long projectA = project(clientA, freelancerA, 10_000);
        long milestoneA = milestones(projectA).get(0);
        String path = "/api/escrow/milestones/" + milestoneA + "/cancel";

        forbidden(api.post(path, key("c1"), actor(freelancerA, "FREELANCER"), ""));
        forbidden(api.post(path, key("c2"), actor(clientB, "CLIENT"), ""));
        forbidden(api.post(path, key("c3"), ApiClient.admin(), ""));
        assertThat(state(milestoneA)).isEqualTo(MilestoneState.DRAFT);

        api.expect(api.post(path, key("c4"), actor(clientA, "CLIENT"), ""), 200);
        assertThat(state(milestoneA)).isEqualTo(MilestoneState.CANCELLED);
    }

    // --- disputes --------------------------------------------------------------------------------

    @Test
    @DisplayName("open dispute: the project's client or freelancer; outsiders and admins are 403")
    void openDispute() throws Exception {
        long projectA = fundedProject(clientA, freelancerA, 10_000, 20_000);
        List<Long> ms = milestones(projectA);
        for (long m : ms) {
            submit(m, freelancerA);
        }

        for (String[] outsider : List.of(actor(clientB, "CLIENT"), actor(freelancerB, "FREELANCER"), ApiClient.admin())) {
            forbidden(api.post("/api/escrow/disputes", key("d"), outsider, disputeBody(ms.get(0))));
        }
        assertThat(state(ms.get(0))).isEqualTo(MilestoneState.SUBMITTED);

        api.expect(api.post("/api/escrow/disputes", key("d1"), actor(clientA, "CLIENT"), disputeBody(ms.get(0))), 201);
        api.expect(api.post("/api/escrow/disputes", key("d2"), actor(freelancerA, "FREELANCER"), disputeBody(ms.get(1))), 201);
        assertThat(states(projectA)).containsOnly(MilestoneState.DISPUTED);
    }

    @Test
    @DisplayName("resolve dispute: ADMIN only")
    void resolveDispute() throws Exception {
        long milestoneA = submittedMilestone(clientA, freelancerA, 10_000);
        long disputeId = api.expect(api.post("/api/escrow/disputes", key("d"), actor(clientA, "CLIENT"),
            disputeBody(milestoneA)), 201).path("id").asLong();
        String path = "/api/escrow/disputes/" + disputeId + "/resolve";
        String body = "{\"freelancerSharePaise\":6000,\"clientSharePaise\":4000,\"resolutionNote\":\"split\"}";

        forbidden(api.post(path, key("r1"), actor(clientA, "CLIENT"), body));
        forbidden(api.post(path, key("r2"), actor(freelancerA, "FREELANCER"), body));
        assertThat(state(milestoneA)).isEqualTo(MilestoneState.DISPUTED);

        Actor admin = newAdmin();
        api.expect(api.post(path, key("r3"), actor(admin.id(), "ADMIN"), body), 200);
        assertThat(state(milestoneA)).isEqualTo(MilestoneState.RESOLVED);
    }

    // --- deposits and payouts --------------------------------------------------------------------

    @Test
    @DisplayName("deposit: the client themselves; a client can't top up someone else's wallet")
    void deposit() throws Exception {
        String own = "{\"amountPaise\":5000}";
        String forB = "{\"amountPaise\":5000,\"clientId\":" + clientB + "}";

        forbidden(api.post("/api/v1/deposits", key("dp1"), actor(clientA, "CLIENT"), forB));
        forbidden(api.post("/api/v1/deposits", key("dp2"), actor(freelancerA, "FREELANCER"), own));
        forbidden(api.post("/api/v1/deposits", key("dp3"), ApiClient.admin(), forB));
        assertThat(depositCount(clientB)).isZero();

        JsonNode mine = api.expect(api.post("/api/v1/deposits", key("dp4"), actor(clientA, "CLIENT"), own), 200);
        assertThat(mine.path("razorpayOrderId").asText()).isNotBlank();
        api.expect(api.post("/api/v1/deposits", key("dp5"), actor(clientB, "CLIENT"), forB), 200);
        assertThat(depositCount(clientB)).isEqualTo(1);
    }

    @Test
    @DisplayName("payout: the freelancer themselves; clients and admins are 403")
    void payout() throws Exception {
        PROVIDER.reset(FakePayoutProvider.Mode.SUCCESS, 1, 0);
        approve(submittedMilestone(clientA, freelancerA, 10_000), clientA);
        long before = walletBalance("FREELANCER_WALLET:" + freelancerA);

        forbidden(api.post("/api/v1/payouts", key("p1"), actor(clientA, "CLIENT"), "{\"amountPaise\":1000}"));
        forbidden(api.post("/api/v1/payouts", key("p2"), ApiClient.admin(), "{\"amountPaise\":1000}"));
        assertThat(walletBalance("FREELANCER_WALLET:" + freelancerA)).isEqualTo(before);

        JsonNode payout = api.expect(api.post("/api/v1/payouts", key("p3"), actor(freelancerA, "FREELANCER"),
            "{\"amountPaise\":1000}"), 202);
        assertThat(payout.path("state").asText()).isEqualTo("CONFIRMED");
        assertThat(walletBalance("FREELANCER_WALLET:" + freelancerA)).isEqualTo(before - 1000);
    }

    @Test
    @DisplayName("payout service: a freelancer can't pay out for someone else or from someone else's wallet")
    void payoutOnlyFromOwnWallet() throws Exception {
        approve(submittedMilestone(clientA, freelancerA, 10_000), clientA);
        long walletA = accountId("FREELANCER_WALLET:" + freelancerA);
        long walletB = accountId("FREELANCER_WALLET:" + freelancerB);
        long before = walletBalance("FREELANCER_WALLET:" + freelancerA);

        // Freelancer B asking for freelancer A's money, either way round.
        assertForbidden(() -> payoutService.requestPayout(Actors.freelancer(freelancerB), freelancerA, walletA, 500, key("x1")));
        assertForbidden(() -> payoutService.requestPayout(Actors.freelancer(freelancerB), freelancerB, walletA, 500, key("x2")));
        // A client's wallet is not a payout source.
        assertForbidden(() -> payoutService.requestPayout(Actors.freelancer(freelancerA), freelancerA,
            accountId("CLIENT_WALLET:" + clientA), 500, key("x3")));
        // Nor is another freelancer's wallet, even for its owner id mismatch the other way.
        assertForbidden(() -> payoutService.requestPayout(Actors.freelancer(freelancerA), freelancerA, walletB, 500, key("x4")));
        assertThat(walletBalance("FREELANCER_WALLET:" + freelancerA)).isEqualTo(before);
    }

    // --- admin endpoints -------------------------------------------------------------------------

    @Test
    @DisplayName("admin endpoints: ADMIN only")
    void adminEndpoints() throws Exception {
        long walletA = accountId("CLIENT_WALLET:" + clientA);
        String simulate = "{\"clientAccountId\":" + walletA + ",\"amountPaise\":100}";
        String mismatches = "/api/v1/admin/recon/runs/" + UUID.randomUUID() + "/mismatches";

        for (String[] notAdmin : List.of(actor(clientA, "CLIENT"), actor(freelancerA, "FREELANCER"))) {
            forbidden(api.get("/api/v1/admin/invariants", notAdmin));
            forbidden(api.get("/api/v1/admin/recon/runs", notAdmin));
            forbidden(api.get(mismatches, notAdmin));
            forbidden(api.post("/api/v1/admin/recon/run?date=2020-01-01", null, notAdmin, ""));
            forbidden(api.post("/api/v1/admin/deposits/simulate", key("sim"), notAdmin, simulate));
        }
        assertThat(walletBalance("CLIENT_WALLET:" + clientA)).isEqualTo(1_000_000);

        api.expect(api.get("/api/v1/admin/invariants", ApiClient.admin()), 200);
        api.expect(api.get("/api/v1/admin/recon/runs", ApiClient.admin()), 200);
        api.expect(api.get(mismatches, ApiClient.admin()), 404);
        api.expect(api.post("/api/v1/admin/recon/run?date=2020-01-01", null, ApiClient.admin(), ""), 200);
        api.expect(api.post("/api/v1/admin/deposits/simulate", key("sim-ok"), ApiClient.admin(), simulate), 201);
    }

    // --- ledger endpoints ------------------------------------------------------------------------

    @Test
    @DisplayName("transfer: ADMIN only; a client can't move money out of any wallet, even their own")
    void transfer() throws Exception {
        long walletA = accountId("CLIENT_WALLET:" + clientA);
        long walletB = accountId("CLIENT_WALLET:" + clientB);
        String body = "{\"fromAccountId\":" + walletA + ",\"toAccountId\":" + walletB + ",\"amountPaise\":500,\"memo\":\"x\"}";

        forbidden(api.post("/api/v1/transfers", key("t1"), actor(clientA, "CLIENT"), body));
        forbidden(api.post("/api/v1/transfers", key("t2"), actor(clientB, "CLIENT"), body));
        forbidden(api.post("/api/v1/transfers", key("t3"), actor(freelancerA, "FREELANCER"), body));
        unauthenticated(api.postWithoutActor("/api/v1/transfers", key("t4"), body));
        assertThat(walletBalance("CLIENT_WALLET:" + clientA)).isEqualTo(1_000_000);
        assertThat(walletBalance("CLIENT_WALLET:" + clientB)).isEqualTo(1_000_000);

        api.expect(api.transfer(key("t5"), walletA, walletB, 500), 201);
        assertThat(walletBalance("CLIENT_WALLET:" + clientB)).isEqualTo(1_000_500);
    }

    @Test
    @DisplayName("create account: ADMIN only")
    void createAccount() throws Exception {
        long owner = 900_000_000L + Math.abs(UUID.randomUUID().getMostSignificantBits() % 99_999_999L);
        String body = "{\"type\":\"CLIENT_WALLET\",\"ownerId\":" + owner + "}";

        forbidden(api.post("/api/v1/accounts", null, actor(clientA, "CLIENT"), body));
        forbidden(api.post("/api/v1/accounts", null, actor(freelancerA, "FREELANCER"), body));
        unauthenticated(api.postWithoutActor("/api/v1/accounts", null, body));
        assertThat(accountCount("CLIENT_WALLET:" + owner)).as("nothing created").isZero();

        api.expect(api.post("/api/v1/accounts", null, ApiClient.admin(), body), 201);
        assertThat(accountCount("CLIENT_WALLET:" + owner)).isEqualTo(1);
    }

    // --- authentication --------------------------------------------------------------------------

    @Test
    @DisplayName("missing or invalid actor headers are 401 UNAUTHENTICATED; SYSTEM can't come from headers")
    void missingOrInvalidHeadersAre401() throws Exception {
        long projectA = project(clientA, freelancerA, 10_000);
        String fund = "/api/escrow/projects/" + projectA + "/fund";

        unauthenticated(api.postWithoutActor(fund, key("u1"), ""));
        unauthenticated(api.post(fund, key("u2"), new String[]{"X-Actor-Id", String.valueOf(clientA)}, ""));
        unauthenticated(api.post(fund, key("u3"), new String[]{"X-Actor-Role", "CLIENT"}, ""));
        unauthenticated(api.post(fund, key("u4"), new String[]{"X-Actor-Id", "abc", "X-Actor-Role", "CLIENT"}, ""));
        unauthenticated(api.post(fund, key("u5"), new String[]{"X-Actor-Id", "0", "X-Actor-Role", "CLIENT"}, ""));
        unauthenticated(api.post(fund, key("u6"), actor(clientA, "client"), ""));
        unauthenticated(api.post(fund, key("u7"), actor(clientA, "ROOT"), ""));
        unauthenticated(api.post(fund, key("u8"), actor(clientA, "SYSTEM"), ""));
        unauthenticated(api.get("/api/v1/admin/invariants"));
        unauthenticated(api.get("/api/v1/admin/invariants", actor(1, "SYSTEM")));
        unauthenticated(api.post("/api/v1/payouts", key("u9"), new String[0], "{\"amountPaise\":100}"));
        assertThat(states(projectA)).containsOnly(MilestoneState.DRAFT);
    }

    // --- milestone_transitions -------------------------------------------------------------------

    @Test
    @DisplayName("every transition records the actor's id and role, and the journal entry that moved money")
    void transitionsRecordTheActor() throws Exception {
        long milestone = submittedMilestone(clientA, freelancerA, 10_000);
        approve(milestone, clientA);

        List<Transition> t = milestoneRepository.findTransitions(milestone);
        assertThat(t).extracting(Transition::to).containsExactly(
            MilestoneState.FUNDED, MilestoneState.SUBMITTED, MilestoneState.APPROVED, MilestoneState.RELEASED);
        assertThat(t).extracting(Transition::actorRole).containsExactly("CLIENT", "FREELANCER", "CLIENT", "CLIENT");
        assertThat(t).extracting(Transition::actorId).containsExactly(clientA, freelancerA, clientA, clientA);
        assertThat(t.get(0).journalEntryId()).as("fund entry").isNotNull();
        assertThat(t.get(1).journalEntryId()).as("submit moves no money").isNull();
        assertThat(t.get(3).journalEntryId()).as("release entry").isNotNull();
    }

    @Test
    @DisplayName("auto-release is recorded as SYSTEM with no user id")
    void autoReleaseIsRecordedAsSystem() throws Exception {
        long milestone = submittedMilestone(clientA, freelancerA, 10_000);
        clock.advance(Duration.ofDays(6)); // review window is 5 days
        autoReleaseJob.runOnce();

        assertThat(state(milestone)).isEqualTo(MilestoneState.RELEASED);
        List<Transition> t = milestoneRepository.findTransitions(milestone);
        assertThat(t).extracting(Transition::to).endsWith(MilestoneState.APPROVED, MilestoneState.RELEASED);
        Transition release = t.get(t.size() - 1);
        assertThat(release.actorRole()).isEqualTo("SYSTEM");
        assertThat(release.actorId()).isNull();
    }

    // --- helpers ---------------------------------------------------------------------------------

    private void forbidden(HttpResponse<String> response) throws Exception {
        assertThat(api.expect(response, 403).path("code").asText()).isEqualTo("FORBIDDEN");
    }

    private void unauthenticated(HttpResponse<String> response) throws Exception {
        assertThat(api.expect(response, 401).path("code").asText()).isEqualTo("UNAUTHENTICATED");
    }

    private static void assertForbidden(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
            .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.getCode()).isEqualTo("FORBIDDEN"));
    }

    private String key(String prefix) {
        return prefix + "-" + run + "-" + UUID.randomUUID();
    }

    private void deposit(long clientId, long amount) throws Exception {
        api.expect(api.post("/api/v1/admin/deposits/simulate", key("seed"), ApiClient.admin(),
            "{\"clientAccountId\":" + accountId("CLIENT_WALLET:" + clientId) + ",\"amountPaise\":" + amount + "}"), 201);
    }

    private long project(long client, long freelancer, long... amounts) throws Exception {
        return api.expect(api.post("/api/escrow/projects", null, actor(client, "CLIENT"),
            ApiClient.projectBody(client, freelancer, "p-" + run, amounts)), 201).path("id").asLong();
    }

    private long fundedProject(long client, long freelancer, long... amounts) throws Exception {
        long id = project(client, freelancer, amounts);
        api.expect(api.post("/api/escrow/projects/" + id + "/fund", key("fund"), actor(client, "CLIENT"), ""), 200);
        return id;
    }

    private long submittedMilestone(long client, long freelancer, long amount) throws Exception {
        long m = milestones(fundedProject(client, freelancer, amount)).get(0);
        submit(m, freelancer);
        return m;
    }

    private void submit(long milestone, long freelancer) throws Exception {
        api.expect(api.post("/api/escrow/milestones/" + milestone + "/submit", key("sub"), actor(freelancer, "FREELANCER"), ""), 200);
    }

    private void approve(long milestone, long client) throws Exception {
        api.expect(api.post("/api/escrow/milestones/" + milestone + "/approve", key("appr"), actor(client, "CLIENT"), ""), 200);
    }

    private static String disputeBody(long milestoneId) {
        return "{\"milestoneId\":" + milestoneId + ",\"reason\":\"not done\"}";
    }

    private List<Long> milestones(long projectId) {
        return jdbcClient.sql("SELECT id FROM milestones WHERE project_id = ? ORDER BY seq").param(projectId)
            .query(Long.class).list();
    }

    private List<MilestoneState> states(long projectId) {
        return milestoneRepository.findByProjectId(projectId).stream().map(m -> m.state()).toList();
    }

    private MilestoneState state(long milestoneId) {
        return milestoneRepository.findById(milestoneId).orElseThrow().state();
    }

    private long accountId(String code) {
        return jdbcClient.sql("SELECT id FROM accounts WHERE code = ?").param(code).query(Long.class).single();
    }

    private long walletBalance(String code) {
        return jdbcClient.sql("SELECT balance FROM accounts WHERE code = ?").param(code).query(Long.class).single();
    }

    private long accountCount(String code) {
        return jdbcClient.sql("SELECT count(*) FROM accounts WHERE code = ?").param(code).query(Long.class).single();
    }

    private long depositCount(long clientId) {
        return jdbcClient.sql("SELECT count(*) FROM deposits WHERE client_id = ?").param(clientId).query(Long.class).single();
    }
}

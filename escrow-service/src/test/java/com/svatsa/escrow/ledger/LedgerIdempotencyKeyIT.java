package com.svatsa.escrow.ledger;

import com.svatsa.escrow.chaos.FakePayoutProvider;
import com.svatsa.escrow.common.Actor;
import com.svatsa.escrow.common.Money;
import com.svatsa.escrow.dispute.DisputeService;
import com.svatsa.escrow.escrow.EscrowConfigurationProperties;
import com.svatsa.escrow.escrow.EscrowService;
import com.svatsa.escrow.escrow.MilestoneRepository;
import com.svatsa.escrow.escrow.MilestoneState;
import com.svatsa.escrow.payouts.Payout;
import com.svatsa.escrow.payouts.PayoutRepository;
import com.svatsa.escrow.payouts.PayoutService;
import com.svatsa.escrow.payouts.PayoutState;
import com.svatsa.escrow.recon.Violation;
import com.svatsa.escrow.test.AbstractIntegrationTest;
import com.svatsa.escrow.test.Actors;
import com.svatsa.escrow.test.ApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static com.svatsa.escrow.test.ApiClient.actor;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Invariant 8 at the ledger: every money flow posts with its AGENTS.md business key, so one
 * business action is one journal entry, whatever HTTP Idempotency-Key the caller sent.
 *
 * Each test does the action once over HTTP (key K1), repeats it with a different key K2 (the
 * state machine rejects it), and then, where the schema allows, puts the row back in its old
 * state behind the service's back and calls the service again with a third key. That last call
 * gets past every state guard, so only the ledger key stands between it and a second posting.
 * Before this change these flows posted with the caller's key, and that call moved money twice.
 */
@DisplayName("Ledger business idempotency keys (invariant 8)")
class LedgerIdempotencyKeyIT extends AbstractIntegrationTest {

    @Autowired private EscrowService escrowService;
    @Autowired private DisputeService disputeService;
    @Autowired private PayoutService payoutService;
    @Autowired private PayoutRepository payoutRepository;
    @Autowired private MilestoneRepository milestoneRepository;
    @Autowired private LedgerService ledgerService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private TransactionTemplate tx;
    @Autowired private EscrowConfigurationProperties escrowConfig;

    private ApiClient api;
    private String run;
    private long client;
    private long freelancer;

    @BeforeEach
    void world() throws Exception {
        api = new ApiClient(serverPort);
        run = UUID.randomUUID().toString().substring(0, 8);
        client = api.createUser("ik-c-" + run, "CLIENT");
        freelancer = api.createUser("ik-f-" + run, "FREELANCER");
        api.expect(api.post("/api/v1/admin/deposits/simulate", key(), ApiClient.admin(),
            "{\"clientAccountId\":" + accountId("CLIENT_WALLET:" + client) + ",\"amountPaise\":1000000}"), 201);
    }

    @Test
    @DisplayName("fund: fund:project:{id}, one entry")
    void fund() throws Exception {
        long project = project(10_000, 20_000);
        fundOverHttp(project);
        rejected(api.post(fundPath(project), key(), actor(client, "CLIENT"), ""));

        // Bypass: milestones back to DRAFT, then fund again through the service.
        jdbcClient.sql("UPDATE milestones SET state = 'DRAFT' WHERE project_id = ?").param(project).update();
        escrowService.fundProject(clientOfProject(project), project, key());

        assertOneEntry(LedgerKeys.FUND, "PROJECT", project, LedgerKeys.fund(project));
        assertThat(balance("CLIENT_WALLET:" + client)).as("charged once").isEqualTo(1_000_000 - 30_000);
    }

    @Test
    @DisplayName("release: release:milestone:{id}, one entry")
    void release() throws Exception {
        long project = project(10_000, 20_000);   // the second milestone keeps enough in escrow for a double release
        fundOverHttp(project);
        long m = milestones(project).get(0);
        submit(m);
        api.expect(api.post(approvePath(m), key(), actor(client, "CLIENT"), ""), 200);
        rejected(api.post(approvePath(m), key(), actor(client, "CLIENT"), ""));

        setState(m, MilestoneState.SUBMITTED);
        escrowService.approveMilestone(clientOfMilestone(m), m, key());

        assertOneEntry(LedgerKeys.RELEASE, "MILESTONE", m, LedgerKeys.release(m));
        long fee = Money.calculateFee(10_000, escrowConfig.getFeeBps());
        assertThat(balance("FREELANCER_WALLET:" + freelancer)).as("paid once").isEqualTo(10_000 - fee);
    }

    @Test
    @DisplayName("auto-release and a manual approve share release:milestone:{id}")
    void autoReleaseUsesTheSameKey() throws Exception {
        long project = project(10_000, 20_000);
        fundOverHttp(project);
        long m = milestones(project).get(0);
        submit(m);
        escrowService.approveMilestone(Actor.SYSTEM, m, "job-run-1");   // what AutoReleaseJob does
        setState(m, MilestoneState.SUBMITTED);
        escrowService.approveMilestone(clientOfMilestone(m), m, key());

        assertOneEntry(LedgerKeys.RELEASE, "MILESTONE", m, LedgerKeys.release(m));
    }

    @Test
    @DisplayName("refund: refund:milestone:{id}, one entry")
    void refund() throws Exception {
        long project = project(10_000, 20_000);
        fundOverHttp(project);
        long m = milestones(project).get(0);
        clock.advance(Duration.ofDays(38));   // due in 30 days, 7 days' grace
        api.expect(api.post(cancelPath(m), key(), actor(client, "CLIENT"), ""), 200);
        rejected(api.post(cancelPath(m), key(), actor(client, "CLIENT"), ""));

        setState(m, MilestoneState.FUNDED);
        escrowService.cancelMilestone(clientOfMilestone(m), m, key(), Duration.ofDays(7));

        assertOneEntry(LedgerKeys.REFUND, "MILESTONE", m, LedgerKeys.refund(m));
        assertThat(balance("CLIENT_WALLET:" + client)).as("refunded once").isEqualTo(1_000_000 - 20_000);
    }

    @Test
    @DisplayName("dispute freeze: dispute-freeze:milestone:{id}, one entry")
    void disputeFreeze() throws Exception {
        long project = project(10_000, 20_000);
        fundOverHttp(project);
        long m = milestones(project).get(0);
        submit(m);
        api.expect(api.post("/api/escrow/disputes", key(), actor(client, "CLIENT"), disputeBody(m)), 201);
        rejected(api.post("/api/escrow/disputes", key(), actor(freelancer, "FREELANCER"), disputeBody(m)));

        // The disputes row (one per milestone) also blocks a second freeze, so the service can't
        // be driven past it; post the same business action straight to the ledger instead.
        assertReplays(new PostingRequest(LedgerKeys.DISPUTE_FREEZE, "MILESTONE", String.valueOf(m),
            LedgerKeys.disputeFreeze(m), "again", List.of(
                new Leg(accountId("ESCROW:" + project), -10_000), new Leg(accountId("DISPUTE_HOLD:" + project), 10_000))));
        assertOneEntry(LedgerKeys.DISPUTE_FREEZE, "MILESTONE", m, LedgerKeys.disputeFreeze(m));
        assertThat(balance("DISPUTE_HOLD:" + project)).isEqualTo(10_000);
    }

    @Test
    @DisplayName("dispute resolve: dispute-resolve:{id}, one entry; a second resolve is 409")
    void disputeResolve() throws Exception {
        long project = project(10_000, 20_000);
        fundOverHttp(project);
        long m = milestones(project).get(0);
        submit(m);
        long dispute = api.expect(api.post("/api/escrow/disputes", key(), actor(client, "CLIENT"), disputeBody(m)), 201)
            .path("id").asLong();
        Actor admin = newAdmin();
        String body = "{\"freelancerSharePaise\":0,\"clientSharePaise\":10000,\"resolutionNote\":\"refund\"}";
        String path = "/api/escrow/disputes/" + dispute + "/resolve";
        api.expect(api.post(path, key(), actor(admin.id(), "ADMIN"), body), 200);
        // A resolved dispute used to throw IllegalStateException here: a 500.
        assertThat(api.expect(api.post(path, key(), actor(admin.id(), "ADMIN"), body), 409).path("code").asText())
            .isEqualTo("INVALID_STATE_TRANSITION");

        // A resolved dispute row can't be reopened (DB trigger), so replay at the ledger.
        assertReplays(new PostingRequest(LedgerKeys.DISPUTE_RESOLVE, "DISPUTE", String.valueOf(dispute),
            LedgerKeys.disputeResolve(dispute), "again", List.of(
                new Leg(accountId("DISPUTE_HOLD:" + project), -10_000), new Leg(accountId("CLIENT_WALLET:" + client), 10_000))));
        assertOneEntry(LedgerKeys.DISPUTE_RESOLVE, "DISPUTE", dispute, LedgerKeys.disputeResolve(dispute));
        assertThat(balance("CLIENT_WALLET:" + client)).isEqualTo(1_000_000 - 30_000 + 10_000);
    }

    @Test
    @DisplayName("payout reserve and confirm: payout:reserve|confirm:{id}, one entry each")
    void payoutConfirm() throws Exception {
        PROVIDER.reset(FakePayoutProvider.Mode.SUCCESS, 1, 0);
        earn();
        long wallet = accountId("FREELANCER_WALLET:" + freelancer);
        long before = balance("FREELANCER_WALLET:" + freelancer);
        Payout payout = payoutService.requestPayout(Actors.freelancer(freelancer), freelancer, wallet, 1_000, key());
        assertThat(payout.state()).isEqualTo(PayoutState.CONFIRMED);
        assertThat(payoutService.confirmPayout(payout)).as("already settled").isFalse();

        // Bypass the settle guard: back to PROCESSING, confirm again.
        setPayoutState(payout, PayoutState.PROCESSING);
        assertThat(payoutService.confirmPayout(payout)).isTrue();

        assertReplays(new PostingRequest(LedgerKeys.PAYOUT_RESERVE, "PAYOUT", payout.id().toString(),
            LedgerKeys.payoutReserve(payout.id()), "again", List.of(
                new Leg(wallet, -1_000), new Leg(accountId("PAYOUT_IN_FLIGHT"), 1_000))));
        assertOneEntry(LedgerKeys.PAYOUT_RESERVE, "PAYOUT", payout.id(), LedgerKeys.payoutReserve(payout.id()));
        assertOneEntry(LedgerKeys.PAYOUT_CONFIRM, "PAYOUT", payout.id(), LedgerKeys.payoutConfirm(payout.id()));
        assertThat(balance("FREELANCER_WALLET:" + freelancer)).isEqualTo(before - 1_000);
    }

    @Test
    @DisplayName("payout reverse and cancel share payout:reverse:{id}, one entry")
    void payoutReverse() throws Exception {
        PROVIDER.reset(FakePayoutProvider.Mode.FAIL, 1, 0);
        earn();
        long wallet = accountId("FREELANCER_WALLET:" + freelancer);
        long before = balance("FREELANCER_WALLET:" + freelancer);
        Payout payout = payoutService.requestPayout(Actors.freelancer(freelancer), freelancer, wallet, 1_000, key());
        assertThat(payout.state()).isEqualTo(PayoutState.FAILED);

        setPayoutState(payout, PayoutState.PROCESSING);
        assertThat(payoutService.reversePayout(payout, PayoutState.FAILED, "FAILED", "again")).isTrue();
        setPayoutState(payout, PayoutState.PROCESSING);
        assertThat(payoutService.reversePayout(payout, PayoutState.FAILED, "FAILED", "and again")).isTrue();

        assertThat(jdbcClient.sql("SELECT count(*) FROM journal_entries WHERE reference_type = 'PAYOUT' AND reference_id = ?"
                + " AND type IN ('PAYOUT_REVERSE', 'PAYOUT_CANCEL')")
            .param(payout.id().toString()).query(Long.class).single()).isEqualTo(1);
        assertThat(entryKey("PAYOUT", payout.id())).contains(LedgerKeys.payoutReverse(payout.id()));
        assertThat(balance("FREELANCER_WALLET:" + freelancer)).as("money back once").isEqualTo(before);
    }

    // --- I10 ------------------------------------------------------------------------------------

    @Test
    @DisplayName("I10 flags a business entry whose key doesn't match its pattern and reference")
    void i10FlagsWrongKeys() {
        long bank = accountId("EXTERNAL_BANK:RAZORPAY");
        long payouts = accountId("EXTERNAL_BANK:PAYOUTS");
        String tag = UUID.randomUUID().toString();
        List<Violation> violations = tx.execute(status -> {
            status.setRollbackOnly();   // never commit these: entries are immutable
            List<Leg> legs = List.of(new Leg(bank, -1), new Leg(payouts, 1));
            // The caller's HTTP key instead of the business key (what fund/release/... used to do).
            ledgerService.post(new PostingRequest(LedgerKeys.RELEASE, "MILESTONE", "77", "http-key-" + tag, "x", legs));
            // The right pattern but another milestone's id.
            ledgerService.post(new PostingRequest(LedgerKeys.REFUND, "MILESTONE", "77", LedgerKeys.refund(78) + "-" + tag, "x", legs));
            // A deposit keyed by a payment id the deposit doesn't have.
            ledgerService.post(new PostingRequest(LedgerKeys.DEPOSIT, "DEPOSIT", "-1", LedgerKeys.deposit("pay_" + tag), "x", legs));
            // A PAYOUT_* type that has no pattern at all.
            ledgerService.post(new PostingRequest("PAYOUT_MYSTERY", "PAYOUT", tag, "payout:mystery:" + tag, "x", legs));
            return invariantChecker.check();
        });
        assertThat(violations).filteredOn(v -> v.code().equals("I10")).hasSize(4);
        assertThat(invariantChecker.check()).as("rolled back").isEmpty();
    }

    // --- helpers --------------------------------------------------------------------------------

    private void assertOneEntry(String type, String referenceType, Object referenceId, String expectedKey) {
        List<String> keys = jdbcClient.sql(
                "SELECT idempotency_key FROM journal_entries WHERE type = ? AND reference_type = ? AND reference_id = ?")
            .param(type).param(referenceType).param(referenceId.toString()).query(String.class).list();
        assertThat(keys).as(type + " entries for " + referenceType + " " + referenceId).containsExactly(expectedKey);
    }

    private List<String> entryKey(String referenceType, Object referenceId) {
        return jdbcClient.sql("SELECT idempotency_key FROM journal_entries WHERE reference_type = ? AND reference_id = ?")
            .param(referenceType).param(referenceId.toString()).query(String.class).list();
    }

    /** Posting the same business key again is a replay: no new entry, no money moves. */
    private void assertReplays(PostingRequest again) {
        PostingResult result = tx.execute(s -> ledgerService.post(again));
        assertThat(result.replayed()).as("ledger replays " + again.idempotencyKey()).isTrue();
    }

    private void rejected(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isBetween(400, 499);
    }

    private void earn() throws Exception {
        long project = project(100_000);
        fundOverHttp(project);
        long m = milestones(project).get(0);
        submit(m);
        api.expect(api.post(approvePath(m), key(), actor(client, "CLIENT"), ""), 200);
    }

    private long project(long... amounts) throws Exception {
        return api.expect(api.post("/api/escrow/projects", null, actor(client, "CLIENT"),
            ApiClient.projectBody(client, freelancer, "ik-" + run, amounts)), 201).path("id").asLong();
    }

    private void fundOverHttp(long project) throws Exception {
        api.expect(api.post(fundPath(project), key(), actor(client, "CLIENT"), ""), 200);
    }

    private void submit(long m) throws Exception {
        api.expect(api.post("/api/escrow/milestones/" + m + "/submit", key(), actor(freelancer, "FREELANCER"), ""), 200);
    }

    private void setState(long milestoneId, MilestoneState state) {
        jdbcClient.sql("UPDATE milestones SET state = ? WHERE id = ?").param(state.name()).param(milestoneId).update();
    }

    private void setPayoutState(Payout payout, PayoutState state) {
        jdbcClient.sql("UPDATE payouts SET state = ? WHERE id = ?").param(state.name()).param(payout.id()).update();
    }

    private static String fundPath(long project) {
        return "/api/escrow/projects/" + project + "/fund";
    }

    private static String approvePath(long m) {
        return "/api/escrow/milestones/" + m + "/approve";
    }

    private static String cancelPath(long m) {
        return "/api/escrow/milestones/" + m + "/cancel";
    }

    private static String disputeBody(long m) {
        return "{\"milestoneId\":" + m + ",\"reason\":\"not done\"}";
    }

    private List<Long> milestones(long project) {
        return milestoneRepository.findByProjectId(project).stream().map(x -> x.id()).toList();
    }

    private String key() {
        return "http-" + run + "-" + UUID.randomUUID();
    }

    private long accountId(String code) {
        return accountRepository.findByCode(code).orElseThrow().id();
    }

    private long balance(String code) {
        return accountRepository.findByCode(code).orElseThrow().balance();
    }
}

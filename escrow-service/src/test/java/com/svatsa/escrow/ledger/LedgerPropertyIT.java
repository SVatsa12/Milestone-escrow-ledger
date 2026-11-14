package com.svatsa.escrow.ledger;

import com.svatsa.escrow.recon.InvariantChecker;
import com.svatsa.escrow.test.AbstractIntegrationTest;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AfterContainer;
import net.jqwik.spring.JqwikSpringSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P4: random sequences of ledger operations keep I1–I5 and match a pure in-memory model.
 *
 * Lifecycle note: jqwik runs on its own engine, so JUnit's @BeforeEach/@AfterEach (including the
 * base class's invariant check) do not run here, and Spring injection comes from
 * {@link JqwikSpringSupport}. The containers are the base class's static singletons. Each try
 * creates its own 20 wallets, so tries never share state, and checks the invariants itself.
 *
 * 100 tries × 100 operations = 10,000 operations per run.
 */
@JqwikSpringSupport
class LedgerPropertyIT extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(LedgerPropertyIT.class);
    private static final int WALLETS = 20;
    private static final AtomicLong SUCCEEDED = new AtomicLong();
    private static final AtomicLong REJECTED = new AtomicLong();
    private static final AtomicLong REPLAYED = new AtomicLong();

    @Autowired private LedgerService ledgerService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private TransactionTemplate tx;
    @Autowired private InvariantChecker checker;

    /** One generated step. Indices are taken modulo the pool, so any int is valid. */
    record Op(String kind, int a, int b, long amount, int replayPick) {}

    @Property(tries = 100)
    void randomOperationsKeepTheLedgerConsistentAndMatchTheModel(@ForAll("operations") List<Op> ops) {
        String run = UUID.randomUUID().toString().substring(0, 8);
        long bank = accountRepository.findByCode("EXTERNAL_BANK:RAZORPAY").orElseThrow().id();

        // Pool of 20 wallets, each seeded with a deposit.
        List<Long> wallets = new ArrayList<>();
        Map<Long, Long> model = new HashMap<>();
        Set<String> appliedKeys = new HashSet<>();
        for (int i = 0; i < WALLETS; i++) {
            long id = accountRepository.create("PROP:" + run + ":" + i, AccountType.CLIENT_WALLET, null, false).id();
            wallets.add(id);
            model.put(id, 0L);
            long seed = 1_000 + (long) (i * 7_919 % 9_000);
            apply(new PostingRequest("DEPOSIT", "TEST", run, "prop-seed:" + run + ":" + i, "seed",
                List.of(new Leg(bank, -seed), new Leg(id, seed))), model, appliedKeys);
        }

        // Requests already sent (to replay) and keys the ledger has actually applied.
        List<PostingRequest> sent = new ArrayList<>();
        int n = 0;
        for (Op op : ops) {
            PostingRequest req;
            if (op.kind().equals("replay") && !sent.isEmpty()) {
                req = sent.get(Math.floorMod(op.replayPick(), sent.size()));
            } else if (op.kind().equals("deposit")) {
                long to = wallets.get(Math.floorMod(op.a(), WALLETS));
                req = new PostingRequest("DEPOSIT", "TEST", run, "prop:" + run + ":" + n, "deposit",
                    List.of(new Leg(bank, -op.amount()), new Leg(to, op.amount())));
            } else {
                int fromIdx = Math.floorMod(op.a(), WALLETS);
                int toIdx = Math.floorMod(fromIdx + 1 + Math.floorMod(op.b(), WALLETS - 1), WALLETS);
                req = new PostingRequest("TRANSFER", "TEST", run, "prop:" + run + ":" + n, "transfer",
                    List.of(new Leg(wallets.get(fromIdx), -op.amount()), new Leg(wallets.get(toIdx), op.amount())));
            }
            sent.add(req);
            n++;
            apply(req, model, appliedKeys);
        }

        // The ledger and the model agree, wallet by wallet...
        for (long id : wallets) {
            assertThat(accountRepository.findById(id).orElseThrow().balance())
                .as("wallet %d", id).isEqualTo(model.get(id));
        }
        // ...and the invariants hold for the whole database.
        assertThat(checker.check()).isEmpty();
    }

    /**
     * Post through LedgerService and mirror the same rule in the model: a key the ledger already
     * applied is a no-op replay; otherwise the posting applies if no wallet would go below zero,
     * else it is rejected and leaves nothing behind (so the same key may succeed later).
     */
    private void apply(PostingRequest req, Map<Long, Long> model, Set<String> appliedKeys) {
        boolean modelReplay = appliedKeys.contains(req.idempotencyKey());
        boolean modelAccepts = req.legs().stream()
            .allMatch(l -> !model.containsKey(l.accountId()) || model.get(l.accountId()) + l.amountPaise() >= 0);
        try {
            PostingResult result = tx.execute(s -> ledgerService.post(req));
            if (result.replayed()) {
                assertThat(modelReplay).as("ledger replayed %s, model had not applied it", req.idempotencyKey()).isTrue();
                REPLAYED.incrementAndGet();
                return;
            }
            assertThat(modelReplay).as("ledger re-applied %s", req.idempotencyKey()).isFalse();
            assertThat(modelAccepts).as("ledger accepted %s, model rejects it", req.idempotencyKey()).isTrue();
            req.legs().forEach(l -> model.computeIfPresent(l.accountId(), (k, v) -> v + l.amountPaise()));
            appliedKeys.add(req.idempotencyKey());
            SUCCEEDED.incrementAndGet();
        } catch (InsufficientFundsException e) {
            assertThat(modelReplay || modelAccepts).as("ledger rejected %s, model accepts it", req.idempotencyKey()).isFalse();
            REJECTED.incrementAndGet();
        }
    }

    @Provide
    Arbitrary<List<Op>> operations() {
        Arbitrary<String> kind = Arbitraries.of("transfer", "transfer", "transfer", "replay", "deposit");
        Arbitrary<Integer> idx = Arbitraries.integers().between(0, 1_000);
        // Up to ~2x a typical balance, so many transfers are rejected.
        Arbitrary<Long> amount = Arbitraries.longs().between(1, 12_000);
        return Combinators.combine(kind, idx, idx, amount, idx).as(Op::new).list().ofSize(100);
    }

    @AfterContainer
    static void report() {
        log.info("ledger.property ops: succeeded={} rejected={} replayed={} total={}",
            SUCCEEDED.get(), REJECTED.get(), REPLAYED.get(), SUCCEEDED.get() + REJECTED.get() + REPLAYED.get());
    }
}

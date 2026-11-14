package com.svatsa.escrow.consumers;

import com.svatsa.escrow.chaos.AbstractToxiproxyIT;
import com.svatsa.escrow.ledger.AccountRepository;
import com.svatsa.escrow.ledger.LedgerService;
import com.svatsa.escrow.ledger.Leg;
import com.svatsa.escrow.ledger.PostingRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Outbox -> Kafka -> audit consumer end to end (chaos/FINDINGS.md #12). The consumer used to
 * fail every record with "No type information in headers" and sit at offset 0 forever.
 */
@DisplayName("Audit log consumer receives relayed events")
class AuditLogConsumerIT extends AbstractToxiproxyIT {

    @Autowired private AccountRepository accountRepository;
    @Autowired private LedgerService ledgerService;
    @Autowired private TransactionTemplate tx;

    @Test
    @DisplayName("a ledger entry's LedgerEntryPosted event lands in audit_log exactly once")
    void ledgerEventReachesAuditLog() {
        long bank = accountRepository.findByCode("EXTERNAL_BANK:RAZORPAY").orElseThrow().id();
        long fees = accountRepository.findByCode("PLATFORM_FEES").orElseThrow().id();
        // LedgerService.post writes the LedgerEntryPosted event to the outbox in the same transaction.
        long entryId = tx.execute(s -> ledgerService.post(new PostingRequest(
            "TEST", "TEST", "audit", "audit-test:" + UUID.randomUUID(), "audit consumer test",
            List.of(new Leg(bank, -100), new Leg(fees, 100))))).entryId();

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
            assertThat(jdbcClient.sql("SELECT count(*) FROM audit_log WHERE aggregate_id = ?")
                .param(String.valueOf(entryId)).query(Long.class).single()).isEqualTo(1));
    }
}

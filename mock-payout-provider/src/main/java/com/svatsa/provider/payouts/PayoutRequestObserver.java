package com.svatsa.provider.payouts;

/**
 * TEST-ONLY extension point. Called when a POST /v1/payouts arrives, before anything is looked
 * up, decided or stored. The provider never registers one itself, and without a bean of this type
 * nothing happens ({@link #NONE}).
 *
 * It exists so a test that runs this provider in-process can observe the exact moment the bank
 * receives a request, e.g. escrow-service's PayoutSagaRegressionIT checks that the payout's
 * reservation is already committed in escrow's database at that instant. Don't use it to change
 * the provider's behaviour; use the modes for that.
 */
@FunctionalInterface
public interface PayoutRequestObserver {

    PayoutRequestObserver NONE = idempotencyKey -> { };

    void onPayoutRequest(String idempotencyKey);
}

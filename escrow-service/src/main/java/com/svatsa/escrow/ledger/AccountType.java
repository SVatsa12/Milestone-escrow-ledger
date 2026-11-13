package com.svatsa.escrow.ledger;

/**
 * Account types in the ledger.
 * 
 * Each type serves a specific purpose in the money flow:
 * - CLIENT_WALLET: client's wallet (allow_negative=false)
 * - FREELANCER_WALLET: freelancer's wallet (allow_negative=false)
 * - ESCROW: milestone funds held in escrow (allow_negative=false)
 * - DISPUTE_HOLD: milestone funds frozen during disputes (allow_negative=false)
 * - PAYOUT_IN_FLIGHT: freelancer payouts in progress (allow_negative=false)
 * - PLATFORM_FEES: accumulated service fees (allow_negative=false)
 * - EXTERNAL_BANK: funding/settlement accounts (allow_negative=true, seeded data)
 */
public enum AccountType {
    CLIENT_WALLET,
    FREELANCER_WALLET,
    ESCROW,
    DISPUTE_HOLD,
    PAYOUT_IN_FLIGHT,
    PLATFORM_FEES,
    EXTERNAL_BANK
}

package com.svatsa.escrow.common;

/**
 * Money utilities.
 * All amounts are in paise (1 rupee = 100 paise).
 * Never use double, float, or BigDecimal for stored amounts.
 */
public class Money {

    /**
     * Calculates platform fee using integer floor division.
     * fee = amount * feeBps / 10_000
     *
     * Example: amount = 1000 paise (10 rupees), feeBps = 500 (5%)
     * fee = 1000 * 500 / 10_000 = 50 paise (0.50 rupees)
     *
     * @param amountPaise the amount in paise
     * @param feeBps fee rate in basis points (e.g., 500 = 5%)
     * @return fee in paise (integer floor division)
     */
    public static long calculateFee(long amountPaise, int feeBps) {
        if (amountPaise < 0) throw new IllegalArgumentException("amountPaise cannot be negative");
        if (feeBps < 0) throw new IllegalArgumentException("feeBps cannot be negative");

        return (amountPaise * feeBps) / 10_000;
    }

    /**
     * Formats paise as a readable string with rupee symbol.
     * 1000 paise -> "₹10.00"
     * 50 paise -> "₹0.50"
     * 1 paise -> "₹0.01"
     *
     * @param paise amount in paise
     * @return formatted string
     */
    public static String formatPaise(long paise) {
        if (paise < 0) {
            return "-₹" + formatPaise(-paise);
        }

        long rupees = paise / 100;
        long remainingPaise = paise % 100;

        return String.format("₹%d.%02d", rupees, remainingPaise);
    }

    /**
     * Parses a rupee amount to paise.
     * "10.50" -> 1050 paise
     * "10" -> 1000 paise
     *
     * @param rupeeString amount as string (e.g., "10.50")
     * @return amount in paise
     */
    public static long parsePaise(String rupeeString) {
        try {
            String[] parts = rupeeString.split("\\.");
            long rupees = Long.parseLong(parts[0]);
            long paise = 0;

            if (parts.length > 1) {
                String paiseStr = parts[1];
                if (paiseStr.length() == 1) {
                    paise = Long.parseLong(paiseStr) * 10;
                } else {
                    paise = Long.parseLong(paiseStr.substring(0, 2));
                }
            }

            return rupees * 100 + paise;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid rupee format: " + rupeeString, e);
        }
    }

    private Money() {
        // Utility class
    }
}

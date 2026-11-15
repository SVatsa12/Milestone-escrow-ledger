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
     * Formats paise for display, with Indian digit grouping, using only integer arithmetic
     * (rupees = paise / 100, paise part = paise % 100, always two digits).
     * <pre>
     *   0          -> "₹0.00"
     *   5          -> "₹0.05"
     *   99999      -> "₹999.99"
     *   1,00,00,000 -> "₹1,00,000.00"   (lakh / crore grouping: 3 digits, then 2s)
     * </pre>
     * Display only: amounts are stored and computed as long paise everywhere else.
     */
    public static String formatPaise(long paise) {
        // Math.abs per part, not on paise itself: -Long.MIN_VALUE overflows.
        long rupees = Math.abs(paise / 100);
        long rest = Math.abs(paise % 100);
        String sign = paise < 0 ? "-" : "";
        return sign + "₹" + groupIndian(Long.toString(rupees)) + "." + (rest < 10 ? "0" : "") + rest;
    }

    /** "12345678" -> "1,23,45,678": the last three digits, then groups of two. */
    private static String groupIndian(String digits) {
        int n = digits.length();
        if (n <= 3) {
            return digits;
        }
        StringBuilder out = new StringBuilder(digits.substring(n - 3));
        for (int end = n - 3; end > 0; end -= 2) {
            out.insert(0, ',').insert(0, digits.substring(Math.max(0, end - 2), end));
        }
        return out.toString();
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

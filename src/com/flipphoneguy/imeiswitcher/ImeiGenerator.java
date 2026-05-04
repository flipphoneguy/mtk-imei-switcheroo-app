package com.flipphoneguy.imeiswitcher;

import java.security.SecureRandom;

/**
 * Generates a 15-digit IMEI from a prefix (8 to 14 digits): prefix + random
 * fill + Luhn check digit. Java port of imei.py's calculate_luhn().
 */
public final class ImeiGenerator {

    private static final SecureRandom RNG = new SecureRandom();

    private ImeiGenerator() {}

    public static final class Result {
        public final String imei;
        public final String prefixUsed;
        Result(String imei, String prefixUsed) {
            this.imei = imei;
            this.prefixUsed = prefixUsed;
        }
    }

    /** Pick a random prefix from the pool and generate an IMEI. */
    public static Result generate(String[] prefixes) {
        if (prefixes == null || prefixes.length == 0) {
            throw new IllegalArgumentException("prefixes must be non-empty");
        }
        String prefix = prefixes[RNG.nextInt(prefixes.length)];
        return new Result(generateFromPrefix(prefix), prefix);
    }

    static String generateFromPrefix(String prefix) {
        if (prefix == null || prefix.length() < 8 || prefix.length() > 14) {
            throw new IllegalArgumentException("Prefix must be 8-14 digits, got: " + prefix);
        }
        for (int i = 0; i < prefix.length(); i++) {
            char c = prefix.charAt(i);
            if (c < '0' || c > '9') throw new IllegalArgumentException("Prefix must be digits");
        }
        StringBuilder sb = new StringBuilder(15);
        sb.append(prefix);
        int need = 14 - prefix.length();
        for (int i = 0; i < need; i++) sb.append((char) ('0' + RNG.nextInt(10)));
        sb.append((char) ('0' + luhnCheckDigit(sb.toString())));
        return sb.toString();
    }

    /** Luhn check digit for a 14-digit IMEI body. */
    static int luhnCheckDigit(String body14) {
        int sum = 0;
        for (int i = 0; i < body14.length(); i++) {
            int d = body14.charAt(i) - '0';
            if ((i & 1) == 1) {
                d *= 2;
                if (d > 9) d -= 9;
            }
            sum += d;
        }
        return (10 - (sum % 10)) % 10;
    }

    /** Reporting Body Identifier label from the first 2 digits of an IMEI. */
    public static String regionForImei(String imei) {
        if (imei == null || imei.length() < 2) return "Unknown";
        String rbi = imei.substring(0, 2);
        switch (rbi) {
            case "01": return "CTIA (USA)";
            case "35": return "BABT (UK/Global)";
            case "86": return "TAF (China)";
            case "91": return "MSAI (India)";
            case "99": return "GHA (Multi-RAT/Global)";
            default:   return "Other (" + rbi + ")";
        }
    }
}

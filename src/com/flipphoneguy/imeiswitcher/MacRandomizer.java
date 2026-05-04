package com.flipphoneguy.imeiswitcher;

import java.security.SecureRandom;

/**
 * Generate a new MAC by preserving the OUI (first 3 bytes) of the supplied
 * current MAC and randomizing the last 3. Keeps the device looking like an
 * OEM unit — the MTK family OUI 10:DF:8B carries through.
 */
public final class MacRandomizer {

    private static final SecureRandom RNG = new SecureRandom();

    private MacRandomizer() {}

    public static byte[] randomizePreservingOui(byte[] current) {
        if (current == null || current.length != 6) {
            throw new IllegalArgumentException("Current MAC must be 6 bytes");
        }
        byte[] out = new byte[6];
        System.arraycopy(current, 0, out, 0, 3);
        byte[] tail = new byte[3];
        RNG.nextBytes(tail);
        System.arraycopy(tail, 0, out, 3, 3);
        return out;
    }
}

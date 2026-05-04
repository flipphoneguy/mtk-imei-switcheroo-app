package com.flipphoneguy.imeiswitcher;

/**
 * Java port of mac_tool.py — read/write the BT MAC and WiFi MAC inside
 * /mnt/vendor/nvdata/APCFG/APRDEB/{BT_Addr, WIFI}.
 *
 * The format is plaintext (no AES) but the MTK NVRAM daemon validates a
 * 2-byte trailer ("0xaa" + 1-byte checksum) at boot via NVM_CheckFile in
 * /vendor/lib64/libnvram.so. Files whose trailer doesn't match are silently
 * restored from BinRegion. The checksum algorithm — recovered by
 * disassembling NVM_ComputeCheckNo on the F21 Pro and verified live on this
 * device (see tmp/bt_mac/) — walks data[:-2] maintaining an 8-bit running
 * value: ADD on even-indexed bytes, XOR on odd-indexed bytes.
 *
 * Universal across all MT67xx devices observed (F21 Pro, F25, TIQ M5, F30
 * stock). The only per-device variation is the 4-byte WIFI header at offset
 * 0: F25 uses 01 00 09 00 instead of 01 00 08 00 — irrelevant to us because
 * we never touch it. The "is this device supported?" gate is the trailer
 * checksum: if the existing on-device file's trailer matches what we'd
 * compute, the algorithm is what we expect and writes will validate.
 */
public final class MacCrypto {

    public static final int BT_FILE_SIZE = 440;
    public static final int WIFI_FILE_SIZE = 2050;

    public static final int BT_MAC_OFFSET = 0x00;
    public static final int WIFI_MAC_OFFSET = 0x04;
    public static final int MAC_LEN = 6;

    public static final byte TRAILER_MAGIC = (byte) 0xAA;

    private MacCrypto() {}

    /**
     * Walks data[:-2], running checksum: ADD on even index, XOR on odd, &0xff.
     * The final byte at data[-1] must equal compute(data) for the daemon to
     * accept the file.
     */
    public static int computeChecksum(byte[] data) {
        int cs = 0;
        int end = data.length - 2;
        for (int i = 0; i < end; i++) {
            int b = data[i] & 0xFF;
            cs = (i & 1) == 0 ? (cs + b) & 0xFF : (cs ^ b) & 0xFF;
        }
        return cs;
    }

    /**
     * True if the file's trailer is "0xaa" followed by the correct checksum
     * for its body. This is the device-supported gate: a matching trailer
     * proves the on-device libnvram speaks the same dialect this code was
     * built for. A mismatch means the algorithm is different and any write
     * we do will be reverted at boot — so refuse to touch it.
     */
    public static boolean trailerValid(byte[] data) {
        if (data == null || data.length < 2) return false;
        if (data[data.length - 2] != TRAILER_MAGIC) return false;
        return (data[data.length - 1] & 0xFF) == computeChecksum(data);
    }

    public static boolean isValidBt(byte[] data) {
        return data != null && data.length == BT_FILE_SIZE && trailerValid(data);
    }

    public static boolean isValidWifi(byte[] data) {
        return data != null && data.length == WIFI_FILE_SIZE && trailerValid(data);
    }

    public static byte[] readBtMac(byte[] data) {
        if (!isValidBt(data)) return null;
        return slice(data, BT_MAC_OFFSET, MAC_LEN);
    }

    public static byte[] readWifiMac(byte[] data) {
        if (!isValidWifi(data)) return null;
        return slice(data, WIFI_MAC_OFFSET, MAC_LEN);
    }

    /**
     * Returns a fresh BT_Addr with the new MAC at [0:6] and a recomputed
     * trailer. Throws if the input file fails the supported-device gate.
     */
    public static byte[] patchBt(byte[] data, byte[] newMac) {
        if (!isValidBt(data)) {
            throw new IllegalStateException("BT_Addr did not pass checksum validation");
        }
        if (newMac == null || newMac.length != MAC_LEN) {
            throw new IllegalArgumentException("BT MAC must be 6 bytes");
        }
        byte[] out = data.clone();
        System.arraycopy(newMac, 0, out, BT_MAC_OFFSET, MAC_LEN);
        out[out.length - 2] = TRAILER_MAGIC;
        out[out.length - 1] = (byte) computeChecksum(out);
        return out;
    }

    public static byte[] patchWifi(byte[] data, byte[] newMac) {
        if (!isValidWifi(data)) {
            throw new IllegalStateException("WIFI did not pass checksum validation");
        }
        if (newMac == null || newMac.length != MAC_LEN) {
            throw new IllegalArgumentException("WiFi MAC must be 6 bytes");
        }
        byte[] out = data.clone();
        System.arraycopy(newMac, 0, out, WIFI_MAC_OFFSET, MAC_LEN);
        out[out.length - 2] = TRAILER_MAGIC;
        out[out.length - 1] = (byte) computeChecksum(out);
        return out;
    }

    // ── MAC string helpers ─────────────────────────────────────────────────

    /** Accepts colon- or dash-separated lowercase/uppercase hex; returns 6 bytes or null. */
    public static byte[] parseMac(String s) {
        if (s == null) return null;
        String[] parts = s.replace('-', ':').split(":");
        if (parts.length != 6) return null;
        byte[] out = new byte[6];
        for (int i = 0; i < 6; i++) {
            String p = parts[i];
            if (p.length() != 2) return null;
            int hi = hex(p.charAt(0));
            int lo = hex(p.charAt(1));
            if (hi < 0 || lo < 0) return null;
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    public static String formatMac(byte[] mac) {
        if (mac == null || mac.length != 6) return "";
        StringBuilder sb = new StringBuilder(17);
        for (int i = 0; i < 6; i++) {
            if (i > 0) sb.append(':');
            int b = mac[i] & 0xFF;
            sb.append(HEX[b >> 4]).append(HEX[b & 0xF]);
        }
        return sb.toString();
    }

    public static boolean isValidMacString(String s) {
        return parseMac(s) != null;
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private static int hex(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }

    private static byte[] slice(byte[] src, int off, int len) {
        byte[] out = new byte[len];
        System.arraycopy(src, off, out, 0, len);
        return out;
    }
}

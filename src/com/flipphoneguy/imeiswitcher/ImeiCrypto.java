package com.flipphoneguy.imeiswitcher;

import java.security.MessageDigest;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * Java port of imei_tool.py. AES-128-ECB over the 32-byte IMEI block at
 * offset 0x40, BCD-encoded IMEI plus an MD5-XOR checksum the modem
 * validates on read.
 */
public final class ImeiCrypto {

    public static final int LD0B_SIZE = 384;
    public static final int HEADER_SIZE = 0x40;
    public static final int IMEI_BLOCK_SIZE = 32;
    public static final int IMEI_BCD_SIZE = 8;

    private static final byte[] AES_KEY = {
        (byte) 0x3f, (byte) 0x06, (byte) 0xbd, (byte) 0x14,
        (byte) 0xd4, (byte) 0x5f, (byte) 0xa9, (byte) 0x85,
        (byte) 0xdd, (byte) 0x02, (byte) 0x74, (byte) 0x10,
        (byte) 0xf0, (byte) 0x21, (byte) 0x4d, (byte) 0x22
    };

    private static final byte[] LD0B_MAGIC = { 'L', 'D', 'I', 0x00 };

    private ImeiCrypto() {}

    public static boolean isValidContainer(byte[] data) {
        if (data == null || data.length != LD0B_SIZE) return false;
        for (int i = 0; i < LD0B_MAGIC.length; i++) {
            if (data[i] != LD0B_MAGIC[i]) return false;
        }
        return true;
    }

    /** Decrypt the IMEI block and decode the BCD IMEI. Returns null if empty/invalid. */
    public static String readImei(byte[] ld0b) throws Exception {
        byte[] block = new byte[IMEI_BLOCK_SIZE];
        System.arraycopy(ld0b, HEADER_SIZE, block, 0, IMEI_BLOCK_SIZE);
        byte[] pt = aesDecrypt(block);
        return bcdToImei(pt, 0);
    }

    /** Returns a fresh 384-byte LD0B_001 with the IMEI rewritten. */
    public static byte[] patchImei(byte[] ld0b, String imei) throws Exception {
        if (!isValidImei(imei)) {
            throw new IllegalArgumentException("IMEI must be 15 digits");
        }
        byte[] block = new byte[IMEI_BLOCK_SIZE];
        System.arraycopy(ld0b, HEADER_SIZE, block, 0, IMEI_BLOCK_SIZE);
        byte[] pt = aesDecrypt(block);

        byte[] bcd = imeiToBcd(imei);
        System.arraycopy(bcd, 0, pt, 0, IMEI_BCD_SIZE);
        // 2-byte filler (tool convention; stock uses 00 00, both round-trip)
        pt[8] = (byte) 0xFF;
        pt[9] = (byte) 0xFF;
        // MD5-XOR checksum over [0:10] -> [10:18]
        byte[] sum = md5XorChecksum(pt, 0, 10);
        System.arraycopy(sum, 0, pt, 10, 8);
        // Zero padding [18:32]
        for (int i = 18; i < IMEI_BLOCK_SIZE; i++) pt[i] = 0;

        byte[] enc = aesEncrypt(pt);
        byte[] out = new byte[LD0B_SIZE];
        System.arraycopy(ld0b, 0, out, 0, LD0B_SIZE);
        System.arraycopy(enc, 0, out, HEADER_SIZE, IMEI_BLOCK_SIZE);
        return out;
    }

    public static boolean isValidImei(String imei) {
        if (imei == null || imei.length() != 15) return false;
        for (int i = 0; i < 15; i++) {
            char c = imei.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    // ── BCD ────────────────────────────────────────────────────────────────

    static byte[] imeiToBcd(String imei) {
        int[] d = new int[15];
        for (int i = 0; i < 15; i++) d[i] = imei.charAt(i) - '0';
        byte[] out = new byte[8];
        for (int i = 0; i < 8; i++) {
            int lo = (2 * i) < 15 ? d[2 * i] : 0xF;
            int hi = (2 * i + 1) < 15 ? d[2 * i + 1] : 0xF;
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    static String bcdToImei(byte[] data, int offset) {
        boolean allFf = true, allZero = true;
        for (int i = 0; i < 8; i++) {
            int b = data[offset + i] & 0xFF;
            if (b != 0xFF) allFf = false;
            if (b != 0x00) allZero = false;
        }
        if (allFf || allZero) return null;

        StringBuilder sb = new StringBuilder(15);
        for (int i = 0; i < 8; i++) {
            int b = data[offset + i] & 0xFF;
            int lo = b & 0xF;
            int hi = (b >> 4) & 0xF;
            if (lo > 9) break;
            sb.append((char) ('0' + lo));
            if (hi > 9) break;
            sb.append((char) ('0' + hi));
        }
        return sb.length() == 15 ? sb.toString() : null;
    }

    // ── Checksum ───────────────────────────────────────────────────────────

    static byte[] md5XorChecksum(byte[] data, int offset, int len) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        md.update(data, offset, len);
        byte[] digest = md.digest();
        byte[] out = new byte[8];
        for (int i = 0; i < 8; i++) out[i] = (byte) (digest[i] ^ digest[i + 8]);
        return out;
    }

    // ── AES wrappers ───────────────────────────────────────────────────────

    static byte[] aesDecrypt(byte[] data) throws Exception {
        Cipher c = Cipher.getInstance("AES/ECB/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(AES_KEY, "AES"));
        return c.doFinal(data);
    }

    static byte[] aesEncrypt(byte[] data) throws Exception {
        Cipher c = Cipher.getInstance("AES/ECB/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(AES_KEY, "AES"));
        return c.doFinal(data);
    }
}

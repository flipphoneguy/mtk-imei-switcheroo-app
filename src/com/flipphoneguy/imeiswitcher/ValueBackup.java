package com.flipphoneguy.imeiswitcher;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * One in-app snapshot of the device's IMEI(s), BT MAC and WiFi MAC. Stored in
 * the app's private SharedPreferences — same place as the history lists — so
 * there is no file on /sdcard to lose. Survives reboots and app updates; goes
 * away only when the app is uninstalled or its data is cleared.
 *
 * Only the values are kept, never the raw NVRAM files. Restore feeds them
 * through the same patch path as Apply (ImeiCrypto.patchImei / MacCrypto.patch*),
 * so the on-device files are re-read and only the value bytes + checksum change.
 */
public final class ValueBackup {

    private static final String PREFS = "value_backup";
    private static final String KEY_TIME = "time";
    private static final String KEY_IMEI = "imei_";   // + slot index
    private static final String KEY_BT = "bt";
    private static final String KEY_WIFI = "wifi";

    /** A set of values. A null entry means "absent" (empty slot / unreadable / unsupported). */
    public static final class Snapshot {
        public final String[] imeis = new String[ImeiCrypto.NUM_SLOTS];
        public String btMac;    // lowercase aa:bb:cc:dd:ee:ff
        public String wifiMac;
        public long time;

        public boolean isEmpty() {
            for (String s : imeis) if (s != null) return false;
            return btMac == null && wifiMac == null;
        }
    }

    private ValueBackup() {}

    /** The stored backup, or null if none has been taken. */
    public static Snapshot load(Context ctx) {
        SharedPreferences sp = prefs(ctx);
        long time = sp.getLong(KEY_TIME, 0);
        if (time == 0) return null;
        Snapshot s = new Snapshot();
        s.time = time;
        for (int i = 0; i < s.imeis.length; i++) {
            String v = sp.getString(KEY_IMEI + i, null);
            if (v != null && ImeiCrypto.isValidImei(v)) s.imeis[i] = v;
        }
        String bt = sp.getString(KEY_BT, null);
        if (bt != null && MacCrypto.isValidMacString(bt)) s.btMac = bt;
        String wifi = sp.getString(KEY_WIFI, null);
        if (wifi != null && MacCrypto.isValidMacString(wifi)) s.wifiMac = wifi;
        return s;
    }

    /** Replaces any previous backup. Written synchronously so it is on disk before we report success. */
    public static void save(Context ctx, Snapshot s) {
        SharedPreferences.Editor ed = prefs(ctx).edit().clear();
        for (int i = 0; i < s.imeis.length; i++) {
            if (s.imeis[i] != null) ed.putString(KEY_IMEI + i, s.imeis[i]);
        }
        if (s.btMac != null) ed.putString(KEY_BT, s.btMac);
        if (s.wifiMac != null) ed.putString(KEY_WIFI, s.wifiMac);
        ed.putLong(KEY_TIME, s.time);
        ed.commit();
    }

    /**
     * Reads the device's current values with the same checks the main screen
     * applies (LD0B magic + per-slot checksum for IMEI, trailer gate for the
     * MACs). Anything that fails a check is left null — if this app can't read
     * a value it couldn't restore it either. Runs su, so call off the main
     * thread. Returns null when root is unavailable.
     */
    public static Snapshot readCurrent() {
        if (!RootRunner.hasRoot()) return null;
        Snapshot s = new Snapshot();
        s.time = System.currentTimeMillis();
        try {
            byte[] ld0b = RootRunner.readFile(RootRunner.IMEI_PATH);
            if (ImeiCrypto.isValidContainer(ld0b)) {
                String[] read = ImeiCrypto.readAllImeis(ld0b);
                for (int i = 0; i < s.imeis.length; i++) s.imeis[i] = read[i];
            }
        } catch (Exception ignored) {}
        s.btMac = readMac(RootRunner.BT_PATH, MacCrypto.BT_FILE_SIZE, true);
        s.wifiMac = readMac(RootRunner.WIFI_PATH, MacCrypto.WIFI_FILE_SIZE, false);
        return s;
    }

    private static String readMac(String path, int expectedSize, boolean bt) {
        try {
            byte[] data = RootRunner.readFile(path);
            if (data.length != expectedSize || !MacCrypto.trailerValid(data)) return null;
            byte[] mac = bt ? MacCrypto.readBtMac(data) : MacCrypto.readWifiMac(data);
            String s = MacCrypto.formatMac(mac);
            return MacCrypto.isValidMacString(s) ? s : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}

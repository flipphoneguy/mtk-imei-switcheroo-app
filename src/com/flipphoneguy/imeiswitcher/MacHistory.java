package com.flipphoneguy.imeiswitcher;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * Most-recent-first list of MACs the user has applied, one list per kind
 * (BT and WiFi tracked independently). Capped at 5; a re-applied MAC moves
 * to the front instead of duplicating. Mirrors ImeiHistory.
 */
public final class MacHistory {

    public enum Kind {
        BT("bt_mac_history"),
        WIFI("wifi_mac_history");
        final String prefs;
        Kind(String p) { this.prefs = p; }
    }

    private static final String KEY = "list";
    private static final int MAX = 5;
    private static final String SEP = ",";

    private MacHistory() {}

    public static List<String> load(Context ctx, Kind kind) {
        SharedPreferences sp = ctx.getSharedPreferences(kind.prefs, Context.MODE_PRIVATE);
        String raw = sp.getString(KEY, "");
        List<String> out = new ArrayList<>();
        if (raw.isEmpty()) return out;
        for (String s : raw.split(SEP)) {
            if (MacCrypto.isValidMacString(s)) out.add(s);
        }
        return out;
    }

    public static void add(Context ctx, Kind kind, String mac) {
        if (!MacCrypto.isValidMacString(mac)) return;
        String norm = MacCrypto.formatMac(MacCrypto.parseMac(mac));
        List<String> list = load(ctx, kind);
        list.remove(norm);
        list.add(0, norm);
        while (list.size() > MAX) list.remove(list.size() - 1);
        save(ctx, kind, list);
    }

    /** Seeds the history with the device's current MAC if the list is empty. */
    public static boolean seedIfEmpty(Context ctx, Kind kind, byte[] currentMac) {
        if (currentMac == null) return false;
        if (!load(ctx, kind).isEmpty()) return false;
        String s = MacCrypto.formatMac(currentMac);
        if (!MacCrypto.isValidMacString(s)) return false;
        List<String> seed = new ArrayList<>();
        seed.add(s);
        save(ctx, kind, seed);
        return true;
    }

    private static void save(Context ctx, Kind kind, List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(SEP);
            sb.append(list.get(i));
        }
        ctx.getSharedPreferences(kind.prefs, Context.MODE_PRIVATE)
            .edit().putString(KEY, sb.toString()).apply();
    }
}

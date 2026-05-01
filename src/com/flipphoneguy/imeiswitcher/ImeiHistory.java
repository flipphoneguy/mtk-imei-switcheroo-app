package com.flipphoneguy.imeiswitcher;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * Most-recent-first list of IMEIs the user has applied. Capped at 5; a
 * re-applied IMEI moves to the front instead of duplicating.
 */
public final class ImeiHistory {

    private static final String PREFS = "imei_history";
    private static final String KEY = "list";
    private static final int MAX = 5;
    private static final String SEP = ",";

    private ImeiHistory() {}

    public static List<String> load(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = sp.getString(KEY, "");
        List<String> out = new ArrayList<>();
        if (raw.isEmpty()) return out;
        for (String s : raw.split(SEP)) {
            if (ImeiCrypto.isValidImei(s)) out.add(s);
        }
        return out;
    }

    public static void add(Context ctx, String imei) {
        if (!ImeiCrypto.isValidImei(imei)) return;
        List<String> list = load(ctx);
        list.remove(imei);
        list.add(0, imei);
        while (list.size() > MAX) list.remove(list.size() - 1);
        save(ctx, list);
    }

    private static void save(Context ctx, List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(SEP);
            sb.append(list.get(i));
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, sb.toString()).apply();
    }
}

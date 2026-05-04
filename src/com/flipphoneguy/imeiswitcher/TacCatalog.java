package com.flipphoneguy.imeiswitcher;

/**
 * Curated TACs / IMEI prefixes for the IMEI generator. Kept tiny on purpose —
 * a useful selection, not a full TAC database.
 *
 * Entries are grouped by intended use-case. Each entry holds a list of
 * prefixes (8 to 14 digits); the generator fills the remaining digits with
 * a random serial and a Luhn check.
 *
 * The Israeli Kosher entry uses 9-digit prefixes from the Cellular Israel
 * approved-phone list — kosher SIMs lock themselves in non-certified phones,
 * and 8 digits (TAC alone) isn't specific enough.
 * Source: https://cellularisrael.helpjuice.com/en_US/devices/16-compatible-phones-with-israeli-sim
 *
 * "US / Verizon BYOD" reflects community wisdom that current US-sold iPhone
 * TACs are reliably accepted by Verizon's BYOD activation flow — Verizon's
 * actual whitelist isn't published.
 */
public final class TacCatalog {

    public static final class Entry {
        public final String category;
        public final String displayName;
        public final String[] prefixes;

        Entry(String category, String displayName, String[] prefixes) {
            this.category = category;
            this.displayName = displayName;
            this.prefixes = prefixes;
        }

        public String label() {
            return "[" + category + "] " + displayName;
        }
    }

    /** 26 nine-digit kosher prefixes derived from Cellular Israel's list. */
    private static final String[] KOSHER_PREFIXES_9 = {
        "352377060", "040354083", "357464051", "352377067", "354905070",
        "352382060", "354904070", "357698070", "357464050", "357464605",
        "352377068", "352377063", "352377087", "358102056", "357464052",
        "351971059", "357130090", "865847059", "868711060", "354647053",
        "350859600", "350859602", "359191050", "353196115", "357913540",
        "351953052"
    };

    public static final Entry[] ENTRIES = {
        new Entry("US / Verizon BYOD", "Apple iPhone 11",  new String[]{ "35875110" }),
        new Entry("US / Verizon BYOD", "Apple iPhone 13",  new String[]{ "35104463" }),
        new Entry("US / Verizon BYOD", "Apple iPhone SE",  new String[]{ "35544207" }),
        new Entry("Global Android",    "Samsung Galaxy S8",new String[]{ "35903908" }),
        new Entry("Global Android",    "Google Nexus 5",   new String[]{ "35824005" }),
        new Entry("Israeli Kosher",    "(any approved device)", KOSHER_PREFIXES_9),
        new Entry("Feature Phone",     "Nokia 8210 (4G)",  new String[]{ "35011200" }),
        new Entry("Feature Phone",     "Nokia 1100",       new String[]{ "35795500" }),
    };

    private TacCatalog() {}
}

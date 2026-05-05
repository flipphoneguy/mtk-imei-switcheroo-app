package com.flipphoneguy.imeiswitcher;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Thin wrapper around `su -c "..."`. Mirrors the steps termux_patch.sh does
 * (cat the file, mount remount,rw, cp back, chmod, chown, reboot). One su
 * invocation per command — splitting was deliberate in live_patch.sh because
 * chained su -c commands hit a permission-denied bug.
 */
public final class RootRunner {

    public static final String IMEI_PATH =
        "/mnt/vendor/nvdata/md/NVRAM/NVD_IMEI/LD0B_001";
    public static final String BT_PATH =
        "/mnt/vendor/nvdata/APCFG/APRDEB/BT_Addr";
    public static final String WIFI_PATH =
        "/mnt/vendor/nvdata/APCFG/APRDEB/WIFI";

    private RootRunner() {}

    /** Returns true if `su -c id` reports uid=0. */
    public static boolean hasRoot() {
        try {
            Result r = run("id");
            return r.exit == 0 && r.stdout.contains("uid=0");
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    /** Reads a file as root via `su -c "cat <path>"`. */
    public static byte[] readFile(String path) throws IOException, InterruptedException {
        Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "cat " + path});
        p.getOutputStream().close();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        InputStream in = p.getInputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        int exit = p.waitFor();
        if (exit != 0) {
            throw new IOException("su cat exited " + exit);
        }
        return out.toByteArray();
    }

    /**
     * Replaces the on-device IMEI file with the bytes provided. Stages the
     * payload to the app's cache dir first (which root can read regardless of
     * mode), defensively remounts rw, then cp/chmod/chown.
     */
    public static void replaceImeiFile(byte[] patched, String stagingPath)
            throws IOException, InterruptedException {
        replaceFile(stagingPath, IMEI_PATH, "system");
    }

    /**
     * Generic NVRAM file replace used by IMEI / BT_Addr / WIFI flows. Group
     * differs per file: IMEI/WIFI are root:system, BT_Addr is root:bluetooth.
     */
    public static void replaceFile(String stagingPath, String dest, String group)
            throws IOException, InterruptedException {
        run("mount -o remount,rw /mnt/vendor/nvdata");
        run("mount -o remount,rw /");
        Result cp = run("cp " + stagingPath + " " + dest);
        if (cp.exit != 0) {
            throw new IOException("cp failed: " + cp.stderr);
        }
        run("chmod 660 " + dest);
        run("chown root:" + group + " " + dest);
    }

    public static void reboot() {
        try {
            run("reboot");
        } catch (Exception ignored) {}
    }

    public static final String WIFI_CONFIG_STORE =
        "/data/misc/apexdata/com.android.wifi/WifiConfigStore.xml";

    /**
     * Best-effort sync of Android's cached factory WiFi MAC. The radio uses
     * the NVRAM MAC, but Android's WifiService caches the factory MAC the
     * first time it sees it and seeds per-SSID MAC randomization from that
     * cache — leaving it stale means joined networks still derive the same
     * randomized MAC they did before. Patches the one tag in-place if the
     * file exists and matches the expected format; silently skips otherwise.
     * Reboot recommended (which is already part of the apply flow).
     */
    public static void syncAndroidWifiFactoryMac(String newMacLower) {
        try {
            String tag = "wifi_sta_factory_mac_address";
            String cmd =
                "F=" + WIFI_CONFIG_STORE + "; " +
                "[ -f \"$F\" ] && " +
                "grep -qE '<string name=\"" + tag + "\">[0-9a-fA-F:]{17}</string>' \"$F\" && " +
                "sed -i -E 's|<string name=\"" + tag + "\">[^<]*</string>|" +
                "<string name=\"" + tag + "\">" + newMacLower + "</string>|' \"$F\"";
            run(cmd);
        } catch (Exception ignored) {}
    }

    public static Result run(String cmd) throws IOException, InterruptedException {
        Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
        p.getOutputStream().close();
        String stdout = drain(p.getInputStream());
        String stderr = drain(p.getErrorStream());
        int exit = p.waitFor();
        return new Result(exit, stdout, stderr);
    }

    private static String drain(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toString();
    }

    public static final class Result {
        public final int exit;
        public final String stdout;
        public final String stderr;
        Result(int exit, String stdout, String stderr) {
            this.exit = exit;
            this.stdout = stdout;
            this.stderr = stderr;
        }
    }
}

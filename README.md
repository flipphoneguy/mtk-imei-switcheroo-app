# mtk-imei-switcheroo-app

Android app that reads and rewrites the **IMEI**, **Bluetooth MAC**, and **WiFi MAC** in NVRAM on rooted MediaTek MT67xx phones — tested on **DuoQin F21 Pro** (single-SIM), **DuoQin F25** (dual-SIM), **Lom S9**, and **TIQ M5** (dual-SIM, MT6761). Wraps the IMEI crypto from [`alltechdev/mtk-imei-switcheroo`](https://github.com/alltechdev/mtk-imei-switcheroo) and the BT/WiFi work from [`alltechdev/f21-imei-switcheroo`](https://github.com/alltechdev/f21-imei-switcheroo) in a single APK so you don't need a host PC, ADB, Termux, or Python.

Three cards in one screen:

- **IMEI** — current IMEI(s), edit field per slot on dual-SIM, **Apply** to write, **Generate** for a curated TAC picker.
- **Bluetooth MAC** — current value, edit field, **Apply BT**, **🎲 Randomize** to spin a new MAC that keeps the OEM OUI.
- **WiFi MAC** — same shape as BT.

Each section keeps the last 5 values you've applied so you can flip back. Requires root (Magisk).

Both MAC sections run a checksum-based **supported-device gate** before they let you write anything — if your phone's NVRAM doesn't match the algorithm this app implements, editing is disabled and the section says so, instead of writing a file that the MTK NVRAM daemon would silently revert at boot. See [Supported-device gate](#bt-mac--bt_addr--wifi-mac--wifi) below.

## Install

Grab the APK from [Releases](../../releases) (or build it — see below) and install. On first run, grant root when Magisk prompts.

## Build

```bash
./build.sh
```

Termux build environment with `aapt2`, `ecj`, `d8`, `apksigner`, `zip`, plus `~/.android/android.jar`, `~/.android/framework-res.apk`, and `~/.android/debug.keystore`. No external Java libs — AES-128-ECB and MD5 come from `javax.crypto` / `java.security`. Output: `ImeiSwitcheroo.apk`.

`VERSION` is the single source of truth for `versionName`/`versionCode`; `build.sh` syncs `AndroidManifest.xml` from it on every build.

## How it works

### IMEI — `LD0B_001`

The IMEI binary format and crypto are documented in detail in [`docs/format.md`](https://github.com/alltechdev/mtk-imei-switcheroo/blob/main/docs/format.md) and [`docs/reverse_engineering.md`](https://github.com/alltechdev/mtk-imei-switcheroo/blob/main/docs/reverse_engineering.md) of the upstream repo. Short version: `/mnt/vendor/nvdata/md/NVRAM/NVD_IMEI/LD0B_001` holds the IMEI as an AES-128-ECB encrypted block with a modem-validated MD5-XOR checksum. Rewrite the BCD-encoded IMEI, recompute the checksum, re-encrypt, push it back.

The app:

1. `su -c cat <LD0B_001>` to pull the 384-byte file.
2. AES-128-ECB decrypt **both** IMEI blocks (offset `0x40` slot 1, `0x60` slot 2). A slot whose plaintext is all-`0x00` or all-`0xFF` is treated as absent (single-SIM units), otherwise its BCD IMEI is decoded and shown.
3. On change: for each filled slot, re-encode the new IMEI, recompute the MD5-XOR checksum, re-encrypt that one slot. Untouched slots stay byte-identical — only the modified blocks are rewritten.
4. Stage to the app's cache dir, `mount -o remount,rw …`, `cp` into place, `chmod 660`, `chown root:system`.
5. Offer to reboot — the modem caches the old IMEI until then.

The Java port lives in [`ImeiCrypto.java`](src/com/flipphoneguy/imeiswitcher/ImeiCrypto.java).

### BT MAC — `BT_Addr` / WiFi MAC — `WIFI`

Both files live under `/mnt/vendor/nvdata/APCFG/APRDEB/` and are plaintext, with a 2-byte trailer (`0xAA` + 1-byte checksum) that the MTK NVRAM daemon validates at every boot. Files whose trailer doesn't match are silently restored from BinRegion. The app rewrites the MAC bytes, recomputes the trailer, and writes the file back the same way the IMEI flow does (`mount remount,rw`, `cp`, `chmod`, `chown`).

Full RE (algorithm, offsets, layout) is in [`f21-imei-switcheroo/docs/wifi_bt_reverse_engineering.md`](https://github.com/alltechdev/f21-imei-switcheroo/blob/main/docs/wifi_bt_reverse_engineering.md). Java port: [`MacCrypto.java`](src/com/flipphoneguy/imeiswitcher/MacCrypto.java).

**Supported-device gate.** Before writing, each MAC section reads its NVRAM file and runs `MacCrypto.trailerValid()`. If the trailer doesn't match what the algorithm computes, editing is disabled and the section shows "unsupported" — a mismatch means anything we wrote would be silently reverted at boot, so we refuse to touch it. Enforced for BT and WiFi; the IMEI block has its own MD5-XOR checksum gate at decrypt time.

### Randomize (BT / WiFi)

**🎲 Randomize** keeps the first 3 bytes of the current MAC byte-for-byte (the OEM OUI — typically `10:DF:8B` for MTK reference devices) and replaces the last 3 with `SecureRandom`. Preserving the OUI keeps the device looking like genuine OEM hardware to fingerprinters; only the per-unit serial portion changes. See [`MacRandomizer.java`](src/com/flipphoneguy/imeiswitcher/MacRandomizer.java).

## Generate IMEI

Tap **Generate** in the IMEI card for a curated picker of hardcoded device templates grouped by use-case: US-sold Apple TACs (commonly accepted on Verizon BYOD), global Android, Israeli Kosher (works with kosher SIMs), and global feature phones. Pick one, see a preview with the IMEI / prefix / RBI region, **🎲 Regenerate** to roll a new serial, or **Use** to drop it into the slot input (you still confirm with **Apply IMEI**).

For non-kosher entries the catalog stores an 8-digit TAC; the generator appends 6 random digits and a Luhn check. The Israeli Kosher entry stores 26 nine-digit prefixes pulled from [Cellular Israel's approved-device list](https://cellularisrael.helpjuice.com/en_US/devices/16-compatible-phones-with-israeli-sim) — the generator picks one at random, appends 5 random digits and Luhn. Nine digits is used (not just the TAC) because kosher SIMs check beyond the first 8.

Catalog and generator live in [`TacCatalog.java`](src/com/flipphoneguy/imeiswitcher/TacCatalog.java) and [`ImeiGenerator.java`](src/com/flipphoneguy/imeiswitcher/ImeiGenerator.java).

## History

Each section remembers the last 5 values you've applied (most-recent first, deduplicated) in `SharedPreferences`. Tap **Use** to refill the input field and switch back. On dual-SIM units, IMEI **Use** asks which slot to drop the value into. Storage key per kind: `imei_history`, `bt_mac_history`, `wifi_mac_history`.

> ⚠ Modifying an IMEI, Bluetooth, or WiFi MAC is illegal in some jurisdictions. You are responsible for checking your local laws and using this tool accordingly.

## Credits

- [alltechdev](https://github.com/alltechdev) — IMEI Python tool, AES key, BCD layout, MD5-XOR checksum RE ([`mtk-imei-switcheroo`](https://github.com/alltechdev/mtk-imei-switcheroo)) and the BT/WiFi tooling and `NVM_ComputeCheckNo` disassembly ([`f21-imei-switcheroo`](https://github.com/alltechdev/f21-imei-switcheroo)). All the hard parts come from there; this app is just a UI wrapper.
- [bkerler/mtkclient](https://github.com/bkerler/mtkclient) — MTK NVRAM AES key derivation algorithm.
- [MTK MOLY modem source](https://github.com/hyperion70/HSPA_MOLY.WR8.W1449.MD.WG.MP.V16) — `LD0B_001` structure.

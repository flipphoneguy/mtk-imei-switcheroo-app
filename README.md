# mtk-imei-switcheroo-app

Android app that reads and rewrites the IMEI on rooted MediaTek MT67xx phones — tested on **DuoQin F21 Pro** (single-SIM), **DuoQin F25** (dual-SIM), **Lom S9** and **TIQ M5** (dual-SIM, MT6761). Same crypto and on-device flow as [`alltechdev/mtk-imei-switcheroo`](https://github.com/alltechdev/mtk-imei-switcheroo), wrapped in a small native UI so you don't need a host PC, ADB, Termux, or Python.

Shows the current IMEI(s), lets you type a new one, and remembers the last 5 you've used so you can flip back. Dual-SIM units are detected automatically — both IMEIs are shown side-by-side and you can rewrite either or both in one go. Requires root (Magisk).

## Install

Grab the APK from Releases (or build it — see below) and install. On first run, grant root when Magisk prompts.

## Build

```bash
./build.sh
```

Termux build environment with `aapt2`, `ecj`, `d8`, `apksigner`, `zip`, plus `~/.android/android.jar`, `~/.android/framework-res.apk`, and `~/.android/debug.keystore`. No external Java libs — AES-128-ECB and MD5 come from `javax.crypto` / `java.security`. Output: `ImeiSwitcheroo.apk`.

`VERSION` is the single source of truth for `versionName`/`versionCode`; `build.sh` syncs `AndroidManifest.xml` from it on every build.

## How it works

The crypto and binary format are documented in detail in the original repo's [`docs/format.md`](https://github.com/alltechdev/mtk-imei-switcheroo/blob/main/docs/format.md) and [`docs/reverse_engineering.md`](https://github.com/alltechdev/mtk-imei-switcheroo/blob/main/docs/reverse_engineering.md). The short version: `LD0B_001` at `/mnt/vendor/nvdata/md/NVRAM/NVD_IMEI/` holds the IMEI as an AES-128-ECB encrypted block with a modem-validated MD5-XOR checksum. Rewrite the BCD-encoded IMEI, recompute the checksum, re-encrypt, push it back.

The app does the same dance the shell scripts do, just from inside a single APK:

1. `su -c cat <LD0B_001>` to pull the 384-byte file.
2. AES-128-ECB decrypt **both** IMEI blocks (offset `0x40` for slot 1, `0x60` for slot 2). A slot whose plaintext is all-`0x00` or all-`0xFF` is treated as absent (single-SIM units), otherwise its BCD IMEI is decoded and shown.
3. On change: for each slot the user filled, re-encode the new IMEI, recompute the MD5-XOR checksum, re-encrypt that one slot. Untouched slots are byte-identical to the original — only the modified blocks are rewritten.
4. Write the patched 384 bytes to the app's cache dir, `su -c "mount -o remount,rw …"`, `cp` into place, `chmod 660`, `chown root:system`.
5. Offer to reboot — the modem caches the old IMEI until then.

The Java port lives in [`ImeiCrypto.java`](src/com/flipphoneguy/imeiswitcher/ImeiCrypto.java) and is a direct translation of [`imei_tool.py`](https://github.com/alltechdev/mtk-imei-switcheroo/blob/main/imei_tool.py) extended to handle the second IMEI slot (no partition-image mode — the app only ever touches the live `LD0B_001`). The on-device steps are in [`RootRunner.java`](src/com/flipphoneguy/imeiswitcher/RootRunner.java) and mirror the `su -c` calls in `termux_patch.sh` / `live_patch.sh`.

## History

The app remembers the last 5 IMEIs you've applied (most-recent first, deduplicated) in `SharedPreferences`. Tap **Use** to refill the input field and switch back. On dual-SIM units, **Use** asks which slot to drop the value into.

## Generate IMEI

Tap **Generate IMEI** for a curated picker of hardcoded device templates grouped by use-case: US-sold Apple TACs (commonly accepted on Verizon BYOD), global Android, Israeli Kosher (works with kosher SIMs), and global feature phones. Pick one, see a preview with the IMEI / prefix / RBI region, **🎲 Regenerate** to roll a new serial, or **Use** to drop it into the slot input (you still confirm with **Change IMEI**).

For non-kosher entries the catalog stores an 8-digit TAC; the generator appends 6 random digits and a Luhn check. The Israeli Kosher entry stores 26 nine-digit prefixes pulled from [Cellular Israel's approved-device list](https://cellularisrael.helpjuice.com/en_US/devices/16-compatible-phones-with-israeli-sim) — the generator picks one at random, appends 5 random digits and Luhn. Nine digits is used (not just the TAC) because kosher SIMs check beyond the first 8.

Catalog and generator live in [`TacCatalog.java`](src/com/flipphoneguy/imeiswitcher/TacCatalog.java) and [`ImeiGenerator.java`](src/com/flipphoneguy/imeiswitcher/ImeiGenerator.java).

> ⚠ Modifying an IMEI is illegal in some jurisdictions. You are responsible for checking your local laws.

## Credits

- [alltechdev](https://github.com/alltechdev) — original Python tool, AES key, BCD layout, and the reverse-engineering of the MD5-XOR checksum: [`alltechdev/mtk-imei-switcheroo`](https://github.com/alltechdev/mtk-imei-switcheroo). All the hard parts come from there; this app is just a UI wrapper.
- [bkerler/mtkclient](https://github.com/bkerler/mtkclient) — MTK NVRAM AES key derivation algorithm.
- [MTK MOLY modem source](https://github.com/hyperion70/HSPA_MOLY.WR8.W1449.MD.WG.MP.V16) — `LD0B_001` structure.

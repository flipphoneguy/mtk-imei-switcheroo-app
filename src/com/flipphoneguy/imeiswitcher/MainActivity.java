package com.flipphoneguy.imeiswitcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class MainActivity extends Activity {

    // IMEI section
    private TextView imeiLoading;
    private LinearLayout imeiSlotsContainer;
    private Button btnChangeImei, btnGenerateImei;
    private LinearLayout imeiHistoryList;
    private TextView imeiHistoryEmpty;
    private final List<SlotRow> slots = new ArrayList<>();

    // BT section
    private TextView btStatus, btCurrentView;
    private LinearLayout btBody;
    private EditText btInput;
    private Button btnChangeBt, btnRandomizeBt;
    private LinearLayout btHistoryList;
    private TextView btHistoryEmpty;
    private byte[] btCurrentMac;

    // WiFi section
    private TextView wifiStatus, wifiCurrentView;
    private LinearLayout wifiBody;
    private EditText wifiInput;
    private Button btnChangeWifi, btnRandomizeWifi;
    private LinearLayout wifiHistoryList;
    private TextView wifiHistoryEmpty;
    private byte[] wifiCurrentMac;

    // Backup section
    private TextView backupStatus, backupSummary;
    private Button btnBackup, btnRestore;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean rebootPromptOpen = false;

    private static class SlotRow {
        final int slotIndex;
        final String currentImei;
        final EditText input;
        SlotRow(int idx, String current, EditText input) {
            this.slotIndex = idx;
            this.currentImei = current;
            this.input = input;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // IMEI bindings
        imeiLoading        = findViewById(R.id.imei_loading);
        imeiSlotsContainer = findViewById(R.id.imei_slots_container);
        btnChangeImei      = findViewById(R.id.btn_change_imei);
        btnGenerateImei    = findViewById(R.id.btn_generate_imei);
        imeiHistoryList    = findViewById(R.id.imei_history_list);
        imeiHistoryEmpty   = findViewById(R.id.imei_history_empty);

        // BT bindings
        btStatus         = findViewById(R.id.bt_status);
        btBody           = findViewById(R.id.bt_body);
        btCurrentView    = findViewById(R.id.bt_current);
        btInput          = findViewById(R.id.bt_input);
        btnChangeBt      = findViewById(R.id.btn_change_bt);
        btnRandomizeBt   = findViewById(R.id.btn_randomize_bt);
        btHistoryList    = findViewById(R.id.bt_history_list);
        btHistoryEmpty   = findViewById(R.id.bt_history_empty);

        // WiFi bindings
        wifiStatus       = findViewById(R.id.wifi_status);
        wifiBody         = findViewById(R.id.wifi_body);
        wifiCurrentView  = findViewById(R.id.wifi_current);
        wifiInput        = findViewById(R.id.wifi_input);
        btnChangeWifi    = findViewById(R.id.btn_change_wifi);
        btnRandomizeWifi = findViewById(R.id.btn_randomize_wifi);
        wifiHistoryList  = findViewById(R.id.wifi_history_list);
        wifiHistoryEmpty = findViewById(R.id.wifi_history_empty);

        // Backup bindings
        backupStatus  = findViewById(R.id.backup_status);
        backupSummary = findViewById(R.id.backup_summary);
        btnBackup     = findViewById(R.id.btn_backup);
        btnRestore    = findViewById(R.id.btn_restore);

        findViewById(R.id.btn_about).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, InfoActivity.class));
            }
        });

        btnChangeImei.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { confirmImeiChange(); }
        });
        btnGenerateImei.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showGeneratePicker(); }
        });

        btnChangeBt.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { confirmMacChange(MacKind.BT); }
        });
        btnRandomizeBt.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { randomize(MacKind.BT); }
        });
        btInput.addTextChangedListener(macInputWatcher(MacKind.BT));

        btnChangeWifi.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { confirmMacChange(MacKind.WIFI); }
        });
        btnRandomizeWifi.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { randomize(MacKind.WIFI); }
        });
        wifiInput.addTextChangedListener(macInputWatcher(MacKind.WIFI));

        btnBackup.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { confirmBackup(); }
        });
        btnRestore.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { confirmRestore(); }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadImei();
        loadMac(MacKind.BT);
        loadMac(MacKind.WIFI);
        renderBackup();
    }

    // ─── IMEI section ──────────────────────────────────────────────────────

    private void loadImei() {
        imeiLoading.setVisibility(View.VISIBLE);
        imeiSlotsContainer.removeAllViews();
        slots.clear();
        btnChangeImei.setEnabled(false);
        btnGenerateImei.setEnabled(false);

        new Thread(new Runnable() {
            @Override public void run() {
                final String error;
                final String[] imeis;
                if (!RootRunner.hasRoot()) {
                    error = getString(R.string.err_no_root);
                    imeis = null;
                } else {
                    String err = null;
                    String[] read = null;
                    try {
                        byte[] data = RootRunner.readFile(RootRunner.IMEI_PATH);
                        if (data.length != ImeiCrypto.LD0B_SIZE) {
                            err = getString(R.string.err_pull_size);
                        } else if (!ImeiCrypto.isValidContainer(data)) {
                            err = getString(R.string.err_decrypt_failed);
                        } else {
                            read = ImeiCrypto.readAllImeis(data);
                        }
                    } catch (Exception e) {
                        err = getString(R.string.err_read_failed);
                    }
                    error = err;
                    imeis = read;
                }
                ui.post(new Runnable() {
                    @Override public void run() { applyImeiSlots(imeis, error); }
                });
            }
        }).start();
    }

    private void applyImeiSlots(String[] imeis, String error) {
        imeiSlotsContainer.removeAllViews();
        slots.clear();

        if (imeis == null) {
            imeiLoading.setText(error != null ? error : getString(R.string.err_read_failed));
            imeiLoading.setVisibility(View.VISIBLE);
            renderImeiHistory();
            return;
        }

        if (ImeiHistory.seedIfEmpty(this, imeis)) renderImeiHistory();

        int populated = 0;
        for (String s : imeis) if (s != null) populated++;
        boolean dual = populated > 1;

        LayoutInflater inflater = LayoutInflater.from(this);
        for (int i = 0; i < imeis.length; i++) {
            if (imeis[i] == null) continue;
            View row = inflater.inflate(R.layout.item_slot, imeiSlotsContainer, false);

            TextView label = row.findViewById(R.id.slot_label);
            TextView current = row.findViewById(R.id.slot_current);
            EditText input = row.findViewById(R.id.slot_input);

            label.setText(dual
                ? getString(R.string.label_imei_n, i + 1)
                : getString(R.string.label_current_imei));
            current.setText(imeis[i]);
            input.setHint(R.string.hint_keep);

            input.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void afterTextChanged(Editable s) { updateImeiButtonState(); }
            });

            imeiSlotsContainer.addView(row);
            slots.add(new SlotRow(i, imeis[i], input));
        }

        imeiLoading.setVisibility(View.GONE);
        btnGenerateImei.setEnabled(!slots.isEmpty());
        updateImeiButtonState();
        renderImeiHistory();
    }

    private void updateImeiButtonState() {
        boolean anyFilled = false;
        for (SlotRow s : slots) {
            if (s.input.getText().toString().trim().length() > 0) {
                anyFilled = true;
                break;
            }
        }
        btnChangeImei.setEnabled(anyFilled);
    }

    private static class ImeiChange {
        final int slotIndex;
        final String imei;
        ImeiChange(int s, String i) { this.slotIndex = s; this.imei = i; }
    }

    private List<ImeiChange> collectImeiChanges() {
        List<ImeiChange> out = new ArrayList<>();
        for (SlotRow s : slots) {
            String v = s.input.getText().toString().trim();
            if (v.isEmpty()) continue;
            if (!ImeiCrypto.isValidImei(v)) {
                Toast.makeText(this,
                    getString(R.string.err_invalid_imei) + " (IMEI " + (s.slotIndex + 1) + ")",
                    Toast.LENGTH_SHORT).show();
                return null;
            }
            out.add(new ImeiChange(s.slotIndex, v));
        }
        return out;
    }

    private void confirmImeiChange() {
        final List<ImeiChange> changes = collectImeiChanges();
        if (changes == null) return;
        if (changes.isEmpty()) {
            Toast.makeText(this, R.string.err_no_changes, Toast.LENGTH_SHORT).show();
            return;
        }

        StringBuilder summary = new StringBuilder(getString(R.string.confirm_change_msg))
            .append("\n");
        boolean dual = slots.size() > 1;
        for (ImeiChange c : changes) {
            summary.append("\n");
            summary.append(dual ? getString(R.string.slot_n, c.slotIndex + 1) + ": " : "");
            summary.append(c.imei);
        }

        new AlertDialog.Builder(this)
            .setTitle(R.string.confirm_change_title)
            .setMessage(summary.toString())
            .setPositiveButton(R.string.btn_change, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) { applyImeiChanges(changes); }
            })
            .setNegativeButton(R.string.btn_cancel, null)
            .show();
    }

    private void applyImeiChanges(final List<ImeiChange> changes) {
        btnChangeImei.setEnabled(false);
        new Thread(new Runnable() {
            @Override public void run() {
                final String error = doImeiPatch(changes);
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (error != null) {
                            btnChangeImei.setEnabled(true);
                            Toast.makeText(MainActivity.this, error, Toast.LENGTH_LONG).show();
                            return;
                        }
                        for (ImeiChange c : changes) ImeiHistory.add(MainActivity.this, c.imei);
                        Toast.makeText(MainActivity.this, R.string.ok_updated_imei, Toast.LENGTH_SHORT).show();
                        loadImei();
                        promptReboot();
                    }
                });
            }
        }).start();
    }

    private String doImeiPatch(List<ImeiChange> changes) {
        try {
            if (!RootRunner.hasRoot()) return getString(R.string.err_no_root);

            byte[] ld0b = RootRunner.readFile(RootRunner.IMEI_PATH);
            if (ld0b.length != ImeiCrypto.LD0B_SIZE) return getString(R.string.err_pull_size);
            if (!ImeiCrypto.isValidContainer(ld0b)) return getString(R.string.err_decrypt_failed);

            for (ImeiChange c : changes) {
                ld0b = ImeiCrypto.patchImei(ld0b, c.slotIndex, c.imei);
            }

            File staging = new File(getCacheDir(), "patched_LD0B_001.bin");
            FileOutputStream fos = new FileOutputStream(staging);
            try { fos.write(ld0b); } finally { fos.close(); }
            staging.setReadable(true, false);

            RootRunner.replaceFile(staging.getAbsolutePath(), RootRunner.IMEI_PATH, "system");
            staging.delete();
            return null;
        } catch (Exception e) {
            String msg = e.getMessage();
            return getString(R.string.err_write_failed) + (msg != null ? " (" + msg + ")" : "");
        }
    }

    private void renderImeiHistory() {
        imeiHistoryList.removeAllViews();
        List<String> items = ImeiHistory.load(this);
        if (items.isEmpty()) {
            imeiHistoryEmpty.setVisibility(View.VISIBLE);
            return;
        }
        imeiHistoryEmpty.setVisibility(View.GONE);
        LayoutInflater inflater = LayoutInflater.from(this);
        for (final String imei : items) {
            View row = inflater.inflate(R.layout.item_history, imeiHistoryList, false);
            ((TextView) row.findViewById(R.id.history_imei)).setText(imei);
            row.findViewById(R.id.history_use).setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { applyImeiToSlot(imei); }
            });
            imeiHistoryList.addView(row);
        }
    }

    private void applyImeiToSlot(final String imei) {
        if (slots.isEmpty()) return;
        if (slots.size() == 1) {
            fillImeiSlot(slots.get(0), imei);
            return;
        }
        final String[] labels = new String[slots.size()];
        for (int i = 0; i < slots.size(); i++) {
            labels[i] = getString(R.string.slot_n, slots.get(i).slotIndex + 1)
                + " — " + slots.get(i).currentImei;
        }
        new AlertDialog.Builder(this)
            .setTitle(R.string.apply_to_slot_title)
            .setItems(labels, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int which) {
                    fillImeiSlot(slots.get(which), imei);
                }
            })
            .show();
    }

    private void showGeneratePicker() {
        if (slots.isEmpty()) return;
        final TacCatalog.Entry[] entries = TacCatalog.ENTRIES;
        final String[] labels = new String[entries.length];
        for (int i = 0; i < entries.length; i++) labels[i] = entries[i].label();

        new AlertDialog.Builder(this)
            .setTitle(R.string.generate_title)
            .setItems(labels, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int which) {
                    showGeneratePreview(entries[which]);
                }
            })
            .setNegativeButton(R.string.btn_cancel, null)
            .show();
    }

    private void showGeneratePreview(final TacCatalog.Entry entry) {
        final ImeiGenerator.Result r = ImeiGenerator.generate(entry.prefixes);
        String msg = getString(R.string.generate_preview_fmt,
            entry.displayName, r.imei, r.prefixUsed, ImeiGenerator.regionForImei(r.imei));

        new AlertDialog.Builder(this)
            .setTitle(R.string.generate_preview_title)
            .setMessage(msg)
            .setPositiveButton(R.string.btn_use, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) { applyImeiToSlot(r.imei); }
            })
            .setNeutralButton(R.string.btn_regenerate, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) { showGeneratePreview(entry); }
            })
            .setNegativeButton(R.string.btn_cancel, null)
            .show();
    }

    private void fillImeiSlot(SlotRow s, String imei) {
        s.input.setText(imei);
        s.input.setSelection(imei.length());
        s.input.requestFocus();
    }

    // ─── MAC sections (BT + WiFi share most code) ──────────────────────────

    private enum MacKind {
        BT(MacHistory.Kind.BT),
        WIFI(MacHistory.Kind.WIFI);
        final MacHistory.Kind histKind;
        MacKind(MacHistory.Kind k) { this.histKind = k; }
    }

    private TextView statusOf(MacKind k) { return k == MacKind.BT ? btStatus : wifiStatus; }
    private LinearLayout bodyOf(MacKind k) { return k == MacKind.BT ? btBody : wifiBody; }
    private TextView currentViewOf(MacKind k) { return k == MacKind.BT ? btCurrentView : wifiCurrentView; }
    private EditText inputOf(MacKind k) { return k == MacKind.BT ? btInput : wifiInput; }
    private Button changeBtnOf(MacKind k) { return k == MacKind.BT ? btnChangeBt : btnChangeWifi; }
    private LinearLayout historyListOf(MacKind k) { return k == MacKind.BT ? btHistoryList : wifiHistoryList; }
    private TextView historyEmptyOf(MacKind k) { return k == MacKind.BT ? btHistoryEmpty : wifiHistoryEmpty; }
    private String pathOf(MacKind k) { return k == MacKind.BT ? RootRunner.BT_PATH : RootRunner.WIFI_PATH; }
    private int expectedSize(MacKind k) { return k == MacKind.BT ? MacCrypto.BT_FILE_SIZE : MacCrypto.WIFI_FILE_SIZE; }
    private String groupOf(MacKind k) { return k == MacKind.BT ? "bluetooth" : "system"; }
    private int unsupportedMsgRes(MacKind k) { return k == MacKind.BT ? R.string.err_bt_unsupported : R.string.err_wifi_unsupported; }
    private int missingMsgRes(MacKind k) { return k == MacKind.BT ? R.string.err_bt_missing : R.string.err_wifi_missing; }
    private int loadingMsgRes(MacKind k) { return k == MacKind.BT ? R.string.loading_bt : R.string.loading_wifi; }
    private int okMsgRes(MacKind k) { return k == MacKind.BT ? R.string.ok_updated_bt : R.string.ok_updated_wifi; }

    private TextWatcher macInputWatcher(final MacKind k) {
        return new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { updateMacButtonState(k); }
        };
    }

    private void setCurrentMac(MacKind k, byte[] mac) {
        if (k == MacKind.BT) btCurrentMac = mac; else wifiCurrentMac = mac;
    }
    private byte[] currentMac(MacKind k) {
        return k == MacKind.BT ? btCurrentMac : wifiCurrentMac;
    }

    private void loadMac(final MacKind k) {
        statusOf(k).setVisibility(View.VISIBLE);
        statusOf(k).setText(loadingMsgRes(k));
        bodyOf(k).setVisibility(View.GONE);
        setCurrentMac(k, null);
        changeBtnOf(k).setEnabled(false);

        new Thread(new Runnable() {
            @Override public void run() {
                final String error;
                final byte[] data;
                if (!RootRunner.hasRoot()) {
                    error = getString(R.string.err_no_root);
                    data = null;
                } else {
                    String err = null;
                    byte[] d = null;
                    try {
                        d = RootRunner.readFile(pathOf(k));
                        if (d.length != expectedSize(k)) {
                            err = getString(missingMsgRes(k));
                            d = null;
                        }
                    } catch (Exception e) {
                        err = getString(missingMsgRes(k));
                    }
                    error = err;
                    data = d;
                }
                ui.post(new Runnable() {
                    @Override public void run() { applyMacLoad(k, data, error); }
                });
            }
        }).start();
    }

    private void applyMacLoad(MacKind k, byte[] data, String error) {
        if (data == null) {
            statusOf(k).setText(error != null ? error : getString(R.string.err_read_failed));
            statusOf(k).setVisibility(View.VISIBLE);
            bodyOf(k).setVisibility(View.GONE);
            renderMacHistory(k);
            return;
        }

        // The supported-device gate.
        if (!MacCrypto.trailerValid(data)) {
            statusOf(k).setText(unsupportedMsgRes(k));
            statusOf(k).setVisibility(View.VISIBLE);
            bodyOf(k).setVisibility(View.GONE);
            renderMacHistory(k);
            return;
        }

        byte[] mac = (k == MacKind.BT) ? MacCrypto.readBtMac(data) : MacCrypto.readWifiMac(data);
        setCurrentMac(k, mac);

        currentViewOf(k).setText(MacCrypto.formatMac(mac).toUpperCase());
        inputOf(k).setText("");
        statusOf(k).setVisibility(View.GONE);
        bodyOf(k).setVisibility(View.VISIBLE);

        if (MacHistory.seedIfEmpty(this, k.histKind, mac)) renderMacHistory(k);
        else renderMacHistory(k);

        updateMacButtonState(k);
    }

    private void updateMacButtonState(MacKind k) {
        String v = inputOf(k).getText().toString().trim();
        boolean ok = !v.isEmpty() && MacCrypto.isValidMacString(v);
        changeBtnOf(k).setEnabled(ok && currentMac(k) != null);
    }

    private void randomize(MacKind k) {
        byte[] cur = currentMac(k);
        if (cur == null) return;
        byte[] rnd = MacRandomizer.randomizePreservingOui(cur);
        String s = MacCrypto.formatMac(rnd);
        inputOf(k).setText(s);
        inputOf(k).setSelection(s.length());
        inputOf(k).requestFocus();
    }

    private void confirmMacChange(final MacKind k) {
        String input = inputOf(k).getText().toString().trim();
        final byte[] newMac = MacCrypto.parseMac(input);
        if (newMac == null) {
            Toast.makeText(this, R.string.err_invalid_mac, Toast.LENGTH_SHORT).show();
            return;
        }

        String label = getString(k == MacKind.BT ? R.string.section_bt : R.string.section_wifi);
        String msg = getString(R.string.confirm_change_msg)
            + "\n\n" + label + ": " + MacCrypto.formatMac(newMac);

        new AlertDialog.Builder(this)
            .setTitle(R.string.confirm_change_title)
            .setMessage(msg)
            .setPositiveButton(R.string.btn_change, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) { applyMacChange(k, newMac); }
            })
            .setNegativeButton(R.string.btn_cancel, null)
            .show();
    }

    private void applyMacChange(final MacKind k, final byte[] newMac) {
        changeBtnOf(k).setEnabled(false);
        new Thread(new Runnable() {
            @Override public void run() {
                final String error = doMacPatch(k, newMac);
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (error != null) {
                            updateMacButtonState(k);
                            Toast.makeText(MainActivity.this, error, Toast.LENGTH_LONG).show();
                            return;
                        }
                        MacHistory.add(MainActivity.this, k.histKind, MacCrypto.formatMac(newMac));
                        Toast.makeText(MainActivity.this, okMsgRes(k), Toast.LENGTH_SHORT).show();
                        loadMac(k);
                        promptReboot();
                    }
                });
            }
        }).start();
    }

    private String doMacPatch(MacKind k, byte[] newMac) {
        try {
            if (!RootRunner.hasRoot()) return getString(R.string.err_no_root);

            byte[] data = RootRunner.readFile(pathOf(k));
            if (data.length != expectedSize(k)) return getString(missingMsgRes(k));
            if (!MacCrypto.trailerValid(data)) return getString(unsupportedMsgRes(k));

            byte[] patched = (k == MacKind.BT)
                ? MacCrypto.patchBt(data, newMac)
                : MacCrypto.patchWifi(data, newMac);

            String stagingName = (k == MacKind.BT ? "patched_BT_Addr.bin" : "patched_WIFI.bin");
            File staging = new File(getCacheDir(), stagingName);
            FileOutputStream fos = new FileOutputStream(staging);
            try { fos.write(patched); } finally { fos.close(); }
            staging.setReadable(true, false);

            RootRunner.replaceFile(staging.getAbsolutePath(), pathOf(k), groupOf(k));
            staging.delete();
            if (k == MacKind.WIFI) {
                RootRunner.syncAndroidWifiFactoryMac(MacCrypto.formatMac(newMac));
            }
            return null;
        } catch (Exception e) {
            String msg = e.getMessage();
            return getString(R.string.err_write_failed) + (msg != null ? " (" + msg + ")" : "");
        }
    }

    private void renderMacHistory(final MacKind k) {
        LinearLayout list = historyListOf(k);
        TextView empty = historyEmptyOf(k);
        list.removeAllViews();
        List<String> items = MacHistory.load(this, k.histKind);
        if (items.isEmpty()) {
            empty.setVisibility(View.VISIBLE);
            return;
        }
        empty.setVisibility(View.GONE);
        LayoutInflater inflater = LayoutInflater.from(this);
        for (final String mac : items) {
            View row = inflater.inflate(R.layout.item_history, list, false);
            ((TextView) row.findViewById(R.id.history_imei)).setText(mac.toUpperCase());
            row.findViewById(R.id.history_use).setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    inputOf(k).setText(mac);
                    inputOf(k).setSelection(mac.length());
                    inputOf(k).requestFocus();
                }
            });
            list.addView(row);
        }
    }

    // ─── Backup / restore ──────────────────────────────────────────────────

    private void renderBackup() {
        ValueBackup.Snapshot s = ValueBackup.load(this);
        if (s == null || s.isEmpty()) {
            backupStatus.setText(R.string.backup_none);
            backupSummary.setVisibility(View.GONE);
            btnRestore.setEnabled(false);
            return;
        }
        backupStatus.setText(getString(R.string.backup_exists, formatTime(s.time)));
        backupSummary.setText(describe(s));
        backupSummary.setVisibility(View.VISIBLE);
        btnRestore.setEnabled(true);
    }

    private String formatTime(long millis) {
        return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(new Date(millis));
    }

    /** One "label: value" line per backed-up value; shown in the card and the confirm dialogs. */
    private String describe(ValueBackup.Snapshot s) {
        StringBuilder sb = new StringBuilder();
        int populated = 0;
        for (String v : s.imeis) if (v != null) populated++;
        for (int i = 0; i < s.imeis.length; i++) {
            if (s.imeis[i] == null) continue;
            String label = populated > 1
                ? getString(R.string.label_imei_n, i + 1)
                : getString(R.string.section_imei);
            appendLine(sb, label, s.imeis[i]);
        }
        if (s.btMac != null) appendLine(sb, getString(R.string.section_bt), s.btMac.toUpperCase());
        if (s.wifiMac != null) appendLine(sb, getString(R.string.section_wifi), s.wifiMac.toUpperCase());
        return sb.toString();
    }

    private static void appendLine(StringBuilder sb, String label, String value) {
        if (sb.length() > 0) sb.append('\n');
        sb.append(label).append(": ").append(value);
    }

    private void confirmBackup() {
        final ValueBackup.Snapshot existing = ValueBackup.load(this);
        if (existing == null || existing.isEmpty()) {
            runBackup();
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle(R.string.backup_replace_title)
            .setMessage(getString(R.string.backup_replace_msg, formatTime(existing.time))
                + "\n\n" + describe(existing))
            .setPositiveButton(R.string.btn_replace, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) { runBackup(); }
            })
            .setNegativeButton(R.string.btn_cancel, null)
            .show();
    }

    /** Reads the current values off the device (same checks as the cards) and stores them. */
    private void runBackup() {
        btnBackup.setEnabled(false);
        btnRestore.setEnabled(false);
        new Thread(new Runnable() {
            @Override public void run() {
                final ValueBackup.Snapshot s = ValueBackup.readCurrent();
                if (s != null && !s.isEmpty()) ValueBackup.save(MainActivity.this, s);
                ui.post(new Runnable() {
                    @Override public void run() {
                        btnBackup.setEnabled(true);
                        renderBackup();
                        if (s == null) {
                            Toast.makeText(MainActivity.this, R.string.err_no_root, Toast.LENGTH_LONG).show();
                        } else if (s.isEmpty()) {
                            Toast.makeText(MainActivity.this, R.string.err_backup_nothing, Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(MainActivity.this, R.string.ok_backup, Toast.LENGTH_SHORT).show();
                        }
                    }
                });
            }
        }).start();
    }

    private void confirmRestore() {
        final ValueBackup.Snapshot s = ValueBackup.load(this);
        if (s == null || s.isEmpty()) return;
        new AlertDialog.Builder(this)
            .setTitle(R.string.restore_confirm_title)
            .setMessage(getString(R.string.restore_confirm_msg) + "\n\n" + describe(s))
            .setPositiveButton(R.string.btn_restore, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) { runRestore(s); }
            })
            .setNegativeButton(R.string.btn_cancel, null)
            .show();
    }

    /**
     * Writes every value in the backup through the same patch path Apply uses
     * (doImeiPatch / doMacPatch): the on-device files are re-read and only the
     * value bytes + checksum change, with the same supported-device gates.
     * Values that fail are reported; the rest still land.
     */
    private void runRestore(final ValueBackup.Snapshot s) {
        btnBackup.setEnabled(false);
        btnRestore.setEnabled(false);
        new Thread(new Runnable() {
            @Override public void run() {
                final List<String> errors = new ArrayList<>();
                final List<ImeiChange> imeiChanges = new ArrayList<>();
                boolean imeiOk = false, btOk = false, wifiOk = false;

                if (!RootRunner.hasRoot()) {
                    errors.add(getString(R.string.err_no_root));
                } else {
                    for (int i = 0; i < s.imeis.length; i++) {
                        if (s.imeis[i] != null) imeiChanges.add(new ImeiChange(i, s.imeis[i]));
                    }
                    if (!imeiChanges.isEmpty()) {
                        String e = doImeiPatch(imeiChanges);
                        if (e == null) imeiOk = true;
                        else errors.add(getString(R.string.section_imei) + ": " + e);
                    }
                    if (s.btMac != null) {
                        String e = doMacPatch(MacKind.BT, MacCrypto.parseMac(s.btMac));
                        if (e == null) btOk = true;
                        else errors.add(getString(R.string.section_bt) + ": " + e);
                    }
                    if (s.wifiMac != null) {
                        String e = doMacPatch(MacKind.WIFI, MacCrypto.parseMac(s.wifiMac));
                        if (e == null) wifiOk = true;
                        else errors.add(getString(R.string.section_wifi) + ": " + e);
                    }
                }

                final boolean restoredImei = imeiOk, restoredBt = btOk, restoredWifi = wifiOk;
                ui.post(new Runnable() {
                    @Override public void run() {
                        btnBackup.setEnabled(true);
                        btnRestore.setEnabled(true);
                        if (restoredImei) {
                            for (ImeiChange c : imeiChanges) ImeiHistory.add(MainActivity.this, c.imei);
                        }
                        if (restoredBt) MacHistory.add(MainActivity.this, MacHistory.Kind.BT, s.btMac);
                        if (restoredWifi) MacHistory.add(MainActivity.this, MacHistory.Kind.WIFI, s.wifiMac);

                        boolean any = restoredImei || restoredBt || restoredWifi;
                        if (errors.isEmpty()) {
                            Toast.makeText(MainActivity.this, R.string.ok_restored, Toast.LENGTH_SHORT).show();
                        } else {
                            String head = getString(any ? R.string.err_restore_partial : R.string.err_restore_failed);
                            Toast.makeText(MainActivity.this,
                                head + "\n" + TextUtils.join("\n", errors), Toast.LENGTH_LONG).show();
                        }
                        if (any) {
                            loadImei();
                            loadMac(MacKind.BT);
                            loadMac(MacKind.WIFI);
                            promptReboot();
                        }
                    }
                });
            }
        }).start();
    }

    // ─── Reboot ────────────────────────────────────────────────────────────

    private void promptReboot() {
        if (rebootPromptOpen) return;
        rebootPromptOpen = true;
        new AlertDialog.Builder(this)
            .setTitle(R.string.reboot_title)
            .setMessage(R.string.reboot_msg)
            .setOnDismissListener(new DialogInterface.OnDismissListener() {
                @Override public void onDismiss(DialogInterface d) { rebootPromptOpen = false; }
            })
            .setPositiveButton(R.string.btn_reboot, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) {
                    new Thread(new Runnable() {
                        @Override public void run() { RootRunner.reboot(); }
                    }).start();
                }
            })
            .setNegativeButton(R.string.btn_later, null)
            .show();
    }
}

package com.flipphoneguy.imeiswitcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
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
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private TextView loading;
    private LinearLayout slotsContainer;
    private Button btnChange;
    private Button btnGenerate;
    private LinearLayout historyList;
    private TextView emptyHistory;

    private final Handler ui = new Handler(Looper.getMainLooper());

    /** One UI section per populated slot. */
    private final List<SlotRow> slots = new ArrayList<>();

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

        loading        = findViewById(R.id.loading_text);
        slotsContainer = findViewById(R.id.slots_container);
        btnChange      = findViewById(R.id.btn_change);
        btnGenerate    = findViewById(R.id.btn_generate);
        historyList    = findViewById(R.id.history_list);
        emptyHistory   = findViewById(R.id.empty_history);

        findViewById(R.id.btn_about).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, InfoActivity.class));
            }
        });

        btnChange.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { confirmChange(); }
        });

        btnGenerate.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showGeneratePicker(); }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadSlots();
        renderHistory();
    }

    private void loadSlots() {
        loading.setVisibility(View.VISIBLE);
        slotsContainer.removeAllViews();
        slots.clear();
        btnChange.setEnabled(false);
        btnGenerate.setEnabled(false);

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
                    @Override public void run() { applySlots(imeis, error); }
                });
            }
        }).start();
    }

    private void applySlots(String[] imeis, String error) {
        // Idempotent against re-entry (config change, overlapping loadSlots calls).
        slotsContainer.removeAllViews();
        slots.clear();

        if (imeis == null) {
            loading.setText(error != null ? error : getString(R.string.err_read_failed));
            loading.setVisibility(View.VISIBLE);
            return;
        }

        if (ImeiHistory.seedIfEmpty(this, imeis)) renderHistory();

        // Count populated slots so we know whether to label them "IMEI" or "IMEI 1/2"
        int populated = 0;
        for (String s : imeis) if (s != null) populated++;
        boolean dual = populated > 1;

        LayoutInflater inflater = LayoutInflater.from(this);
        for (int i = 0; i < imeis.length; i++) {
            if (imeis[i] == null) continue;
            View row = inflater.inflate(R.layout.item_slot, slotsContainer, false);

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
                @Override public void afterTextChanged(Editable s) { updateChangeButtonState(); }
            });

            slotsContainer.addView(row);
            slots.add(new SlotRow(i, imeis[i], input));
        }

        loading.setVisibility(View.GONE);
        btnGenerate.setEnabled(!slots.isEmpty());
        updateChangeButtonState();
    }

    private void updateChangeButtonState() {
        boolean anyFilled = false;
        for (SlotRow s : slots) {
            if (s.input.getText().toString().trim().length() > 0) {
                anyFilled = true;
                break;
            }
        }
        btnChange.setEnabled(anyFilled);
    }

    private static class Change {
        final int slotIndex;
        final String imei;
        Change(int s, String i) { this.slotIndex = s; this.imei = i; }
    }

    /** Returns the slots the user actually wants to change, validated. Null on validation failure. */
    private List<Change> collectChanges() {
        List<Change> out = new ArrayList<>();
        for (SlotRow s : slots) {
            String v = s.input.getText().toString().trim();
            if (v.isEmpty()) continue;
            if (!ImeiCrypto.isValidImei(v)) {
                Toast.makeText(this,
                    getString(R.string.err_invalid_imei) + " (IMEI " + (s.slotIndex + 1) + ")",
                    Toast.LENGTH_SHORT).show();
                return null;
            }
            out.add(new Change(s.slotIndex, v));
        }
        return out;
    }

    private void confirmChange() {
        final List<Change> changes = collectChanges();
        if (changes == null) return;
        if (changes.isEmpty()) {
            Toast.makeText(this, R.string.err_no_changes, Toast.LENGTH_SHORT).show();
            return;
        }

        StringBuilder summary = new StringBuilder(getString(R.string.confirm_change_msg))
            .append("\n");
        boolean dual = slots.size() > 1;
        for (Change c : changes) {
            summary.append("\n");
            summary.append(dual
                ? getString(R.string.slot_n, c.slotIndex + 1) + ": "
                : "");
            summary.append(c.imei);
        }

        new AlertDialog.Builder(this)
            .setTitle(R.string.confirm_change_title)
            .setMessage(summary.toString())
            .setPositiveButton(R.string.btn_change, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) {
                    applyChanges(changes);
                }
            })
            .setNegativeButton(R.string.btn_cancel, null)
            .show();
    }

    private void applyChanges(final List<Change> changes) {
        btnChange.setEnabled(false);
        new Thread(new Runnable() {
            @Override public void run() {
                final String error = doPatch(changes);
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (error != null) {
                            btnChange.setEnabled(true);
                            Toast.makeText(MainActivity.this, error, Toast.LENGTH_LONG).show();
                            return;
                        }
                        for (Change c : changes) {
                            ImeiHistory.add(MainActivity.this, c.imei);
                        }
                        Toast.makeText(MainActivity.this, R.string.ok_updated, Toast.LENGTH_SHORT).show();
                        loadSlots();
                        renderHistory();
                        promptReboot();
                    }
                });
            }
        }).start();
    }

    /** Returns null on success, error message on failure. */
    private String doPatch(List<Change> changes) {
        try {
            if (!RootRunner.hasRoot()) return getString(R.string.err_no_root);

            byte[] ld0b = RootRunner.readFile(RootRunner.IMEI_PATH);
            if (ld0b.length != ImeiCrypto.LD0B_SIZE) return getString(R.string.err_pull_size);
            if (!ImeiCrypto.isValidContainer(ld0b)) return getString(R.string.err_decrypt_failed);

            for (Change c : changes) {
                ld0b = ImeiCrypto.patchImei(ld0b, c.slotIndex, c.imei);
            }

            File staging = new File(getCacheDir(), "patched_LD0B_001.bin");
            FileOutputStream fos = new FileOutputStream(staging);
            try { fos.write(ld0b); } finally { fos.close(); }
            staging.setReadable(true, false);

            RootRunner.replaceImeiFile(ld0b, staging.getAbsolutePath());
            staging.delete();
            return null;
        } catch (Exception e) {
            String msg = e.getMessage();
            return getString(R.string.err_write_failed) + (msg != null ? " (" + msg + ")" : "");
        }
    }

    private void promptReboot() {
        new AlertDialog.Builder(this)
            .setTitle(R.string.reboot_title)
            .setMessage(R.string.reboot_msg)
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

    private void renderHistory() {
        historyList.removeAllViews();
        List<String> items = ImeiHistory.load(this);
        if (items.isEmpty()) {
            emptyHistory.setVisibility(View.VISIBLE);
            return;
        }
        emptyHistory.setVisibility(View.GONE);
        LayoutInflater inflater = LayoutInflater.from(this);
        for (final String imei : items) {
            View row = inflater.inflate(R.layout.item_history, historyList, false);
            ((TextView) row.findViewById(R.id.history_imei)).setText(imei);
            row.findViewById(R.id.history_use).setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { applyImeiToSlot(imei); }
            });
            historyList.addView(row);
        }
    }

    private void applyImeiToSlot(final String imei) {
        if (slots.isEmpty()) return;
        if (slots.size() == 1) {
            fillSlot(slots.get(0), imei);
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
                    fillSlot(slots.get(which), imei);
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
            entry.displayName,
            r.imei,
            r.prefixUsed,
            ImeiGenerator.regionForImei(r.imei));

        new AlertDialog.Builder(this)
            .setTitle(R.string.generate_preview_title)
            .setMessage(msg)
            .setPositiveButton(R.string.btn_use, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) {
                    applyImeiToSlot(r.imei);
                }
            })
            .setNeutralButton(R.string.btn_regenerate, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) {
                    showGeneratePreview(entry);
                }
            })
            .setNegativeButton(R.string.btn_cancel, null)
            .show();
    }

    private void fillSlot(SlotRow s, String imei) {
        s.input.setText(imei);
        s.input.setSelection(imei.length());
        s.input.requestFocus();
    }
}

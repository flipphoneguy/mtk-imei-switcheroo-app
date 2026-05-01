package com.flipphoneguy.imeiswitcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;

public class MainActivity extends Activity {

    private TextView currentImeiView;
    private EditText input;
    private Button btnChange;
    private LinearLayout historyList;
    private TextView emptyHistory;

    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        currentImeiView = findViewById(R.id.current_imei);
        input           = findViewById(R.id.input_imei);
        btnChange       = findViewById(R.id.btn_change);
        historyList     = findViewById(R.id.history_list);
        emptyHistory    = findViewById(R.id.empty_history);

        findViewById(R.id.btn_about).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, InfoActivity.class));
            }
        });

        btnChange.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { confirmChange(); }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadCurrentImei();
        renderHistory();
    }

    private void loadCurrentImei() {
        currentImeiView.setText(R.string.loading);
        new Thread(new Runnable() {
            @Override public void run() {
                final String result;
                final boolean ok;
                if (!RootRunner.hasRoot()) {
                    result = getString(R.string.err_no_root);
                    ok = false;
                } else {
                    String r;
                    boolean success = false;
                    try {
                        byte[] data = RootRunner.readFile(RootRunner.IMEI_PATH);
                        if (data.length != ImeiCrypto.LD0B_SIZE) {
                            r = getString(R.string.err_pull_size);
                        } else if (!ImeiCrypto.isValidContainer(data)) {
                            r = getString(R.string.err_decrypt_failed);
                        } else {
                            String imei = ImeiCrypto.readImei(data);
                            r = (imei != null) ? imei : getString(R.string.empty_imei);
                            success = true;
                        }
                    } catch (Exception e) {
                        r = getString(R.string.err_read_failed);
                    }
                    result = r;
                    ok = success;
                }
                ui.post(new Runnable() {
                    @Override public void run() {
                        currentImeiView.setText(result);
                        btnChange.setEnabled(ok);
                    }
                });
            }
        }).start();
    }

    private void confirmChange() {
        final String imei = input.getText().toString().trim();
        if (!ImeiCrypto.isValidImei(imei)) {
            Toast.makeText(this, R.string.err_invalid_imei, Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle(R.string.confirm_change_title)
            .setMessage(getString(R.string.confirm_change_msg) + "\n\n" + imei)
            .setPositiveButton(R.string.btn_change, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) {
                    applyChange(imei);
                }
            })
            .setNegativeButton(R.string.btn_cancel, null)
            .show();
    }

    private void applyChange(final String imei) {
        btnChange.setEnabled(false);
        new Thread(new Runnable() {
            @Override public void run() {
                final String error = doPatch(imei);
                ui.post(new Runnable() {
                    @Override public void run() {
                        btnChange.setEnabled(true);
                        if (error != null) {
                            Toast.makeText(MainActivity.this, error, Toast.LENGTH_LONG).show();
                            return;
                        }
                        ImeiHistory.add(MainActivity.this, imei);
                        input.setText("");
                        Toast.makeText(MainActivity.this, R.string.ok_updated, Toast.LENGTH_SHORT).show();
                        loadCurrentImei();
                        renderHistory();
                        promptReboot();
                    }
                });
            }
        }).start();
    }

    /** Returns null on success, error message on failure. */
    private String doPatch(String imei) {
        try {
            if (!RootRunner.hasRoot()) return getString(R.string.err_no_root);

            byte[] ld0b = RootRunner.readFile(RootRunner.IMEI_PATH);
            if (ld0b.length != ImeiCrypto.LD0B_SIZE) return getString(R.string.err_pull_size);
            if (!ImeiCrypto.isValidContainer(ld0b)) return getString(R.string.err_decrypt_failed);

            byte[] patched = ImeiCrypto.patchImei(ld0b, imei);

            File staging = new File(getCacheDir(), "patched_LD0B_001.bin");
            FileOutputStream fos = new FileOutputStream(staging);
            try { fos.write(patched); } finally { fos.close(); }
            // Make the staging file world-readable so su's cp can definitely read it
            // even if the cache dir's mode would otherwise block traversal.
            staging.setReadable(true, false);

            RootRunner.replaceImeiFile(patched, staging.getAbsolutePath());
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
                @Override public void onClick(View v) {
                    input.setText(imei);
                    input.setSelection(imei.length());
                }
            });
            historyList.addView(row);
        }
    }
}

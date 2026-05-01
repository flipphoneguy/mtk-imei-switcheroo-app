package com.flipphoneguy.imeiswitcher;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

public class InfoActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_info);

        ((TextView) findViewById(R.id.version_text)).setText("v" + currentVersion());

        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });

        link(R.id.btn_github,      R.string.info_github_url);
        link(R.id.btn_repo,        R.string.info_repo_url);
        link(R.id.btn_orig_author, R.string.info_orig_author_url);
        link(R.id.btn_orig_repo,   R.string.info_orig_repo_url);
    }

    private void link(int viewId, final int urlRes) {
        ((Button) findViewById(viewId)).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openUrl(getString(urlRes)); }
        });
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception ignored) {}
    }

    private String currentVersion() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return pi.versionName != null ? pi.versionName : "?";
        } catch (Exception e) {
            return "?";
        }
    }
}

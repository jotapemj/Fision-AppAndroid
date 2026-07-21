package com.jotapeveloper.fision;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;

/** Full-text view for a single licence, opened from LicensesActivity. */
public class LicenseDetailActivity extends AppCompatActivity {

    public static final String EXTRA_NAME   = "license_name";
    public static final String EXTRA_AUTHOR = "license_author";
    public static final String EXTRA_TYPE   = "license_type";
    public static final String EXTRA_URL    = "license_url";
    public static final String EXTRA_TEXT   = "license_text";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_license_detail);

        String name   = getIntent().getStringExtra(EXTRA_NAME);
        String author = getIntent().getStringExtra(EXTRA_AUTHOR);
        String type   = getIntent().getStringExtra(EXTRA_TYPE);
        final String url = getIntent().getStringExtra(EXTRA_URL);
        String text   = getIntent().getStringExtra(EXTRA_TEXT);

        TextView tvTitle = findViewById(R.id.tv_licenseDetailTitle);
        TextView tvMeta  = findViewById(R.id.tv_licenseDetailMeta);
        TextView tvBody  = findViewById(R.id.tv_licenseDetailBody);
        MaterialButton bLink = findViewById(R.id.b_licenseDetailLink);

        tvTitle.setText(name != null ? name : "");
        tvMeta.setText(getString(R.string.license_meta_fmt,
                author != null ? author : "", type != null ? type : ""));
        tvBody.setText(text != null ? text : "");
        bLink.setOnClickListener(v -> {
            if (url == null || url.isEmpty()) return;
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Throwable t) {
                Toast.makeText(this, R.string.login_err_server, Toast.LENGTH_SHORT).show();
            }
        });
    }

    public void closeLicenseDetail(View v) { onBackPressed(); }
}

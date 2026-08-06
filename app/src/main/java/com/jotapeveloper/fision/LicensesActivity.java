package com.jotapeveloper.fision;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

/** Lists every third-party dependency shipped with the app. Tap on a card
 *  opens the full licence text and a link to the project. */
public class LicensesActivity extends AppCompatActivity {

    private static final int VT_HEADER = 0;
    private static final int VT_ITEM   = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        com.jotapeveloper.fision.design.AccentColorManager.get(this).applyOverlayTo(this);
        setContentView(R.layout.activity_licenses);

        RecyclerView rv = findViewById(R.id.rv_licenses);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(new Adapter(LicenseCatalog.all()));
    }

    public void closeLicenses(View v) { onBackPressed(); }

    private class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private final List<LicenseEntry> data;
        Adapter(List<LicenseEntry> data) { this.data = data; }

        @Override public int getItemViewType(int position) {
            return position == 0 ? VT_HEADER : VT_ITEM;
        }

        @Override public int getItemCount() { return data.size() + 1; }

        @NonNull @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inf = LayoutInflater.from(parent.getContext());
            if (viewType == VT_HEADER) {
                return new HeaderVH(inf.inflate(R.layout.layout_licenses_header, parent, false));
            }
            return new RowVH(inf.inflate(R.layout.item_license_row, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (holder instanceof HeaderVH) {
                ((HeaderVH) holder).bind();
            } else {
                LicenseEntry e = data.get(position - 1);
                ((RowVH) holder).bind(e);
            }
        }
    }

    class HeaderVH extends RecyclerView.ViewHolder {
        final TextView tvVersion;
        HeaderVH(@NonNull View v) {
            super(v);
            tvVersion = v.findViewById(R.id.tv_licensesVersion);
        }
        void bind() {
            String vName = "?";
            try {
                vName = getPackageManager()
                        .getPackageInfo(getPackageName(), 0).versionName;
            } catch (Throwable ignored) {}
            tvVersion.setText(getString(R.string.about_version_fmt, vName));
        }
    }

    class RowVH extends RecyclerView.ViewHolder {
        final TextView tvName;
        final TextView tvMeta;
        RowVH(@NonNull View v) {
            super(v);
            tvName = v.findViewById(R.id.tv_licenseName);
            tvMeta = v.findViewById(R.id.tv_licenseMeta);
        }
        void bind(LicenseEntry e) {
            tvName.setText(e.name);
            tvMeta.setText(getString(R.string.license_meta_fmt, e.author, e.license));
            itemView.setOnClickListener(v -> {
                Intent i = new Intent(LicensesActivity.this, LicenseDetailActivity.class);
                i.putExtra(LicenseDetailActivity.EXTRA_NAME,   e.name);
                i.putExtra(LicenseDetailActivity.EXTRA_AUTHOR, e.author);
                i.putExtra(LicenseDetailActivity.EXTRA_TYPE,   e.license);
                i.putExtra(LicenseDetailActivity.EXTRA_URL,    e.url);
                i.putExtra(LicenseDetailActivity.EXTRA_TEXT,   e.fullText);
                startActivity(i);
            });
        }
    }
}

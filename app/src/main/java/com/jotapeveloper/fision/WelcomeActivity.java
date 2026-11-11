package com.jotapeveloper.fision;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;
import androidx.core.widget.TextViewCompat;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.button.MaterialButton;
import com.jotapeveloper.fision.design.AccentColorManager;

import java.util.ArrayList;
import java.util.List;

/**
 * First launch: what Fision is, that songs are split on the user's own
 * server, the notification permission (Android 13+, with its reason), and the
 * choice between connecting to a server and local mode. Returns the choice in
 * {@link #EXTRA_CHOICE}; backing out of the first page cancels.
 */
public class WelcomeActivity extends AppCompatActivity {

    public static final String EXTRA_CHOICE = "choice";
    public static final String CHOICE_SERVER = "server";
    public static final String CHOICE_LOCAL  = "local";

    private static final String SERVER_REPO_URL = "https://github.com/jotapemj/Fision-Server";

    private enum Page { INTRO, SERVER, NOTIFICATIONS, CHOICE }

    private final List<Page> pages = new ArrayList<>();
    private ViewPager2 pager;
    private LinearLayout dots;
    private MaterialButton bNext, bServer, bLocal;
    private View choice;
    private com.jotapeveloper.fision.brand.WelcomeBackdropView backdrop;
    private int accent;

    private final ActivityResultLauncher<String> notificationPermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(),
                    granted -> next());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AccentColorManager.get(this).applyOverlayTo(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_welcome);
        accent = AccentColorManager.resolveAccent(this);

        pages.add(Page.INTRO);
        pages.add(Page.SERVER);
        if (needsNotificationPermission()) pages.add(Page.NOTIFICATIONS);
        pages.add(Page.CHOICE);

        pager = findViewById(R.id.vp_welcome);
        dots = findViewById(R.id.ll_welcomeDots);
        bNext = findViewById(R.id.b_welcomeNext);
        choice = findViewById(R.id.ll_welcomeChoice);
        pager.setAdapter(new PageAdapter());
        backdrop = findViewById(R.id.v_welcomeBackdrop);
        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override public void onPageScrolled(int position, float offset, int px) {
                onScroll(position + offset);
            }
        });
        buildDots();

        bNext.setOnClickListener(v -> next());
        bServer = findViewById(R.id.b_welcomeServer);
        bLocal = findViewById(R.id.b_welcomeLocal);
        bServer.setOnClickListener(v -> finishWith(CHOICE_SERVER));
        bLocal.setOnClickListener(v -> finishWith(CHOICE_LOCAL));

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                int i = pager.getCurrentItem();
                if (i > 0) pager.setCurrentItem(i - 1);
                else { setResult(RESULT_CANCELED); finish(); }
            }
        });
        pager.post(() -> onScroll(pager.getCurrentItem()));
    }

    private boolean needsNotificationPermission() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                   != PackageManager.PERMISSION_GRANTED;
    }

    private void next() {
        int i = pager.getCurrentItem();
        if (i + 1 < pages.size()) pager.setCurrentItem(i + 1);
    }

    private void finishWith(String c) {
        setResult(RESULT_OK, new Intent().putExtra(EXTRA_CHOICE, c));
        finish();
    }

    /**
     * Follows the finger: the next button fades into the two choices on the
     * way to the last page, and the active dot stretches into a pill that
     * slides between dots. The action area has a fixed height, so the pages
     * never move.
     */
    private void onScroll(float pos) {
        backdrop.setPage(pos);
        float dp = getResources().getDisplayMetrics().density;
        float f = Math.max(0f, Math.min(1f, pos - (pages.size() - 2)));   // 0..1 into the last page
        bNext.setAlpha(1f - f);
        bNext.setTranslationY(f * 12 * dp);
        bNext.setEnabled(f < 0.5f);
        bNext.setVisibility(f >= 1f ? View.INVISIBLE : View.VISIBLE);
        choice.setAlpha(f);
        choice.setTranslationY((1f - f) * 12 * dp);
        choice.setVisibility(f <= 0f ? View.INVISIBLE : View.VISIBLE);
        // Half-faded buttons do not take taps.
        boolean choiceOn = f >= 0.5f;
        bServer.setEnabled(choiceOn);
        bLocal.setEnabled(choiceOn);
        for (int i = 0; i < dots.getChildCount(); i++) {
            float a = Math.max(0f, 1f - Math.abs(i - pos));   // 1 on the current page
            View d = dots.getChildAt(i);
            ViewGroup.LayoutParams lp = d.getLayoutParams();
            lp.width = (int) ((8 + 16 * a) * dp);
            d.setLayoutParams(lp);
            d.setAlpha(0.3f + 0.7f * a);
        }
    }

    private void buildDots() {
        float dp = getResources().getDisplayMetrics().density;
        for (int i = 0; i < pages.size(); i++) {
            View d = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(4 * dp);   // dot, or pill when stretched
            g.setColor(accent);
            d.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) (8 * dp), (int) (8 * dp));
            lp.setMargins((int) (5 * dp), 0, (int) (5 * dp), 0);
            dots.addView(d, lp);
        }
    }

    // ------------------------------------------------------------------ pages

    private static final class Holder extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView title, body;
        final MaterialButton action;
        Holder(View v) {
            super(v);
            icon = v.findViewById(R.id.iv_welcomeIcon);
            title = v.findViewById(R.id.tv_welcomeTitle);
            body = v.findViewById(R.id.tv_welcomeBody);
            action = v.findViewById(R.id.b_welcomeAction);
        }
    }

    private final class PageAdapter extends RecyclerView.Adapter<Holder> {
        @NonNull @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.welcome_page, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            h.action.setVisibility(View.GONE);
            h.icon.setImageTintList(null);
            switch (pages.get(position)) {
                case INTRO:
                    bind(h, R.drawable.ic_launcher_fg, false, R.string.welcome_intro_title,
                            R.string.welcome_intro_body, true);
                    break;
                case SERVER:
                    bind(h, R.drawable.ic_server, true, R.string.welcome_server_title,
                            R.string.welcome_server_body, false);
                    h.action.setText(R.string.welcome_server_link);
                    h.action.setVisibility(View.VISIBLE);
                    h.action.setOnClickListener(v -> {
                        try {
                            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(SERVER_REPO_URL)));
                        } catch (Exception ignored) {}
                    });
                    break;
                case NOTIFICATIONS:
                    bind(h, R.drawable.ic_notifications, true, R.string.welcome_notif_title,
                            R.string.welcome_notif_body, false);
                    h.action.setText(R.string.welcome_notif_allow);
                    h.action.setVisibility(View.VISIBLE);
                    h.action.setOnClickListener(v -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
                        }
                    });
                    break;
                case CHOICE:
                    bind(h, R.drawable.ic_fision_mark, true, R.string.welcome_choice_title, R.string.welcome_choice_body);
                    break;
            }
        }

        /** The intro subtitle is a tagline: italic, on one line, shrinking to fit
         *  the biggest system font instead of wrapping. */
        private void styleBody(TextView tv, boolean tagline) {
            Typeface base = ResourcesCompat.getFont(WelcomeActivity.this, R.font.noto_sans_regular);
            tv.setTypeface(base, tagline ? Typeface.ITALIC : Typeface.NORMAL);
            if (tagline) {
                tv.setMaxLines(1);
                TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(tv,
                        11, 16, 1, TypedValue.COMPLEX_UNIT_SP);
            } else {
                TextViewCompat.setAutoSizeTextTypeWithDefaults(tv,
                        TextViewCompat.AUTO_SIZE_TEXT_TYPE_NONE);
                tv.setMaxLines(Integer.MAX_VALUE);
            }
        }

        private void bind(Holder h, @DrawableRes int icon, boolean tint, @StringRes int title, @StringRes int body) {
            bind(h, icon, tint, title, body, false);
        }

        private void bind(Holder h, @DrawableRes int icon, boolean tint, @StringRes int title,
                          @StringRes int body, boolean tagline) {
            h.icon.setImageResource(icon);
            // The launcher artwork carries the adaptive-icon safe margin: shown larger.
            int size = (int) ((icon == R.drawable.ic_launcher_fg ? 220 : 120)
                    * getResources().getDisplayMetrics().density);
            ViewGroup.LayoutParams lp = h.icon.getLayoutParams();
            lp.width = lp.height = size;
            h.icon.setLayoutParams(lp);
            if (tint) h.icon.setImageTintList(ColorStateList.valueOf(accent));
            h.title.setText(title);
            h.body.setText(body);
            styleBody(h.body, tagline);
        }

        @Override public int getItemCount() { return pages.size(); }
    }
}

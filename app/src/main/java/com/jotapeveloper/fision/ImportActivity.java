package com.jotapeveloper.fision;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.gson.Gson;
import com.jotapeveloper.fision.cloud.ApiClient;
import com.jotapeveloper.fision.cloud.CloudRepository;
import com.jotapeveloper.fision.cloud.ContentUriRequestBody;
import com.jotapeveloper.fision.cloud.FisionApi;
import com.jotapeveloper.fision.cloud.JobSubmitResponse;
import com.jotapeveloper.fision.cloud.ProgressRequestBody;
import com.jotapeveloper.fision.cloud.ServerPrefs;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Wizard to submit audio to the self-hosted server. Mirror of the Python GUI:
 *   - Classic (4stem/2stem) vs Personalized (vocals + per-stem checks).
 *   - Device CPU/CUDA. Chords always BTC (chordino deprecated).
 *   - Sends POST /jobs multipart with config_json ("rich" format
 *     {sep, device, chords} understood by headless_fision_CLI._load_config).
 */
public class ImportActivity extends AppCompatActivity {

    /** Optional extra: hash of an already-uploaded song. When present, the activity
     *  starts in REPROCESS mode: no file picker, prefills the current config, "Reprocess"
     *  button and calls POST /library/{hash}/reprocess. */
    public static final String EXTRA_REPROCESS_HASH = "reprocessHash";

    private Uri selectedUri = null;
    private String selectedFilename = null;
    private String reprocessHash = null;    // != null while in reprocess mode

    private TextView tvFilename;
    private MaterialButton bSend;
    // Sticky header with cover + title + artist
    private android.widget.ImageView ivHeaderCover;
    private TextView tvHeaderTitle, tvHeaderArtist;
    private View tvDrumsSplitHint;

    private MaterialButtonToggleGroup segSepGroup, segClassicPreset, segVocalsVar,
            segDrumsVar, segDevice;
    private View panelClassic, panelPersonalized, segDrumsVarBar;
    private MaterialCheckBox cbGuitar, cbBass, cbDrums, cbPiano, cbBrass;

    private final Handler main = new Handler(Looper.getMainLooper());
    private AlertDialog uploadDialog;
    private ProgressBar pbUpload;
    private TextView tvUploadSubtitle;
    private Call<JobSubmitResponse> activeUploadCall;
    private long lastUploadNotifyNs = 0L;
    private boolean uploadCancelled = false;

    private final ActivityResultLauncher<String[]> filePicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) return;
                try {
                    getContentResolver().takePersistableUriPermission(uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Throwable ignored) {}
                selectedUri = uri;
                selectedFilename = queryDisplayName(uri);
                tvFilename.setText(selectedFilename != null
                        ? selectedFilename : getString(R.string.import_no_file));
                refreshHeaderFromSelectedUri();
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Cloud pipeline only: bail out if the user is not signed in. Prevents
        // the activity from being reached from external intents while in local mode.
        if (!com.jotapeveloper.fision.cloud.AuthManager.get(this).isLoggedIn()) {
            android.widget.Toast.makeText(this,
                    R.string.local_mode_import_blocked,
                    android.widget.Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        setContentView(R.layout.activity_import);

        // toolbar back
        findViewById(R.id.b_import_back).setOnClickListener(v -> finish());

        tvFilename = findViewById(R.id.tv_import_filename);
        bSend      = findViewById(R.id.b_import_send);
        ivHeaderCover  = findViewById(R.id.iv_import_cover);
        tvHeaderTitle  = findViewById(R.id.tv_import_headerTitle);
        tvHeaderArtist = findViewById(R.id.tv_import_headerArtist);
        tvDrumsSplitHint = findViewById(R.id.tv_import_drumsSplitHint);

        // The header and the edit (pencil) button open the file picker
        View pickBtn = findViewById(R.id.b_import_pickFile);
        View header  = findViewById(R.id.ll_import_header);
        View.OnClickListener openPicker = v -> filePicker.launch(new String[]{"audio/*"});
        if (pickBtn != null) pickBtn.setOnClickListener(openPicker);
        if (header  != null) header.setOnClickListener(openPicker);

        // segments
        segSepGroup       = findViewById(R.id.seg_sep_group);
        segClassicPreset  = findViewById(R.id.seg_classic_preset);
        segVocalsVar      = findViewById(R.id.seg_vocals_variant);
        segDrumsVar       = findViewById(R.id.seg_drums_variant);
        segDevice         = findViewById(R.id.seg_device);
        panelClassic      = findViewById(R.id.panel_classic);
        panelPersonalized = findViewById(R.id.panel_personalized);
        segDrumsVarBar    = findViewById(R.id.seg_drums_variant);

        cbGuitar = findViewById(R.id.cb_stem_guitar);
        cbBass   = findViewById(R.id.cb_stem_bass);
        cbDrums  = findViewById(R.id.cb_stem_drums);
        cbPiano  = findViewById(R.id.cb_stem_piano);
        cbBrass  = findViewById(R.id.cb_stem_brass);

        // defaults
        segSepGroup.check(R.id.b_sep_classic);
        segClassicPreset.check(R.id.b_classic_4stem);
        segVocalsVar.check(R.id.b_vocals_single);
        segDrumsVar.check(R.id.b_drums_full);
        segDevice.check(R.id.b_dev_cpu);

        // Separation mode change: animate the transition with TransitionManager so the
        // panel_personalized expands / collapses smoothly (fade + reflow).
        segSepGroup.addOnButtonCheckedListener((g, id, checked) -> {
            if (!checked) return;
            android.view.ViewGroup root = findViewById(R.id.ll_import_root);
            if (root != null) {
                androidx.transition.TransitionSet set = new androidx.transition.TransitionSet();
                set.addTransition(new androidx.transition.Fade());
                set.addTransition(new androidx.transition.ChangeBounds());
                set.setDuration(260);
                set.setInterpolator(new android.view.animation.DecelerateInterpolator());
                androidx.transition.TransitionManager.beginDelayedTransition(root, set);
            }
            boolean classic = id == R.id.b_sep_classic;
            panelClassic.setVisibility(classic ? View.VISIBLE : View.GONE);
            panelPersonalized.setVisibility(classic ? View.GONE : View.VISIBLE);
        });
        // Initial state without animation (the listener animates later changes).
        segDrumsVarBar.setVisibility(cbDrums.isChecked() ? View.VISIBLE : View.GONE);
        // Seed the initial tag so the first toggle already animates.
        segDrumsVarBar.setTag(R.id.tag_expand_state, cbDrums.isChecked());

        // "Split drums" hint: only visible when that option is selected and the
        // personalized panel has drums active.
        segDrumsVar.addOnButtonCheckedListener((g, id, checked) -> refreshDrumsSplitHint());
        cbDrums.setOnCheckedChangeListener((b, checked) -> {
            animateExpandCollapse(segDrumsVarBar, checked);
            refreshDrumsSplitHint();
        });
        // Seed the hint tag too (initially invisible).
        tvDrumsSplitHint.setTag(R.id.tag_expand_state,
                cbDrums.isChecked() && segDrumsVar.getCheckedButtonId() == R.id.b_drums_split);
        refreshDrumsSplitHint();

        // Dynamic tint of the toggle groups: the active button becomes colorPrimary with
        // white text; the rest stay with translucent grey stroke. Chip-style, same as
        // in the order_by and single_choice dialogs.
        attachSegmentTint(segSepGroup);
        attachSegmentTint(segClassicPreset);
        attachSegmentTint(segVocalsVar);
        attachSegmentTint(segDrumsVar);
        attachSegmentTint(segDevice);

        bSend.setOnClickListener(v -> onSendClicked());

        // Reprocess mode: hash passed via intent extra
        reprocessHash = getIntent().getStringExtra(EXTRA_REPROCESS_HASH);
        if (reprocessHash != null) applyReprocessMode();
    }

    /** Configures the activity in reprocess mode: no picker, header from the
     *  CloudItem, "Reprocess" button and UI prefill from the current config. */
    private void applyReprocessMode() {
        com.jotapeveloper.fision.cloud.CloudItem ci =
                com.jotapeveloper.fision.cloud.CloudRepository.get(this).getByHash(reprocessHash);
        if (ci == null) {
            Toast.makeText(this, R.string.reprocess_failed, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // Toolbar title and main button
        TextView tvToolbarTitle = null;
        // The title TextView lives inside the toolbar without an id; located by scanning
        androidx.appcompat.widget.Toolbar tb = findViewById(R.id.import_toolbar);
        if (tb != null) {
            for (int i = 0; i < tb.getChildCount(); i++) {
                View child = tb.getChildAt(i);
                if (child instanceof android.view.ViewGroup) {
                    android.view.ViewGroup vg = (android.view.ViewGroup) child;
                    for (int j = 0; j < vg.getChildCount(); j++) {
                        View sub = vg.getChildAt(j);
                        if (sub instanceof TextView) { tvToolbarTitle = (TextView) sub; break; }
                    }
                }
                if (tvToolbarTitle != null) break;
            }
        }
        if (tvToolbarTitle != null) tvToolbarTitle.setText(R.string.import_reprocess_title);
        bSend.setText(R.string.import_reprocess_short);

        // Hide the edit (pencil) button and make the header non-clickable
        View pickBtn = findViewById(R.id.b_import_pickFile);
        if (pickBtn != null) pickBtn.setVisibility(View.GONE);
        View header = findViewById(R.id.ll_import_header);
        if (header != null) {
            header.setOnClickListener(null);
            header.setClickable(false);
        }

        // Header: title, artist and cover pulled from the CloudItem
        String title = (ci.displayName != null && !ci.displayName.isEmpty())
                ? ci.displayName : reprocessHash;
        tvHeaderTitle.setText(title);
        if (ci.artist != null && !ci.artist.isEmpty()) {
            tvHeaderArtist.setText(ci.artist);
            tvHeaderArtist.setVisibility(View.VISIBLE);
        } else {
            tvHeaderArtist.setVisibility(View.GONE);
        }
        if (ci.coverPath != null) {
            android.graphics.Bitmap bm = android.graphics.BitmapFactory.decodeFile(ci.coverPath);
            if (bm != null) ivHeaderCover.setImageBitmap(bm);
        }

        // Prefill UI from the current server config
        if (ci.config != null) prefillFromConfig(ci.config);
    }

    /** Applies UI checks/segments from a previous server config
     *  ("rich" format: {sep:{mode, preset|vocals+stems}, device, chords}). Missing or
     *  unknown fields keep the default already selected in onCreate. */
    private void prefillFromConfig(Map<String, Object> cfg) {
        Object device = cfg.get("device");
        if ("cuda".equals(device)) segDevice.check(R.id.b_dev_cuda);
        else                        segDevice.check(R.id.b_dev_cpu);

        // Server "chords": BTC only in the current version. The field is ignored
        // (retro-compat with old configs that still carried "chordino").

        Object sepObj = cfg.get("sep");
        if (!(sepObj instanceof Map)) return;
        @SuppressWarnings("unchecked")
        Map<String, Object> sep = (Map<String, Object>) sepObj;
        String mode = String.valueOf(sep.get("mode"));

        if ("classic".equals(mode)) {
            segSepGroup.check(R.id.b_sep_classic);
            panelClassic.setVisibility(View.VISIBLE);
            panelPersonalized.setVisibility(View.GONE);
            String preset = String.valueOf(sep.get("preset"));
            Object smObj = sep.get("sep_mode");
            int sepMode = (smObj instanceof Number) ? ((Number) smObj).intValue() : -1;
            boolean fourStem = "4stem".equals(preset) || sepMode == 4;
            segClassicPreset.check(fourStem ? R.id.b_classic_4stem : R.id.b_classic_2stem);
        } else if ("personalized".equals(mode)) {
            segSepGroup.check(R.id.b_sep_personalized);
            panelClassic.setVisibility(View.GONE);
            panelPersonalized.setVisibility(View.VISIBLE);
            segVocalsVar.check("split".equals(sep.get("vocals"))
                    ? R.id.b_vocals_split : R.id.b_vocals_single);

            Object stemsObj = sep.get("stems");
            if (stemsObj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> stems = (Map<String, Object>) stemsObj;
                cbGuitar.setChecked(isStemEnabled(stems, "guitar"));
                cbBass.setChecked  (isStemEnabled(stems, "bass"));
                cbDrums.setChecked (isStemEnabled(stems, "drums"));
                cbPiano.setChecked (isStemEnabled(stems, "piano"));
                cbBrass.setChecked (isStemEnabled(stems, "brass"));
                segDrumsVar.check("split".equals(stemVariant(stems, "drums"))
                        ? R.id.b_drums_split : R.id.b_drums_full);
                // sync visibility + tags of the drums variant row and the hint
                boolean drums = cbDrums.isChecked();
                segDrumsVarBar.setVisibility(drums ? View.VISIBLE : View.GONE);
                segDrumsVarBar.setTag(R.id.tag_expand_state, drums);
                boolean split = drums && segDrumsVar.getCheckedButtonId() == R.id.b_drums_split;
                tvDrumsSplitHint.setVisibility(split ? View.VISIBLE : View.GONE);
                tvDrumsSplitHint.setTag(R.id.tag_expand_state, split);
            }
        }
    }

    private static boolean isStemEnabled(Map<String, Object> stems, String key) {
        Object o = stems.get(key);
        if (!(o instanceof Map)) return false;
        Object en = ((Map<?, ?>) o).get("enabled");
        return en instanceof Boolean ? (Boolean) en : false;
    }
    private static String stemVariant(Map<String, Object> stems, String key) {
        Object o = stems.get(key);
        if (!(o instanceof Map)) return null;
        Object v = ((Map<?, ?>) o).get("variant");
        return v != null ? v.toString() : null;
    }

    /** Keeps the visual coherence of the MaterialButtonToggleGroups: the selected
     *  button is painted with colorPrimary (app green background), the rest stay
     *  transparent with colorPrimary stroke. Without this, the MaterialComponents
     *  default leaves them all grey and the active one is not obvious. */
    private void attachSegmentTint(MaterialButtonToggleGroup group) {
        int primary   = androidx.core.content.ContextCompat.getColor(this, R.color.colorPrimary);
        int white     = androidx.core.content.ContextCompat.getColor(this, R.color.AppWhite);
        int transparent = android.graphics.Color.TRANSPARENT;
        Runnable apply = () -> {
            int checked = group.getCheckedButtonId();
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (!(child instanceof MaterialButton)) continue;
                MaterialButton b = (MaterialButton) child;
                boolean on = b.getId() == checked;
                b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                        on ? primary : transparent));
                b.setStrokeColor(android.content.res.ColorStateList.valueOf(primary));
                b.setTextColor(white);
            }
        };
        group.addOnButtonCheckedListener((g, id, isChecked) -> apply.run());
        group.post(apply);
    }

    private void refreshDrumsSplitHint() {
        if (tvDrumsSplitHint == null) return;
        boolean visible = cbDrums.isChecked()
                && segDrumsVar.getCheckedButtonId() == R.id.b_drums_split;
        animateExpandCollapse(tvDrumsSplitHint, visible);
    }

    /** Height + alpha expand/collapse animation. Idempotent for repeated calls with
     *  the same state (avoids restarting the animator when visibility already matches). */
    private void animateExpandCollapse(final View v, final boolean expand) {
        if (v == null) return;
        Object tag = v.getTag(R.id.tag_expand_state);
        Boolean current = (tag instanceof Boolean) ? (Boolean) tag : null;
        if (current != null && current == expand) return;
        // First call (no tag): set the state without animating. Prevents a dirty
        // animation from firing when the activity is inflated.
        if (current == null) {
            v.setTag(R.id.tag_expand_state, expand);
            v.setVisibility(expand ? View.VISIBLE : View.GONE);
            v.setAlpha(1f);
            return;
        }
        v.setTag(R.id.tag_expand_state, expand);

        Object prev = v.getTag(R.id.tag_expand_anim);
        if (prev instanceof android.animation.ValueAnimator) {
            ((android.animation.ValueAnimator) prev).cancel();
        }

        final android.view.ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (expand) {
            v.setVisibility(View.VISIBLE);
            // Measure at wrap_content to know the target height.
            int wSpec = View.MeasureSpec.makeMeasureSpec(
                    ((View) v.getParent()).getWidth(), View.MeasureSpec.AT_MOST);
            int hSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
            v.measure(wSpec, hSpec);
            final int target = v.getMeasuredHeight();
            android.animation.ValueAnimator anim = android.animation.ValueAnimator.ofInt(0, target);
            anim.setDuration(220);
            anim.setInterpolator(new android.view.animation.DecelerateInterpolator());
            anim.addUpdateListener(a -> {
                lp.height = (int) a.getAnimatedValue();
                v.setLayoutParams(lp);
                v.setAlpha(a.getAnimatedFraction());
            });
            anim.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator a) {
                    lp.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
                    v.setLayoutParams(lp);
                    v.setAlpha(1f);
                }
            });
            v.setTag(R.id.tag_expand_anim, anim);
            anim.start();
        } else {
            final int start = v.getHeight() > 0 ? v.getHeight() : v.getMeasuredHeight();
            android.animation.ValueAnimator anim = android.animation.ValueAnimator.ofInt(start, 0);
            anim.setDuration(200);
            anim.setInterpolator(new android.view.animation.AccelerateInterpolator());
            anim.addUpdateListener(a -> {
                lp.height = (int) a.getAnimatedValue();
                v.setLayoutParams(lp);
                v.setAlpha(1f - a.getAnimatedFraction());
            });
            anim.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator a) {
                    v.setVisibility(View.GONE);
                    lp.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
                    v.setLayoutParams(lp);
                    v.setAlpha(1f);
                }
            });
            v.setTag(R.id.tag_expand_anim, anim);
            anim.start();
        }
    }

    /** Extracts local metadata from the selected audio (title/artist/cover) via
     *  MediaMetadataRetriever and drops it into the sticky header. Fallback cover = generic. */
    private void refreshHeaderFromSelectedUri() {
        if (selectedUri == null) return;
        String title = null, artist = null;
        android.graphics.Bitmap cover = null;
        android.media.MediaMetadataRetriever mmr = new android.media.MediaMetadataRetriever();
        try {
            mmr.setDataSource(this, selectedUri);
            title  = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE);
            artist = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST);
            byte[] art = mmr.getEmbeddedPicture();
            if (art != null && art.length > 0) {
                cover = android.graphics.BitmapFactory.decodeByteArray(art, 0, art.length);
            }
        } catch (Throwable ignored) {
        } finally {
            try { mmr.release(); } catch (Throwable ignored) {}
        }
        if (title == null || title.isEmpty()) title = stripExtension(selectedFilename);
        tvHeaderTitle.setText(title);
        if (artist != null && !artist.isEmpty()) {
            tvHeaderArtist.setText(artist);
            tvHeaderArtist.setVisibility(View.VISIBLE);
        } else {
            tvHeaderArtist.setVisibility(View.GONE);
        }
        if (cover != null) ivHeaderCover.setImageBitmap(cover);
        else ivHeaderCover.setImageResource(R.raw.artcover_generic);
    }

    // ---------------------------------------------------------------
    // Submit
    // ---------------------------------------------------------------
    private void onSendClicked() {
        if (!com.jotapeveloper.fision.cloud.AuthManager.get(this).isLoggedIn()) {
            Toast.makeText(this, R.string.local_mode_import_blocked, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        if (!ServerPrefs.get(this).isConfigured()) {
            Toast.makeText(this, R.string.import_no_server, Toast.LENGTH_LONG).show();
            return;
        }
        FisionApi api = ApiClient.get(this).api();
        if (api == null) {
            Toast.makeText(this, R.string.import_no_server, Toast.LENGTH_LONG).show();
            return;
        }

        String configJson = new Gson().toJson(buildConfig());

        // REPROCESS MODE: no upload, only POST /library/{hash}/reprocess with the
        // new config_json. The input mp3 already lives on the server.
        if (reprocessHash != null) {
            onReprocessClicked(api, configJson);
            return;
        }

        if (selectedUri == null) {
            Toast.makeText(this, R.string.import_no_file, Toast.LENGTH_SHORT).show();
            return;
        }

        ContentResolver cr = getContentResolver();
        String mime = cr.getType(selectedUri);
        if (TextUtils.isEmpty(mime)) mime = "application/octet-stream";

        // Wrap the body with a ProgressRequestBody that counts bytes live.
        RequestBody rawBody = new ContentUriRequestBody(cr, selectedUri, mime);
        ProgressRequestBody fileBody = new ProgressRequestBody(rawBody, (sent, total) -> {
            // Throttle to ~12 updates/s to avoid saturating the UI thread
            long now = System.nanoTime();
            if (now - lastUploadNotifyNs < 80_000_000L && sent < total) return;
            lastUploadNotifyNs = now;
            main.post(() -> updateUploadUi(sent, total));
        });
        String filename = selectedFilename != null ? selectedFilename : "audio";
        MultipartBody.Part filePart = MultipartBody.Part.createFormData("file", filename, fileBody);
        RequestBody cfgBody = RequestBody.create(configJson,
                MediaType.parse("application/json; charset=utf-8"));

        uploadCancelled = false;
        showUploadDialog();

        // Extract local metadata (title/artist) + embedded cover so the list shows
        // something readable while the server processes, instead of the hash or filename.
        LocalMeta meta = extractLocalMetadata(selectedUri, filename);
        final String displayName = meta.title;
        final String artist = meta.artist;
        final String coverPath = meta.coverPath;

        activeUploadCall = api.submitJob(filePart, cfgBody);
        activeUploadCall.enqueue(new Callback<JobSubmitResponse>() {
            @Override public void onResponse(Call<JobSubmitResponse> c, Response<JobSubmitResponse> r) {
                main.post(() -> {
                    dismissUploadDialog();
                    if (uploadCancelled) return;
                    if (!r.isSuccessful() || r.body() == null) {
                        Toast.makeText(ImportActivity.this,
                                getString(R.string.import_upload_failed) + " (" + r.code() + ")",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    String finalCover = coverPath;
                    // Rename cover_pending → cover_<hash> to associate it with the final item
                    if (finalCover != null && r.body().hash != null) {
                        finalCover = renameCoverForHash(coverPath, r.body().hash);
                    }
                    CloudRepository.get(ImportActivity.this)
                            .onJobSubmitted(r.body(), displayName, artist, finalCover);
                    setResult(Activity.RESULT_OK);
                    finish();
                });
            }
            @Override public void onFailure(Call<JobSubmitResponse> c, Throwable t) {
                main.post(() -> {
                    dismissUploadDialog();
                    if (uploadCancelled) return;
                    Toast.makeText(ImportActivity.this,
                            getString(R.string.import_upload_failed) + ": " + t.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    /** Reprocess: POST /library/{hash}/reprocess with the current config. No upload,
     *  quick response (the server enqueues and replies with a job_id). Same callbacks
     *  to CloudRepository as a normal submit for MainActivity polling. */
    private void onReprocessClicked(FisionApi api, String configJson) {
        bSend.setEnabled(false);
        final String hash = reprocessHash;
        Call<JobSubmitResponse> call = api.reprocess(hash, configJson);
        call.enqueue(new Callback<JobSubmitResponse>() {
            @Override public void onResponse(Call<JobSubmitResponse> c, Response<JobSubmitResponse> r) {
                main.post(() -> {
                    bSend.setEnabled(true);
                    if (r.code() == 404) {
                        Toast.makeText(ImportActivity.this,
                                R.string.reprocess_missing_input, Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (!r.isSuccessful() || r.body() == null) {
                        Toast.makeText(ImportActivity.this,
                                getString(R.string.reprocess_failed) + " (" + r.code() + ")",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    // Reflect the new job in the repo; onJobSubmitted updates jobId
                    // and state without stomping the CloudItem displayName/artist/coverPath.
                    com.jotapeveloper.fision.cloud.CloudItem ci =
                            com.jotapeveloper.fision.cloud.CloudRepository.get(ImportActivity.this)
                                    .getByHash(hash);
                    String displayName = (ci != null) ? ci.displayName : null;
                    String artist      = (ci != null) ? ci.artist      : null;
                    String coverPath   = (ci != null) ? ci.coverPath   : null;
                    com.jotapeveloper.fision.cloud.CloudRepository.get(ImportActivity.this)
                            .onJobSubmitted(r.body(), displayName, artist, coverPath);
                    Toast.makeText(ImportActivity.this, R.string.reprocess_success,
                            Toast.LENGTH_SHORT).show();
                    setResult(Activity.RESULT_OK);
                    finish();
                });
            }
            @Override public void onFailure(Call<JobSubmitResponse> c, Throwable t) {
                main.post(() -> {
                    bSend.setEnabled(true);
                    Toast.makeText(ImportActivity.this,
                            getString(R.string.reprocess_failed) + ": " + t.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    // ---------------------------------------------------------------
    // Upload dialog with real progress + blur
    // ---------------------------------------------------------------
    private void showUploadDialog() {
        LayoutInflater inflater = LayoutInflater.from(this);
        View dialogView = inflater.inflate(R.layout.upload_progress_dialog, null);
        pbUpload = dialogView.findViewById(R.id.pb_upload);
        tvUploadSubtitle = dialogView.findViewById(R.id.tv_uploadSubtitle);
        MaterialButton bCancel = dialogView.findViewById(R.id.b_uploadCancel);
        tvUploadSubtitle.setText("0%");

        uploadDialog = new AlertDialog.Builder(this, R.style.Theme_Fision_BlurDialog)
                .setView(dialogView)
                .setCancelable(false)
                .create();
        android.view.Window win = uploadDialog.getWindow();
        if (win != null) win.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        applyActivityBlur();
        uploadDialog.setOnDismissListener(d -> removeActivityBlur());
        uploadDialog.show();
        bSend.setEnabled(false);

        bCancel.setOnClickListener(v -> {
            uploadCancelled = true;
            if (activeUploadCall != null) activeUploadCall.cancel();
            dismissUploadDialog();
        });
    }

    private void updateUploadUi(long sent, long total) {
        if (pbUpload == null || tvUploadSubtitle == null) return;
        if (total > 0) {
            int pct = (int) Math.min(100, (sent * 100L) / total);
            pbUpload.setProgress(pct);
            tvUploadSubtitle.setText(pct + "% · " + humanBytes(sent) + " / " + humanBytes(total));
        } else {
            tvUploadSubtitle.setText(humanBytes(sent));
        }
    }

    private void dismissUploadDialog() {
        if (uploadDialog != null && uploadDialog.isShowing()) {
            try { uploadDialog.dismiss(); } catch (Throwable ignored) {}
        }
        uploadDialog = null;
        activeUploadCall = null;
        bSend.setEnabled(true);
    }

    private void applyActivityBlur() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getWindow().getDecorView().setRenderEffect(
                    android.graphics.RenderEffect.createBlurEffect(
                            40f, 40f, android.graphics.Shader.TileMode.CLAMP));
        }
    }

    private void removeActivityBlur() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getWindow().getDecorView().setRenderEffect(null);
        }
    }

    private static String humanBytes(long n) {
        if (n < 1024) return n + " B";
        double v = n;
        String[] u = {"KB", "MB", "GB"};
        int i = -1;
        do { v /= 1024.0; i++; } while (v >= 1024.0 && i < u.length - 1);
        return String.format(java.util.Locale.US, "%.1f %s", v, u[i]);
    }

    // ---------------------------------------------------------------
    // Config builder: rich format { sep, device, chords }
    // ---------------------------------------------------------------
    private Map<String, Object> buildConfig() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("sep", buildSepConfig());
        root.put("device", segDevice.getCheckedButtonId() == R.id.b_dev_cuda ? "cuda" : "cpu");
        // Chordino deprecated: always BTC. Field kept for server compatibility.
        root.put("chords", "btc");
        return root;
    }

    private Map<String, Object> buildSepConfig() {
        Map<String, Object> sep = new LinkedHashMap<>();
        if (segSepGroup.getCheckedButtonId() == R.id.b_sep_classic) {
            boolean fourStem = segClassicPreset.getCheckedButtonId() == R.id.b_classic_4stem;
            sep.put("mode", "classic");
            sep.put("preset", fourStem ? "4stem" : "2stem");
            sep.put("sep_mode", fourStem ? 4 : 2);
        } else {
            sep.put("mode", "personalized");
            sep.put("vocals",
                    segVocalsVar.getCheckedButtonId() == R.id.b_vocals_split ? "split" : "single");

            Map<String, Object> stems = new LinkedHashMap<>();
            stems.put("guitar", stemEntry(cbGuitar.isChecked(), null));
            stems.put("bass",   stemEntry(cbBass.isChecked(),   null));
            stems.put("drums",  stemEntry(cbDrums.isChecked(),
                    segDrumsVar.getCheckedButtonId() == R.id.b_drums_split ? "split" : "full"));
            stems.put("piano",  stemEntry(cbPiano.isChecked(),  null));
            stems.put("brass",  stemEntry(cbBrass.isChecked(),  null));
            sep.put("stems", stems);
        }
        return sep;
    }

    private static Map<String, Object> stemEntry(boolean enabled, String variant) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", enabled);
        if (variant != null) m.put("variant", variant);
        return m;
    }

    // ---------------------------------------------------------------
    // Util: human name of the Uri
    // ---------------------------------------------------------------
    private String queryDisplayName(Uri uri) {
        String name = null;
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) name = c.getString(idx);
            }
        } catch (Throwable ignored) {}
        return name != null ? name : uri.getLastPathSegment();
    }

    // ---------------------------------------------------------------
    // Local metadata: extract title/artist/cover from the audio before upload
    // to show it in the list while the server processes.
    // ---------------------------------------------------------------
    private static class LocalMeta {
        final String title;
        final String artist;
        final String coverPath;
        LocalMeta(String t, String a, String c) { title = t; artist = a; coverPath = c; }
    }

    private LocalMeta extractLocalMetadata(Uri uri, String fallbackFilename) {
        String title = null, artist = null, coverPath = null;
        android.media.MediaMetadataRetriever mmr = new android.media.MediaMetadataRetriever();
        try {
            mmr.setDataSource(this, uri);
            title  = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE);
            artist = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST);
            byte[] art = mmr.getEmbeddedPicture();
            if (art != null && art.length > 0) {
                // Pending cover: temporary name until the server hash is known
                java.io.File f = new java.io.File(getCacheDir(),
                        "cover_pending_" + System.currentTimeMillis() + ".png");
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(f)) {
                    fos.write(art);
                    coverPath = f.getAbsolutePath();
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {
        } finally {
            try { mmr.release(); } catch (Throwable ignored) {}
        }
        if (title == null || title.isEmpty()) {
            // Fallback: filename without extension
            title = stripExtension(fallbackFilename);
        }
        return new LocalMeta(title, artist, coverPath);
    }

    /** Moves cover_pending_*.png to cover_<hash>.png to associate it with the final item. */
    private String renameCoverForHash(String pendingPath, String hash) {
        if (pendingPath == null || hash == null) return pendingPath;
        java.io.File src = new java.io.File(pendingPath);
        if (!src.exists()) return null;
        java.io.File dst = new java.io.File(getCacheDir(), "cover_" + hash + ".png");
        if (src.renameTo(dst)) return dst.getAbsolutePath();
        return pendingPath;
    }

    private static String stripExtension(String name) {
        if (name == null) return "audio";
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}

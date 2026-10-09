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
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.gson.Gson;
import com.jotapeveloper.fision.cloud.ApiClient;
import com.jotapeveloper.fision.cloud.CloudRepository;
import com.jotapeveloper.fision.cloud.ContentUriRequestBody;
import com.jotapeveloper.fision.cloud.FisionApi;
import com.jotapeveloper.fision.cloud.JobSubmitResponse;
import com.jotapeveloper.fision.cloud.ProgressRequestBody;
import com.jotapeveloper.fision.cloud.ServerPrefs;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Submits audio to the self-hosted server.
 *   - The song arrives already picked (intent data); the hero card shows it
 *     and offers choosing another one.
 *   - Separation: the two classic Demucs presets (4 / 2 stems) or a custom
 *     stem set chosen on a tile grid. Editing any tile switches to custom.
 *   - Sends POST /jobs multipart with config_json ("rich" format
 *     {sep, device, chords} understood by headless_fision_CLI._load_config).
 */
public class ImportActivity extends AppCompatActivity implements ImportStemTileAdapter.Host {

    /** Optional extra: hash of an already-uploaded song. When present, the activity
     *  starts in REPROCESS mode: no file picker, prefills the current config, "Reprocess"
     *  button and calls POST /library/{hash}/reprocess. */
    public static final String EXTRA_REPROCESS_HASH = "reprocessHash";

    /** Row order of the stem list. "winds" maps to the server key "brass";
     *  "other" is the rest of the mix, not a server stem. */
    private static final List<String> TILE_STEMS =
            Arrays.asList("vocals", "guitar", "bass", "drums", "piano", "winds",
                    ImportStemTileAdapter.REST_STEM);
    /** Stems isolated by the classic 4-stem preset (besides the rest of the mix). */
    private static final Set<String> PRESET_4STEM =
            new HashSet<>(Arrays.asList("vocals", "bass", "drums"));
    private static final Set<String> PRESET_2STEM =
            new HashSet<>(Arrays.asList("vocals"));

    private Uri selectedUri = null;
    private String selectedFilename = null;
    private String reprocessHash = null;    // != null while in reprocess mode

    private MaterialButton bSend;
    // Hero card: cover + title + artist + file details
    private android.widget.ImageView ivHeaderCover, ivHeroBg;
    private View vHeroScrim, vHeroCorner;
    private TextView tvHeaderTitle, tvHeaderArtist, tvHeaderMeta;

    // Separation state. The custom set survives switching to a preset and back.
    private MaterialButtonToggleGroup segSepMode;
    private ImportStemTileAdapter tileAdapter;
    private final Set<String> customSelected = new HashSet<>(PRESET_4STEM);
    private boolean customVocalsSplit = false;
    private boolean customDrumsSplit = false;

    // GPU-aware submission: banner shown when the server has no GPU + blocking
    // overlay while ServerInfo is still being fetched.
    private View gpuWarningBanner;
    private View loadingOverlay;
    private final com.jotapeveloper.fision.cloud.ServerInfoManager.Listener serverInfoListener =
            this::applyServerInfoState;

    private final Handler main = new Handler(Looper.getMainLooper());
    private AlertDialog uploadDialog;
    private ProgressBar pbUpload;
    private TextView tvUploadSubtitle;
    private Call<JobSubmitResponse> activeUploadCall;
    private long lastUploadNotifyNs = 0L;
    private boolean uploadCancelled = false;

    private final ActivityResultLauncher<String[]> filePicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) {
                    // Cancelled with nothing chosen yet: nothing to show here.
                    if (selectedUri == null) finish();
                    return;
                }
                try {
                    getContentResolver().takePersistableUriPermission(uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Throwable ignored) {}
                setSelectedUri(uri);
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        com.jotapeveloper.fision.design.AccentColorManager.get(this).applyOverlayTo(this);
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

        bSend          = findViewById(R.id.b_import_send);
        ivHeaderCover  = findViewById(R.id.iv_import_cover);
        ivHeroBg       = findViewById(R.id.iv_import_heroBg);
        vHeroScrim     = findViewById(R.id.v_import_heroScrim);
        vHeroCorner    = findViewById(R.id.v_import_heroCorner);
        tvHeaderTitle  = findViewById(R.id.tv_import_headerTitle);
        tvHeaderArtist = findViewById(R.id.tv_import_headerArtist);
        tvHeaderMeta   = findViewById(R.id.tv_import_headerMeta);
        gpuWarningBanner = findViewById(R.id.ll_import_gpuWarning);
        loadingOverlay   = findViewById(R.id.fl_import_loading);

        findViewById(R.id.b_import_changeSong).setOnClickListener(
                v -> filePicker.launch(new String[]{"audio/*"}));

        // Separation: mode segment + tile grid
        segSepMode = findViewById(R.id.seg_sep_mode);
        segSepMode.check(R.id.b_mode_4stem);
        segSepMode.addOnButtonCheckedListener((g, id, checked) -> {
            if (checked) refreshSeparationUi();
        });
        attachSegmentTint(segSepMode);

        RecyclerView rvStems = findViewById(R.id.rv_import_stems);
        rvStems.setLayoutManager(new LinearLayoutManager(this));
        // The default animator interpolates bounds on every rebind.
        rvStems.setItemAnimator(null);
        tileAdapter = new ImportStemTileAdapter(TILE_STEMS, this);
        rvStems.setAdapter(tileAdapter);

        // Edge fades: each one shows only while content remains beyond its edge.
        fadeTop = findViewById(R.id.v_import_fadeTop);
        fadeBottom = findViewById(R.id.v_import_fadeBottom);
        rvStems.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@androidx.annotation.NonNull RecyclerView rv,
                                             int dx, int dy) {
                updateListFades(rv);
            }
        });
        rvStems.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                updateListFades(rvStems));

        bSend.setOnClickListener(v -> onSendClicked());

        // Reprocess mode: hash passed via intent extra
        reprocessHash = getIntent().getStringExtra(EXTRA_REPROCESS_HASH);
        if (reprocessHash != null) {
            applyReprocessMode();
        } else if (savedInstanceState != null
                && savedInstanceState.getParcelable(STATE_URI) != null) {
            setSelectedUri(savedInstanceState.getParcelable(STATE_URI));
        } else if (getIntent().getData() != null) {
            setSelectedUri(getIntent().getData());
        } else if (savedInstanceState == null) {
            // Opened without a song: go straight to the picker.
            filePicker.launch(new String[]{"audio/*"});
        }
        refreshSeparationUi();

        // Gate the UI on the ServerInfo cache. If it is missing (fresh login
        // that skipped the hook, first launch after a legacy migration, or a
        // manual clear), trigger a refresh and keep the overlay up until the
        // listener reports back.
        com.jotapeveloper.fision.cloud.ServerInfoManager infoMgr =
                com.jotapeveloper.fision.cloud.ServerInfoManager.get(this);
        applyServerInfoState();
        if (!infoMgr.isLoaded()) infoMgr.refresh();
    }

    /** Keeps the chosen song across recreation (e.g. an accent change). */
    private static final String STATE_URI = "selectedUri";

    @Override
    protected void onSaveInstanceState(@androidx.annotation.NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (selectedUri != null) outState.putParcelable(STATE_URI, selectedUri);
    }

    @Override
    protected void onResume() {
        super.onResume();
        com.jotapeveloper.fision.design.AccentColorManager.get(this)
                .maybeRecreateForAccent(this);
        com.jotapeveloper.fision.cloud.ServerInfoManager.get(this)
                .addListener(serverInfoListener);
        applyServerInfoState();
    }

    @Override
    protected void onPause() {
        super.onPause();
        com.jotapeveloper.fision.cloud.ServerInfoManager.get(this)
                .removeListener(serverInfoListener);
    }

    /** Reflects the ServerInfo cache into the UI:
     *   - No cache yet → block the whole form with the loading overlay.
     *   - Cache loaded + gpu=false → show the GPU warning banner.
     *   - Cache loaded + gpu=true  → hide banner and overlay. */
    private void applyServerInfoState() {
        com.jotapeveloper.fision.cloud.ServerInfoManager mgr =
                com.jotapeveloper.fision.cloud.ServerInfoManager.get(this);
        boolean ready = mgr.isLoaded();
        if (loadingOverlay != null)
            loadingOverlay.setVisibility(ready ? View.GONE : View.VISIBLE);
        if (gpuWarningBanner != null)
            gpuWarningBanner.setVisibility(ready && !mgr.hasGpu() ? View.VISIBLE : View.GONE);
        if (bSend != null) bSend.setEnabled(ready);
    }

    /** Configures the activity in reprocess mode: no picker, hero from the
     *  CloudItem, "Reprocess" button and UI prefill from the current config. */
    private void applyReprocessMode() {
        com.jotapeveloper.fision.cloud.CloudItem ci =
                com.jotapeveloper.fision.cloud.CloudRepository.get(this).getByHash(reprocessHash);
        if (ci == null) {
            Toast.makeText(this, R.string.reprocess_failed, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        TextView tvToolbarTitle = findViewById(R.id.tv_import_title);
        tvToolbarTitle.setText(R.string.import_reprocess_title);
        bSend.setText(R.string.import_reprocess_short);

        // The input already lives on the server: no other song can be chosen.
        findViewById(R.id.b_import_changeSong).setVisibility(View.GONE);

        String title = (ci.displayName != null && !ci.displayName.isEmpty())
                ? ci.displayName : reprocessHash;
        android.graphics.Bitmap bm = ci.coverPath != null
                ? android.graphics.BitmapFactory.decodeFile(ci.coverPath) : null;
        bindHero(title, ci.artist, null, bm);

        // Prefill UI from the current server config
        if (ci.config != null) prefillFromConfig(ci.config);
    }

    /** Applies the separation UI from a previous server config
     *  ("rich" format: {sep:{mode, preset|vocals+stems}, device, chords}). Missing or
     *  unknown fields keep the default already selected in onCreate. The device
     *  field is ignored: the app decides between cuda and cpu automatically
     *  based on the cached ServerInfo. */
    private void prefillFromConfig(Map<String, Object> cfg) {
        // Server "chords": BTC only in the current version. The field is ignored
        // (retro-compat with old configs that still carried "chordino").

        Object sepObj = cfg.get("sep");
        if (!(sepObj instanceof Map)) return;
        @SuppressWarnings("unchecked")
        Map<String, Object> sep = (Map<String, Object>) sepObj;
        String mode = String.valueOf(sep.get("mode"));

        if ("classic".equals(mode)) {
            String preset = String.valueOf(sep.get("preset"));
            Object smObj = sep.get("sep_mode");
            int sepMode = (smObj instanceof Number) ? ((Number) smObj).intValue() : -1;
            boolean fourStem = "4stem".equals(preset) || sepMode == 4;
            segSepMode.check(fourStem ? R.id.b_mode_4stem : R.id.b_mode_2stem);
        } else if ("personalized".equals(mode)) {
            customVocalsSplit = "split".equals(sep.get("vocals"));
            customSelected.clear();
            customSelected.add(ImportStemTileAdapter.LOCKED_STEM);
            Object stemsObj = sep.get("stems");
            if (stemsObj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> stems = (Map<String, Object>) stemsObj;
                for (String stem : TILE_STEMS) {
                    if (ImportStemTileAdapter.REST_STEM.equals(stem)) continue;
                    if (isStemEnabled(stems, serverKey(stem))) customSelected.add(stem);
                }
                customDrumsSplit = "split".equals(stemVariant(stems, "drums"));
            }
            segSepMode.check(R.id.b_mode_custom);
        }
        refreshSeparationUi();
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

    // ---------------------------------------------------------------
    // Separation: presets + custom tile grid
    // ---------------------------------------------------------------
    private boolean isPreset4() { return segSepMode.getCheckedButtonId() == R.id.b_mode_4stem; }
    private boolean isPreset2() { return segSepMode.getCheckedButtonId() == R.id.b_mode_2stem; }

    private boolean isCustomMode() {
        return segSepMode.getCheckedButtonId() == R.id.b_mode_custom;
    }

    @Override public boolean isSelected(String stem) {
        if (ImportStemTileAdapter.REST_STEM.equals(stem)) return true;
        if (isPreset4()) return PRESET_4STEM.contains(stem);
        if (isPreset2()) return PRESET_2STEM.contains(stem);
        return customSelected.contains(stem);
    }

    // Presets have no variants: the classic separation is fixed.
    @Override public boolean isVocalsSplit() { return isCustomMode() && customVocalsSplit; }
    @Override public boolean isDrumsSplit()  { return isCustomMode() && customDrumsSplit; }

    @Override public int restNameResource() {
        return isPreset2() ? R.string.title_accompaniment : R.string.title_other;
    }

    @Override public void onTileTapped(String stem) {
        if (ImportStemTileAdapter.LOCKED_STEM.equals(stem)
                || ImportStemTileAdapter.REST_STEM.equals(stem)) return;
        enterCustomFromCurrent();
        if (!customSelected.remove(stem)) customSelected.add(stem);
        refreshSeparationUi();
    }

    /** Picking a variant also selects the stem. Picking the one already shown
     *  on a selected stem changes nothing (a preset stays a preset). */
    @Override public void onVariantTapped(String stem, boolean split) {
        boolean current = "vocals".equals(stem) ? isVocalsSplit() : isDrumsSplit();
        if (current == split && isSelected(stem)) return;
        enterCustomFromCurrent();
        if ("vocals".equals(stem)) customVocalsSplit = split;
        else customDrumsSplit = split;
        customSelected.add(stem);
        refreshSeparationUi();
    }

    /** Editing a tile while a preset is active starts the custom set from
     *  that preset's stems, so the grid does not jump. */
    private void enterCustomFromCurrent() {
        if (isCustomMode()) return;
        Set<String> base = isPreset2() ? PRESET_2STEM : PRESET_4STEM;
        customSelected.clear();
        customSelected.addAll(base);
        customVocalsSplit = false;
        customDrumsSplit = false;
        segSepMode.check(R.id.b_mode_custom);
    }

    private View fadeTop, fadeBottom;

    private void updateListFades(RecyclerView rv) {
        fadeTop.setVisibility(rv.canScrollVertically(-1) ? View.VISIBLE : View.INVISIBLE);
        fadeBottom.setVisibility(rv.canScrollVertically(1) ? View.VISIBLE : View.INVISIBLE);
    }

    /** Rebinds the stem rows. */
    private void refreshSeparationUi() {
        if (tileAdapter == null) return;
        // Range change keeps each holder on its stem (no reshuffle as with
        // notifyDataSetChanged), so icon cross-fades only on real variant changes.
        tileAdapter.notifyItemRangeChanged(0, tileAdapter.getItemCount());
    }

    private static String serverKey(String stem) {
        return "winds".equals(stem) ? "brass" : stem;
    }

    /** Keeps the visual coherence of the MaterialButtonToggleGroups: the selected
     *  button is painted with colorPrimary (app green background), the rest stay
     *  transparent with colorPrimary stroke. Without this, the MaterialComponents
     *  default leaves them all grey and the active one is not obvious. */
    private void attachSegmentTint(MaterialButtonToggleGroup group) {
        int primary   = com.jotapeveloper.fision.design.AccentColorManager.resolveAccent(this);
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

    // ---------------------------------------------------------------
    // Hero card
    // ---------------------------------------------------------------
    private void setSelectedUri(Uri uri) {
        selectedUri = uri;
        selectedFilename = queryDisplayName(uri);
        refreshHeroFromSelectedUri();
    }

    /** Extracts local metadata from the selected audio (title/artist/cover/duration)
     *  via MediaMetadataRetriever and drops it into the hero card. */
    private void refreshHeroFromSelectedUri() {
        if (selectedUri == null) return;
        String title = null, artist = null, duration = null;
        android.graphics.Bitmap cover = null;
        android.media.MediaMetadataRetriever mmr = new android.media.MediaMetadataRetriever();
        try {
            mmr.setDataSource(this, selectedUri);
            title  = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE);
            artist = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST);
            duration = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
            byte[] art = mmr.getEmbeddedPicture();
            if (art != null && art.length > 0) {
                cover = android.graphics.BitmapFactory.decodeByteArray(art, 0, art.length);
            }
        } catch (Throwable ignored) {
        } finally {
            try { mmr.release(); } catch (Throwable ignored) {}
        }
        if (title == null || title.isEmpty()) title = stripExtension(selectedFilename);
        bindHero(title, artist, buildMetaLine(duration), cover);
    }

    /** "5:55 · FLAC · 41.0 MB". Missing parts are skipped. */
    private String buildMetaLine(String durationMs) {
        List<String> parts = new java.util.ArrayList<>();
        try {
            long ms = Long.parseLong(durationMs);
            long s = ms / 1000;
            parts.add(String.format(java.util.Locale.US, "%d:%02d", s / 60, s % 60));
        } catch (Throwable ignored) {}
        if (selectedFilename != null) {
            int dot = selectedFilename.lastIndexOf('.');
            if (dot > 0 && dot < selectedFilename.length() - 1) {
                parts.add(selectedFilename.substring(dot + 1).toUpperCase(java.util.Locale.ROOT));
            }
        }
        long size = querySize(selectedUri);
        if (size > 0) parts.add(humanBytes(size));
        return parts.isEmpty() ? null : TextUtils.join(" · ", parts);
    }

    /** Fills the hero. A real cover also paints the blurred background;
     *  without one, the generic cover sits on the plain card. */
    private void bindHero(String title, String artist, String meta,
                          android.graphics.Bitmap cover) {
        tvHeaderTitle.setText(title);
        setOptionalText(tvHeaderArtist, artist);
        setOptionalText(tvHeaderMeta, meta);
        if (cover != null) {
            ivHeaderCover.setImageBitmap(cover);
            ivHeroBg.setImageBitmap(cover);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ivHeroBg.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(
                        60f, 60f, android.graphics.Shader.TileMode.CLAMP));
                ivHeroBg.setAlpha(1f);
            } else {
                // No blur available: keep the cover faint behind the scrim.
                ivHeroBg.setAlpha(0.35f);
            }
            ivHeroBg.setVisibility(View.VISIBLE);
            vHeroScrim.setVisibility(View.VISIBLE);
            vHeroCorner.setVisibility(View.VISIBLE);
        } else {
            ivHeaderCover.setImageResource(R.raw.artcover_generic);
            ivHeroBg.setImageDrawable(null);
            ivHeroBg.setVisibility(View.GONE);
            vHeroScrim.setVisibility(View.GONE);
            vHeroCorner.setVisibility(View.GONE);
        }
    }

    private static void setOptionalText(TextView tv, String text) {
        if (text != null && !text.isEmpty()) {
            tv.setText(text);
            tv.setVisibility(View.VISIBLE);
        } else {
            tv.setVisibility(View.GONE);
        }
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
        // Lock window width so live subtitle updates never re-flow the dialog.
        if (win != null) {
            float density = getResources().getDisplayMetrics().density;
            int widthPx = (int) (340f * density);
            win.setLayout(widthPx, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        }
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
        // Device follows the cached ServerInfo. Missing cache = safest fallback
        // (CPU). The server also degrades cuda→cpu silently if it lacks a GPU.
        boolean useCuda = com.jotapeveloper.fision.cloud.ServerInfoManager
                .get(getApplicationContext()).hasGpu();
        root.put("device", useCuda ? "cuda" : "cpu");
        // Chordino deprecated: always BTC. Field kept for server compatibility.
        root.put("chords", "btc");
        return root;
    }

    private Map<String, Object> buildSepConfig() {
        Map<String, Object> sep = new LinkedHashMap<>();
        if (!isCustomMode()) {
            boolean fourStem = isPreset4();
            sep.put("mode", "classic");
            sep.put("preset", fourStem ? "4stem" : "2stem");
            sep.put("sep_mode", fourStem ? 4 : 2);
        } else {
            sep.put("mode", "personalized");
            sep.put("vocals", customVocalsSplit ? "split" : "single");

            Map<String, Object> stems = new LinkedHashMap<>();
            for (String stem : TILE_STEMS) {
                if (ImportStemTileAdapter.LOCKED_STEM.equals(stem)
                        || ImportStemTileAdapter.REST_STEM.equals(stem)) continue;
                stems.put(serverKey(stem), stemEntry(customSelected.contains(stem),
                        "drums".equals(stem) ? (customDrumsSplit ? "split" : "full") : null));
            }
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
    // Util: human name and size of the Uri
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

    private long querySize(Uri uri) {
        if (uri == null) return -1;
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.SIZE);
                if (idx >= 0 && !c.isNull(idx)) return c.getLong(idx);
            }
        } catch (Throwable ignored) {}
        return -1;
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

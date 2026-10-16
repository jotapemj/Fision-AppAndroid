package com.jotapeveloper.fision;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.jotapeveloper.fision.cloud.CloudRepository;
import com.jotapeveloper.fision.cloud.PlaylistErrors;
import com.jotapeveloper.fision.cloud.PlaylistRepository;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Creates or edits a playlist: title, square cover and songs (only songs in
 * the cloud, no repeats, drag to reorder). Each save sends the whole
 * playlist; the cover goes up afterwards with the playlist id.
 */
public class PlaylistEditActivity extends AppCompatActivity {

    /** Absent: create a new playlist. */
    public static final String EXTRA_PLAYLIST_ID = "playlist_id";

    @Nullable private String playlistId;
    private TextInputLayout tilTitle;
    private TextInputEditText etTitle;
    private ImageView ivCover;
    private TextView tvSongsHeader;
    private Button bSave;
    private View busy;
    private PlaylistSongAdapter adapter;
    private int coverPx;
    private MiniPlayerController miniPlayer;

    /** Cover chosen in this session (JPEG), or null. */
    @Nullable private byte[] newCover;
    /** [newCover] is the plain Fision art (cover removed by the user). */
    private boolean newCoverIsLogo;
    /** Back to the song mosaic: the server cover is deleted on save. */
    private boolean coverRemoved;
    /** The playlist being edited has a server cover. */
    private boolean hadCover;

    private ActivityResultLauncher<String> imagePicker;
    private ActivityResultLauncher<Intent> cropper;

    private final CloudRepository.Listener cloudListener = new CloudRepository.Listener() {
        @Override public void onCloudChanged() { }
        @Override public void onConnectivityChanged(boolean reachable) { refreshSaveState(); }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        com.jotapeveloper.fision.design.AccentColorManager.get(this).applyOverlayTo(this);
        setContentView(R.layout.activity_playlist_edit);

        playlistId = getIntent().getStringExtra(EXTRA_PLAYLIST_ID);
        tilTitle = findViewById(R.id.til_peTitle);
        etTitle = findViewById(R.id.et_peTitle);
        ivCover = findViewById(R.id.iv_peCover);
        tvSongsHeader = findViewById(R.id.tv_peSongsHeader);
        bSave = findViewById(R.id.b_peSave);
        busy = findViewById(R.id.fl_peBusy);
        coverPx = (int) (120 * getResources().getDisplayMetrics().density);

        ((TextView) findViewById(R.id.tv_peHeader)).setText(
                playlistId == null ? R.string.playlist_new : R.string.playlist_edit_title);

        int rowPx = (int) (52 * getResources().getDisplayMetrics().density);
        adapter = new PlaylistSongAdapter(PlaylistSongAdapter.Mode.EDIT, rowPx,
                new PlaylistSongAdapter.Listener() {
                    @Override public void onListChanged() { onSongsChanged(); }
                });
        RecyclerView rv = findViewById(R.id.rv_peSongs);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(adapter);
        adapter.attachDrag(rv);
        miniPlayer = new MiniPlayerController(this);
        miniPlayer.setBlurSource(rv);
        miniPlayer.setVisibilityListener(visible ->
                MiniPlayerController.padListBottom(rv, visible, 16));

        cropper = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                r -> {
                    if (r.getResultCode() != RESULT_OK || r.getData() == null) return;
                    String path = r.getData().getStringExtra(CropAvatarActivity.EXTRA_RESULT_PATH);
                    byte[] bytes = readFile(path);
                    if (bytes == null) return;
                    if (bytes.length > PlaylistRepository.MAX_COVER_BYTES) {
                        Toast.makeText(this, R.string.playlist_err_cover_too_large,
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    newCover = bytes;
                    newCoverIsLogo = false;
                    coverRemoved = false;
                    renderCover();
                });
        imagePicker = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri == null) return;
            Intent i = new Intent(this, CropAvatarActivity.class);
            i.putExtra(CropAvatarActivity.EXTRA_SOURCE_URI, uri);
            i.putExtra(CropAvatarActivity.EXTRA_SQUARE_COVER, true);
            cropper.launch(i);
        });

        findViewById(R.id.ib_peClose).setOnClickListener(v -> finish());
        findViewById(R.id.fl_peCover).setOnClickListener(v -> showCoverOptions());
        findViewById(R.id.b_peAddSongs).setOnClickListener(v -> showSongPicker());
        bSave.setOnClickListener(v -> save());
        etTitle.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                tilTitle.setError(null);
            }
            @Override public void afterTextChanged(Editable s) { }
        });

        if (savedInstanceState == null) loadInitial();
        else restore(savedInstanceState);
        onSongsChanged();
        renderCover();
    }

    @Override
    protected void onStart() {
        super.onStart();
        CloudRepository.get(this).addListener(cloudListener);
        miniPlayer.onStart();
        refreshSaveState();
    }

    @Override
    protected void onStop() {
        super.onStop();
        CloudRepository.get(this).removeListener(cloudListener);
        miniPlayer.onStop();
    }

    private void loadInitial() {
        if (playlistId == null) return;
        PlaylistRepository.Playlist p = PlaylistRepository.get(this).getById(playlistId);
        if (p == null) {
            Toast.makeText(this, R.string.playlist_err_gone, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        etTitle.setText(p.title);
        hadCover = p.hasCover;
        List<SongCatalog.Song> songs = new ArrayList<>();
        for (String h : p.songs) songs.add(SongCatalog.resolve(this, h));
        adapter.submit(songs);
    }

    // ---- state across recreation ----
    private static final String S_SONGS = "pe_songs", S_COVER = "pe_cover",
            S_REMOVED = "pe_removed", S_HAD = "pe_had", S_LOGO = "pe_logo";

    @Override
    protected void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out);
        out.putStringArrayList(S_SONGS, new ArrayList<>(currentHashes()));
        out.putByteArray(S_COVER, newCover);
        out.putBoolean(S_REMOVED, coverRemoved);
        out.putBoolean(S_HAD, hadCover);
        out.putBoolean(S_LOGO, newCoverIsLogo);
    }

    private void restore(Bundle in) {
        List<String> hashes = in.getStringArrayList(S_SONGS);
        List<SongCatalog.Song> songs = new ArrayList<>();
        if (hashes != null) for (String h : hashes) songs.add(SongCatalog.resolve(this, h));
        adapter.submit(songs);
        newCover = in.getByteArray(S_COVER);
        coverRemoved = in.getBoolean(S_REMOVED);
        hadCover = in.getBoolean(S_HAD);
        newCoverIsLogo = in.getBoolean(S_LOGO);
    }

    // ---- rendering ----
    private List<String> currentHashes() {
        List<String> out = new ArrayList<>();
        for (SongCatalog.Song s : adapter.getItems()) out.add(s.hash);
        return out;
    }

    private void onSongsChanged() {
        int n = adapter.getItemCount();
        tvSongsHeader.setText(getResources().getQuantityString(R.plurals.playlist_song_count, n, n));
        tvSongsHeader.setVisibility(n == 0 ? View.INVISIBLE : View.VISIBLE);
        // The mosaic follows the first four songs.
        if (isMosaic()) renderCover();
    }

    /** No own cover: the artwork is the mosaic of the first songs. */
    private boolean isMosaic() {
        return newCover == null && (coverRemoved || !hadCover);
    }

    private void showCoverOptions() {
        com.google.android.material.bottomsheet.BottomSheetDialog sheet =
                new com.google.android.material.bottomsheet.BottomSheetDialog(
                        this, R.style.TransparentBottomSheetDialog);
        View v = LayoutInflater.from(this).inflate(R.layout.playlist_cover_sheet, null);
        sheet.setContentView(v);
        View bMosaic = v.findViewById(R.id.b_coverMosaic);
        View bRemove = v.findViewById(R.id.b_coverRemove);
        bMosaic.setVisibility(isMosaic() ? View.GONE : View.VISIBLE);
        bRemove.setVisibility(newCoverIsLogo ? View.GONE : View.VISIBLE);
        v.findViewById(R.id.b_coverPick).setOnClickListener(x -> {
            sheet.dismiss();
            imagePicker.launch("image/*");
        });
        bMosaic.setOnClickListener(x -> {
            sheet.dismiss();
            newCover = null;
            newCoverIsLogo = false;
            coverRemoved = true;
            renderCover();
        });
        bRemove.setOnClickListener(x -> {
            sheet.dismiss();
            byte[] logo = logoCover();
            if (logo == null) return;
            newCover = logo;
            newCoverIsLogo = true;
            coverRemoved = false;
            renderCover();
        });
        sheet.show();
    }

    /** Plain Fision art as a square JPEG, uploaded as the cover. */
    @Nullable
    private byte[] logoCover() {
        android.graphics.Bitmap src = android.graphics.BitmapFactory.decodeResource(
                getResources(), R.raw.artcover_generic);
        if (src == null) return null;
        int size = 512;
        android.graphics.Bitmap out = android.graphics.Bitmap.createBitmap(
                size, size, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(out);
        c.drawColor(android.graphics.Color.BLACK);
        c.drawBitmap(src, null, new android.graphics.Rect(0, 0, size, size),
                new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));
        src.recycle();
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        out.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, bos);
        out.recycle();
        return bos.toByteArray();
    }

    private void renderCover() {
        if (newCover != null) {
            ivCover.setImageBitmap(android.graphics.BitmapFactory.decodeByteArray(
                    newCover, 0, newCover.length));
            return;
        }
        if (hadCover && !coverRemoved && playlistId != null) {
            PlaylistRepository.Playlist p = PlaylistRepository.get(this).getById(playlistId);
            if (p != null) { PlaylistCovers.load(ivCover, p, coverPx); return; }
        }
        PlaylistCovers.load(ivCover, PlaylistRepository.Playlist.preview(currentHashes()), coverPx);
    }

    private void refreshSaveState() {
        boolean can = PlaylistRepository.get(this).canEdit();
        bSave.setEnabled(can);
        bSave.setAlpha(can ? 1f : 0.4f);
    }

    // ---- song picker ----
    private void showSongPicker() {
        View v = LayoutInflater.from(this).inflate(R.layout.playlist_song_picker_dialog, null);
        TextInputEditText etSearch = v.findViewById(R.id.et_pickSearch);
        RecyclerView rv = v.findViewById(R.id.rv_pickSongs);
        TextView tvEmpty = v.findViewById(R.id.tv_pickEmpty);
        TextView tvTitle = v.findViewById(R.id.tv_pickTitle);

        List<SongCatalog.Song> all = SongCatalog.cloudSongs(this);
        // Checked state starts from the current playlist (no repeats).
        Set<String> checked = new LinkedHashSet<>(currentHashes());
        int rowPx = (int) (52 * getResources().getDisplayMetrics().density);
        Runnable[] updateTitle = new Runnable[1];
        PlaylistSongAdapter pick = new PlaylistSongAdapter(PlaylistSongAdapter.Mode.PICK, rowPx,
                new PlaylistSongAdapter.Listener() {
                    @Override public void onSongClick(SongCatalog.Song s) {
                        if (checked.contains(s.hash)) {
                            checked.remove(s.hash);
                        } else if (checked.size() >= PlaylistRepository.MAX_SONGS) {
                            Toast.makeText(PlaylistEditActivity.this,
                                    getString(R.string.playlist_err_song_limit,
                                            PlaylistRepository.MAX_SONGS),
                                    Toast.LENGTH_SHORT).show();
                        } else {
                            checked.add(s.hash);
                        }
                        updateTitle[0].run();
                    }
                });
        pick.setChecked(checked);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(pick);
        updateTitle[0] = () -> tvTitle.setText(checked.isEmpty()
                ? getString(R.string.playlist_add_songs)
                : getResources().getQuantityString(R.plurals.playlist_selected_count,
                        checked.size(), checked.size()));
        updateTitle[0].run();

        Runnable filter = () -> {
            String q = fold(etSearch.getText() != null ? etSearch.getText().toString() : "");
            List<SongCatalog.Song> shown = new ArrayList<>();
            for (SongCatalog.Song s : all) {
                if (q.isEmpty() || fold(s.title).contains(q)
                        || (s.artist != null && fold(s.artist).contains(q))) {
                    shown.add(s);
                }
            }
            pick.submit(shown);
            if (all.isEmpty()) {
                tvEmpty.setText(R.string.playlist_no_songs_in_cloud);
                tvEmpty.setVisibility(View.VISIBLE);
            } else if (shown.isEmpty()) {
                tvEmpty.setText(R.string.no_results);
                tvEmpty.setVisibility(View.VISIBLE);
            } else {
                tvEmpty.setVisibility(View.GONE);
            }
        };
        filter.run();
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { filter.run(); }
            @Override public void afterTextChanged(Editable s) { }
        });

        AlertDialog dlg = BlurDialogs.show(this, v, 0.9f);
        v.findViewById(R.id.b_pickCancel).setOnClickListener(x -> dlg.dismiss());
        v.findViewById(R.id.b_pickDone).setOnClickListener(x -> {
            applyPicked(checked, all);
            dlg.dismiss();
        });
    }

    /** Keeps the current order for songs still checked and appends the new
     *  ones in library order. */
    private void applyPicked(Set<String> checked, List<SongCatalog.Song> library) {
        List<SongCatalog.Song> out = new ArrayList<>();
        Set<String> present = new HashSet<>();
        for (SongCatalog.Song s : adapter.getItems()) {
            if (checked.contains(s.hash)) { out.add(s); present.add(s.hash); }
        }
        for (SongCatalog.Song s : library) {
            if (checked.contains(s.hash) && !present.contains(s.hash)) out.add(s);
        }
        adapter.submit(out);
        onSongsChanged();
    }

    private static String fold(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFD);
        return n.replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT).trim();
    }

    // ---- save ----
    private void save() {
        String title = etTitle.getText() != null ? etTitle.getText().toString().trim() : "";
        if (title.isEmpty()) {
            tilTitle.setError(getString(R.string.playlist_err_title_empty));
            return;
        }
        if (title.length() > PlaylistRepository.MAX_TITLE_LENGTH) {
            tilTitle.setError(getString(R.string.playlist_err_title_length,
                    PlaylistRepository.MAX_TITLE_LENGTH));
            return;
        }
        List<String> songs = currentHashes();
        if (songs.size() > PlaylistRepository.MAX_SONGS) {
            Toast.makeText(this, getString(R.string.playlist_err_song_limit,
                    PlaylistRepository.MAX_SONGS), Toast.LENGTH_LONG).show();
            return;
        }
        PlaylistRepository.CoverEdit cover = newCover != null
                ? PlaylistRepository.CoverEdit.set(newCover)
                : (coverRemoved ? PlaylistRepository.CoverEdit.REMOVE
                                : PlaylistRepository.CoverEdit.KEEP);

        setBusy(true);
        PlaylistRepository.Result cb = new PlaylistRepository.Result() {
            @Override public void onSuccess(@Nullable PlaylistRepository.Playlist p) {
                setBusy(false);
                finish();
            }
            @Override public void onError(@NonNull PlaylistRepository.PlaylistError e) {
                setBusy(false);
                Toast.makeText(PlaylistEditActivity.this,
                        PlaylistErrors.message(PlaylistEditActivity.this, e),
                        Toast.LENGTH_LONG).show();
                if (e.isGone()) { finish(); return; }
                // The playlist was saved but its cover was not: further saves edit it.
                if (e.savedId != null) {
                    playlistId = e.savedId;
                    ((TextView) findViewById(R.id.tv_peHeader)).setText(R.string.playlist_edit_title);
                }
            }
        };
        PlaylistRepository repo = PlaylistRepository.get(this);
        if (playlistId == null) repo.create(title, songs, cover, cb);
        else repo.update(playlistId, title, songs, cover, cb);
    }

    private void setBusy(boolean b) {
        busy.setVisibility(b ? View.VISIBLE : View.GONE);
    }

    @Nullable
    private static byte[] readFile(@Nullable String path) {
        if (path == null) return null;
        File f = new File(path);
        if (!f.exists()) return null;
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int off = 0, n;
            while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) off += n;
            return buf;
        } catch (Exception e) {
            return null;
        }
    }
}

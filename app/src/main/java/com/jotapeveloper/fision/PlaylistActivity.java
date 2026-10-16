package com.jotapeveloper.fision;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.jotapeveloper.fision.cloud.CloudRepository;
import com.jotapeveloper.fision.cloud.DownloadManagerLite;
import com.jotapeveloper.fision.cloud.PlaylistErrors;
import com.jotapeveloper.fision.cloud.PlaylistRepository;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** One playlist: artwork, title and songs in play order. Edit and delete
 *  need the server; playing works offline from the Room copy. */
public class PlaylistActivity extends AppCompatActivity {

    public static final String EXTRA_PLAYLIST_ID = "playlist_id";

    private String playlistId;
    private ImageView ivCover;
    private TextView tvTitle, tvCount, tvEmpty;
    private ImageButton ibEdit, ibDelete;
    private PlaylistSongAdapter adapter;
    private int heroPx;
    private RecyclerView rv;
    private View header, heroCover, fadeTop, fadeBottom;
    private TextView tvBarTitle, tvFloatTitle;
    private MiniPlayerController miniPlayer;

    private final PlaylistRepository.Listener repoListener = this::render;
    private final CloudRepository.Listener cloudListener = new CloudRepository.Listener() {
        @Override public void onCloudChanged() { render(); }
        @Override public void onConnectivityChanged(boolean reachable) { render(); }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        com.jotapeveloper.fision.design.AccentColorManager.get(this).applyOverlayTo(this);
        setContentView(R.layout.activity_playlist);

        playlistId = getIntent().getStringExtra(EXTRA_PLAYLIST_ID);
        ivCover = findViewById(R.id.iv_playlistHeroCover);
        tvTitle = findViewById(R.id.tv_playlistHeroTitle);
        tvCount = findViewById(R.id.tv_playlistHeroCount);
        tvEmpty = findViewById(R.id.tv_playlistSongsEmpty);
        ibEdit = findViewById(R.id.ib_playlistEdit);
        ibDelete = findViewById(R.id.ib_playlistDelete);
        heroPx = (int) (160 * getResources().getDisplayMetrics().density);

        int rowPx = (int) (52 * getResources().getDisplayMetrics().density);
        adapter = new PlaylistSongAdapter(PlaylistSongAdapter.Mode.VIEW, rowPx,
                new PlaylistSongAdapter.Listener() {
                    @Override public void onSongClick(SongCatalog.Song song) { openSong(song); }
                });
        rv = findViewById(R.id.rv_playlistSongs);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(adapter);
        setupCollapsingHeader();
        miniPlayer = new MiniPlayerController(this);
        miniPlayer.setBlurSource(rv);
        miniPlayer.setVisibilityListener(visible ->
                MiniPlayerController.padListBottom(rv, visible, 16));

        findViewById(R.id.ib_playlistBack).setOnClickListener(v -> finish());
        ibEdit.setOnClickListener(v -> {
            Intent i = new Intent(this, PlaylistEditActivity.class);
            i.putExtra(PlaylistEditActivity.EXTRA_PLAYLIST_ID, playlistId);
            startActivity(i);
        });
        ibDelete.setOnClickListener(v -> confirmDelete());
    }

    @Override
    protected void onStart() {
        super.onStart();
        PlaylistRepository repo = PlaylistRepository.get(this);
        repo.addListener(repoListener);
        CloudRepository.get(this).addListener(cloudListener);
        miniPlayer.onStart();
        render();
        // Fresh copy from the server; a 404 means it was deleted elsewhere.
        if (repo.canEdit()) {
            repo.refresh(playlistId, new PlaylistRepository.Result() {
                @Override public void onSuccess(@Nullable PlaylistRepository.Playlist p) { }
                @Override public void onError(@NonNull PlaylistRepository.PlaylistError e) {
                    if (e.isGone()) closeAsGone();
                }
            });
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        PlaylistRepository.get(this).removeListener(repoListener);
        CloudRepository.get(this).removeListener(cloudListener);
        miniPlayer.onStop();
    }

    private void render() {
        if (isFinishing() || isDestroyed()) return;
        PlaylistRepository repo = PlaylistRepository.get(this);
        PlaylistRepository.Playlist p = repo.getById(playlistId);
        if (p == null) {
            // Gone from the list (deleted here or on another device).
            if (repo.isLoaded()) closeAsGone();
            return;
        }
        tvTitle.setText(p.title);
        tvBarTitle.setText(p.title);
        tvFloatTitle.setText(p.title);
        int n = p.songs.size();
        tvCount.setText(getResources().getQuantityString(R.plurals.playlist_song_count, n, n));
        PlaylistCovers.load(ivCover, p, heroPx);
        List<SongCatalog.Song> songs = new ArrayList<>(n);
        for (String hash : p.songs) songs.add(SongCatalog.resolve(this, hash));
        adapter.submit(songs);
        tvEmpty.setVisibility(n == 0 ? View.VISIBLE : View.GONE);

        boolean canEdit = repo.canEdit();
        for (View b : new View[] { ibEdit, ibDelete }) {
            b.setEnabled(canEdit);
            b.setAlpha(canEdit ? 1f : 0.35f);
        }
    }

    // ---- collapsing header ----
    private void setupCollapsingHeader() {
        header = findViewById(R.id.ll_playlistHeader);
        heroCover = findViewById(R.id.fl_playlistHeroCover);
        fadeTop = findViewById(R.id.v_playlistFadeTop);
        fadeBottom = findViewById(R.id.v_playlistFadeBottom);
        tvBarTitle = findViewById(R.id.tv_playlistBarTitle);
        tvFloatTitle = findViewById(R.id.tv_playlistFloatTitle);
        // The list reserves the header height so its first row starts below it.
        header.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int h = b - t;
            if (rv.getPaddingTop() != h) {
                // A list at the top stays at the top: without this the first row
                // keeps its old offset and the header opens collapsed.
                boolean atTop = !rv.canScrollVertically(-1) || isListAtTop();
                rv.setPadding(rv.getPaddingLeft(), h, rv.getPaddingRight(), rv.getPaddingBottom());
                if (atTop && rv.getLayoutManager() instanceof LinearLayoutManager) {
                    ((LinearLayoutManager) rv.getLayoutManager()).scrollToPositionWithOffset(0, 0);
                }
            }
            rv.post(this::applyHeaderCollapse);
        });
        rv.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView r, int dx, int dy) {
                applyHeaderCollapse();
            }
        });
        rv.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> applyHeaderCollapse());
        tvFloatTitle.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                tvFloatTitle.post(this::applyHeaderCollapse));
    }

    /** List scroll in px from the top, capped at the header height. */
    private int listScroll() {
        int h = header.getHeight();
        View first = rv.getChildCount() > 0 ? rv.getChildAt(0) : null;
        if (first == null) return 0;
        if (rv.getChildAdapterPosition(first) > 0) return h;
        return Math.max(0, Math.min(h, rv.getPaddingTop() - first.getTop()));
    }

    /** First row at its resting place (no scroll), whatever the current padding. */
    private boolean isListAtTop() {
        View first = rv.getChildCount() > 0 ? rv.getChildAt(0) : null;
        return first == null || (rv.getChildAdapterPosition(first) == 0
                && first.getTop() >= rv.getPaddingTop());
    }

    /** The header rides up with the list: the cover shrinks and fades first,
     *  then the title shrinks and travels into the bar. */
    private void applyHeaderCollapse() {
        int h = header.getHeight();
        if (h == 0) return;
        int s = listScroll();
        header.setTranslationY(-s);
        float coverRange = heroCover.getHeight() + 8 * getResources().getDisplayMetrics().density;
        float pc = clamp01(s / coverRange);
        heroCover.setPivotX(heroCover.getWidth() / 2f);
        heroCover.setPivotY(heroCover.getHeight());
        heroCover.setScaleX(1f - 0.4f * pc);
        heroCover.setScaleY(1f - 0.4f * pc);
        heroCover.setAlpha(1f - pc);
        // The count goes while the cover is leaving.
        tvCount.setAlpha(1f - clamp01((s - coverRange * 0.4f) / (coverRange * 0.4f)));
        float titleStart = coverRange * 0.5f;
        float t = smooth(clamp01((s - titleStart) / Math.max(1f, h - titleStart)));
        moveTitle(s, t);
        fadeTop.setVisibility(s > 0 ? View.VISIBLE : View.INVISIBLE);
        fadeBottom.setVisibility(rv.canScrollVertically(1) ? View.VISIBLE : View.INVISIBLE);
    }

    /** Places the floating title: over its header slot at [t]=0, shrunk to the
     *  bar title size and on the bar title's spot at [t]=1, where the one-line
     *  bar title takes over. */
    private void moveTitle(int scroll, float t) {
        android.text.Layout layout = tvFloatTitle.getLayout();
        if (layout == null || tvFloatTitle.getHeight() == 0) return;
        float lineLeft = layout.getLineLeft(0);
        float lineH = layout.getLineBottom(0) - layout.getLineTop(0);
        float padL = tvFloatTitle.getPaddingLeft(), padT = tvFloatTitle.getPaddingTop();
        float k = tvBarTitle.getTextSize() / tvFloatTitle.getTextSize();

        // Start: over the placeholder, which rides with the header.
        float startTy = header.getTop() + tvTitle.getTop() - scroll - tvFloatTitle.getTop();
        // End: first line's left edge and centre on the bar title's.
        float endTx = tvBarTitle.getLeft() + tvBarTitle.getPaddingLeft()
                - (tvFloatTitle.getLeft() + padL + lineLeft);
        float barCentre = tvBarTitle.getTop() + tvBarTitle.getHeight() / 2f;
        float endTy = barCentre - lineH * k / 2f - tvFloatTitle.getTop() - padT;

        tvFloatTitle.setPivotX(padL + lineLeft);
        tvFloatTitle.setPivotY(padT);
        float scale = 1f + (k - 1f) * t;
        tvFloatTitle.setScaleX(scale);
        tvFloatTitle.setScaleY(scale);
        tvFloatTitle.setTranslationX(endTx * t);
        tvFloatTitle.setTranslationY(startTy + (endTy - startTy) * t);
        // Lines past the first fold away on the trip, so the arrival matches
        // the one-line bar title and the swap is invisible.
        int fullH = tvFloatTitle.getHeight();
        int oneLineH = (int) (padT + layout.getLineBottom(0) + 0.5f);
        int clipH = Math.round(fullH + (Math.min(fullH, oneLineH) - fullH) * t);
        tvFloatTitle.setClipBounds(new android.graphics.Rect(0, 0, tvFloatTitle.getWidth(), clipH));
        boolean arrived = t >= 1f;
        tvFloatTitle.setAlpha(arrived ? 0f : 1f);
        tvBarTitle.setAlpha(arrived ? 1f : 0f);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    private static float smooth(float x) {
        return x * x * (3f - 2f * x);
    }

    private void closeAsGone() {
        if (isFinishing()) return;
        Toast.makeText(this, R.string.playlist_err_gone, Toast.LENGTH_LONG).show();
        finish();
    }

    /** Downloaded songs open in the Player; cloud-only ones start downloading. */
    private void openSong(SongCatalog.Song song) {
        if (!song.local) {
            if (!com.jotapeveloper.fision.cloud.AuthManager.get(this).isLoggedIn()) {
                LoginDialog.show(this, true, new LoginDialog.Callback() {
                    @Override public void onLoggedIn(String u) {
                        com.jotapeveloper.fision.cloud.CloudRepository.get(PlaylistActivity.this)
                                .notifyConfigChanged();
                        render();
                    }
                });
                return;
            }
            if (song.cloud != null) {
                DownloadManagerLite.get(this).start(song.hash);
                Toast.makeText(this, R.string.playlist_downloading, Toast.LENGTH_SHORT).show();
            }
            return;
        }
        File folder = new File(SongCatalog.songsRoot(this), song.hash);
        PlayQueue.playFromPlaylist(playlistId);
        Intent player = new Intent(this, PlayerActivity.class);
        player.putExtra("SongPathString", folder.getAbsolutePath());
        player.putExtra("SongName", song.hash);
        startActivity(player);
    }

    private void confirmDelete() {
        PlaylistRepository.Playlist p = PlaylistRepository.get(this).getById(playlistId);
        if (p == null) return;
        BlurDialogs.confirm(this, R.drawable.ic_delete,
                getString(R.string.playlist_delete_confirm, p.title),
                getString(R.string.playlist_delete_action), getString(R.string.playlist_cancel),
                () -> PlaylistRepository.get(this).delete(playlistId, new PlaylistRepository.Result() {
                    @Override public void onSuccess(@Nullable PlaylistRepository.Playlist x) {
                        Toast.makeText(PlaylistActivity.this, R.string.playlist_deleted,
                                Toast.LENGTH_SHORT).show();
                        finish();
                    }
                    @Override public void onError(@NonNull PlaylistRepository.PlaylistError e) {
                        Toast.makeText(PlaylistActivity.this,
                                PlaylistErrors.message(PlaylistActivity.this, e),
                                Toast.LENGTH_LONG).show();
                    }
                }));
    }
}

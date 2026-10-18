package com.jotapeveloper.fision;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.google.android.material.button.MaterialButton;
import com.jotapeveloper.fision.cloud.CloudItem;
import com.jotapeveloper.fision.cloud.CloudRepository;

import java.io.File;

/**
 * Mini-player bar shared by every screen but the Player and Settings
 * (layout/mini_player.xml). Binds to a running PlaybackService between the
 * host's onStart/onStop, shows the loaded song with its progress, toggles
 * play/pause, opens the Player on tap and switches song with a horizontal
 * drag (the prev/next previews follow the finger).
 */
public final class MiniPlayerController {

    /** Host hook: the bar was shown or hidden (e.g. to pad lists under it). */
    public interface VisibilityListener { void onMiniPlayerVisibility(boolean visible); }

    private static final long PROGRESS_TICK_MS = 250;

    private final Activity host;
    private final View root;
    private final TextView tvTitle, tvArtist, tvTitlePrev, tvArtistPrev, tvTitleNext, tvArtistNext;
    private final View llCurrent, llPrev, llNext, flTextWrap;
    private final ImageView ivIcon;
    private final MaterialButton bPlayPause;
    private final ProgressBar pbProgress;
    @Nullable private VisibilityListener visibilityListener;

    @Nullable private PlaybackService service;
    private boolean bound = false;
    private Boolean lastVisible = null;

    // Play/pause icon morph state: false = play icon (paused), true = pause icon.
    private boolean playIconState = false;
    private boolean playIconInitialized = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable progressTick = new Runnable() {
        @Override public void run() {
            tickProgress();
            handler.postDelayed(this, PROGRESS_TICK_MS);
        }
    };

    private final PlaybackService.Listener serviceListener = this::refresh;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((PlaybackService.LocalBinder) binder).getService();
            bound = true;
            service.addListener(serviceListener);
            refresh();
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            if (service != null) service.removeListener(serviceListener);
            service = null;
            bound = false;
        }
    };

    public MiniPlayerController(Activity host) {
        this.host = host;
        root         = host.findViewById(R.id.mini_player);
        tvTitle      = root.findViewById(R.id.tv_mini_title);
        tvArtist     = root.findViewById(R.id.tv_mini_artist);
        tvTitlePrev  = root.findViewById(R.id.tv_mini_titlePrev);
        tvArtistPrev = root.findViewById(R.id.tv_mini_artistPrev);
        tvTitleNext  = root.findViewById(R.id.tv_mini_titleNext);
        tvArtistNext = root.findViewById(R.id.tv_mini_artistNext);
        llCurrent    = root.findViewById(R.id.ll_mini_current);
        llPrev       = root.findViewById(R.id.ll_mini_prev);
        llNext       = root.findViewById(R.id.ll_mini_next);
        flTextWrap   = root.findViewById(R.id.fl_mini_textWrap);
        ivIcon       = root.findViewById(R.id.iv_mini_icon);
        bPlayPause   = root.findViewById(R.id.b_mini_playPause);
        pbProgress   = root.findViewById(R.id.pb_mini_progress);

        bPlayPause.setOnClickListener(v -> {
            if (service == null) return;
            if (service.isPlayingOrStarting()) service.pause();
            else                               service.play();
        });
        attachSwipe();
    }

    public void setVisibilityListener(@Nullable VisibilityListener l) { visibilityListener = l; }

    /** Content blurred behind the bar (usually the list under it). */
    public void setBlurSource(@Nullable View source) {
        if (root instanceof BlurBehindLayout) ((BlurBehindLayout) root).setSourceView(source);
    }

    public void invalidateBlur() {
        if (root instanceof BlurBehindLayout) ((BlurBehindLayout) root).invalidateBlur();
    }

    /** Host onStart: connects to the service only when it already runs. */
    public void onStart() {
        Intent svc = new Intent(host, PlaybackService.class);
        host.bindService(svc, connection, 0);
    }

    /** Host onStop. */
    public void onStop() {
        handler.removeCallbacks(progressTick);
        if (bound) {
            if (service != null) service.removeListener(serviceListener);
            host.unbindService(connection);
            bound = false;
            service = null;
        }
    }

    @Nullable public PlaybackService getService() { return service; }

    public boolean isVisible() { return root.getVisibility() == View.VISIBLE; }

    /** Hides the bar at once (the loaded song is going away). */
    public void hideNow() {
        handler.removeCallbacks(progressTick);
        setShown(false);
    }

    private void setShown(boolean visible) {
        root.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (lastVisible == null || lastVisible != visible) {
            lastVisible = visible;
            if (visibilityListener != null) visibilityListener.onMiniPlayerVisibility(visible);
        }
    }

    /** Visibility, title, cover and icon from the service state. */
    public void refresh() {
        if (service == null || !service.isSongLoaded()) {
            handler.removeCallbacks(progressTick);
            setShown(false);
            return;
        }
        setShown(true);
        SongMetadata meta = service.getCurrentMetadata();
        String title  = service.getDisplayTitle();
        String artist = meta != null ? meta.artist : null;
        String cover  = meta != null ? meta.coverPath : null;
        String hash   = service.getCurrentSongName();
        // Server-side info when the local metadata.txt is missing and the
        // display fields would collapse to the folder hash / blank.
        if (hash != null && (title == null || title.equals(hash) || artist == null
                || cover == null || !new File(cover).exists())) {
            CloudItem ci = CloudRepository.get(host).getByHash(hash);
            if (ci != null) {
                if ((title == null || title.equals(hash))
                        && ci.displayName != null && !ci.displayName.isEmpty()) {
                    title = ci.displayName;
                }
                if ((artist == null || artist.isEmpty())
                        && ci.artist != null && !ci.artist.isEmpty()) {
                    artist = ci.artist;
                }
                if ((cover == null || !new File(cover).exists())
                        && ci.coverPath != null && new File(ci.coverPath).exists()) {
                    cover = ci.coverPath;
                }
            }
        }
        tvTitle.setText(title);
        tvArtist.setText(artist != null ? artist : "");
        tvArtist.setVisibility(artist != null && !artist.isEmpty() ? View.VISIBLE : View.GONE);

        if (cover != null && new File(cover).exists()) {
            android.graphics.Bitmap bm = ImageDecode.file(cover, 256);
            if (bm != null) ivIcon.setImageBitmap(bm);
            else ivIcon.setImageResource(R.raw.artcover_generic);
        } else {
            ivIcon.setImageResource(R.raw.artcover_generic);
        }
        applyPlayIcon(service.isPlayingOrStarting(), true);
        populatePreviews();
        handler.removeCallbacks(progressTick);
        handler.post(progressTick);
    }

    /** AVD morph when [animate] (state change while visible), static vector
     *  on the first show so no animation fires on entry. */
    private void applyPlayIcon(boolean playing, boolean animate) {
        if (playIconInitialized && playIconState == playing) return;
        if (!playIconInitialized || !animate) {
            bPlayPause.setIconResource(playing ? R.drawable.avd_ic_pause : R.drawable.avd_ic_play);
        } else {
            bPlayPause.setIconResource(playing ? R.drawable.avd_play_to_pause : R.drawable.avd_pause_to_play);
            Drawable icon = bPlayPause.getIcon();
            if (icon instanceof Animatable) ((Animatable) icon).start();
        }
        playIconState = playing;
        playIconInitialized = true;
    }

    private void tickProgress() {
        if (service == null || !service.isSongLoaded()) return;
        long durationMs = service.getDurationMs();
        long currentMs  = service.getCurrentPositionMs();
        if (durationMs <= 0) { pbProgress.setProgress(0); return; }
        int promille = (int) ((currentMs * 1000L) / durationMs);
        pbProgress.setProgress(Math.max(0, Math.min(1000, promille)));
    }

    /** Opens the Player on the loaded song without touching playback
     *  (NoAutoplay: a finished song must not restart on entry). */
    private void openPlayer() {
        if (service == null || !service.isSongLoaded()) return;
        Intent player = new Intent(host, PlayerActivity.class);
        player.putExtra("SongPathString", service.getCurrentSongPath());
        player.putExtra("SongName",       service.getCurrentSongName());
        player.putExtra("NoAutoplay",     true);
        host.startActivity(player);
    }

    // ---- swipe ----
    /** Tap opens the Player; a horizontal drag moves the current text with the
     *  finger while the prev/next previews slide alongside. */
    private void attachSwipe() {
        View swipeArea = root.findViewById(R.id.v_mini_swipeArea);
        final int touchSlop = ViewConfiguration.get(host).getScaledTouchSlop();
        final float[] start = {0f, 0f};
        final long[] startTime = {0L};
        final boolean[] swiping = {false};

        swipeArea.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    start[0] = ev.getRawX();
                    start[1] = ev.getRawY();
                    startTime[0] = ev.getEventTime();
                    swiping[0] = false;
                    cancelTextAnimations();
                    populatePreviews();
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = ev.getRawX() - start[0];
                    float dy = ev.getRawY() - start[1];
                    if (!swiping[0] && Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy)) {
                        swiping[0] = true;
                    }
                    if (swiping[0]) {
                        int w = wrapWidth();
                        llCurrent.setTranslationX(dx);
                        llPrev.setTranslationX(-w + dx);
                        llNext.setTranslationX(w + dx);
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    float endDx = ev.getRawX() - start[0];
                    long elapsed = ev.getEventTime() - startTime[0];
                    if (!swiping[0]) {
                        if (ev.getActionMasked() == MotionEvent.ACTION_UP
                                && elapsed < 300 && Math.abs(endDx) < touchSlop) {
                            openPlayer();
                        }
                    } else {
                        int w = wrapWidth();
                        if (Math.abs(endDx) > w * 0.30f) commitSongChange(endDx < 0 ? +1 : -1, w);
                        else snapBackText(w);
                    }
                    return true;
                }
            }
            return false;
        });
    }

    private int wrapWidth() {
        int w = flTextWrap.getWidth();
        return w > 0 ? w : 600;
    }

    private void cancelTextAnimations() {
        llCurrent.animate().cancel();
        llPrev.animate().cancel();
        llNext.animate().cancel();
    }

    private void snapBackText(int w) {
        llCurrent.animate().translationX(0f).setDuration(160).start();
        llPrev.animate().translationX(-w).setDuration(160).start();
        llNext.animate().translationX(w).setDuration(160).start();
    }

    /** Current text out, chosen preview in; at the end the preview becomes the
     *  current text and the song loads. */
    private void commitSongChange(int direction, int w) {
        if (service == null || !service.isSongLoaded()) { snapBackText(w); return; }
        File next = adjacentSong(direction);
        if (next == null) { snapBackText(w); return; }
        View entering = direction > 0 ? llNext : llPrev;
        View other    = direction > 0 ? llPrev : llNext;
        llCurrent.animate().translationX(direction > 0 ? -w : w).setDuration(160).start();
        other.animate().translationX(direction > 0 ? -2 * w : 2 * w).setDuration(160).start();
        entering.animate().translationX(0f).setDuration(160).withEndAction(() -> {
            TextView pt = direction > 0 ? tvTitleNext : tvTitlePrev;
            TextView pa = direction > 0 ? tvArtistNext : tvArtistPrev;
            String a = pa.getText().toString();
            tvTitle.setText(pt.getText());
            tvArtist.setText(a);
            tvArtist.setVisibility(a.isEmpty() ? View.GONE : View.VISIBLE);
            llCurrent.setTranslationX(0f);
            if (service != null) service.loadAndPlay(next);
            populatePreviews();
        }).start();
    }

    /** Prev/next song texts, parked at ±wrapper width. */
    private void populatePreviews() {
        if (service == null || !service.isSongLoaded()) return;
        assignPreview(adjacentSong(-1), tvTitlePrev, tvArtistPrev);
        assignPreview(adjacentSong(+1), tvTitleNext, tvArtistNext);
        if (flTextWrap.getWidth() <= 0) flTextWrap.post(this::positionPreviews);
        else positionPreviews();
    }

    private void positionPreviews() {
        int w = wrapWidth();
        llPrev.setTranslationX(-w);
        llNext.setTranslationX(w);
    }

    private static void assignPreview(@Nullable File folder, TextView title, TextView artist) {
        if (folder == null) {
            title.setText("");
            artist.setText("");
            artist.setVisibility(View.GONE);
            return;
        }
        SongMetadata meta = SongMetadata.read(folder);
        String t = meta.displayTitle != null ? meta.displayTitle : folder.getName();
        String a = meta.artist != null ? meta.artist : "";
        title.setText(t);
        artist.setText(a);
        artist.setVisibility(a.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /** Song [direction] steps from the current one in library order. */
    @Nullable
    private File adjacentSong(int direction) {
        if (service == null) return null;
        String currentPath = service.getCurrentSongPath();
        if (currentPath == null) return null;
        return SongOrder.adjacent(host, new File(currentPath), direction);
    }

    /** Bottom padding that keeps the last row of a list clear of the bar
     *  (64 high + 10 margin + 8 of air). */
    public static int listClearancePx(Context ctx) {
        return (int) (82f * ctx.getResources().getDisplayMetrics().density);
    }

    /** Bottom padding of [list]: clearance while the bar is shown, [restDp] otherwise. */
    public static void padListBottom(View list, boolean barVisible, int restDp) {
        int px = barVisible ? listClearancePx(list.getContext())
                : (int) (restDp * list.getResources().getDisplayMetrics().density);
        list.setPadding(list.getPaddingLeft(), list.getPaddingTop(), list.getPaddingRight(), px);
    }
}

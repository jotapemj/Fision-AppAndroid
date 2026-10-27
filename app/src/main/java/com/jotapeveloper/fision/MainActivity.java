package com.jotapeveloper.fision;

import com.jotapeveloper.fision.onboarding.CoachMarkOverlay;
import com.jotapeveloper.fision.onboarding.Onboarding;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.jotapeveloper.fision.cloud.AuthManager;
import com.jotapeveloper.fision.cloud.AvatarManager;
import com.jotapeveloper.fision.cloud.CloudItem;
import com.jotapeveloper.fision.cloud.CloudRepository;
import com.jotapeveloper.fision.cloud.ServerInfoManager;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;

public class MainActivity extends androidx.appcompat.app.AppCompatActivity {

    public static String language = "en";
    private EditText et_find;
    private TextView tv_title;
    private TextView tv_SongsCount;
    private View b_add;
    private ImageView ivAddIcon;
    private TextView tvAddLabel;
    private List<String> SongsInDirectory;
    private android.widget.ImageButton b_orderBy;

    // Song list sort order (global, SharedPreferences).
    // 0 = date added descending (most recent first).
    // 1 = date added ascending (oldest first).
    private int sortOrder = 0;
    private static final String PREF_SORT_ORDER = "songs_sort_order";
    /** Decode bounds: avatar / small covers, and the HD cover of the song sheet. */
    private static final int AVATAR_MAX_PX = 512;
    private static final int COVER_HD_MAX_PX = 1024;

    private Uri SongFolderUri;

    private RecyclerView rvSongList;
    private SwipeRefreshLayout refreshSongList;

    /** Visible list (search matches when a query is active). */
    private SongListData[] Songs;
    /** Full merged list (local + cloud) from the last rebuild, before the search filter. */
    private SongListData[] allSongs = new SongListData[0];
    /** Cloud hashes of the last rebuild (LOCAL_ONLY excluded) and whether each was
     *  in flight then: detects adds/removes and slot changes without a rebuild. */
    private final Map<String, Boolean> builtInFlightByHash = new HashMap<>();

    // === Mini-player ===
    private MiniPlayerController miniPlayer;

    // === Bottom tabs: songs / playlists ===
    private static final String STATE_TAB = "main_tab";
    private static final int TAB_SONGS = 0, TAB_PLAYLISTS = 1;
    private int currentTab = TAB_SONGS;
    private com.google.android.material.bottomnavigation.BottomNavigationView bnvMain;
    private View playlistsTab;
    private RecyclerView rvPlaylists;
    private TextView tvPlaylistsCount, tvPlaylistsEmpty;
    private View bAddPlaylist;
    private com.jotapeveloper.fision.brand.EmptyBlobsView vEmptyBlobs;
    /** Signed in with no playlists: the playlists empty notice is up. */
    private boolean playlistsEmptyShown;
    private View llPlaylistsAddFirst;
    private PlaylistListAdapter playlistAdapter;
    private final com.jotapeveloper.fision.cloud.PlaylistRepository.Listener playlistListener =
            this::refreshPlaylistsTab;


    // === Server state ===
    private ImageView ivCloudStatus;
    /** Repaints the cloud icon when a /server/info fetch lands (free VRAM). */
    private final ServerInfoManager.Listener serverInfoListener = () -> {
        if (isFinishing() || isDestroyed()) return;
        refreshCloudStatusIcon();
    };
    private View flAvatar;
    private TextView tvAvatarInitial;
    private com.google.android.material.imageview.ShapeableImageView ivAvatarImage;
    private MaterialButton bSettings;
    private final AvatarManager.Listener avatarListener = user -> {
        if (isFinishing() || isDestroyed()) return;
        runOnUiThread(this::refreshToolbarAvatar);
    };
    private final CloudRepository.Listener cloudListener = new CloudRepository.Listener() {
        @Override public void onCloudChanged() {
            // CloudRepository already posts to the main thread; do not re-dispatch.
            if (isFinishing() || isDestroyed()) return;
            try { applyCloudSnapshotLight(); }
            catch (Throwable t) { Log.e("MainActivity", "applyCloudSnapshotLight threw", t); }
        }
        @Override public void onConnectivityChanged(boolean reachable) {
            if (isFinishing() || isDestroyed()) return;
            updateCloudStatusIcon(reachable);
            // Back online: pick up playlist changes made elsewhere.
            if (reachable) com.jotapeveloper.fision.cloud.PlaylistRepository.get(MainActivity.this).sync();
            refreshPlaylistsTab();
        }
        @Override public void onRetryableJobError(String hash) {
            if (isFinishing() || isDestroyed()) return;
            showJobRetryDialog(hash);
        }
    };

    /**
     * Light refresh without touching disk or bitmaps: only updates the cloud field
     * of items that actually changed. If the item set itself changes (adds/removes),
     * falls back to a full refresh.
     */
    private void applyCloudSnapshotLight() {
        if (Songs == null || rvSongList == null) return;
        RecyclerView.Adapter<?> ad = rvSongList.getAdapter();
        if (!(ad instanceof SongListAdapter)) { refreshSongList(); return; }

        java.util.List<com.jotapeveloper.fision.cloud.CloudItem> snap =
                com.jotapeveloper.fision.cloud.CloudRepository.get(this).snapshot();
        java.util.Map<String, com.jotapeveloper.fision.cloud.CloudItem> byKey = new java.util.HashMap<>();
        for (com.jotapeveloper.fision.cloud.CloudItem c : snap) {
            byKey.put(c.hash, c);
            if (c.localFolderName != null) byKey.put(c.localFolderName, c);
        }

        // Structural changes are checked against the full merged list (allSongs),
        // not the visible one: with a search active, Songs only holds the matches.
        java.util.Set<String> snapHashes = new java.util.HashSet<>();
        for (com.jotapeveloper.fision.cloud.CloudItem c : snap) snapHashes.add(c.hash);

        // 1) server item not in the merged list -> add.
        //    An item entering or leaving the in-flight state changes its slot
        //    (in-flight block on top vs date order) -> full rebuild to re-sort.
        for (com.jotapeveloper.fision.cloud.CloudItem c : snap) {
            if (c.state == com.jotapeveloper.fision.cloud.CloudItem.State.LOCAL_ONLY) continue;
            Boolean wasInFlight = builtInFlightByHash.get(c.hash);
            if (wasInFlight == null || wasInFlight != c.isInFlight()) {
                refreshSongList();
                return;
            }
        }

        // 2) merged item with cloud that is no longer on the server -> remove
        for (String hash : builtInFlightByHash.keySet()) {
            if (!snapHashes.contains(hash)) { refreshSongList(); return; }
        }

        // 3) No adds/removes: refresh only items with real changes
        SongListAdapter songsAd = (SongListAdapter) ad;
        for (int i = 0; i < Songs.length; i++) {
            SongListData s = Songs[i];
            com.jotapeveloper.fision.cloud.CloudItem nc = byKey.get(s.getTitle());
            if (nc == null && s.cloud != null) nc = byKey.get(s.cloud.hash);

            // Title change? If it went from hash to a readable displayName (syncActiveJobs
            // arriving after mergeLibrary), the tvTitle must also be repainted.
            boolean titleChanged = false;
            if (nc != null && nc.displayName != null && !nc.displayName.isEmpty()
                    && !nc.displayName.equals(s.getDisplayTitle())
                    && !s.hasLocalFolder()) {
                s.setDisplayTitle(nc.displayName);
                titleChanged = true;
            }

            // Cover arrived asynchronously? buildMergedSongsList runs before
            // fetchCover completes, so cloud-only songs are initially built
            // without a coverPath. Sync s.coverPath from nc.coverPath and
            // force a full bind so the cover cell decodes the bitmap.
            boolean coverArrived = false;
            if (nc != null && nc.coverPath != null && !nc.coverPath.isEmpty()
                    && (s.getCoverPath() == null || !s.getCoverPath().equals(nc.coverPath))
                    && new java.io.File(nc.coverPath).exists()) {
                s.setCoverPath(nc.coverPath);
                coverArrived = true;
            }

            // For in-flight items (running/queued/downloading) the CloudItem is the SAME
            // object in memory as in the repository: when it changes its pct/state,
            // s.cloud already reflects the new value and cloudDeltaChanged would return false even though
            // the UI needs to repaint. Force a light repaint in those cases.
            // Also cache the last rendered state to detect the transition
            // DOWNLOADING -> LOCAL_AND_CLOUD: without this cache, the final bind never
            // fires because a == b (same CloudItem object) and the item stays visually
            // "downloading 100%" until the next scroll away and back.
            boolean inFlight = nc != null && nc.isInFlight();
            com.jotapeveloper.fision.cloud.CloudItem.State cachedState =
                    nc != null ? lastRenderedStateByHash.get(nc.hash) : null;
            boolean stateChangedSinceRender = nc != null && cachedState != nc.state;
            if (titleChanged || coverArrived) {
                s.cloud = nc;
                songsAd.notifyItemChanged(i);     // full bind, no payload
            } else if (stateChangedSinceRender || inFlight || cloudDeltaChanged(s.cloud, nc)) {
                songsAd.updateCloudAt(i, nc);     // light bind with payload
            }
            if (nc != null) lastRenderedStateByHash.put(nc.hash, nc.state);
        }
    }

    /** Cache of the last cloud state rendered per hash. Detects transitions
     *  between states when the CloudItem object is the same in the repo and in Songs[]. */
    private final java.util.Map<String, com.jotapeveloper.fision.cloud.CloudItem.State>
            lastRenderedStateByHash = new java.util.HashMap<>();

    private static boolean cloudDeltaChanged(com.jotapeveloper.fision.cloud.CloudItem a,
                                             com.jotapeveloper.fision.cloud.CloudItem b) {
        if (a == b) return false;
        if (a == null || b == null) return true;
        return a.state != b.state
                || a.queuePosition != b.queuePosition
                || a.downloadPct  != b.downloadPct;
    }


    /** Song folders of the active account: changes with login and logout. */
    public File getSongFolder() {
        return SongStorage.root(this);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        com.jotapeveloper.fision.design.AccentColorManager.get(this).applyOverlayTo(this);

        // Attach the app-wide foreground tracker: dismisses pending "song
        // ready" notifications the moment the user returns to any screen.
        com.jotapeveloper.fision.cloud.AppForegroundState.get(this);

        AppLanguage.migrateLegacy(this);

        setContentView(R.layout.activity_main);


        et_find = findViewById(R.id.et_find);
        tv_title = findViewById(R.id.tv_title);
        tv_SongsCount = findViewById(R.id.tv_SongsCount);
        b_add = findViewById(R.id.b_add);
        ivAddIcon = findViewById(R.id.iv_addIcon);
        tvAddLabel = findViewById(R.id.tv_addLabel);
        vEmptyBlobs = findViewById(R.id.v_emptyBlobs);
        b_orderBy = findViewById(R.id.b_orderBy);
        ivCloudStatus = findViewById(R.id.iv_cloud_status);
        flAvatar = findViewById(R.id.fl_avatar);
        tvAvatarInitial = findViewById(R.id.tv_avatarInitial);
        ivAvatarImage = findViewById(R.id.iv_avatarImage);
        bSettings = findViewById(R.id.b_settings);
        ivCloudStatus.setOnClickListener(v -> handleCloudIconTap());
        flAvatar.setOnClickListener(v -> openSettings(v));
        refreshToolbarAvatar();
        refreshCloudStatusIcon();

        // Load the saved sort order and wire up the button
        SharedPreferences sortPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        sortOrder = sortPrefs.getInt(PREF_SORT_ORDER, 0);
        b_orderBy.setOnClickListener(v -> showSortDialog());
        rvSongList =(RecyclerView) findViewById(R.id.rv_songList);
        // No cross-fade on item changes: a cover or title arriving from the
        // server would otherwise flash the whole row.
        RecyclerView.ItemAnimator songsAnimator = rvSongList.getItemAnimator();
        if (songsAnimator instanceof androidx.recyclerview.widget.SimpleItemAnimator) {
            ((androidx.recyclerview.widget.SimpleItemAnimator) songsAnimator)
                    .setSupportsChangeAnimations(false);
        }
        // Edge fades: each one shows only while songs remain beyond its edge.
        fadeTop = findViewById(R.id.v_main_fadeTop);
        fadeBottom = findViewById(R.id.v_main_fadeBottom);
        rvSongList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                updateListFades();
            }
        });
        rvSongList.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                updateListFades());
        refreshSongList = findViewById(R.id.swipeRefreshLayout);
        refreshSongList.setColorSchemeColors(
                com.jotapeveloper.fision.design.AccentColorManager.resolveAccent(this));

        // Mini-player: live blur of the list sitting behind it. With the bar
        // shown, the lists get bottom padding so the last row stays reachable.
        miniPlayer = new MiniPlayerController(this);
        miniPlayer.setBlurSource(rvSongList);
        miniPlayer.setVisibilityListener(visible -> setListsBottomPadding(
                visible ? MiniPlayerController.listClearancePx(this) : 0));

        setupTabs(savedInstanceState != null
                ? savedInstanceState.getInt(STATE_TAB, TAB_SONGS) : TAB_SONGS);

        refreshSongList.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {
            @Override
            public void onRefresh() {
                refreshSongList();
                refreshSongList.setRefreshing(false);
            }
        });


        // Load Main Activity RecyclerView with songs
        refreshSongList();
        AuthManager.get(this).addLibraryChangedListener(libraryChangedListener);


        // First-run flow: prompt the sign-in dialog on the very first launch. The
        // user can either authenticate against a server or skip into local mode.
        maybeShowFirstRunLogin();
        // Leftovers of an import cut short (app killed mid copy).
        new Thread(() -> SongFolderImporter.cleanStaging(getApplicationContext()), "ImportCleanup").start();
    }

    /** Shows the login dialog once, on the first ever launch. The dialog itself
     *  transitions the app out of first-run state via AuthManager. */
    private void maybeShowFirstRunLogin() {
        if (!AuthManager.get(this).isFirstRun()) {
            // Debug builds preview the welcome once per app start, whatever the
            // session; the choice is then ignored.
            if (com.jotapeveloper.fision.onboarding.Onboarding.debugAlwaysShow(this) && !welcomePreviewShown) {
                welcomePreviewShown = true;
                startActivity(new Intent(this, WelcomeActivity.class));
            }
            return;
        }
        welcome.launch(new Intent(this, WelcomeActivity.class));
    }

    private static boolean welcomePreviewShown = false;

    private boolean isDebuggable() {
        return (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    /** First launch: the welcome screens end in a choice; backing out of them closes the app. */
    private final androidx.activity.result.ActivityResultLauncher<Intent> welcome =
            registerForActivityResult(
                    new androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
                    r -> {
                        String choice = r.getData() != null
                                ? r.getData().getStringExtra(WelcomeActivity.EXTRA_CHOICE) : null;
                        if (r.getResultCode() != RESULT_OK || choice == null) { finish(); return; }
                        if (WelcomeActivity.CHOICE_LOCAL.equals(choice)) {
                            AuthManager.get(this).enterLocalMode();
                            refreshToolbarAvatar();
                            refreshCloudStatusIcon();
                            rvSongList.postDelayed(this::maybeStartLibraryTour, 700);
                        } else {
                            showFirstRunLogin();
                        }
                    });

    /** Server sign-in after the welcome screens; local mode stays offered in the dialog. */
    private void showFirstRunLogin() {
        // Defer to the next UI cycle so the activity is fully laid out.
        rvSongList.post(() -> {
            if (isFinishing() || isDestroyed()) return;
            LoginDialog.show(this, true, new LoginDialog.Callback() {
                @Override public void onLoggedIn(String u) {
                    refreshToolbarAvatar();
                    refreshCloudStatusIcon();
                    CloudRepository.get(MainActivity.this).notifyConfigChanged();
                    CloudRepository.get(MainActivity.this).startPolling();
                    rvSongList.postDelayed(MainActivity.this::maybeStartLibraryTour, 700);
                }
                @Override public void onLocalMode() {
                    refreshToolbarAvatar();
                    refreshCloudStatusIcon();
                    rvSongList.postDelayed(MainActivity.this::maybeStartLibraryTour, 700);
                }
                @Override public void onCancelled() {
                    // No decision yet; keep first_run true so it prompts again next open.
                }
            });
        });
    }

    /** Guided tour of the library: first visit after the sign-in decision,
     *  and again after "Show the tour again" in Settings. Never over a dialog. */
    private void maybeStartLibraryTour() {
        if (isFinishing() || isDestroyed() || !hasWindowFocus()) return;
        AuthManager auth = AuthManager.get(this);
        if (auth.isFirstRun()) return;
        if (Onboarding.isDone(this, Onboarding.LIBRARY)) return;
        boolean local = auth.isLocalMode();
        java.util.List<CoachMarkOverlay.Step> steps = new java.util.ArrayList<>();
        steps.add(new CoachMarkOverlay.Step(CoachMarkOverlay.views(b_add),
                local ? R.string.coach_lib_import_title : R.string.coach_lib_add_title,
                local ? R.string.coach_lib_import_body : R.string.coach_lib_add_body));
        steps.add(new CoachMarkOverlay.Step(CoachMarkOverlay.views(et_find, b_orderBy),
                R.string.coach_lib_search_title, R.string.coach_lib_search_body).corner(16));
        View firstSong = rvSongList.getChildCount() > 0 ? rvSongList.getChildAt(0) : null;
        if (firstSong != null) {
            steps.add(new CoachMarkOverlay.Step(CoachMarkOverlay.views(firstSong),
                    R.string.coach_lib_song_title, R.string.coach_lib_song_body).corner(16));
        } else {
            // Empty library: the empty notice stands in for the first song.
            steps.add(new CoachMarkOverlay.Step(CoachMarkOverlay.views(findViewById(R.id.ll_songsEmptyText)),
                    R.string.coach_lib_song_title, R.string.coach_lib_song_empty_body).corner(16));
        }
        steps.add(new CoachMarkOverlay.Step(
                CoachMarkOverlay.views(bnvMain.findViewById(R.id.tab_playlists)),
                R.string.coach_lib_playlists_title, R.string.coach_lib_playlists_body).corner(16));
        if (!local) {
            steps.add(new CoachMarkOverlay.Step(CoachMarkOverlay.views(ivCloudStatus),
                    R.string.coach_lib_cloud_title, R.string.coach_lib_cloud_body));
        }
        steps.add(new CoachMarkOverlay.Step(CoachMarkOverlay.views(findViewById(R.id.b_settings)),
                R.string.coach_lib_settings_title, R.string.coach_lib_settings_body));
        CoachMarkOverlay.start(this, Onboarding.LIBRARY, steps);
    }

    public void changeTextAnimation(TextView text, String NewText){
        Animation fade_out = new AlphaAnimation(1.0f, 0.0f);
        Animation fade_in = new AlphaAnimation(0.0f, 1.0f);
        fade_out.setDuration(200);
        fade_in.setDuration(200);

        text.startAnimation(fade_out);


        fade_out.setAnimationListener(new Animation.AnimationListener() {
            @Override
            public void onAnimationStart(Animation animation) {

            }

            @Override
            public void onAnimationEnd(Animation animation) {
                text.setText(NewText);
                text.startAnimation(fade_in);
            }

            @Override
            public void onAnimationRepeat(Animation animation) {

            }
        });
    }
    public void buttonAnimation(boolean visible, View button){
        // Cross-fade the button while the ConstraintLayout re-lays out its
        // children so the RecyclerView slides into (or out of) the freed slot
        // instead of jumping when the button visibility flips.
        android.view.ViewGroup root = (android.view.ViewGroup) button.getParent();
        androidx.transition.TransitionSet set = new androidx.transition.TransitionSet()
                .addTransition(new androidx.transition.Fade(
                        visible ? androidx.transition.Fade.IN
                                : androidx.transition.Fade.OUT).setDuration(180))
                .addTransition(new androidx.transition.ChangeBounds().setDuration(260))
                .setOrdering(androidx.transition.TransitionSet.ORDERING_TOGETHER)
                .setInterpolator(new androidx.interpolator.view.animation.FastOutSlowInInterpolator());
        androidx.transition.TransitionManager.beginDelayedTransition(root, set);
        button.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private View fadeTop, fadeBottom;

    // ---------------------------------------------------------------
    // Bottom tabs
    // ---------------------------------------------------------------
    private void setupTabs(int initialTab) {
        bnvMain = findViewById(R.id.bnv_main);
        playlistsTab = findViewById(R.id.cl_playlistsTab);
        rvPlaylists = findViewById(R.id.rv_playlists);
        tvPlaylistsCount = findViewById(R.id.tv_playlistsCount);
        tvPlaylistsEmpty = findViewById(R.id.tv_playlistsEmpty);
        bAddPlaylist = findViewById(R.id.b_addPlaylist);

        songSearch = new SearchBar(et_find, b_add, R.string.tv_title, R.string.tv_title_search, q -> {
            boolean empty = q.isEmpty();
            // Pull-to-refresh only without a query.
            refreshSongList.setEnabled(empty);
            if (empty) {
                // Empty query: rebuild the full merged list (local + cloud +
                // sticky order).
                refreshSongList();
            } else {
                applySearchFilter(q);
            }
        });
        playlistSearch = new SearchBar(findViewById(R.id.et_findPlaylist), bAddPlaylist,
                R.string.tab_playlists, R.string.tv_title_search_playlist, q -> refreshPlaylistsTab());

        int coverPx = (int) (52 * getResources().getDisplayMetrics().density);
        playlistAdapter = new PlaylistListAdapter(coverPx, new PlaylistListAdapter.OnPlaylistClick() {
            @Override public void onClick(com.jotapeveloper.fision.cloud.PlaylistRepository.Playlist p) {
                Intent i = new Intent(MainActivity.this, PlaylistActivity.class);
                i.putExtra(PlaylistActivity.EXTRA_PLAYLIST_ID, p.id);
                startActivity(i);
            }
            @Override public void onOptions(com.jotapeveloper.fision.cloud.PlaylistRepository.Playlist p) {
                showPlaylistOptions(p);
            }
        });
        rvPlaylists.setLayoutManager(new LinearLayoutManager(this));
        rvPlaylists.setAdapter(playlistAdapter);
        bAddPlaylist.setOnClickListener(v ->
                startActivity(new Intent(this, PlaylistEditActivity.class)));
        llPlaylistsAddFirst = findViewById(R.id.ll_playlistsAddFirst);
        llPlaylistsAddFirst.setOnClickListener(v ->
                startActivity(new Intent(this, PlaylistEditActivity.class)));

        bnvMain.setSelectedItemId(initialTab == TAB_PLAYLISTS ? R.id.tab_playlists : R.id.tab_songs);
        bnvMain.setOnItemSelectedListener(item -> {
            int tab = item.getItemId() == R.id.tab_playlists ? TAB_PLAYLISTS : TAB_SONGS;
            if (tab != targetTab) switchTabAnimated(tab);
            return true;
        });
        showTab(initialTab);
    }

    private static final long TAB_OUT_MS = 120, TAB_IN_MS = 220;
    /** Bumped on every switch so a late end action of a superseded one is ignored. */
    private int tabSwitchToken = 0;
    /** Tab being shown or animated to (currentTab changes mid-animation). */
    private int targetTab = TAB_SONGS;

    /** Views that make up the content of [tab] (title excluded). */
    private View[] tabViews(int tab) {
        return tab == TAB_SONGS
                ? new View[] { et_find, b_add, tv_SongsCount, b_orderBy, refreshSongList,
                               findViewById(R.id.ll_songsAddFirst) }
                : new View[] { playlistsTab };
    }

    /** Cross-slide between tabs: the old content fades out drifting away from
     *  the new tab, the new content fades in from its side. The title fades
     *  with it. */
    private void switchTabAnimated(int tab) {
        final int token = ++tabSwitchToken;
        targetTab = tab;
        float dx = 24f * getResources().getDisplayMetrics().density;
        // Moving to the right tab: content travels left, and vice versa.
        float dir = tab == TAB_PLAYLISTS ? 1f : -1f;
        View[] out = tabViews(currentTab);
        View[] in = tabViews(tab);
        android.view.animation.Interpolator accel = new android.view.animation.AccelerateInterpolator();
        android.view.animation.Interpolator decel = new android.view.animation.DecelerateInterpolator(1.6f);

        fadeTop.setVisibility(View.INVISIBLE);
        fadeBottom.setVisibility(View.INVISIBLE);
        // Blobs stay put between two empty tabs; otherwise they fade.
        updateEmptyBlobs();
        for (View v : out) {
            v.animate().cancel();
            v.animate().alpha(0f).translationX(-dir * dx).setDuration(TAB_OUT_MS)
                    .setInterpolator(accel).start();
        }
        tv_title.animate().cancel();
        tv_title.animate().alpha(0f).setDuration(TAB_OUT_MS).setInterpolator(accel)
                .withEndAction(() -> {
                    if (token != tabSwitchToken) return;
                    for (View v : out) { v.setAlpha(1f); v.setTranslationX(0f); }
                    showTab(tab);
                    for (View v : in) {
                        v.animate().cancel();
                        v.setAlpha(0f);
                        v.setTranslationX(dir * dx);
                        v.animate().alpha(1f).translationX(0f).setDuration(TAB_IN_MS)
                                .setInterpolator(decel).start();
                    }
                    tv_title.animate().alpha(1f).setDuration(TAB_IN_MS)
                            .setInterpolator(decel).start();
                }).start();
    }

    private void showTab(int tab) {
        // Leaving a tab ends its search in progress.
        if (tab != TAB_SONGS && songSearch.isActive()) songSearch.close(false);
        if (tab != TAB_PLAYLISTS && playlistSearch.isActive()) playlistSearch.close(false);
        currentTab = tab;
        targetTab = tab;
        boolean songs = tab == TAB_SONGS;
        // INVISIBLE, not GONE: the shared blobs hang from the songs header.
        et_find.setVisibility(songs && !isAddWide(b_add) ? View.VISIBLE : View.INVISIBLE);
        b_add.setVisibility(songs ? View.VISIBLE : View.INVISIBLE);
        // Also INVISIBLE: the count line is chained to the list, and a GONE
        // list lets it (and the blobs under it) drift down.
        refreshSongList.setVisibility(songs ? View.VISIBLE : View.INVISIBLE);
        applySongsEmptyState();
        if (tvNoResults != null && !songs) tvNoResults.setVisibility(View.GONE);
        playlistsTab.setVisibility(songs ? View.GONE : View.VISIBLE);
        tv_title.setText(songs ? R.string.tv_title : R.string.tab_playlists);
        // Any half-finished switch leaves nothing faded or shifted.
        for (View v : tabViews(tab)) { v.setAlpha(1f); v.setTranslationX(0f); }
        if (songs) {
            updateListFades();
        } else {
            fadeTop.setVisibility(View.INVISIBLE);
            fadeBottom.setVisibility(View.INVISIBLE);
            refreshPlaylistsTab();
        }
        // The mini-player blurs whichever list sits behind it.
        miniPlayer.setBlurSource(songs ? rvSongList : rvPlaylists);
    }

    private void hideKeyboard() {
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(getWindow().getDecorView().getWindowToken(), 0);
    }

    /** Repaints the Playlists tab from the repository (cheap; no network). */
    private void refreshPlaylistsTab() {
        if (playlistAdapter == null || isFinishing() || isDestroyed()) return;
        com.jotapeveloper.fision.cloud.PlaylistRepository repo =
                com.jotapeveloper.fision.cloud.PlaylistRepository.get(this);
        List<com.jotapeveloper.fision.cloud.PlaylistRepository.Playlist> list = repo.getAll();
        boolean none = list.isEmpty();
        // Last playlist gone mid-search: the search row itself goes away.
        if (none && playlistSearch.isActive()) playlistSearch.close(currentTab == TAB_PLAYLISTS);
        String query = playlistSearch.query();
        List<com.jotapeveloper.fision.cloud.PlaylistRepository.Playlist> shown = list;
        if (!query.isEmpty()) {
            String k = foldForSearch(query);
            shown = new ArrayList<>();
            for (com.jotapeveloper.fision.cloud.PlaylistRepository.Playlist p : list) {
                if (foldForSearch(p.title).contains(k)) shown.add(p);
            }
        }
        playlistAdapter.submit(shown);
        tvPlaylistsCount.setText(query.isEmpty()
                ? getResources().getQuantityString(R.plurals.playlist_count, list.size(), list.size())
                : getResources().getQuantityString(R.plurals.search_result_count, shown.size(), shown.size()));
        boolean loggedIn = AuthManager.get(this).isLoggedIn();
        // No playlists: the add button widens into "New playlist" in the search slot.
        View notice = llPlaylistsAddFirst;
        setAddWide(bAddPlaylist, findViewById(R.id.tv_addPlaylistLabel), playlistSearch.field, none,
                currentTab == TAB_PLAYLISTS, tvPlaylistsCount, notice);
        playlistSearch.field.setVisibility(none ? View.INVISIBLE : View.VISIBLE);
        // INVISIBLE keeps the list where the songs list starts (shared blobs).
        tvPlaylistsCount.setVisibility(none ? View.INVISIBLE : View.VISIBLE);
        playlistsEmptyShown = loggedIn && none;
        // A playlist needs at least one song in the cloud library.
        boolean hasSongs = SongCatalog.hasCloudSongs(this);
        // Signed out: plain message. Search with no matches: "No results".
        // Signed in and empty: blobs notice.
        if (!loggedIn) {
            tvPlaylistsEmpty.setText(R.string.playlists_need_login);
            tvPlaylistsEmpty.setVisibility(View.VISIBLE);
        } else if (!query.isEmpty() && shown.isEmpty()) {
            tvPlaylistsEmpty.setText(R.string.no_results);
            tvPlaylistsEmpty.setVisibility(View.VISIBLE);
        } else {
            tvPlaylistsEmpty.setVisibility(View.GONE);
        }
        llPlaylistsAddFirst.setVisibility(loggedIn && none ? View.VISIBLE : View.GONE);
        // Creating needs the server; offline the list stays playable.
        boolean canEdit = hasSongs && repo.canEdit()
                && list.size() < com.jotapeveloper.fision.cloud.PlaylistRepository.MAX_PLAYLISTS;
        bAddPlaylist.setEnabled(canEdit);
        bAddPlaylist.setAlpha(canEdit ? 1f : 0.4f);
        llPlaylistsAddFirst.setClickable(canEdit);
        updateEmptyBlobs();
        TextView emptyTitle = findViewById(R.id.tv_playlistsEmptyTitle);
        TextView emptyHint = findViewById(R.id.tv_playlistsEmptyHint);
        if (!hasSongs) {
            emptyTitle.setText(R.string.playlists_empty_nosongs_title);
            emptyHint.setText(R.string.playlists_empty_nosongs_hint);
        } else {
            emptyTitle.setText(R.string.playlists_empty_title);
            emptyHint.setText(accentTap(R.string.playlists_empty_hint));
        }
        if (currentTab == TAB_PLAYLISTS) miniPlayer.invalidateBlur();
    }

    /** Playlist ⋮ sheet: edit / delete (both need the server). */
    private void showPlaylistOptions(com.jotapeveloper.fision.cloud.PlaylistRepository.Playlist p) {
        com.jotapeveloper.fision.cloud.PlaylistRepository repo =
                com.jotapeveloper.fision.cloud.PlaylistRepository.get(this);
        BottomSheetDialog sheet = new BottomSheetDialog(this, R.style.TransparentBottomSheetDialog);
        View v = getLayoutInflater().inflate(R.layout.modal_sheet_dialog, null);
        sheet.setContentView(v);
        ImageView ivCover = v.findViewById(R.id.iv_cover);
        TextView tvTitle  = v.findViewById(R.id.Title);
        TextView tvSub    = v.findViewById(R.id.Artist);
        Button bEdit      = v.findViewById(R.id.b_rename);
        Button bDelete    = v.findViewById(R.id.b_delete);
        PlaylistCovers.load(ivCover, p, (int) (140 * getResources().getDisplayMetrics().density));
        tvTitle.setText(p.title);
        int n = p.songs.size();
        tvSub.setText(getResources().getQuantityString(R.plurals.playlist_song_count, n, n));
        bEdit.setText(R.string.playlist_edit_title);
        bDelete.setText(R.string.playlist_delete);
        boolean canEdit = repo.canEdit();
        for (Button b : new Button[] { bEdit, bDelete }) {
            b.setEnabled(canEdit);
            b.setAlpha(canEdit ? 1f : 0.4f);
        }
        bEdit.setOnClickListener(x -> {
            sheet.dismiss();
            Intent i = new Intent(this, PlaylistEditActivity.class);
            i.putExtra(PlaylistEditActivity.EXTRA_PLAYLIST_ID, p.id);
            startActivity(i);
        });
        bDelete.setOnClickListener(x -> {
            sheet.dismiss();
            BlurDialogs.confirm(this, R.drawable.ic_delete,
                    getString(R.string.playlist_delete_confirm, p.title),
                    getString(R.string.playlist_delete_action), getString(R.string.playlist_cancel),
                    () -> repo.delete(p.id, new com.jotapeveloper.fision.cloud.PlaylistRepository.Result() {
                        @Override public void onSuccess(@androidx.annotation.Nullable com.jotapeveloper.fision.cloud.PlaylistRepository.Playlist x) {
                            Toast.makeText(MainActivity.this, R.string.playlist_deleted,
                                    Toast.LENGTH_SHORT).show();
                        }
                        @Override public void onError(@NonNull com.jotapeveloper.fision.cloud.PlaylistRepository.PlaylistError e) {
                            Toast.makeText(MainActivity.this,
                                    com.jotapeveloper.fision.cloud.PlaylistErrors.message(MainActivity.this, e),
                                    Toast.LENGTH_LONG).show();
                        }
                    }));
        });
        sheet.show();
    }

    private void updateListFades() {
        if (currentTab != TAB_SONGS) return;
        fadeTop.setVisibility(rvSongList.canScrollVertically(-1) ? View.VISIBLE : View.INVISIBLE);
        fadeBottom.setVisibility(rvSongList.canScrollVertically(1) ? View.VISIBLE : View.INVISIBLE);
    }

    private SearchBar songSearch, playlistSearch;

    interface QueryListener { void onQuery(@NonNull String query); }

    /**
     * Search bar of a library tab. With focus the add button steps aside and
     * the title switches to the search wording; the clear (X) fades in with
     * text (it stays installed so the text never shifts); the keyboard Search
     * key only hides the keyboard and leaves the results on screen.
     */
    private final class SearchBar {
        final EditText field;
        private final View addButton;
        private final int titleRes;
        private final Drawable clearIcon;
        private boolean clearVisible;
        private android.animation.ValueAnimator clearAnim;

        SearchBar(EditText field, View addButton, int titleRes, int searchTitleRes,
                  QueryListener listener) {
            this.field = field;
            this.addButton = addButton;
            this.titleRes = titleRes;
            clearIcon = field.getCompoundDrawables()[2].mutate();
            clearIcon.setAlpha(0);
            field.setOnFocusChangeListener((v, hasFocus) -> {
                if (!hasFocus) return;
                if (addButton.getVisibility() == View.VISIBLE) buttonAnimation(false, addButton);
                if (tv_title.getText().toString().equals(getString(titleRes))) {
                    changeTextAnimation(tv_title, getString(searchTitleRes));
                }
            });
            field.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                    String q = s.toString();
                    showClear(!TextUtils.isEmpty(q));
                    listener.onQuery(q);
                }
                @Override public void afterTextChanged(Editable e) {}
            });
            field.setOnTouchListener((v, ev) -> {
                // Tap on the X (only while it is shown): the icon plus its
                // end padding, in view coordinates.
                if (!clearVisible) return false;
                if (ev.getAction() == MotionEvent.ACTION_UP) {
                    int hitStart = field.getWidth() - field.getPaddingEnd()
                            - clearIcon.getBounds().width() - field.getCompoundDrawablePadding();
                    if (ev.getX() >= hitStart) {
                        field.setText("");
                        return true;
                    }
                }
                return false;
            });
            field.setOnEditorActionListener((v, actionId, event) -> {
                if (actionId != android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) return false;
                hideKeyboard();
                return true;
            });
        }

        boolean isActive() {
            return field.hasFocus() || field.length() > 0;
        }

        String query() {
            return field.getText().toString();
        }

        /** Ends the search: query cleared, add button back, keyboard down.
         *  [restoreTitle] fades the tab title back (not when the tab changes). */
        void close(boolean restoreTitle) {
            field.clearFocus();
            field.setText("");
            if (addButton.getVisibility() != View.VISIBLE) buttonAnimation(true, addButton);
            if (restoreTitle) changeTextAnimation(tv_title, getString(titleRes));
            hideKeyboard();
        }

        private void showClear(boolean visible) {
            if (clearVisible == visible) return;
            clearVisible = visible;
            if (clearAnim != null) clearAnim.cancel();
            clearAnim = android.animation.ValueAnimator.ofInt(clearIcon.getAlpha(), visible ? 255 : 0);
            clearAnim.setDuration(visible ? 160 : 120);
            clearAnim.addUpdateListener(a -> {
                clearIcon.setAlpha((int) a.getAnimatedValue());
                field.invalidate();
            });
            clearAnim.start();
        }
    }

    public List<String> getSubdirectories(File Folder, ContentResolver contentResolver){
        List<String> subdirectories = new ArrayList<>();

        DocumentFile folder = DocumentFile.fromFile(Folder);

        if (folder.isDirectory()){Log.d("Log:","Path exists");}else{Log.d("Log:","Path does NOT exists");}

        DocumentFile[] files = folder.listFiles();


        for (DocumentFile file : files){
            Log.d("Log:",file.getName());
            if (file.isDirectory()){
                subdirectories.add(file.getName());
            }
        }
        return subdirectories;
    }


    /** Search key form: lower case without diacritics, so "cancion" matches
     *  "Canción" and the other way round. */
    private static String foldForSearch(String s) {
        if (s == null) return "";
        String decomposed = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{M}+", "").toLowerCase(java.util.Locale.ROOT);
    }

    public void addSong(View view){
        // Local mode: songs come as ready-made folders.
        if (AuthManager.get(this).isLocalMode()) {
            confirmImportFolder();
            return;
        }
        // Cloud pipeline requires an authenticated server: prompt the login dialog.
        if (!AuthManager.get(this).isLoggedIn()) {
            Toast.makeText(this, R.string.local_mode_import_blocked, Toast.LENGTH_LONG).show();
            LoginDialog.show(this, true, new LoginDialog.Callback() {
                @Override public void onLoggedIn(String u) {
                    refreshToolbarAvatar();
                    refreshCloudStatusIcon();
                    CloudRepository.get(MainActivity.this).notifyConfigChanged();
                    songPicker.launch(new String[]{"audio/*"});
                }
            });
            return;
        }
        songPicker.launch(new String[]{"audio/*"});
    }

    // === Local mode: song folder import ===

    private final androidx.activity.result.ActivityResultLauncher<android.net.Uri> folderPicker =
            registerForActivityResult(
                    new androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree(),
                    uri -> { if (uri != null) runFolderImport(uri); });

    private void confirmImportFolder() {
        BlurDialogs.confirm(this, R.drawable.ic_folder_import,
                getString(R.string.import_folder_confirm),
                getString(R.string.import_folder_pick), getString(R.string.NO),
                () -> folderPicker.launch(null));
    }

    /** Copies and checks the folder off the main thread behind a progress dialog. */
    private void runFolderImport(android.net.Uri uri) {
        View v = LayoutInflater.from(this).inflate(R.layout.delete_alert_dialog, null);
        ((ImageView) v.findViewById(R.id.iv_alertDialog_ic)).setImageResource(R.drawable.ic_folder_import);
        ((TextView) v.findViewById(R.id.tv_alertDialog_text)).setText(R.string.import_folder_checking);
        v.findViewById(R.id.b_yes).setVisibility(View.GONE);
        v.findViewById(R.id.b_no).setVisibility(View.GONE);
        android.widget.ProgressBar pb = new android.widget.ProgressBar(this);
        pb.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(
                com.jotapeveloper.fision.design.AccentColorManager.resolveAccent(this)));
        ((android.view.ViewGroup) v).addView(pb);
        androidx.appcompat.app.AlertDialog progress = BlurDialogs.show(this, v);
        progress.setCancelable(false);
        new Thread(() -> {
            SongFolderImporter.Result r = SongFolderImporter.importTree(getApplicationContext(), uri);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                progress.dismiss();
                if (r.ok) {
                    refreshSongList();
                    Toast.makeText(this, getString(R.string.import_folder_done, r.name),
                            Toast.LENGTH_LONG).show();
                } else {
                    showImportError(r.name != null ? getString(r.error, r.name) : getString(r.error));
                }
            });
        }, "SongFolderImport").start();
    }

    private void showImportError(CharSequence message) {
        View v = LayoutInflater.from(this).inflate(R.layout.delete_alert_dialog, null);
        ((ImageView) v.findViewById(R.id.iv_alertDialog_ic)).setImageResource(R.drawable.ic_warning);
        ((TextView) v.findViewById(R.id.tv_alertDialog_text)).setText(message);
        v.findViewById(R.id.b_no).setVisibility(View.GONE);
        android.widget.Button ok = v.findViewById(R.id.b_yes);
        ok.setText(android.R.string.ok);
        androidx.appcompat.app.AlertDialog dlg = BlurDialogs.show(this, v);
        ok.setOnClickListener(x -> dlg.dismiss());
    }

    /** Add button and empty list follow the mode: folder import in local mode. */
    private void refreshAddAffordance() {
        boolean local = AuthManager.get(this).isLocalMode();
        if (ivAddIcon != null) {
            ivAddIcon.setImageResource(local ? R.drawable.ic_folder_import : R.drawable.ic_fision_mark);
            tvAddLabel.setText(local ? R.string.songs_import_bar : R.string.songs_add_bar);
        }
        TextView firstText = findViewById(R.id.tv_songsAddFirst);
        if (firstText != null) {
            firstText.setText(accentTap(local ? R.string.songs_empty_hint_local : R.string.songs_empty_hint));
        }
    }

    /** Hint with "Tap" in the accent, wherever the language puts it. */
    private CharSequence accentTap(int hintRes) {
        String tap = getString(R.string.songs_empty_tap);
        String hint = getString(hintRes, tap);
        android.text.SpannableString s = new android.text.SpannableString(hint);
        int at = hint.indexOf(tap);
        if (at >= 0) {
            s.setSpan(new android.text.style.ForegroundColorSpan(
                            com.jotapeveloper.fision.design.AccentColorManager.resolveAccent(this)),
                    at, at + tap.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return s;
    }

    /** Add flow: the picker opens first; the import screen only appears with
     *  a song chosen. Cancelling leaves the list untouched. */
    private final androidx.activity.result.ActivityResultLauncher<String[]> songPicker =
            registerForActivityResult(
                    new androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
                    uri -> {
                        if (uri == null) return;
                        try {
                            getContentResolver().takePersistableUriPermission(uri,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        } catch (Throwable ignored) {}
                        Intent i = new Intent(this, ImportActivity.class);
                        i.setData(uri);
                        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(i);
                    });

    /**
     * Builds a SongListData with optional metadata from metadata.txt and artcover.png.
     * Fallback: folder name as title and "Stems" as subtitle.
     */
    private SongListData buildSongListData(String folderName) {
        SongListData s = new SongListData(folderName, "Stems");
        SongMetadata meta = SongMetadata.read(new File(getSongFolder(), folderName));
        if (meta.displayTitle != null) s.setDisplayTitle(meta.displayTitle);
        if (meta.artist != null)       s.setArtist(meta.artist);
        if (meta.coverPath != null)    s.setCoverPath(meta.coverPath);
        return s;
    }

    public void refreshSongList(){
        ContentResolver contentResolver = getContentResolver();
        SongsInDirectory = getSubdirectories(getSongFolder(), contentResolver);
        applySortToSongs(SongsInDirectory);

        // Notify the repository about local folders (marks LOCAL_AND_CLOUD / LOCAL_ONLY)
        CloudRepository.get(this).mergeLocalFolders(SongsInDirectory);

        // Combined list: in-flight on top, then local + cloud-only by date
        List<SongListData> mixed = buildMergedSongsList(SongsInDirectory);
        allSongs = mixed.toArray(new SongListData[0]);
        builtInFlightByHash.clear();
        for (SongListData s : allSongs) {
            if (s.cloud == null || s.cloud.state == CloudItem.State.LOCAL_ONLY) continue;
            builtInFlightByHash.put(s.cloud.hash, s.cloud.isInFlight());
        }
        // A rebuild during a search keeps showing only the matches.
        String query = currentQuery();
        Songs = filterSongs(allSongs, query);

        syncSongsWithDatabase();

        // Reuse the adapter to avoid resetting the scroll on every server poll.
        RecyclerView.Adapter<?> current = rvSongList.getAdapter();
        if (current instanceof SongListAdapter) {
            ((SongListAdapter) current).setData(Songs);
        } else {
            rvSongList.setHasFixedSize(true);
            rvSongList.setLayoutManager(new LinearLayoutManager(this));
            rvSongList.setAdapter(new SongListAdapter(MainActivity.this, Songs, this));
        }

        updateSearchState(query);
        // The playlists empty state depends on having songs.
        if (currentTab == TAB_PLAYLISTS) refreshPlaylistsTab();
    }

    /** Count line and "No results" notice for the current query. */
    private void updateSearchState(String query) {
        int n = query.isEmpty() ? SongsInDirectory.size() : Songs.length;
        tv_SongsCount.setText(getResources().getQuantityString(query.isEmpty()
                ? R.plurals.playlist_song_count : R.plurals.search_result_count, n, n));
        if (tvNoResults == null) tvNoResults = findViewById(R.id.tv_noResults);
        tvNoResults.setVisibility(!query.isEmpty() && Songs.length == 0
                ? View.VISIBLE : View.GONE);
        boolean wasEmpty = libraryEmpty;
        libraryEmpty = query.isEmpty() && Songs.length == 0;
        applySongsEmptyState();
        // The shine pace follows the library: restart it when it empties or fills up.
        if (wasEmpty != libraryEmpty && shineRunning) startAddShine();
    }

    private TextView tvNoResults;
    private View llSongsAddFirst;
    private boolean libraryEmpty;

    private com.jotapeveloper.fision.brand.ButtonShine addShine;
    /** Between onStart and onStop. */
    private boolean shineRunning;
    private final java.util.Random shineRandom = new java.util.Random();

    /**
     * Glossy sweep on the add (split) button: often while the library is
     * empty (first after 5-10 s, then every 20-30 s), about every 5 minutes
     * once there are songs. Same in local mode, where the button imports. As
     * a bar (empty library) the label lights up with the icon.
     */
    private void startAddShine() {
        if (b_add == null) return;
        if (addShine == null) addShine = new com.jotapeveloper.fision.brand.ButtonShine(b_add, ivAddIcon, tvAddLabel,
                com.jotapeveloper.fision.design.AccentColorManager.resolveAccent(this));
        shineRunning = true;
        addShine.start(first -> {
            if (!libraryEmpty) return randomMs(240_000, 360_000);
            return first ? randomMs(5_000, 10_000) : randomMs(20_000, 30_000);
        });
    }

    private long randomMs(long min, long max) {
        return min + (long) (shineRandom.nextDouble() * (max - min));
    }

    /** Empty library: the add button widens into a labelled bar in the search
     *  slot, no count line, the blobs notice that adds the first song. */
    private void applySongsEmptyState() {
        if (llSongsAddFirst == null) llSongsAddFirst = findViewById(R.id.ll_songsAddFirst);
        boolean songsTab = currentTab == TAB_SONGS;
        // Nothing to search: a search with focus ends first.
        if (libraryEmpty && songSearch != null && songSearch.field.hasFocus()) songSearch.close(true);
        setAddWide(b_add, tvAddLabel, et_find, libraryEmpty, songsTab,
                tv_SongsCount, b_orderBy, llSongsAddFirst);
        // INVISIBLE, not GONE: the shared blobs hang from the count line.
        int countVis = songsTab && !libraryEmpty ? View.VISIBLE : View.INVISIBLE;
        tv_SongsCount.setVisibility(countVis);
        b_orderBy.setVisibility(countVis);
        llSongsAddFirst.setVisibility(songsTab && libraryEmpty ? View.VISIBLE : View.GONE);
        updateEmptyBlobs();
    }

    /** Blobs behind the empty notice of the tab shown (or being switched to). */
    private void updateEmptyBlobs() {
        if (vEmptyBlobs == null) return;
        vEmptyBlobs.setShown(targetTab == TAB_SONGS ? libraryEmpty : playlistsEmptyShown);
    }

    private static final long ADD_MORPH_MS = 300;

    private boolean isAddWide(View button) {
        return button.getLayoutParams().width == 0;
    }

    /**
     * Add button of a tab: the square beside the search bar, or, with nothing
     * to search, a bar with its label in the search slot. [animate] = the tab
     * is on screen: morphs between both (the bar shrinks into the square, the
     * search fades in) and cross-fades [others] (count line, empty notice) in
     * the same pass.
     */
    private void setAddWide(View button, View label, EditText search, boolean wide,
                            boolean animate, View... others) {
        if (isAddWide(button) == wide) return;
        if (animate && button.isLaidOut()) {
            androidx.transition.TransitionSet set = new androidx.transition.TransitionSet()
                    .addTransition(new androidx.transition.ChangeBounds())
                    .addTransition(new androidx.transition.Fade())
                    .setOrdering(androidx.transition.TransitionSet.ORDERING_TOGETHER)
                    .setDuration(ADD_MORPH_MS)
                    .setInterpolator(new androidx.interpolator.view.animation.FastOutSlowInInterpolator());
            // The centered icon + label group slides as the label comes and goes.
            set.addTarget(button).addTarget((View) label.getParent())
                    .addTarget(label).addTarget(search);
            for (View o : others) set.addTarget(o);
            // The growing field passes through widths that may scroll it again.
            if (!wide) set.addListener(new androidx.transition.TransitionListenerAdapter() {
                @Override public void onTransitionEnd(@NonNull androidx.transition.Transition t) {
                    search.scrollTo(0, 0);
                }
            });
            androidx.transition.TransitionManager.beginDelayedTransition(
                    (android.view.ViewGroup) button.getParent(), set);
        }
        androidx.constraintlayout.widget.ConstraintLayout.LayoutParams lp = (androidx.constraintlayout.widget.ConstraintLayout.LayoutParams) button.getLayoutParams();
        float d = getResources().getDisplayMetrics().density;
        if (wide) {
            lp.width = 0;
            lp.startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID;
            lp.setMarginStart((int) (20 * d));
        } else {
            lp.width = (int) (53 * d);
            lp.startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET;
            lp.setMarginStart(0);
        }
        button.setLayoutParams(lp);
        // Behind the bar the field is squeezed to zero width, where any cursor
        // update scrolls the text far right; it reappears scrolled to its start.
        if (!wide) search.scrollTo(0, 0);
        label.setVisibility(wide ? View.VISIBLE : View.GONE);
        // A hidden tab keeps its search hidden; showing the tab restores it.
        search.setVisibility(!wide && animate ? View.VISIBLE : View.INVISIBLE);
    }

    private String currentQuery() {
        return et_find != null ? et_find.getText().toString() : "";
    }

    /** Search over the merged list (local and cloud items alike): folder name,
     *  title and artist, ignoring case and diacritics. */
    private static SongListData[] filterSongs(SongListData[] all, String key) {
        if (key == null || key.isEmpty()) return all;
        String k = foldForSearch(key);
        List<SongListData> out = new ArrayList<>();
        for (SongListData s : all) {
            if (foldForSearch(s.getTitle()).contains(k)
                    || foldForSearch(s.getDisplayTitle()).contains(k)
                    || foldForSearch(s.getArtist()).contains(k)) {
                out.add(s);
            }
        }
        return out.toArray(new SongListData[0]);
    }

    /** Applies a search query to the current merged list (no disk access).
     *  No animation: it runs on every keystroke. */
    private void applySearchFilter(String key) {
        Songs = filterSongs(allSongs, key);
        RecyclerView.Adapter<?> current = rvSongList.getAdapter();
        if (current instanceof SongListAdapter) {
            ((SongListAdapter) current).setData(Songs);
        } else {
            rvSongList.setHasFixedSize(true);
            rvSongList.setLayoutManager(new LinearLayoutManager(this));
            rvSongList.setAdapter(new SongListAdapter(MainActivity.this, Songs, this));
        }
        updateSearchState(key);
    }

    /** Merges local songs with server items (queued/processing/cloud-only).
     *  ONE single final sort pass by tsForItem so downloading a song does not
     *  jump to the top just because its local lastModified is recent:
     *  serverCreated has priority over lastModified whenever a cloud counterpart exists. */
    private List<SongListData> buildMergedSongsList(List<String> localFolders) {
        List<CloudItem> snap = CloudRepository.get(this).snapshot();
        Map<String, CloudItem> byHash = new HashMap<>();
        for (CloudItem c : snap) byHash.put(c.hash, c);

        List<SongListData> inFlight = new ArrayList<>();
        List<SongListData> regular  = new ArrayList<>();
        Set<String> handled = new HashSet<>();

        // 1) Locals (with or without cloud). Items with a local counterpart never
        //    jump to the in-flight section: a reprocess must not reorder the list.
        for (String folder : localFolders) {
            SongListData s = buildSongListData(folder);
            CloudItem c = byHash.get(folder);
            if (c != null) {
                s.cloud = c;
                handled.add(folder);
                // Fallback for a missing / empty metadata.txt (server ZIP
                // shipped without it). CloudItem carries the same fields the
                // server would have written; using it read-only here keeps the
                // server as source of truth so a later server fix propagates
                // without leaving stale synthesised metadata on disk.
                if ((s.getDisplayTitle() == null || s.getDisplayTitle().isEmpty()
                        || s.getDisplayTitle().equals(folder))
                        && c.displayName != null && !c.displayName.isEmpty()) {
                    s.setDisplayTitle(c.displayName);
                    android.util.Log.w("MainActivity",
                            "metadata.txt missing title for " + folder
                                    + " — CloudItem fallback in use");
                }
                if ((s.getArtist() == null || s.getArtist().isEmpty())
                        && c.artist != null && !c.artist.isEmpty()) {
                    s.setArtist(c.artist);
                }
            }
            regular.add(s);
        }

        // 2) Server items without local counterpart (CLOUD_ONLY, sticky, in-flight).
        for (CloudItem c : snap) {
            if (handled.contains(c.hash)) continue;
            if (c.state == CloudItem.State.LOCAL_ONLY) continue;
            SongListData s = new SongListData(c.hash, "");
            s.setDisplayTitle(c.displayName != null && !c.displayName.isEmpty()
                    ? c.displayName : c.hash);
            if (c.artist != null && !c.artist.isEmpty()) s.setArtist(c.artist);
            if (c.coverPath != null && new java.io.File(c.coverPath).exists()) {
                s.setCoverPath(c.coverPath);
            }
            s.cloud = c;
            // A download keeps its place: only songs still processing go on top.
            if (c.isProcessing()) inFlight.add(s); else regular.add(s);
        }

        // 3) Final sort by tsForItem — sticky > serverCreated > serverUpdated >
        //    submittedAt > lastModified. The in-flight block follows the same order
        //    (submission time while the server has no date yet), instead of the
        //    repository's insertion order.
        java.util.Comparator<SongListData> byDate =
                (a, b) -> SongOrder.compare(tsForItem(a), orderKey(a),
                        tsForItem(b), orderKey(b), sortOrder);
        java.util.Collections.sort(inFlight, byDate);
        java.util.Collections.sort(regular, byDate);

        List<SongListData> all = new ArrayList<>(inFlight.size() + regular.size());
        all.addAll(inFlight);
        all.addAll(regular);
        return all;
    }

    /** Returns the timestamp that represents the item's "date" for sorting:
     *  sticky if present, otherwise server created_at (stable order, does not change on
     *  reprocess), otherwise updated_at, otherwise the local folder's lastModified. */
    private long tsForItem(SongListData s) {
        return SongOrder.timestamp(this, s.cloud, new File(getSongFolder(), s.getTitle()));
    }

    /** Tie-break key matching SongOrder.sortedSongFolders: the song hash. */
    private static String orderKey(SongListData s) {
        return s.cloud != null ? s.cloud.hash : s.getTitle();
    }

    // Reachability tracking for the cloud icon. Offline = red, persistent.
    // Transition offline→online flashes a green icon for a short interval and
    // fades it out; a stable online state keeps the icon hidden to reduce noise.
    private Boolean lastReachable = null;
    private static final long CLOUD_RECONNECT_FLASH_MS = 3000;
    private static final long CLOUD_RECONNECT_FADE_MS  = 350;
    // Cross-fade duration for the icon tint offline → online colour.
    private static final long CLOUD_RECONNECT_TINT_MS  = 550;
    private final Runnable cloudReconnectFadeOut = new Runnable() {
        @Override public void run() {
            if (ivCloudStatus == null) return;
            ivCloudStatus.animate().alpha(0f).setDuration(CLOUD_RECONNECT_FADE_MS)
                    .withEndAction(() -> {
                        if (ivCloudStatus != null) ivCloudStatus.setVisibility(View.GONE);
                    })
                    .start();
        }
    };

    private void updateCloudStatusIcon(boolean reachable) {
        // Reachable transitions can arrive before the local session state is
        // reconsulted; delegate to the state-aware refresh path.
        refreshCloudStatusIcon(reachable);
    }

    /** Refreshes the cloud icon reading both the auth state and the last
     *  known server reachability. Four visible outcomes:
     *    - no session                    → grey icon, tap = open login dialog.
     *    - session + unknown (no ping yet) → hidden, until the first answer.
     *    - session + offline             → red icon, persistent.
     *    - session + online              → hidden (with a brief Fision-green
     *                                        flash on reconnect). */
    private void refreshCloudStatusIcon() {
        // lastReachable == null means "no health answer yet". Keep it as
        // unknown instead of collapsing to the repo's initial false, which
        // would paint a red cloud for a split second on cold start.
        refreshCloudStatusIcon(lastReachable);
    }

    private void refreshCloudStatusIcon(Boolean reachable) {
        if (ivCloudStatus == null) return;
        boolean loggedIn = AuthManager.get(this).isLoggedIn();

        // No session → grey cloud icon regardless of reachability. Tap opens the
        // login dialog so the user can authenticate without visiting Settings.
        if (!loggedIn) {
            ivCloudStatus.removeCallbacks(cloudReconnectFadeOut);
            ivCloudStatus.animate().cancel();
            ivCloudStatus.setImageResource(R.drawable.ic_cloud);
            ivCloudStatus.setImageTintList(android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.AppDarkGrey)));
            ivCloudStatus.setContentDescription(getString(R.string.login_status_unauthenticated));
            ivCloudStatus.setAlpha(1f);
            ivCloudStatus.setVisibility(View.VISIBLE);
            // Reset reachability tracking so a later login → offline shows red immediately.
            lastReachable = null;
            return;
        }

        // Unknown reachability: hide the icon until the first health
        // response decides. No false red flash on cold start.
        if (reachable == null) {
            ivCloudStatus.removeCallbacks(cloudReconnectFadeOut);
            ivCloudStatus.animate().cancel();
            cancelCloudTintAnimator();
            ivCloudStatus.setVisibility(View.GONE);
            return;
        }

        boolean firstCall = (lastReachable == null);
        boolean wasOffline = (lastReachable != null && !lastReachable);
        lastReachable = reachable;
        ivCloudStatus.removeCallbacks(cloudReconnectFadeOut);
        ivCloudStatus.animate().cancel();
        cancelCloudTintAnimator();
        if (!reachable) {
            ivCloudStatus.setImageResource(R.drawable.ic_cloud);
            ivCloudStatus.setImageTintList(android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.cloud_offline)));
            ivCloudStatus.setContentDescription(getString(R.string.server_status_offline));
            ivCloudStatus.setAlpha(1f);
            ivCloudStatus.setVisibility(View.VISIBLE);
            return;
        }
        // Online but the server GPU has less free memory than its minimum
        // (last /server/info): orange chip alert, kept on screen.
        if (ServerInfoManager.get(this).isVramLow()) {
            ivCloudStatus.setImageResource(R.drawable.ic_gpu_alert);
            ivCloudStatus.setImageTintList(android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.gpu_warning_accent)));
            ivCloudStatus.setContentDescription(getString(R.string.server_vram_low));
            ivCloudStatus.setAlpha(1f);
            ivCloudStatus.setVisibility(View.VISIBLE);
            return;
        }
        if (firstCall || !wasOffline) {
            ivCloudStatus.setVisibility(View.GONE);
            ivCloudStatus.setAlpha(1f);
            return;
        }
        ivCloudStatus.setImageResource(R.drawable.ic_cloud);
        ivCloudStatus.setContentDescription(getString(R.string.server_status_online));
        ivCloudStatus.setAlpha(1f);
        ivCloudStatus.setVisibility(View.VISIBLE);
        // Smooth colour transition offline (red) → online (green) before the
        // idle fade-out fires. Uses ArgbEvaluator so the intermediate frames
        // sweep through the natural interpolation between both hues.
        int fromColor = ContextCompat.getColor(this, R.color.cloud_offline);
        // The connected cloud is ALWAYS Fision green, independent of the
        // accent chosen in Settings (a direct resource reference bypasses
        // the theme overlay that would otherwise follow the accent).
        int toColor   = ContextCompat.getColor(this, R.color.colorPrimary);
        cloudTintAnimator = android.animation.ValueAnimator.ofArgb(fromColor, toColor);
        cloudTintAnimator.setDuration(CLOUD_RECONNECT_TINT_MS);
        cloudTintAnimator.addUpdateListener(anim -> {
            if (ivCloudStatus == null) return;
            int c = (int) anim.getAnimatedValue();
            ivCloudStatus.setImageTintList(android.content.res.ColorStateList.valueOf(c));
        });
        cloudTintAnimator.start();
        ivCloudStatus.postDelayed(cloudReconnectFadeOut, CLOUD_RECONNECT_FLASH_MS);
    }

    private android.animation.ValueAnimator cloudTintAnimator;
    private void cancelCloudTintAnimator() {
        if (cloudTintAnimator != null) {
            cloudTintAnimator.cancel();
            cloudTintAnimator = null;
        }
    }

    @androidx.annotation.Nullable private Boolean gateLoggedIn;

    /** Without a session the server is out of reach: adding songs is disabled
     *  (grey button and add-first card) and cloud-only rows show a lock. */
    private void applySessionGate() {
        boolean loggedIn = AuthManager.get(this).isLoggedIn();
        // Local mode adds songs by importing folders; only an expired or
        // missing session without local mode leaves the add actions off.
        boolean canAdd = loggedIn || AuthManager.get(this).isLocalMode();
        if (b_add != null) {
            int fg = canAdd ? Color.WHITE : 0xFF4A4A4A;
            ivAddIcon.setImageTintList(android.content.res.ColorStateList.valueOf(fg));
            tvAddLabel.setTextColor(fg);
            b_add.setEnabled(canAdd);
        }
        if (llSongsAddFirst == null) llSongsAddFirst = findViewById(R.id.ll_songsAddFirst);
        if (llSongsAddFirst instanceof android.view.ViewGroup) {
            llSongsAddFirst.setEnabled(canAdd);
            android.view.ViewGroup g = (android.view.ViewGroup) llSongsAddFirst;
            for (int i = 0; i < g.getChildCount(); i++) g.getChildAt(i).setAlpha(canAdd ? 1f : 0.4f);
        }
        refreshAddAffordance();
        // Rows only need a rebind when the session state actually flips.
        if (gateLoggedIn != null && gateLoggedIn != loggedIn && rvSongList != null) refreshSongList();
        gateLoggedIn = loggedIn;
    }

    /** Sign-in prompt for actions that need the server (locked cloud rows). */
    public void promptLogin() {
        LoginDialog.show(this, true, new LoginDialog.Callback() {
            @Override public void onLoggedIn(String u) {
                refreshToolbarAvatar();
                refreshCloudStatusIcon();
                CloudRepository.get(MainActivity.this).notifyConfigChanged();
                CloudRepository.get(MainActivity.this).startPolling();
            }
            @Override public void onLocalMode() {
                refreshToolbarAvatar();
                refreshCloudStatusIcon();
            }
        });
    }

    /** Swaps the toolbar between the settings gear (no session) and the
     *  circular initial avatar (session active). Both open Settings when tapped;
     *  the avatar exists purely to signal an active identity. */
    private void refreshToolbarAvatar() {
        applySessionGate();
        if (flAvatar == null || bSettings == null) return;
        String user = AuthManager.get(this).getUsername();
        boolean loggedIn = AuthManager.get(this).isLoggedIn() && !user.isEmpty();
        // The settings button stays laid out at all times so the cloud icon can
        // anchor to it regardless of the session state. The avatar is a clickable
        // overlay on top; when it is visible it intercepts taps and delegates to
        // openSettings, when hidden the underlying settings button handles them.
        if (!loggedIn) {
            flAvatar.setVisibility(View.GONE);
            return;
        }
        flAvatar.setVisibility(View.VISIBLE);
        AvatarManager am = AvatarManager.get(this);
        // Safety net: covers the race where a post-login fetch completes while
        // MainActivity is not registered as a listener yet, so the change
        // notification is dropped. ensureFetched no-ops when a fetch has
        // already been attempted for this user in the current process.
        am.ensureFetched(user);
        if (am.hasLocalAvatar(user)) {
            android.graphics.Bitmap bm = ImageDecode.file(
                    am.getLocalFile(user).getAbsolutePath(), AVATAR_MAX_PX);
            if (bm != null) {
                ivAvatarImage.setImageBitmap(bm);
                ivAvatarImage.setVisibility(View.VISIBLE);
                tvAvatarInitial.setVisibility(View.GONE);
                return;
            }
        }
        // No cached picture: paint the initial fallback
        tvAvatarInitial.setText(user.substring(0, 1).toUpperCase(Locale.ROOT));
        tvAvatarInitial.setVisibility(View.VISIBLE);
        ivAvatarImage.setVisibility(View.GONE);
    }

    /** Cloud icon tap dispatcher: opens the login dialog when there is no
     *  session (fast entry), otherwise falls back to Settings so the user can
     *  check server details or trigger a health test. */
    private void handleCloudIconTap() {
        if (AuthManager.get(this).isLoggedIn()) {
            // Low-VRAM chip leads straight to the GPU card.
            Intent intent = new Intent(this, SettingsActivity.class);
            if (ServerInfoManager.get(this).isVramLow()) {
                intent.putExtra(SettingsActivity.EXTRA_OPEN_GPU, true);
            }
            startActivity(intent);
        } else {
            LoginDialog.show(this, true, new LoginDialog.Callback() {
                @Override public void onLoggedIn(String u) {
                    refreshToolbarAvatar();
                    refreshCloudStatusIcon();
                    CloudRepository.get(MainActivity.this).notifyConfigChanged();
                    CloudRepository.get(MainActivity.this).startPolling();
                }
                @Override public void onLocalMode() {
                    refreshToolbarAvatar();
                    refreshCloudStatusIcon();
                }
            });
        }
    }

    private void syncSongsWithDatabase() {
        // Get the database instance
        AppDatabase db = AppDatabase.getInstance(getApplicationContext());

        // Run on a separate thread to avoid blocking the UI
        new Thread(() -> {
            // Iterate over the song names
            for (String songName : SongsInDirectory) {
                // Check whether an entry for this song already exists in the database
                List<db_SongParameters> existingEntries = db.DAO_db_SongParameters().getParametersByElementId(songName.hashCode());

                if (existingEntries.isEmpty()) {
                    // Missing: create a new entry in the database
                    db_SongParameters newEntry = new db_SongParameters();
                    newEntry.songId = songName.hashCode();  // hash of the name as a unique ID
                    // Seed the default values
                    newEntry.songName = songName;
                    newEntry.bass = 75;
                    newEntry.vocals = 75;
                    newEntry.drums = 75;
                    newEntry.other = 75;
                    newEntry.instrumental = 75;
                    newEntry.chordsActive = true;
                    newEntry.wdActive = false;
                    newEntry.metronomeActive = false;
                    newEntry.selectedMetronome = 1;
                    newEntry.metronomeVol = 100;
                    // v2: neutral pan, neutral pitch/tempo
                    newEntry.bassPan = 0f;
                    newEntry.drumsPan = 0f;
                    newEntry.otherPan = 0f;
                    newEntry.vocalsPan = 0f;
                    newEntry.metronomePan = 0f;
                    newEntry.pitchIndex = numberPickerData.CENTRAL_PITCH_INDEX;
                    newEntry.tempoIndex = numberPickerData.CENTRAL_INDEX;
                    // v7: neutral tempo (1.0x = 1000 milli). Used by the ruler picker.
                    newEntry.tempoMultMilli = 1000;
                    // v3: empty JSON; stems are populated on demand
                    newEntry.stemSettingsJson = "{}";
                    // v4: advanced chords by default (chords.txt)
                    newEntry.chordsBasicMode = false;
                    // v5: half metronome variant A; downbeat sound off
                    newEntry.metronomeHalfVariant  = 0;
                    newEntry.downbeatSoundEnabled  = false;
                    newEntry.downbeatSoundType     = 0;

                    // Insert the new entry into the database
                    db.DAO_db_SongParameters().insert(newEntry);
                }
            }

            // Fetch every entry in the database
            List<db_SongParameters> allEntries = db.DAO_db_SongParameters().getAllParameters();

            // Remove entries that no longer belong to the song list
            for (db_SongParameters entry : allEntries) {
                // Convert songId into its song name for the comparison
                String songNameFromDb = String.valueOf(entry.songName);

                // When the song name is missing from the current list, delete the entry
                if (!SongsInDirectory.contains(songNameFromDb)) {
                    db.DAO_db_SongParameters().delete(entry);
                }
            }

        }).start();
    }

    /** Blurs the activity decorView (Android 12+). Only affects the background; the dialog stays sharp. */
    private void applyActivityBlur() {
        BlurDialogs.applyBlur(this);
    }

    private void removeActivityBlur() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getWindow().getDecorView().setRenderEffect(null);
        }
    }

    /** Compat: the adapter passes the whole SongListData so title + artist + folderName are available. */
    public void showBottomDialog(@NonNull SongListData song){
        final String folderName  = song.getTitle();
        final String displayName = song.getDisplayTitle() != null ? song.getDisplayTitle() : folderName;
        final String artistName  = song.getArtist() != null ? song.getArtist() : "";
        final String coverPath   = song.getCoverPath();
        final boolean cloudOnly  = song.cloud != null && song.cloud.state == CloudItem.State.CLOUD_ONLY;
        final boolean inFlight   = song.cloud != null && song.cloud.isInFlight();
        final boolean isQueued   = song.cloud != null && song.cloud.state == CloudItem.State.QUEUED;
        // Locked: running on the server or downloading — cannot be touched
        final boolean deleteBlocked = inFlight && !isQueued;

        BottomSheetDialog optionsBottomSheet = new BottomSheetDialog(this, R.style.TransparentBottomSheetDialog);
        View bottomSheetView = getLayoutInflater().inflate(R.layout.modal_sheet_dialog, null);
        optionsBottomSheet.setContentView(bottomSheetView);

        ImageView ivCover     = optionsBottomSheet.findViewById(R.id.iv_cover);
        TextView SongTitle    = optionsBottomSheet.findViewById(R.id.Title);
        TextView SongArtist   = optionsBottomSheet.findViewById(R.id.Artist);
        Button   renameButton = optionsBottomSheet.findViewById(R.id.b_rename);
        Button   deleteButton = optionsBottomSheet.findViewById(R.id.b_delete);

        if (ivCover != null && coverPath != null) {
            android.graphics.Bitmap bm = ImageDecode.file(coverPath, COVER_HD_MAX_PX);
            if (bm != null) ivCover.setImageBitmap(bm);
        }

        SongTitle.setText(displayName);
        SongTitle.requestFocus();
        if (SongArtist != null) {
            SongArtist.setText(artistName);
            SongArtist.setVisibility(artistName.isEmpty() ? View.GONE : View.VISIBLE);
        }

        // Cloud-only or in-flight items: no local folder to rename
        if (cloudOnly || inFlight) {
            renameButton.setEnabled(false);
            renameButton.setAlpha(0.4f);
        }
        // Delete button text based on context
        if (isQueued) {
            deleteButton.setText(R.string.cloud_cancel_queue);
        } else if (cloudOnly) {
            deleteButton.setText(R.string.cloud_delete_cloud);
        } else {
            deleteButton.setText(R.string.b_delete);
        }
        if (deleteBlocked) {
            deleteButton.setEnabled(false);
            deleteButton.setAlpha(0.4f);
        }
        // Failed reprocess of a downloaded song: the row opens the previous
        // stems, so the retry lives here.
        Button retryButton = optionsBottomSheet.findViewById(R.id.b_retryJob);
        final boolean canRetry = song.cloud != null
                && song.cloud.state == CloudItem.State.ERROR
                && song.cloud.hasLocalCopy()
                && CloudRepository.isRetryableJobError(song.cloud.errorCode);
        if (retryButton != null) {
            retryButton.setVisibility(canRetry ? View.VISIBLE : View.GONE);
            retryButton.setOnClickListener(v -> {
                optionsBottomSheet.dismiss();
                showJobRetryDialog(song.cloud.hash);
            });
        }

        optionsBottomSheet.show();

        renameButton.setOnClickListener(v -> {
            optionsBottomSheet.dismiss();
            if (!cloudOnly && !inFlight) {
                showRenameDialog(folderName, displayName, artistName, coverPath);
            }
        });

        deleteButton.setOnClickListener(v -> {
            if (deleteBlocked) return;
            optionsBottomSheet.dismiss();
            showDeleteDialog(song);
        });
    }

    /** Delete confirmation dialog — extracted from the bottom sheet. */
    /** Sorts the list of song names according to the current preference.
     *  Uses File.lastModified() of each subfolder as a "date added" proxy. */
    private void applySortToSongs(List<String> songs) {
        if (songs == null || songs.size() < 2) return;
        java.util.Map<String, Long> mtimeCache = new java.util.HashMap<>();
        for (String name : songs) {
            mtimeCache.put(name, new File(getSongFolder(), name).lastModified());
        }
        java.util.Collections.sort(songs, (a, b) -> {
            long la = mtimeCache.getOrDefault(a, 0L);
            long lb = mtimeCache.getOrDefault(b, 0L);
            // sortOrder=0 → descending (most recent first)
            // sortOrder=1 → ascending (oldest first)
            return sortOrder == 0 ? Long.compare(lb, la) : Long.compare(la, lb);
        });
    }

    /** Sort-by-date-added dialog (desc/asc) with a blurred background.
     *  Same layout/look as the delete dialog: rounded-corner card and background blur. */
    private void showSortDialog() {
        LayoutInflater inflater = LayoutInflater.from(this);
        View dialogView = inflater.inflate(R.layout.order_by_dialog, null);
        TextView optDesc = dialogView.findViewById(R.id.tv_orderByOption_desc);
        TextView optAsc  = dialogView.findViewById(R.id.tv_orderByOption_asc);
        Button   cancel  = dialogView.findViewById(R.id.b_orderByCancel);

        // Marks the active option with the "chip" style — like the single_choice dialogs.
        int primary  = com.jotapeveloper.fision.design.AccentColorManager.resolveAccent(this);
        int white    = androidx.core.content.ContextCompat.getColor(this, R.color.AppWhite);
        java.util.function.BiConsumer<TextView, Boolean> style = (tv, active) -> {
            tv.setTextColor(active ? primary : white);
            tv.setBackgroundResource(active
                    ? R.drawable.dialog_option_active : R.drawable.dialog_option_inactive);
            tv.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(
                    MainActivity.this, R.font.noto_sans_regular),
                    active ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        };
        style.accept(optDesc, sortOrder == 0);
        style.accept(optAsc,  sortOrder == 1);

        AlertDialog sortDialog = new AlertDialog.Builder(this, R.style.Theme_Fision_BlurDialog)
                .setView(dialogView)
                .create();
        android.view.Window win = sortDialog.getWindow();
        if (win != null) win.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        applyActivityBlur();
        sortDialog.setOnDismissListener(d -> removeActivityBlur());
        sortDialog.show();

        View.OnClickListener apply = v -> {
            int which = (v == optDesc) ? 0 : 1;
            sortDialog.dismiss();
            if (which == sortOrder) return;
            sortOrder = which;
            PreferenceManager.getDefaultSharedPreferences(MainActivity.this)
                    .edit().putInt(PREF_SORT_ORDER, sortOrder).apply();
            refreshSongList();
        };
        optDesc.setOnClickListener(apply);
        optAsc.setOnClickListener(apply);
        cancel.setOnClickListener(v -> sortDialog.dismiss());
    }

    /** Blur dialog surfaced when the server aborts a job for a retryable
     *  reason: 10 minutes waiting for a free GPU, or the processing time
     *  limit. Offers a single retry that reprocesses on the server without
     *  re-uploading the audio. Package-private so the list adapter can re-open
     *  it when the user taps an item stuck on this error. */
    void showJobRetryDialog(String hash) {
        CloudItem item = CloudRepository.get(this).getByHash(hash);
        boolean timedOut = item != null && CloudRepository.isTimeoutError(item.errorCode);
        LayoutInflater inflater = LayoutInflater.from(this);
        View dv = inflater.inflate(R.layout.delete_alert_dialog, null);
        android.widget.ImageView icon = dv.findViewById(R.id.iv_alertDialog_ic);
        TextView body     = dv.findViewById(R.id.tv_alertDialog_text);
        android.widget.Button retry  = dv.findViewById(R.id.b_yes);
        android.widget.Button cancel = dv.findViewById(R.id.b_no);

        icon.setImageResource(R.drawable.ic_warning);
        icon.setColorFilter(ContextCompat.getColor(this, R.color.gpu_warning_accent));
        body.setText(timedOut ? R.string.job_timeout_body : R.string.gpu_timeout_body);
        retry.setText(R.string.gpu_timeout_retry);
        cancel.setText(R.string.gpu_timeout_cancel);

        AlertDialog dlg = new AlertDialog.Builder(this, R.style.Theme_Fision_BlurDialog)
                .setView(dv)
                .create();
        android.view.Window win = dlg.getWindow();
        if (win != null) win.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

        applyActivityBlur();
        dlg.setOnDismissListener(d -> removeActivityBlur());
        dlg.show();

        cancel.setOnClickListener(v -> dlg.dismiss());
        retry.setOnClickListener(v -> {
            CloudRepository.get(this).retryJob(hash);
            dlg.dismiss();
        });
    }

    /** Two steps: when the song still has local content, delete local first (it turns
     *  CLOUD_ONLY); a second delete over the cloud_only item removes it from the server.
     *  QUEUED items: cancel the queued job (DELETE /jobs/{id}).
     *  RUNNING / DOWNLOADING items: the button was already disabled. */
    private void showDeleteDialog(@NonNull SongListData song) {
        final String folderName  = song.getTitle();
        final String displayName = song.getDisplayTitle() != null ? song.getDisplayTitle() : folderName;
        final boolean cloudOnly  = song.cloud != null && song.cloud.state == CloudItem.State.CLOUD_ONLY;
        final boolean isQueued   = song.cloud != null && song.cloud.state == CloudItem.State.QUEUED;

        LayoutInflater inflater = LayoutInflater.from(this);
        View dialogView = inflater.inflate(R.layout.delete_alert_dialog, null);

        TextView title = dialogView.findViewById(R.id.tv_alertDialog_text);
        Button delete  = dialogView.findViewById(R.id.b_yes);
        Button cancel  = dialogView.findViewById(R.id.b_no);

        if (isQueued) {
            title.setText(getString(R.string.cloud_cancel_queue_confirm));
        } else if (cloudOnly) {
            title.setText(getString(R.string.cloud_delete_cloud_confirm));
        } else {
            title.setText(getString(R.string.delete_confirm, displayName));
        }

        AlertDialog deleteDialog = new AlertDialog.Builder(this, R.style.Theme_Fision_BlurDialog)
                .setView(dialogView)
                .create();
        android.view.Window win = deleteDialog.getWindow();
        if (win != null) win.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

        applyActivityBlur();
        deleteDialog.setOnDismissListener(d -> removeActivityBlur());
        deleteDialog.show();

        cancel.setOnClickListener(v -> deleteDialog.dismiss());
        delete.setOnClickListener(v -> {
            // Close the dialog first so the decorView blur fades out on its own, and
            // only after that run the action + recycler animation. Without this,
            // the list animated while the background was still blurred.
            deleteDialog.dismiss();
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> runDelete(
                    song, folderName, isQueued, cloudOnly), 180);
        });
    }

    /** Runs the delete operation + recycler refresh. Extracted from the delete
     *  button onClick to defer it after the dialog dismiss (which clears the blur). */
    private void runDelete(@NonNull SongListData song, @NonNull String folderName,
                           boolean isQueued, boolean cloudOnly) {
        if (isQueued && song.cloud != null) {
                CloudRepository.get(this).cancelQueuedJob(song.cloud.hash);
            } else if (cloudOnly && song.cloud != null) {
                CloudRepository.get(this).deleteFromCloud(song.cloud.hash, () ->
                        Toast.makeText(this, R.string.cloud_delete_busy, Toast.LENGTH_LONG).show());
            } else {
                File target = new File(getSongFolder(), folderName);
                // When the song is also on the cloud, remember its current order date so
                // after deletion the entry stays "sticky" at its position as CLOUD_ONLY.
                long lm = SongOrder.timestamp(this, song.cloud, target);
                if (lm <= 0) lm = System.currentTimeMillis();
                // When the song has a cloud counterpart, preserve metadata+cover BEFORE
                // deleting the folder: copies artcover.png to the hash's sticky cover
                // and dumps displayTitle/artist into the CloudItem so the CLOUD_ONLY item
                // keeps showing the same info it had while local.
                if (song.cloud != null) preserveCloudMetadataFromLocal(target, song.cloud);
                // When the deleted song is the one playing in the mini-player, stop the
                // service and clear the UI. Otherwise the mini-player offered "play" on a
                // non-existent path.
                PlaybackService playbackService = miniPlayer.getService();
                boolean wasCurrent = playbackService != null
                        && playbackService.isSongLoaded()
                        && folderName.equals(playbackService.getCurrentSongName());
                if (wasCurrent) {
                    playbackService.releaseSongAndStop();
                    // Hide at once, regardless of the async listener
                    // (notifyListeners posts to mainHandler, arrives late).
                    miniPlayer.hideNow();
                }
                deleteRecursive(target);
                deleteSongFromDb(folderName);
                CloudRepository.get(this).onLocalDeleted(folderName);
                if (song.cloud != null) {
                    song.cloud.stickyOrderTs = lm;
                    // Persist title/artist/cover/orderTs so they survive reboots
                    CloudRepository.get(this).saveStickyMetadata(
                            song.cloud.hash,
                            song.cloud.displayName,
                            song.cloud.artist,
                            song.cloud.coverPath,
                            lm);
                }
            }
        // Keeps the active search: refreshSongList re-applies the query.
        refreshSongList();
    }

    /** "Edit info" dialog: changes displayTitle/artist (metadata.txt).
     *  The folder name is left alone to avoid invalidating Room/DB and cached
     *  absolute paths. Shows the song artcover at the top (falls back to
     *  artcover_generic when missing). */
    private void showRenameDialog(@NonNull String folderName, @NonNull String displayName,
                                  @NonNull String artistName, String coverPath) {
        LayoutInflater inflater = LayoutInflater.from(this);
        View dialogView = inflater.inflate(R.layout.rename_dialog, null);

        ImageView ivCover = dialogView.findViewById(R.id.iv_renameDialog_cover);
        EditText etSong   = dialogView.findViewById(R.id.et_song_name);
        EditText etArtist = dialogView.findViewById(R.id.et_artist_name);
        Button cancel     = dialogView.findViewById(R.id.b_rename_cancel);
        Button save       = dialogView.findViewById(R.id.b_rename_save);

        if (ivCover != null && coverPath != null) {
            android.graphics.Bitmap bm = ImageDecode.file(coverPath, AVATAR_MAX_PX);
            if (bm != null) ivCover.setImageBitmap(bm);
        }
        etSong.setText(displayName);
        etArtist.setText(artistName);

        // Read-only folder info: date, format, size, duration, stems.
        populateSongInfo(dialogView, new File(getSongFolder(), folderName));

        AlertDialog renameDialog = new AlertDialog.Builder(this, R.style.Theme_Fision_BlurDialog)
                .setView(dialogView)
                .create();
        android.view.Window win = renameDialog.getWindow();
        if (win != null) win.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

        applyActivityBlur();
        renameDialog.setOnDismissListener(d -> removeActivityBlur());
        renameDialog.show();

        cancel.setOnClickListener(v -> renameDialog.dismiss());
        save.setOnClickListener(v -> {
            String newSong   = etSong.getText().toString().trim();
            String newArtist = etArtist.getText().toString().trim();
            File folder = new File(getSongFolder(), folderName);
            if (writeMetadata(folder, newSong, newArtist)) {
                refreshSongList();
            } else {
                Toast.makeText(MainActivity.this,
                        "Error writing metadata.txt", Toast.LENGTH_SHORT).show();
            }
            renameDialog.dismiss();
        });
    }

    /** Fills the read-only section of the rename dialog with folder data.
     *  - Date added: folder File.lastModified().
     *  - Format: WAV or FLAC (or WAV+FLAC when both coexist).
     *  - Size: recursive byte sum.
     *  - Duration: MediaMetadataRetriever on the first stem found.
     *  - Stems: number of files matching StemSpec.KNOWN. */
    private void populateSongInfo(View dialogView, File folder) {
        TextView tvAdded    = dialogView.findViewById(R.id.tv_song_info_added);
        TextView tvFormat   = dialogView.findViewById(R.id.tv_song_info_format);
        TextView tvSize     = dialogView.findViewById(R.id.tv_song_info_size);
        TextView tvDuration = dialogView.findViewById(R.id.tv_song_info_duration);
        TextView tvStems    = dialogView.findViewById(R.id.tv_song_info_stems);
        String unknown = getString(R.string.song_info_unknown);
        if (folder == null || !folder.isDirectory()) {
            if (tvAdded    != null) tvAdded.setText(unknown);
            if (tvFormat   != null) tvFormat.setText(unknown);
            if (tvSize     != null) tvSize.setText(unknown);
            if (tvDuration != null) tvDuration.setText(unknown);
            if (tvStems    != null) tvStems.setText(unknown);
            return;
        }

        // Date
        long lm = folder.lastModified();
        String added;
        if (lm > 0) {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat(
                    "dd/MM/yyyy", Locale.getDefault());
            added = sdf.format(new java.util.Date(lm));
        } else added = unknown;
        if (tvAdded != null) tvAdded.setText(added);

        // Recursive walk: bytes + extension set + first stem
        long[] totalBytes = { 0 };
        boolean[] hasWav = { false }, hasFlac = { false };
        int[] stemCount = { 0 };
        File[] firstStem = { null };
        walkSongFolder(folder, totalBytes, hasWav, hasFlac, stemCount, firstStem);

        // Format
        String fmt;
        if (hasWav[0] && hasFlac[0])      fmt = "WAV + FLAC";
        else if (hasFlac[0])               fmt = "FLAC";
        else if (hasWav[0])                fmt = "WAV";
        else                                fmt = unknown;
        if (tvFormat != null) tvFormat.setText(fmt);

        // Size
        if (tvSize != null) tvSize.setText(formatBytes(totalBytes[0]));

        // Stems
        if (tvStems != null) tvStems.setText(String.valueOf(stemCount[0]));

        // Duration (async): MediaMetadataRetriever
        if (tvDuration != null) {
            tvDuration.setText(unknown);
            final File stemFile = firstStem[0];
            if (stemFile != null) {
                new Thread(() -> {
                    long dur = readDurationMs(stemFile);
                    String dtxt = dur > 0 ? formatDuration(dur) : unknown;
                    runOnUiThread(() -> tvDuration.setText(dtxt));
                }).start();
            }
        }
    }

    private void walkSongFolder(File dir, long[] bytes, boolean[] hasWav, boolean[] hasFlac,
                                int[] stemCount, File[] firstStem) {
        File[] entries = dir.listFiles();
        if (entries == null) return;
        for (File f : entries) {
            if (f == null) continue;
            if (f.isDirectory()) {
                walkSongFolder(f, bytes, hasWav, hasFlac, stemCount, firstStem);
                continue;
            }
            bytes[0] += f.length();
            String name = f.getName().toLowerCase(Locale.ROOT);
            boolean isWav  = name.endsWith(".wav");
            boolean isFlac = name.endsWith(".flac");
            if (!isWav && !isFlac) continue;
            if (isWav)  hasWav[0]  = true;
            if (isFlac) hasFlac[0] = true;
            // Base name without extension
            int dot = name.lastIndexOf('.');
            String base = (dot >= 0) ? name.substring(0, dot) : name;
            // Exclude metronomes and other helpers from the stem count
            if (base.startsWith("metronome")) continue;
            if (StemSpec.isKnown(base)) {
                stemCount[0]++;
                if (firstStem[0] == null) firstStem[0] = f;
            }
        }
    }

    private long readDurationMs(File audio) {
        android.media.MediaMetadataRetriever mmr = new android.media.MediaMetadataRetriever();
        try {
            mmr.setDataSource(audio.getAbsolutePath());
            String s = mmr.extractMetadata(
                    android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
            return (s != null) ? Long.parseLong(s) : 0L;
        } catch (Exception e) {
            return 0L;
        } finally {
            try { mmr.release(); } catch (Exception ignored) {}
        }
    }

    private String formatBytes(long b) {
        if (b <= 0) return "0 B";
        double kb = b / 1024.0;
        if (kb < 1024) return String.format(Locale.getDefault(), "%.0f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.getDefault(), "%.1f MB", mb);
        double gb = mb / 1024.0;
        return String.format(Locale.getDefault(), "%.2f GB", gb);
    }

    private String formatDuration(long ms) {
        long total = ms / 1000L;
        long h = total / 3600L;
        long m = (total % 3600L) / 60L;
        long s = total % 60L;
        if (h > 0) return String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, s);
        return String.format(Locale.getDefault(), "%d:%02d", m, s);
    }

    /** Writes metadata.txt in the song folder with Title/Artist. */
    private boolean writeMetadata(@NonNull File songFolder, @NonNull String title,
                                  @NonNull String artist) {
        if (!songFolder.isDirectory()) return false;
        File meta = new File(songFolder, "metadata.txt");
        try (java.io.FileWriter w = new java.io.FileWriter(meta, /*append=*/false)) {
            if (!title.isEmpty())  w.write("Title: "  + title  + "\n");
            if (!artist.isEmpty()) w.write("Artist: " + artist + "\n");
            return true;
        } catch (java.io.IOException e) {
            Log.e("MainActivity", "writeMetadata failed: " + e.getMessage());
            return false;
        }
    }

    /** Copies metadata + artcover from the local folder to the CloudItem and cacheDir
     *  before the folder is deleted. That way the resulting CLOUD_ONLY item keeps
     *  showing title, artist and cover in the list without having to download the
     *  whole server ZIP. */
    private void preserveCloudMetadataFromLocal(File folder, CloudItem cloud) {
        if (folder == null || !folder.isDirectory() || cloud == null) return;
        try {
            SongMetadata meta = SongMetadata.read(folder);
            if (meta.displayTitle != null && !meta.displayTitle.isEmpty()) {
                cloud.displayName = meta.displayTitle;
            }
            if (meta.artist != null && !meta.artist.isEmpty()) {
                cloud.artist = meta.artist;
            }
            if (meta.coverPath != null) {
                File src = new File(meta.coverPath);
                if (src.exists()) {
                    File dst = CloudRepository.get(this).stickyCoverFile(cloud.hash);
                    java.io.FileInputStream  fis = new java.io.FileInputStream(src);
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(dst);
                    byte[] buf = new byte[16 * 1024];
                    int n;
                    while ((n = fis.read(buf)) > 0) fos.write(buf, 0, n);
                    fis.close();
                    fos.close();
                    cloud.coverPath = dst.getAbsolutePath();
                }
            }
        } catch (Throwable ignored) {}
    }

    /** Recursively deletes a file/folder from disk. Disk-only, no DB. */
    public void deleteRecursive(File fileOrDirectory){
        if (fileOrDirectory.isDirectory()){
            File[] children = fileOrDirectory.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursive(child);
            }
        }
        fileOrDirectory.delete();
    }

    /** Deletes the Room entry for the given folder name (background). */
    private void deleteSongFromDb(String folderName) {
        AppDatabase db = AppDatabase.getInstance(getApplicationContext());
        Executors.newSingleThreadExecutor().execute(() ->
                db.DAO_db_SongParameters().deleteBySongName(folderName));
    }

    @Override
    public void onBackPressed() {

        // Back first ends a search with focus.
        SearchBar search = currentTab == TAB_PLAYLISTS ? playlistSearch : songSearch;
        if (search.field.hasFocus()) {
            search.close(true);
            return;
        }

        // Back from the Playlists tab returns to the songs.
        if (currentTab == TAB_PLAYLISTS) {
            bnvMain.setSelectedItemId(R.id.tab_songs);
            return;
        }

        super.onBackPressed();

    }



    public void openSettings(View view){
        Intent intent = new Intent(this, SettingsActivity.class);
        startActivity(intent);
    }

    @Override
    protected void onDestroy() {
        AuthManager.get(this).removeLibraryChangedListener(libraryChangedListener);
        super.onDestroy();
    }

    /** Login, logout or local mode switched the library: list the new one. */
    private final AuthManager.LibraryChangedListener libraryChangedListener = () -> {
        if (isFinishing() || isDestroyed()) return;
        refreshSongList();
    };

    @Override
    protected void onStart() {
        super.onStart();
        // Pick up an accent change performed while this Activity was in the
        // background (recreate re-inflates layouts with the new ?attr/colorPrimary).
        com.jotapeveloper.fision.design.AccentColorManager.get(this)
                .maybeRecreateForAccent(this);
        miniPlayer.onStart();
        startAddShine();

        // Live server state
        CloudRepository.get(this).addListener(cloudListener);
        CloudRepository.get(this).startPolling();
        // Sliding session: a fresh TTL on every app start.
        AuthManager.get(this).refreshSessionOnce();
        AvatarManager.get(this).addListener(avatarListener);
        // One /server/info per visit to the list: seeds the cache for signed-in
        // sessions and refreshes the free VRAM behind the cloud warning. Not a
        // poll (each request runs nvidia-smi on the server).
        ServerInfoManager.get(this).addListener(serverInfoListener);
        if (AuthManager.get(this).isLoggedIn()) {
            ServerInfoManager.get(this).refresh();
        }
        // Session state may have changed since onCreate (Settings → logout, etc.)
        refreshToolbarAvatar();
        refreshCloudStatusIcon();
        // Playlists: offline copy now, server changes when the sync lands.
        com.jotapeveloper.fision.cloud.PlaylistRepository.get(this).addListener(playlistListener);
        com.jotapeveloper.fision.cloud.PlaylistRepository.get(this).sync();
        refreshPlaylistsTab();
        // Expired session: prompt now, or as soon as it happens while visible.
        AuthManager.get(this).addSessionExpiredListener(sessionExpiredListener);
        if (AuthManager.get(this).isSessionExpired()) showSessionExpiredLogin();
        // First-visit tour, once the list has settled.
        if (rvSongList != null) rvSongList.postDelayed(this::maybeStartLibraryTour, 700);
    }

    private final AuthManager.SessionExpiredListener sessionExpiredListener =
            this::showSessionExpiredLogin;

    /** The server rejected the session: asks for the password again, with the
     *  URL and username prefilled. Shown once per expiry. */
    private void showSessionExpiredLogin() {
        refreshToolbarAvatar();
        refreshCloudStatusIcon();
        if (isFinishing() || isDestroyed()) return;
        AuthManager.get(this).acknowledgeSessionExpired();
        LoginDialog.show(this, true, getString(R.string.login_session_expired),
                new LoginDialog.Callback() {
            @Override public void onLoggedIn(String u) {
                refreshToolbarAvatar();
                refreshCloudStatusIcon();
                CloudRepository.get(MainActivity.this).notifyConfigChanged();
                CloudRepository.get(MainActivity.this).startPolling();
            }
            @Override public void onLocalMode() {
                refreshToolbarAvatar();
                refreshCloudStatusIcon();
            }
        });
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_TAB, currentTab);
    }

    @Override
    protected void onStop() {
        super.onStop();
        miniPlayer.onStop();
        shineRunning = false;
        if (addShine != null) addShine.stop();
        CloudRepository.get(this).removeListener(cloudListener);
        CloudRepository.get(this).stopPolling();
        AvatarManager.get(this).removeListener(avatarListener);
        ServerInfoManager.get(this).removeListener(serverInfoListener);
        com.jotapeveloper.fision.cloud.PlaylistRepository.get(this).removeListener(playlistListener);
        AuthManager.get(this).removeSessionExpiredListener(sessionExpiredListener);
    }

    /** Bottom padding of both tab lists, so the last row clears the mini-player. */
    private void setListsBottomPadding(int px) {
        for (RecyclerView rv : new RecyclerView[] { rvSongList, rvPlaylists }) {
            if (rv != null) rv.setPadding(rv.getPaddingLeft(), rv.getPaddingTop(),
                    rv.getPaddingRight(), px);
        }
    }

}



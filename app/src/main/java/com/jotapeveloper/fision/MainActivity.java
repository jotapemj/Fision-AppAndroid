package com.jotapeveloper.fision;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
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
    private Button b_add;
    private List<String> SongsInDirectory;
    private android.widget.ImageButton b_orderBy;

    // Song list sort order (global, SharedPreferences).
    // 0 = date added descending (most recent first).
    // 1 = date added ascending (oldest first).
    private int sortOrder = 0;
    private static final String PREF_SORT_ORDER = "songs_sort_order";

    private Uri SongFolderUri;

    private  File AppFolder;
    private  File SongFolder;

    private RecyclerView rvSongList;
    private SwipeRefreshLayout refreshSongList;

    private SongListData[] Songs;

    // === Mini-player ===
    private View miniPlayer;
    private TextView tvMiniTitle;
    private TextView tvMiniArtist;
    private TextView tvMiniTitlePrev, tvMiniArtistPrev;
    private TextView tvMiniTitleNext, tvMiniArtistNext;
    private View llMiniCurrent, llMiniPrev, llMiniNext;
    private View flMiniTextWrap;
    private android.widget.ImageView ivMiniIcon;
    private MaterialButton bMiniPlayPause;
    // Play/pause icon morph state for the mini-player button.
    private boolean miniPlayIconState = false;   // false = play icon (paused), true = pause icon (playing)
    private boolean miniPlayIconInitialized = false;
    private android.widget.ProgressBar pbMiniProgress;
    private final android.os.Handler miniHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable miniProgressTick = new Runnable() {
        @Override public void run() {
            tickMiniProgress();
            miniHandler.postDelayed(this, 250);
        }
    };

    private PlaybackService playbackService;
    private boolean serviceBound = false;

    private final PlaybackService.Listener miniListener = new PlaybackService.Listener() {
        @Override public void onPlaybackChanged() { refreshMiniPlayer(); }
    };

    // === Server state ===
    private ImageView ivCloudStatus;
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
        }
        @Override public void onGpuTimeout(String hash) {
            if (isFinishing() || isDestroyed()) return;
            showGpuTimeoutDialog(hash);
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

        // Detect structural changes: item added or removed from the visible set
        java.util.Set<String> snapHashes = new java.util.HashSet<>();
        for (com.jotapeveloper.fision.cloud.CloudItem c : snap) snapHashes.add(c.hash);

        // 1) server item not present in Songs -> add
        java.util.Set<String> currentCloudHashes = new java.util.HashSet<>();
        for (SongListData s : Songs) if (s.cloud != null) currentCloudHashes.add(s.cloud.hash);
        for (com.jotapeveloper.fision.cloud.CloudItem c : snap) {
            if (c.state == com.jotapeveloper.fision.cloud.CloudItem.State.LOCAL_ONLY) continue;
            if (!currentCloudHashes.contains(c.hash)) { refreshSongList(); return; }
        }

        // 2) Songs item with cloud that is no longer on the server -> remove
        for (SongListData s : Songs) {
            if (s.cloud == null) continue;
            if (s.cloud.state == com.jotapeveloper.fision.cloud.CloudItem.State.LOCAL_ONLY) continue;
            if (!snapHashes.contains(s.cloud.hash)) { refreshSongList(); return; }
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

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            playbackService = ((PlaybackService.LocalBinder) binder).getService();
            serviceBound = true;
            playbackService.addListener(miniListener);
            refreshMiniPlayer();
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
            if (playbackService != null) playbackService.removeListener(miniListener);
            playbackService = null;
            serviceBound = false;
        }
    };


    public File getSongFolder() {
        return SongFolder;
    }

    private BroadcastReceiver languageChangeReceiver = new BroadcastReceiver() {


        @Override
        public void onReceive(Context context, Intent intent) {
            // Recreate the activity when the language change broadcast arrives
            recreate();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Attach the app-wide foreground tracker: dismisses pending "song
        // ready" notifications the moment the user returns to any screen.
        com.jotapeveloper.fision.cloud.AppForegroundState.get(this);

        // load preferences language
        PreferenceManager.setDefaultValues(this, R.xml.root_preferences, false);
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        String language = sharedPreferences.getString("language", "default_language");

        setAppLocale(language);
        // Register receiver for language changes

        // Listen to both system LOCALE_CHANGED (device language change) and the
        // in-app broadcast fired by SettingsActivity when the language preference
        // is changed from inside the app.
        IntentFilter langFilter = new IntentFilter(Intent.ACTION_LOCALE_CHANGED);
        langFilter.addAction("com.jotapeveloper.fision.LANGUAGE_CHANGED");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(languageChangeReceiver, langFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(languageChangeReceiver, langFilter);
        }

        setContentView(R.layout.activity_main);


        et_find = findViewById(R.id.et_find);
        tv_title = findViewById(R.id.tv_title);
        tv_SongsCount = findViewById(R.id.tv_SongsCount);
        b_add = findViewById(R.id.b_add);
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
        refreshSongList = findViewById(R.id.swipeRefreshLayout);
        refreshSongList.setColorSchemeResources(R.color.colorPrimary);

        // Mini-player. It's a BlurBehindLayout: the RV is passed as source so the
        // mini-player background is a live blur of the list sitting behind it.
        miniPlayer       = findViewById(R.id.mini_player);
        if (miniPlayer instanceof BlurBehindLayout) {
            ((BlurBehindLayout) miniPlayer).setSourceView(rvSongList);
        }
        tvMiniTitle      = findViewById(R.id.tv_mini_title);
        tvMiniArtist     = findViewById(R.id.tv_mini_artist);
        tvMiniTitlePrev  = findViewById(R.id.tv_mini_titlePrev);
        tvMiniArtistPrev = findViewById(R.id.tv_mini_artistPrev);
        tvMiniTitleNext  = findViewById(R.id.tv_mini_titleNext);
        tvMiniArtistNext = findViewById(R.id.tv_mini_artistNext);
        llMiniCurrent    = findViewById(R.id.ll_mini_current);
        llMiniPrev       = findViewById(R.id.ll_mini_prev);
        llMiniNext       = findViewById(R.id.ll_mini_next);
        flMiniTextWrap   = findViewById(R.id.fl_mini_textWrap);
        ivMiniIcon       = findViewById(R.id.iv_mini_icon);
        bMiniPlayPause   = findViewById(R.id.b_mini_playPause);
        pbMiniProgress   = findViewById(R.id.pb_mini_progress);

        bMiniPlayPause.setOnClickListener(v -> {
            if (playbackService == null) return;
            if (playbackService.isPlaying()) playbackService.pause();
            else                              playbackService.play();
        });
        // Gestures over the text area: tap → open player; lateral fling → switch song
        attachMiniSwipe();

        refreshSongList.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {
            @Override
            public void onRefresh() {
                refreshSongList();
                refreshSongList.setRefreshing(false);
            }
        });


        // Main songs directory checks

        AppFolder = getExternalFilesDir(null);
        SongFolder = new File(AppFolder, "Songs");
        if (!SongFolder.exists()) {
            SongFolder.mkdirs(); // Create subdirectory if does not exist.
        }

        // Load Main Activity RecyclerView with songs
        refreshSongList();

        // Listener for searching for song
        Drawable[] et_find_drawables = et_find.getCompoundDrawables();
        showClearButton(et_find_drawables, false);
        et_find.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View view, boolean hasFocus) {
                if (hasFocus){
                    if (b_add.getVisibility() == View.VISIBLE){
                        buttonAnimation(false, b_add);}
                    if(tv_title.getText().toString().equals(getString(R.string.tv_title))){
                        changeTextAnimation(tv_title, getString(R.string.tv_title_search));}
                }
            }
        });
        et_find.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {

            }

            @Override
            public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {
                boolean TextEmpty = TextUtils.isEmpty(charSequence.toString());
                if (TextEmpty){showClearButton(et_find_drawables, false);}
                else{showClearButton(et_find_drawables, true);}

                if (TextEmpty) {
                    // Empty query: rebuild the full merged list (local + cloud +
                    // sticky order) instead of running filterList("") which only
                    // sees local folders and re-adds them without the merge, so
                    // sticky-ordered items ended up in the wrong slot.
                    refreshSongList();
                } else {
                    filterList(charSequence.toString(), SongsInDirectory, rvSongList);
                }

                Animation songsCountAnimation_out = new AlphaAnimation(1.0f, 0.0f);
                Animation songsCountAnimation_in = new AlphaAnimation(0.0f, 1.0f);
                songsCountAnimation_out.setDuration(300);
                songsCountAnimation_in.setDuration(300);

                if (!TextEmpty){
                    refreshSongList.setEnabled(false);
                    int itemsFiltered = rvSongList.getAdapter().getItemCount();
                    tv_SongsCount.startAnimation(songsCountAnimation_out);
                    String results = itemsFiltered + " " + getString(R.string.results);
                    tv_SongsCount.setText(results);
                    tv_SongsCount.startAnimation(songsCountAnimation_in);
                }
                else{refreshSongList.setEnabled(true);
                    String songsCount = SongsInDirectory.size() + " " + getString(R.string.songs);
                    tv_SongsCount.setText(songsCount);}
            }

            @Override
            public void afterTextChanged(Editable editable) {

            }
        });


        et_find.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent motionEvent) {
                final int DRAWABLE_LEFT = 0;
                final int DRAWABLE_TOP = 1;
                final int DRAWABLE_RIGHT = 2;
                final int DRAWABLE_BOTTOM = 3;
                if(et_find.getCompoundDrawables()[DRAWABLE_RIGHT]==null){return false;}
                if(motionEvent.getAction() == MotionEvent.ACTION_UP) {
                    if(motionEvent.getRawX() >= (et_find.getRight() - et_find.getCompoundDrawables()[DRAWABLE_RIGHT].getBounds().width())) {
                        et_find.setText("");
                        return true;
                    }
                }
                return false;
            }
        });

        // First-run flow: prompt the sign-in dialog on the very first launch. The
        // user can either authenticate against a server or skip into local mode.
        maybeShowFirstRunLogin();
    }

    /** Shows the login dialog once, on the first ever launch. The dialog itself
     *  transitions the app out of first-run state via AuthManager. */
    private void maybeShowFirstRunLogin() {
        if (!AuthManager.get(this).isFirstRun()) return;
        // Defer to the next UI cycle so the activity is fully laid out.
        rvSongList.post(() -> {
            if (isFinishing() || isDestroyed()) return;
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
                @Override public void onCancelled() {
                    // No decision yet; keep first_run true so it prompts again next open.
                }
            });
        });
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
    public void buttonAnimation(boolean visible, Button button){
        Animation animation;
        if (visible) {
            animation = new AlphaAnimation(0.0f, 1.0f);
        } else {
            animation = new AlphaAnimation(1.0f, 0.0f);
        }
        animation.setDuration(150);

        animation.setAnimationListener(new Animation.AnimationListener() {
            @Override
            public void onAnimationStart(Animation animation) {
                // Nothing to do here
            }

            @Override
            public void onAnimationEnd(Animation animation) {
                // Toggle button visibility when the animation ends
                if (!visible) {
                    button.setVisibility(View.GONE);
                }
                if (visible) {
                    button.setVisibility(View.VISIBLE);
                }
            }

            @Override
            public void onAnimationRepeat(Animation animation) {
            }
        });

        button.startAnimation(animation);


    }

    private void showClearButton(Drawable[] drawables,boolean option) {
        if (!option){
            et_find.setCompoundDrawablesWithIntrinsicBounds(drawables[0],drawables[1],null,drawables[3]);
        }
        else{
            et_find.setCompoundDrawablesWithIntrinsicBounds(drawables[0],drawables[1],drawables[2],drawables[3]);
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


    private void filterList(String key,  List<String> inputList, RecyclerView rv){
        List<String> filteredList = new ArrayList<>();
        String k = key.toLowerCase(java.util.Locale.ROOT);

        for (String element : inputList){
            // Match against folder name or metadata (displayTitle + artist)
            if (element.toLowerCase(java.util.Locale.ROOT).contains(k)) {
                filteredList.add(element);
                continue;
            }
            SongMetadata meta = SongMetadata.read(new File(SongFolder, element));
            String t = meta.displayTitle != null ? meta.displayTitle.toLowerCase(java.util.Locale.ROOT) : "";
            String a = meta.artist       != null ? meta.artist.toLowerCase(java.util.Locale.ROOT)       : "";
            if (t.contains(k) || a.contains(k)) {
                filteredList.add(element);
            }
        }

        SongListData[] filteredSongListData = new SongListData[filteredList.size()];

        for (int i=0 ; i<filteredList.size(); i++) {
            String song = filteredList.get(i);
            filteredSongListData[i] = buildSongListData(song);
        }

        Animation rv_out = AnimationUtils.loadAnimation(this, R.anim.fade_out);
        Animation rv_in = AnimationUtils.loadAnimation(this, R.anim.fade_in);

        rv.startAnimation(rv_out);

        SongListAdapter songListAdapter = new SongListAdapter(MainActivity.this, filteredSongListData, this);
        rv.setHasFixedSize(true);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(songListAdapter);

        rv.startAnimation(rv_in);
    }

    public void addSong(View view){
        // Cloud pipeline requires an authenticated server. In local mode, prompt
        // the login dialog instead of opening the import UI.
        if (!AuthManager.get(this).isLoggedIn()) {
            Toast.makeText(this, R.string.local_mode_import_blocked, Toast.LENGTH_LONG).show();
            LoginDialog.show(this, true, new LoginDialog.Callback() {
                @Override public void onLoggedIn(String u) {
                    refreshToolbarAvatar();
                    refreshCloudStatusIcon();
                    CloudRepository.get(MainActivity.this).notifyConfigChanged();
                    startActivity(new Intent(MainActivity.this, ImportActivity.class));
                }
            });
            return;
        }
        startActivity(new Intent(this, ImportActivity.class));
    }

    /**
     * Builds a SongListData with optional metadata from metadata.txt and artcover.png.
     * Fallback: folder name as title and "Stems" as subtitle.
     */
    private SongListData buildSongListData(String folderName) {
        SongListData s = new SongListData(folderName, "Stems");
        SongMetadata meta = SongMetadata.read(new File(SongFolder, folderName));
        if (meta.displayTitle != null) s.setDisplayTitle(meta.displayTitle);
        if (meta.artist != null)       s.setArtist(meta.artist);
        if (meta.coverPath != null)    s.setCoverPath(meta.coverPath);
        return s;
    }

    public void refreshSongList(){
        ContentResolver contentResolver = getContentResolver();
        SongsInDirectory = getSubdirectories(SongFolder, contentResolver);
        applySortToSongs(SongsInDirectory);

        // Notify the repository about local folders (marks LOCAL_AND_CLOUD / LOCAL_ONLY)
        CloudRepository.get(this).mergeLocalFolders(SongsInDirectory);

        // Combined list: in-flight on top, locals in the middle, cloud-only at the bottom
        List<SongListData> mixed = buildMergedSongsList(SongsInDirectory);
        Songs = mixed.toArray(new SongListData[0]);

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

        String songsCount = SongsInDirectory.size() + " " + getString(R.string.songs);
        tv_SongsCount.setText(songsCount);
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
            if (c.isInFlight()) inFlight.add(s); else regular.add(s);
        }

        // 3) Final sort by tsForItem — sticky > serverCreated > serverUpdated > lastModified.
        java.util.Collections.sort(regular, (a, b) -> {
            long ta = tsForItem(a);
            long tb = tsForItem(b);
            return sortOrder == 0 ? Long.compare(tb, ta) : Long.compare(ta, tb);
        });

        List<SongListData> all = new ArrayList<>(inFlight.size() + regular.size());
        all.addAll(inFlight);
        all.addAll(regular);
        return all;
    }

    /** Returns the timestamp that represents the item's "date" for sorting:
     *  sticky if present, otherwise server created_at (stable order, does not change on
     *  reprocess), otherwise updated_at, otherwise the local folder's lastModified. */
    private long tsForItem(SongListData s) {
        if (s.cloud != null) {
            if (s.cloud.stickyOrderTs > 0) return s.cloud.stickyOrderTs;
            long sc = parseIsoTs(s.cloud.serverCreated);
            if (sc > 0) return sc;
            long su = parseIsoTs(s.cloud.serverUpdated);
            if (su > 0) return su;
        }
        File f = new File(SongFolder, s.getTitle());
        return f.exists() ? f.lastModified() : 0L;
    }

    /** Parses a server ISO8601 date to millis. Accepts several common formats
     *  ("2026-06-30T12:34:56", "2026-06-30 12:34:56", with or without timezone). Returns 0 on failure. */
    private static long parseIsoTs(String iso) {
        if (iso == null || iso.isEmpty()) return 0L;
        String s = iso.replace('T', ' ');
        int dot = s.indexOf('.');
        if (dot > 0) s = s.substring(0, dot);
        int z = s.indexOf('+');
        if (z < 0) z = s.indexOf('Z');
        if (z > 0) s = s.substring(0, z).trim();
        try {
            java.text.SimpleDateFormat sdf =
                    new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US);
            sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            java.util.Date d = sdf.parse(s);
            return d != null ? d.getTime() : 0L;
        } catch (Throwable ignored) {
            return 0L;
        }
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
     *  known server reachability. Three visible outcomes:
     *    - no session       → grey icon, tap = open login dialog.
     *    - session + offline → red icon, persistent.
     *    - session + online  → hidden (with a brief green flash on reconnect). */
    private void refreshCloudStatusIcon() {
        boolean reachable = lastReachable != null ? lastReachable
                : CloudRepository.get(this).isReachable();
        refreshCloudStatusIcon(reachable);
    }

    private void refreshCloudStatusIcon(boolean reachable) {
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

    /** Swaps the toolbar between the settings gear (no session) and the
     *  circular initial avatar (session active). Both open Settings when tapped;
     *  the avatar exists purely to signal an active identity. */
    private void refreshToolbarAvatar() {
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
            android.graphics.Bitmap bm = android.graphics.BitmapFactory
                    .decodeFile(am.getLocalFile(user).getAbsolutePath());
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
            openSettings(null);
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
            android.graphics.Bitmap bm = android.graphics.BitmapFactory.decodeFile(coverPath);
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
            mtimeCache.put(name, new File(SongFolder, name).lastModified());
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
        int primary  = androidx.core.content.ContextCompat.getColor(this, R.color.colorPrimary);
        int white    = androidx.core.content.ContextCompat.getColor(this, R.color.AppWhite);
        java.util.function.BiConsumer<TextView, Boolean> style = (tv, active) -> {
            tv.setTextColor(active ? primary : white);
            tv.setBackgroundResource(active
                    ? R.drawable.dialog_option_active : R.drawable.dialog_option_inactive);
            tv.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(
                    MainActivity.this, R.font.yugothic_ui_regular),
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

    /** Blur dialog surfaced when the server aborts a job after 10 minutes of
     *  waiting for a free GPU. Offers a single retry that reprocesses on the
     *  server without re-uploading the audio. Package-private so the list
     *  adapter can re-open it when the user taps an item stuck on this error. */
    void showGpuTimeoutDialog(String hash) {
        LayoutInflater inflater = LayoutInflater.from(this);
        View dv = inflater.inflate(R.layout.delete_alert_dialog, null);
        android.widget.ImageView icon = dv.findViewById(R.id.iv_alertDialog_ic);
        TextView body     = dv.findViewById(R.id.tv_alertDialog_text);
        android.widget.Button retry  = dv.findViewById(R.id.b_yes);
        android.widget.Button cancel = dv.findViewById(R.id.b_no);

        icon.setImageResource(R.drawable.ic_warning);
        icon.setColorFilter(ContextCompat.getColor(this, R.color.gpu_warning_accent));
        body.setText(R.string.gpu_timeout_body);
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
            CloudRepository.get(this).retryGpuJob(hash);
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
            title.setText(getString(R.string.delete_confirm) + " " + displayName + "?");
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
                CloudRepository.get(this).deleteFromCloud(song.cloud.hash);
            } else {
                File target = new File(SongFolder, folderName);
                // When the song is also on the cloud, remember lastModified so after
                // deletion the entry stays "sticky" at its original position as CLOUD_ONLY.
                long lm = target.exists() ? target.lastModified() : System.currentTimeMillis();
                // When the song has a cloud counterpart, preserve metadata+cover BEFORE
                // deleting the folder: copies artcover.png to cacheDir/cover_<hash>.png
                // and dumps displayTitle/artist into the CloudItem so the CLOUD_ONLY item
                // keeps showing the same info it had while local.
                if (song.cloud != null) preserveCloudMetadataFromLocal(target, song.cloud);
                // When the deleted song is the one playing in the mini-player, stop the
                // service and clear the UI. Otherwise the mini-player offered "play" on a
                // non-existent path.
                boolean wasCurrent = playbackService != null
                        && playbackService.isSongLoaded()
                        && folderName.equals(playbackService.getCurrentSongName());
                if (wasCurrent) {
                    playbackService.releaseSongAndStop();
                }
                // Force the mini-player to GONE immediately, regardless of the async
                // listener (notifyListeners posts to mainHandler, arrives late) or of a
                // desynced playbackService.
                if (wasCurrent && miniPlayer != null) {
                    miniPlayer.setVisibility(View.GONE);
                    miniHandler.removeCallbacks(miniProgressTick);
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
        refreshSongList();
        if (!et_find.getText().toString().isEmpty()) {
            filterList(et_find.getText().toString(), SongsInDirectory, rvSongList);
        }
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
            android.graphics.Bitmap bm = android.graphics.BitmapFactory.decodeFile(coverPath);
            if (bm != null) ivCover.setImageBitmap(bm);
        }
        etSong.setText(displayName);
        etArtist.setText(artistName);

        // Read-only folder info: date, format, size, duration, stems.
        populateSongInfo(dialogView, new File(SongFolder, folderName));

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
            File folder = new File(SongFolder, folderName);
            if (writeMetadata(folder, newSong, newArtist)) {
                refreshSongList();
                if (et_find.getText().toString() != "") {
                    filterList(et_find.getText().toString(), SongsInDirectory, rvSongList);
                }
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
                    File dst = new File(getCacheDir(), "cover_" + cloud.hash + ".png");
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


        if(et_find.hasFocus()){
            buttonAnimation(true, b_add);
            changeTextAnimation(tv_title, getString(R.string.tv_title));
            et_find.clearFocus();
            et_find.setText("");
            return;
        }

        super.onBackPressed();

    }

    // configuration

    private void setAppLocale(String languageCode) {
        Locale locale = new Locale(languageCode);
        Locale.setDefault(locale);

        Configuration config = new Configuration();
        config.setLocale(locale);

        getResources().updateConfiguration(config, getBaseContext().getResources().getDisplayMetrics());
    }

    public void openSettings(View view){
        Intent intent = new Intent(this, SettingsActivity.class);
        startActivity(intent);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Deregister receiver when the activity is destroyed
        unregisterReceiver(languageChangeReceiver);
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Bind to the PlaybackService when it is already running
        Intent svc = new Intent(this, PlaybackService.class);
        bindService(svc, serviceConnection, 0); // no BIND_AUTO_CREATE: only connects when it already exists

        // Live server state
        CloudRepository.get(this).addListener(cloudListener);
        CloudRepository.get(this).startPolling();
        AvatarManager.get(this).addListener(avatarListener);
        // Ensure the ServerInfo cache is populated for signed-in sessions.
        // Covers legacy installs whose migration granted a session without
        // ever hitting the login hook that seeds the cache.
        if (AuthManager.get(this).isLoggedIn()
                && !ServerInfoManager.get(this).isLoaded()) {
            ServerInfoManager.get(this).refresh();
        }
        // Session state may have changed since onCreate (Settings → logout, etc.)
        refreshToolbarAvatar();
        refreshCloudStatusIcon();
    }

    @Override
    protected void onStop() {
        super.onStop();
        miniHandler.removeCallbacks(miniProgressTick);
        if (serviceBound) {
            if (playbackService != null) playbackService.removeListener(miniListener);
            unbindService(serviceConnection);
            serviceBound = false;
        }
        CloudRepository.get(this).removeListener(cloudListener);
        CloudRepository.get(this).stopPolling();
        AvatarManager.get(this).removeListener(avatarListener);
    }

    // Swaps the mini-player play/pause icon. Uses an AVD morph when animate=true
    // (state change while the mini-bar is visible), or the static vector when
    // animate=false (first show / cold start) to avoid firing an animation on entry.
    private void applyMiniPlayIcon(boolean playing, boolean animate) {
        if (bMiniPlayPause == null) return;
        if (miniPlayIconInitialized && miniPlayIconState == playing) return;
        if (!miniPlayIconInitialized || !animate) {
            bMiniPlayPause.setIconResource(playing ? R.drawable.avd_ic_pause : R.drawable.avd_ic_play);
        } else {
            bMiniPlayPause.setIconResource(playing ? R.drawable.avd_play_to_pause : R.drawable.avd_pause_to_play);
            android.graphics.drawable.Drawable icon = bMiniPlayPause.getIcon();
            if (icon instanceof android.graphics.drawable.Animatable) {
                ((android.graphics.drawable.Animatable) icon).start();
            }
        }
        miniPlayIconState = playing;
        miniPlayIconInitialized = true;
    }

    /** Refreshes the mini-player visibility/title/icon based on the service state. */
    private void refreshMiniPlayer() {
        if (miniPlayer == null) return;
        if (playbackService == null || !playbackService.isSongLoaded()) {
            miniPlayer.setVisibility(View.GONE);
            miniHandler.removeCallbacks(miniProgressTick);
            // Without a mini-player, the RV does not need the bottom padding (it looked black at the end).
            if (rvSongList != null) rvSongList.setPadding(
                    rvSongList.getPaddingLeft(), rvSongList.getPaddingTop(),
                    rvSongList.getPaddingRight(), 0);
            return;
        }
        miniPlayer.setVisibility(View.VISIBLE);
        // With the mini-player visible, apply bottom padding so the mini-player's
        // rounded corners show items behind and the last one stays reachable.
        if (rvSongList != null) {
            int pad = (int) (92f * getResources().getDisplayMetrics().density);
            rvSongList.setPadding(
                    rvSongList.getPaddingLeft(), rvSongList.getPaddingTop(),
                    rvSongList.getPaddingRight(), pad);
        }
        SongMetadata meta = playbackService.getCurrentMetadata();
        tvMiniTitle.setText(playbackService.getDisplayTitle());
        tvMiniArtist.setText(meta != null && meta.artist != null ? meta.artist : "");
        tvMiniArtist.setVisibility(meta != null && meta.artist != null ? View.VISIBLE : View.GONE);

        if (meta != null && meta.coverPath != null && new File(meta.coverPath).exists()) {
            ivMiniIcon.setImageBitmap(android.graphics.BitmapFactory.decodeFile(meta.coverPath));
        } else {
            ivMiniIcon.setImageResource(R.raw.artcover_generic);
        }
        applyMiniPlayIcon(playbackService.isPlaying(), true);
        // Refresh prev/next previews in case the song list changed
        populateMiniPreviews();
        // Start / restart the progress tick (idempotent)
        miniHandler.removeCallbacks(miniProgressTick);
        miniHandler.post(miniProgressTick);
    }

    /** Updates the mini-player progress bar. Called by miniProgressTick every ~250ms. */
    private void tickMiniProgress() {
        if (pbMiniProgress == null) return;
        if (playbackService == null || !playbackService.isSongLoaded()) return;
        long durationMs = playbackService.getDurationMs();
        long currentMs  = playbackService.getCurrentPositionMs();
        if (durationMs <= 0) { pbMiniProgress.setProgress(0); return; }
        int promille = (int) ((currentMs * 1000L) / durationMs);
        if (promille < 0) promille = 0;
        if (promille > 1000) promille = 1000;
        pbMiniProgress.setProgress(promille);
    }

    /** Opens the player for the song already loaded in the service.
     *  Flag noAutoplay=true: just enters the UI without modifying playback state
     *  (without this, after song end the player auto-started when tapping the mini-player). */
    private void openCurrentSongPlayer() {
        if (playbackService == null || !playbackService.isSongLoaded()) return;
        Intent player = new Intent(this, PlayerActivity.class);
        player.putExtra("SongPathString", playbackService.getCurrentSongPath());
        player.putExtra("SongName",       playbackService.getCurrentSongName());
        player.putExtra("NoAutoplay",     true);
        startActivity(player);
    }

    /** Wires tap (opens player) + drag-follow swipe (switches song) over the
     *  transparent area. The current text follows the finger; the prev and next
     *  previews slide alongside, so a leftward drag reveals the next song coming
     *  in from the right, and vice-versa. */
    private void attachMiniSwipe() {
        View swipeArea = findViewById(R.id.v_mini_swipeArea);
        if (swipeArea == null) return;

        final int touchSlop = android.view.ViewConfiguration.get(this).getScaledTouchSlop();
        final float[] startCoords = {0f, 0f};
        final long[]  startTime   = {0L};
        final boolean[] isSwiping = {false};

        swipeArea.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    startCoords[0] = ev.getRawX();
                    startCoords[1] = ev.getRawY();
                    startTime[0]   = ev.getEventTime();
                    isSwiping[0]   = false;
                    cancelMiniTextAnimations();
                    // Refresh previews in case the song changed since the last gesture
                    populateMiniPreviews();
                    return true;
                case android.view.MotionEvent.ACTION_MOVE: {
                    float dx = ev.getRawX() - startCoords[0];
                    float dy = ev.getRawY() - startCoords[1];
                    if (!isSwiping[0]) {
                        if (Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy)) {
                            isSwiping[0] = true;
                        }
                    }
                    if (isSwiping[0]) {
                        int w = flMiniTextWrap.getWidth();
                        if (w <= 0) w = 600;
                        llMiniCurrent.setTranslationX(dx);
                        llMiniPrev.setTranslationX(-w + dx);
                        llMiniNext.setTranslationX( w + dx);
                    }
                    return true;
                }
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL: {
                    float endDx = ev.getRawX() - startCoords[0];
                    long elapsed = ev.getEventTime() - startTime[0];
                    if (!isSwiping[0]) {
                        if (elapsed < 300 && Math.abs(endDx) < touchSlop) {
                            openCurrentSongPlayer();
                        }
                    } else {
                        int w = flMiniTextWrap.getWidth();
                        if (w <= 0) w = 600;
                        float threshold = w * 0.30f;
                        if (Math.abs(endDx) > threshold) {
                            commitMiniSongChange(endDx < 0 ? +1 : -1, w);
                        } else {
                            snapBackMiniText(w);
                        }
                    }
                    return true;
                }
            }
            return false;
        });
    }

    private void cancelMiniTextAnimations() {
        llMiniCurrent.animate().cancel();
        llMiniPrev.animate().cancel();
        llMiniNext.animate().cancel();
    }

    private void snapBackMiniText(int w) {
        llMiniCurrent.animate().translationX(0f).setDuration(160).start();
        llMiniPrev   .animate().translationX(-w).setDuration(160).start();
        llMiniNext   .animate().translationX( w).setDuration(160).start();
    }

    /** Animates the current text out and the chosen preview in. At the end it
     *  snapshots: copies the preview into the "current", loads the song and repositions. */
    private void commitMiniSongChange(int direction, int w) {
        if (playbackService == null || !playbackService.isSongLoaded()) { snapBackMiniText(w); return; }
        File next = computeAdjacentSongFolder(direction);
        if (next == null) { snapBackMiniText(w); return; }
        final float exitX = direction > 0 ? -w : w;
        final View entering = direction > 0 ? llMiniNext : llMiniPrev;
        // The two animations other than "entering" move to their final positions
        llMiniCurrent.animate().translationX(exitX).setDuration(160).start();
        // The other preview (not "entering") leaves the opposite side out of view
        View other = direction > 0 ? llMiniPrev : llMiniNext;
        other.animate().translationX(direction > 0 ? -2 * w : 2 * w).setDuration(160).start();
        entering.animate().translationX(0f).setDuration(160)
                .withEndAction(() -> {
                    // Snapshot: the preview that just reached 0 is now the "current"
                    TextView previewTitle  = direction > 0 ? tvMiniTitleNext  : tvMiniTitlePrev;
                    TextView previewArtist = direction > 0 ? tvMiniArtistNext : tvMiniArtistPrev;
                    String tStr = previewTitle.getText().toString();
                    String aStr = previewArtist.getText().toString();
                    tvMiniTitle.setText(tStr);
                    tvMiniArtist.setText(aStr);
                    tvMiniArtist.setVisibility(aStr.isEmpty() ? View.GONE : View.VISIBLE);
                    llMiniCurrent.setTranslationX(0f);
                    // Load the song in the service (autoplay after volumes come from Room)
                    loadSongInServiceWithVolumes(next, true);
                    // Recompute and reposition previews for the new current
                    populateMiniPreviews();
                }).start();
    }

    /** Reads prev/next data from disk based on the active sort order and paints them
     *  into the preview TextViews. Repositions them at ±wrapper.getWidth(). */
    private void populateMiniPreviews() {
        if (playbackService == null || !playbackService.isSongLoaded()) return;
        File prev = computeAdjacentSongFolder(-1);
        File next = computeAdjacentSongFolder(+1);
        assignPreview(prev, tvMiniTitlePrev, tvMiniArtistPrev);
        assignPreview(next, tvMiniTitleNext, tvMiniArtistNext);
        int w = flMiniTextWrap.getWidth();
        if (w <= 0) {
            // The wrapper has not been measured yet; defer to the next layout pass
            flMiniTextWrap.post(this::positionMiniPreviews);
        } else {
            positionMiniPreviews();
        }
    }

    private void positionMiniPreviews() {
        int w = flMiniTextWrap.getWidth();
        if (w <= 0) w = 600;
        llMiniPrev.setTranslationX(-w);
        llMiniNext.setTranslationX( w);
    }

    /** Reads metadata.txt from the folder and paints the preview TextViews. */
    private void assignPreview(File folder, TextView title, TextView artist) {
        if (folder == null) {
            title.setText(""); artist.setText(""); artist.setVisibility(View.GONE);
            return;
        }
        String name = folder.getName();
        SongMetadata meta = SongMetadata.read(folder);
        String t = meta.displayTitle != null ? meta.displayTitle : name;
        String a = meta.artist != null ? meta.artist : "";
        title.setText(t);
        artist.setText(a);
        artist.setVisibility(a.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /** Computes the adjacent song folder respecting the active sort order. */
    private File computeAdjacentSongFolder(int direction) {
        if (playbackService == null) return null;
        String currentPath = playbackService.getCurrentSongPath();
        if (currentPath == null) return null;
        File current = new File(currentPath);
        File parent = current.getParentFile();
        if (parent == null || !parent.isDirectory()) return null;
        File[] subs = parent.listFiles(File::isDirectory);
        if (subs == null || subs.length < 2) return null;
        int order = PreferenceManager.getDefaultSharedPreferences(this).getInt(PREF_SORT_ORDER, 0);
        java.util.Arrays.sort(subs, (a, b) -> order == 0
                ? Long.compare(b.lastModified(), a.lastModified())
                : Long.compare(a.lastModified(), b.lastModified()));
        int idx = -1;
        for (int i = 0; i < subs.length; i++) {
            if (subs[i].getName().equals(current.getName())) { idx = i; break; }
        }
        if (idx < 0) return null;
        int target = ((idx + direction) % subs.length + subs.length) % subs.length;
        return subs[target];
    }

    /** Loads a song into the service, reads saved volumes from Room, applies them
     *  to the engine and only then starts playback. Without this, the song would
     *  play at the default 75% during the delay it takes Room to return the settings.
     *  @param autoplay true to start after volumes are applied. */
    private void loadSongInServiceWithVolumes(File folder, boolean autoplay) {
        if (playbackService == null || folder == null) return;
        final String path = folder.getAbsolutePath();
        final String name = folder.getName();
        final int    id   = name.hashCode();
        boolean ok = playbackService.loadSong(path, name, id);
        if (!ok) return;

        // Background: read Room, apply volumes/pan, then play on the main thread
        AppDatabase db = AppDatabase.getInstance(getApplicationContext());
        Executors.newSingleThreadExecutor().execute(() -> {
            java.util.List<db_SongParameters> list =
                    db.DAO_db_SongParameters().getParametersByElementId(id);
            if (!list.isEmpty() && playbackService != null) {
                db_SongParameters p = list.get(0);
                java.util.Map<String, StemSetting> settings =
                        StemSettingsCodec.parse(p.stemSettingsJson);
                AudioEngine engine = playbackService.getEngine();
                if (engine != null) {
                    for (java.util.Map.Entry<String, StemSetting> e : settings.entrySet()) {
                        Integer t = playbackService.getStemTrack(e.getKey());
                        if (t == null) continue;
                        float vol = e.getValue().volume * 0.01f;
                        float L = vol, R = vol;
                        float pan = e.getValue().pan;
                        if (pan > 0)      L = vol * (100f - pan) * 0.01f;
                        else if (pan < 0) R = vol * (100f - Math.abs(pan)) * 0.01f;
                        engine.setVolume(t, L, R);
                    }
                }
            }
            if (autoplay) {
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                    if (playbackService != null) playbackService.play();
                });
            }
        });
    }
}



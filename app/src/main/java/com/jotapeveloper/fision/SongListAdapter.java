package com.jotapeveloper.fision;

import android.content.Context;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import com.google.android.material.imageview.ShapeableImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.collection.LruCache;
import androidx.recyclerview.widget.RecyclerView;

import com.jotapeveloper.fision.cloud.CloudItem;
import com.jotapeveloper.fision.cloud.CloudRepository;
import com.jotapeveloper.fision.cloud.DownloadManagerLite;

import java.io.File;

public class SongListAdapter extends RecyclerView.Adapter<SongListAdapter.ViewHolder>{

    Context context;
    public SongListData[] listData;
    MainActivity ParentActivity;

    public SongListAdapter(Context context, SongListData[] listData, MainActivity ParentActivity) {
        this.context = context;
        this.listData = listData;
        this.ParentActivity = ParentActivity;
        setHasStableIds(true);
    }

    /** Payload for partial bind: refreshes overlay+subtitle only, does not touch cover/tvTitle. */
    public static final Object PAYLOAD_CLOUD = new Object();

    /** LRU cache of cover bitmaps keyed by path. Avoids repeated decoding while scrolling. */
    private static final LruCache<String, android.graphics.Bitmap> COVER_CACHE = new LruCache<>(48);

    private static android.graphics.Bitmap loadCoverCached(String path) {
        if (path == null) return null;
        android.graphics.Bitmap bm = COVER_CACHE.get(path);
        if (bm != null) return bm;
        // Decode with a sample size to avoid pulling a huge bitmap into memory (~300 px target)
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, opts);
        int sample = 1;
        int target = 300;
        while (opts.outWidth / sample > target * 2 && opts.outHeight / sample > target * 2) sample *= 2;
        opts.inJustDecodeBounds = false;
        opts.inSampleSize = sample;
        bm = BitmapFactory.decodeFile(path, opts);
        if (bm != null) COVER_CACHE.put(path, bm);
        return bm;
    }

    /**
     * Replaces the whole dataset. Use ONLY when the content changes
     * (add/remove, sort, search). For cloud-state refreshes, use
     * {@link #updateCloudAt(int, com.jotapeveloper.fision.cloud.CloudItem)}.
     */
    public void setData(SongListData[] newData) {
        this.listData = newData != null ? newData : new SongListData[0];
        notifyDataSetChanged();
    }

    /** Updates only the cloud state of the item; the bind ignores the cover. */
    public void updateCloudAt(int i, com.jotapeveloper.fision.cloud.CloudItem newCloud) {
        if (listData == null || i < 0 || i >= listData.length) return;
        listData[i].cloud = newCloud;
        notifyItemChanged(i, PAYLOAD_CLOUD);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.song_list_item, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position,
                                 @NonNull java.util.List<Object> payloads) {
        // Lightweight bind: overlays + subtitle only, no cover touching nor bitmap decoding
        if (!payloads.isEmpty() && payloads.contains(PAYLOAD_CLOUD)) {
            SongListData it = listData[position];
            if (it.demo) {
                bindDemoState(holder, it, true);
                return;
            }
            String newSub = computeSubtitle(it);
            String newKind = computeSubtitleKind(it);
            String oldKind = holder.lastSubtitleKind;
            if (newKind.equals(oldKind)) {
                // Same "kind" (e.g. DOWNLOADING with a rising %). Update text without fade.
                holder.tvSubtitle.setText(newSub);
            } else {
                // Real type change (DOWNLOADING → artist, RUNNING → DOWNLOADING, etc.). Fade out + in.
                holder.tvSubtitle.animate().cancel();
                holder.tvSubtitle.animate().alpha(0f).setDuration(120)
                        .withEndAction(() -> {
                            holder.tvSubtitle.setText(newSub);
                            holder.tvSubtitle.animate().alpha(1f).setDuration(180).start();
                        }).start();
                holder.lastSubtitleKind = newKind;
            }
            applyCloudOverlay(holder, it.cloud, true);
            return;
        }
        onBindViewHolder(holder, position);
    }

    /** Stable ids per song (folder name or cloud hash): a full refresh keeps each
     *  holder on its song, so covers and veils are not shuffled between rows. */
    private static final java.util.Map<String, Long> STABLE_IDS = new java.util.HashMap<>();

    @Override
    public long getItemId(int position) {
        String key = listData[position].getTitle();
        Long id = STABLE_IDS.get(key);
        if (id == null) {
            id = (long) STABLE_IDS.size();
            STABLE_IDS.put(key, id);
        }
        return id;
    }

    /** Subtitle "kind" used to decide when to animate a fade. Only changes when the
     *  type changes (not when the DOWNLOADING % rises). */
    private String computeSubtitleKind(SongListData item) {
        CloudItem c = item.cloud;
        if (isLocked(c)) return "locked";
        if (c != null) {
            switch (c.state) {
                case QUEUED:      return "queued";
                case RUNNING:     return "running";
                case WAITING_GPU: return "waiting_gpu";
                case DOWNLOADING: return "downloading";
                case ERROR:       return "error";
                default: break;
            }
        }
        return "meta";
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        SongListData item = listData[position];

        String title = item.getDisplayTitle() != null ? item.getDisplayTitle() : item.getTitle();
        holder.tvTitle.setText(title);

        if (item.demo) {
            if (demoCover == null) {
                demoCover = BitmapFactory.decodeResource(context.getResources(), R.drawable.demo_artcover);
            }
            holder.iv_cover.setImageBitmap(demoCover);
            if (holder.vCoverWave != null) holder.vCoverWave.setCover(demoCover);
            boolean sameSong = item.getTitle().equals(holder.boundKey);
            holder.boundKey = item.getTitle();
            holder.lastSubtitleKind = "";
            bindDemoState(holder, item, sameSong);
            holder.options.setOnClickListener(view -> ParentActivity.showBottomDialog(item));
            holder.itemView.setOnLongClickListener(view -> {
                ParentActivity.showBottomDialog(item);
                return true;
            });
            return;
        }

        // Subtitle: cloud state wins; otherwise artist; otherwise legacy subtitle
        String subtitle = computeSubtitle(item);
        holder.tvSubtitle.setText(subtitle);
        holder.tvSubtitle.setAlpha(1f);
        holder.lastSubtitleKind = computeSubtitleKind(item);

        String coverPath = item.getCoverPath();
        android.graphics.Bitmap bm = (coverPath != null && new File(coverPath).exists())
                ? loadCoverCached(coverPath) : null;
        if (bm != null) {
            holder.iv_cover.setImageBitmap(bm);
        } else {
            holder.iv_cover.setImageResource(R.drawable.ic_media);
        }
        // The wave view uses the same bitmap. When there is no cover, the fill wave stays empty.
        if (holder.vCoverWave != null) holder.vCoverWave.setCover(bm);

        // The veil only animates when the holder keeps showing the same song
        // (a real state change). A holder rebound to another song snaps to it.
        boolean sameSong = item.getTitle().equals(holder.boundKey);
        holder.boundKey = item.getTitle();
        applyCloudOverlay(holder, item.cloud, sameSong);

        holder.options.setOnClickListener(view -> openOptions(item));

        holder.itemView.setOnLongClickListener(view -> {
            openOptions(item);
            return true;
        });
    }

    /** Song options; a locked cloud row asks to sign in instead. */
    private void openOptions(SongListData item) {
        if (isLocked(item.cloud)) ParentActivity.promptLogin();
        else ParentActivity.showBottomDialog(item);
    }

    /** Cloud song with no local copy while signed out: it needs the server. */
    private boolean isLocked(CloudItem c) {
        return c != null && !c.hasLocalCopy() && c.state != null
                && !com.jotapeveloper.fision.cloud.AuthManager.get(context).isLoggedIn();
    }

    private String computeSubtitle(SongListData item) {
        CloudItem c = item.cloud;
        if (isLocked(c)) return context.getString(R.string.cloud_state_locked);
        if (c != null) {
            switch (c.state) {
                case QUEUED:
                    return c.queuePosition > 0
                            ? context.getString(R.string.cloud_state_queued_pos, c.queuePosition)
                            : context.getString(R.string.cloud_state_queued);
                case RUNNING:     return context.getString(R.string.cloud_state_running);
                case WAITING_GPU: return context.getString(R.string.cloud_state_waiting_gpu);
                case DOWNLOADING: return context.getString(R.string.cloud_state_downloading, c.downloadPct);
                case ERROR:       return c.hasLocalCopy()
                        ? context.getString(R.string.cloud_state_reprocess_failed)
                        : CloudRepository.errorText(context, c.errorCode);
                default: /* CLOUD_ONLY (the veil tells it), LOCAL_AND_CLOUD, LOCAL_ONLY: artist */ break;
            }
        }
        return item.getArtist() != null ? item.getArtist() : item.getSubtitle();
    }

    private void applyCloudOverlay(ViewHolder h, CloudItem c, boolean animate) {
        boolean locked = isLocked(c);
        // Ready on the server and not downloaded: the cloud says a tap downloads it.
        fadeView(h.ivCloud, !locked && c != null && c.state == CloudItem.State.CLOUD_ONLY, animate);
        if (h.ivLock != null) h.ivLock.setVisibility(locked ? View.VISIBLE : View.GONE);
        if (locked) {
            fadeDim(h, true, animate);
            h.pbSpinner.setVisibility(View.GONE);
            if (h.vCoverWave != null) h.vCoverWave.setVisibility(View.GONE);
            return;
        }
        // A failed reprocess keeps the previous stems: the row stays playable.
        if (c == null || c.state == CloudItem.State.LOCAL_AND_CLOUD
                      || c.state == CloudItem.State.LOCAL_ONLY
                      || (c.state == CloudItem.State.ERROR && c.hasLocalCopy())) {
            fadeDim(h, false, animate);
            h.pbSpinner.setVisibility(View.GONE);
            if (h.vCoverWave != null) h.vCoverWave.setVisibility(View.GONE);
            return;
        }
        switch (c.state) {
            case QUEUED:
            case RUNNING:
                fadeDim(h, true, animate);
                h.pbSpinner.setVisibility(View.VISIBLE);
                h.pbSpinner.setIndeterminateTintList(
                        android.content.res.ColorStateList.valueOf(
                                androidx.core.content.ContextCompat.getColor(
                                        context, R.color.AppWhite)));
                if (h.vCoverWave != null) h.vCoverWave.setVisibility(View.GONE);
                break;
            case WAITING_GPU:
                fadeDim(h, true, animate);
                h.pbSpinner.setVisibility(View.VISIBLE);
                h.pbSpinner.setIndeterminateTintList(
                        android.content.res.ColorStateList.valueOf(
                                androidx.core.content.ContextCompat.getColor(
                                        context, R.color.gpu_waiting_spinner)));
                if (h.vCoverWave != null) h.vCoverWave.setVisibility(View.GONE);
                break;
            case DOWNLOADING:
                fadeDim(h, false, animate);
                h.pbSpinner.setVisibility(View.GONE);
                if (h.vCoverWave != null) {
                    h.vCoverWave.setVisibility(View.VISIBLE);
                    h.vCoverWave.setProgress(Math.max(0, Math.min(100, c.downloadPct)) / 100f);
                }
                break;
            case CLOUD_ONLY:
            case ERROR:
            case CANCELLED:
                // Smooth fade into the dim (LOCAL_AND_CLOUD → CLOUD_ONLY after a local delete)
                fadeDim(h, true, animate);
                h.pbSpinner.setVisibility(View.GONE);
                if (h.vCoverWave != null) h.vCoverWave.setVisibility(View.GONE);
                break;
        }
    }

    private static android.graphics.Bitmap demoCover;

    /** Demo placeholder: dark cover with the download mark, or the filling
     *  wave and percentage while it downloads. */
    private void bindDemoState(ViewHolder h, SongListData item, boolean animate) {
        int pct = DemoSong.progress();
        boolean downloading = pct >= 0;
        String kind = downloading ? "downloading" : "meta";
        String sub = downloading ? context.getString(R.string.cloud_state_downloading, pct) : item.getArtist();
        if (kind.equals(h.lastSubtitleKind) || !animate) {
            h.tvSubtitle.animate().cancel();
            h.tvSubtitle.setAlpha(1f);
            h.tvSubtitle.setText(sub);
        } else {
            h.tvSubtitle.animate().cancel();
            h.tvSubtitle.animate().alpha(0f).setDuration(120).withEndAction(() -> {
                h.tvSubtitle.setText(sub);
                h.tvSubtitle.animate().alpha(1f).setDuration(180).start();
            }).start();
        }
        h.lastSubtitleKind = kind;
        if (h.ivLock != null) h.ivLock.setVisibility(View.GONE);
        h.pbSpinner.setVisibility(View.GONE);
        fadeView(h.ivCloud, !downloading, animate);
        fadeDim(h, !downloading, animate);
        if (h.vCoverWave != null) {
            h.vCoverWave.setVisibility(downloading ? View.VISIBLE : View.GONE);
            if (downloading) h.vCoverWave.setProgress(pct / 100f);
        }
    }

    /** Shows/hides the grey veil: smooth fade, or instant when not animating. */
    private static void fadeDim(ViewHolder h, boolean visible, boolean animate) {
        fadeView(h.vCoverDim, visible, animate);
    }

    /** Cover overlay in or out: smooth fade, or instant when not animating. */
    private static void fadeView(View v, boolean visible, boolean animate) {
        if (v == null) return;
        if (!animate) {
            v.animate().cancel();
            v.setAlpha(1f);
            v.setVisibility(visible ? View.VISIBLE : View.GONE);
            return;
        }
        if (visible) {
            if (v.getVisibility() == View.VISIBLE && v.getAlpha() >= 0.99f) return;
            v.animate().cancel();
            if (v.getVisibility() != View.VISIBLE) v.setAlpha(0f);
            v.setVisibility(View.VISIBLE);
            v.animate().alpha(1f).setDuration(220).start();
        } else {
            if (v.getVisibility() != View.VISIBLE) { v.setAlpha(1f); return; }
            v.animate().cancel();
            v.animate().alpha(0f).setDuration(180)
                    .withEndAction(() -> {
                        v.setVisibility(View.GONE);
                        v.setAlpha(1f);
                    }).start();
        }
    }


    @Override
    public int getItemCount() { return listData.length; }

    public class ViewHolder extends RecyclerView.ViewHolder implements View.OnClickListener{
        TextView tvTitle;
        TextView tvSubtitle;
        ImageButton options;
        /** Song this holder last showed (folder name or cloud hash). */
        String boundKey;
        ShapeableImageView iv_cover;
        View coverWrap;
        View vCoverDim;
        CoverFillWaveView vCoverWave;
        ProgressBar pbSpinner;
        View ivLock;
        View ivCloud;
        String lastSubtitleKind = "";

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvTitle    = itemView.findViewById(R.id.tvTitle);
            tvSubtitle = itemView.findViewById(R.id.tvSubtitle);
            options    = itemView.findViewById(R.id.options);
            iv_cover   = itemView.findViewById(R.id.iv_cover);
            coverWrap  = itemView.findViewById(R.id.fl_cover_wrap);
            vCoverDim  = itemView.findViewById(R.id.v_cover_dim);
            vCoverWave = itemView.findViewById(R.id.v_cover_wave);
            pbSpinner  = itemView.findViewById(R.id.pb_cover_spinner);
            ivLock     = itemView.findViewById(R.id.iv_cover_lock);
            ivCloud    = itemView.findViewById(R.id.iv_cover_cloud);
            itemView.setOnClickListener(this);
        }

        @Override
        public void onClick(View view) {
            int pos = getAdapterPosition();
            if (pos < 0 || pos >= listData.length) return;
            SongListData item = listData[pos];
            CloudItem c = item.cloud;

            if (item.demo) {
                if (!DemoSong.isDownloading()) ParentActivity.showDemoDownloadDialog();
                return;
            }
            if (isLocked(c)) {
                ParentActivity.promptLogin();
                return;
            }
            if (c != null) {
                if (c.state == CloudItem.State.CLOUD_ONLY) {
                    DownloadManagerLite.get(context).start(c.hash);
                    return;
                }
                if (c.isInFlight()) {
                    // Processing/queued/downloading/waiting-gpu: tap does nothing
                    return;
                }
                // Retryable error (GPU busy, time limit): reopen the retry dialog
                // so the user can act on items whose original popup was dismissed.
                // (with a local copy the song opens; the retry is in its options)
                if (c.state == CloudItem.State.ERROR && !c.hasLocalCopy()
                        && CloudRepository.isRetryableJobError(c.errorCode)) {
                    ParentActivity.showJobRetryDialog(c.hash);
                    return;
                }
            }
            openPlayer(pos);
        }

        public void openPlayer(int pos){
            MainActivity object = (MainActivity) context;
            File SongFolder = object.getSongFolder();
            File SongPath = new File(SongFolder, listData[pos].getTitle());
            PlayQueue.playFromLibrary();
            Intent player = new Intent(context, PlayerActivity.class);
            player.putExtra("SongPathString", SongPath.getAbsolutePath());
            player.putExtra("SongName", listData[pos].getTitle());
            context.startActivity(player);
        }
    }
}

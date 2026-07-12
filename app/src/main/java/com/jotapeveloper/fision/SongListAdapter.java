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
            applyCloudOverlay(holder, it.cloud);
            return;
        }
        onBindViewHolder(holder, position);
    }

    /** Subtitle "kind" used to decide when to animate a fade. Only changes when the
     *  type changes (not when the DOWNLOADING % rises). */
    private String computeSubtitleKind(SongListData item) {
        CloudItem c = item.cloud;
        if (c != null) {
            switch (c.state) {
                case QUEUED:      return "queued";
                case RUNNING:     return "running";
                case DOWNLOADING: return "downloading";
                case CLOUD_ONLY:  return "cloud_only";
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

        applyCloudOverlay(holder, item.cloud);

        holder.options.setOnClickListener(view ->
                ParentActivity.showBottomDialog(item));

        holder.itemView.setOnLongClickListener(view -> {
            ParentActivity.showBottomDialog(item);
            return true;
        });
    }

    private String computeSubtitle(SongListData item) {
        CloudItem c = item.cloud;
        if (c != null) {
            switch (c.state) {
                case QUEUED:
                    return c.queuePosition > 0
                            ? context.getString(R.string.cloud_state_queued_pos, c.queuePosition)
                            : context.getString(R.string.cloud_state_queued);
                case RUNNING:     return context.getString(R.string.cloud_state_running);
                case DOWNLOADING: return context.getString(R.string.cloud_state_downloading, c.downloadPct);
                case CLOUD_ONLY:  return context.getString(R.string.cloud_state_cloud_only);
                case ERROR:       return context.getString(R.string.cloud_state_error);
                default: /* LOCAL_AND_CLOUD, LOCAL_ONLY fall through to the normal subtitle */ break;
            }
        }
        return item.getArtist() != null ? item.getArtist() : item.getSubtitle();
    }

    private void applyCloudOverlay(ViewHolder h, CloudItem c) {
        if (c == null || c.state == CloudItem.State.LOCAL_AND_CLOUD
                      || c.state == CloudItem.State.LOCAL_ONLY) {
            fadeDim(h, false);
            h.pbSpinner.setVisibility(View.GONE);
            if (h.vCoverWave != null) h.vCoverWave.setVisibility(View.GONE);
            return;
        }
        switch (c.state) {
            case QUEUED:
            case RUNNING:
                fadeDim(h, true);
                h.pbSpinner.setVisibility(View.VISIBLE);
                if (h.vCoverWave != null) h.vCoverWave.setVisibility(View.GONE);
                break;
            case DOWNLOADING:
                fadeDim(h, false);
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
                fadeDim(h, true);
                h.pbSpinner.setVisibility(View.GONE);
                if (h.vCoverWave != null) h.vCoverWave.setVisibility(View.GONE);
                break;
        }
    }

    /** Animates the grey veil appearing/disappearing with a smooth fade. */
    private static void fadeDim(ViewHolder h, boolean visible) {
        if (h.vCoverDim == null) return;
        if (visible) {
            if (h.vCoverDim.getVisibility() == View.VISIBLE && h.vCoverDim.getAlpha() >= 0.99f) return;
            h.vCoverDim.animate().cancel();
            h.vCoverDim.setVisibility(View.VISIBLE);
            h.vCoverDim.animate().alpha(1f).setDuration(220).start();
        } else {
            if (h.vCoverDim.getVisibility() != View.VISIBLE) { h.vCoverDim.setAlpha(1f); return; }
            h.vCoverDim.animate().cancel();
            h.vCoverDim.animate().alpha(0f).setDuration(180)
                    .withEndAction(() -> {
                        h.vCoverDim.setVisibility(View.GONE);
                        h.vCoverDim.setAlpha(1f);
                    }).start();
        }
    }


    @Override
    public int getItemCount() { return listData.length; }

    public class ViewHolder extends RecyclerView.ViewHolder implements View.OnClickListener{
        TextView tvTitle;
        TextView tvSubtitle;
        ImageButton options;
        ShapeableImageView iv_cover;
        View coverWrap;
        View vCoverDim;
        CoverFillWaveView vCoverWave;
        ProgressBar pbSpinner;
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
            itemView.setOnClickListener(this);
        }

        @Override
        public void onClick(View view) {
            int pos = getAdapterPosition();
            if (pos < 0 || pos >= listData.length) return;
            SongListData item = listData[pos];
            CloudItem c = item.cloud;

            if (c != null) {
                if (c.state == CloudItem.State.CLOUD_ONLY) {
                    DownloadManagerLite.get(context).start(c.hash);
                    return;
                }
                if (c.isInFlight()) {
                    // Processing/queued/downloading: tap does nothing
                    return;
                }
            }
            openPlayer(pos);
        }

        public void openPlayer(int pos){
            MainActivity object = (MainActivity) context;
            File SongFolder = object.getSongFolder();
            File SongPath = new File(SongFolder, listData[pos].getTitle());
            Intent player = new Intent(context, PlayerActivity.class);
            player.putExtra("SongPathString", SongPath.getAbsolutePath());
            player.putExtra("SongName", listData[pos].getTitle());
            context.startActivity(player);
        }
    }
}

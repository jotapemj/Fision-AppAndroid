package com.jotapeveloper.fision;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.jotapeveloper.fision.cloud.PlaylistRepository;

import java.util.ArrayList;
import java.util.List;

/** Rows of the Playlists tab. */
public class PlaylistListAdapter extends RecyclerView.Adapter<PlaylistListAdapter.Holder> {

    public interface OnPlaylistClick {
        void onClick(PlaylistRepository.Playlist p);
        /** The row's options (⋮) button. */
        void onOptions(PlaylistRepository.Playlist p);
    }

    private final List<PlaylistRepository.Playlist> items = new ArrayList<>();
    private final OnPlaylistClick click;
    private final int coverPx;

    public PlaylistListAdapter(int coverPx, OnPlaylistClick click) {
        this.coverPx = coverPx;
        this.click = click;
        setHasStableIds(true);
    }

    public void submit(List<PlaylistRepository.Playlist> list) {
        items.clear();
        items.addAll(list);
        notifyDataSetChanged();
    }

    @Override public long getItemId(int position) {
        return items.get(position).id.hashCode();
    }

    @Override public int getItemCount() { return items.size(); }

    @NonNull @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_playlist, parent, false);
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        PlaylistRepository.Playlist p = items.get(position);
        h.title.setText(p.title);
        int n = p.songs.size();
        h.count.setText(h.itemView.getResources().getQuantityString(R.plurals.playlist_song_count, n, n));
        PlaylistCovers.load(h.cover, p, coverPx);
        h.itemView.setOnClickListener(v -> click.onClick(p));
        h.options.setOnClickListener(v -> click.onOptions(p));
    }

    static class Holder extends RecyclerView.ViewHolder {
        final ImageView cover;
        final TextView title, count;
        final View options;

        Holder(View v) {
            super(v);
            options = v.findViewById(R.id.ib_playlistOptions);
            cover = v.findViewById(R.id.iv_playlistCover);
            title = v.findViewById(R.id.tv_playlistTitle);
            count = v.findViewById(R.id.tv_playlistCount);
        }
    }
}

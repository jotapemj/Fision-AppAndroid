package com.jotapeveloper.fision;

import android.annotation.SuppressLint;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.jotapeveloper.fision.design.AccentColorManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Song rows of the playlist screens, in three modes:
 * VIEW (tap a song), EDIT (drag to reorder, remove) and PICK (check songs).
 */
public class PlaylistSongAdapter extends RecyclerView.Adapter<PlaylistSongAdapter.Holder> {

    public enum Mode { VIEW, EDIT, PICK }

    public interface Listener {
        /** VIEW: song tapped. PICK: selection toggled. */
        default void onSongClick(SongCatalog.Song song) {}
        /** EDIT: the list changed (removal or reorder). */
        default void onListChanged() {}
    }

    private final Mode mode;
    private final List<SongCatalog.Song> items = new ArrayList<>();
    private final Listener listener;
    private final int coverPx;
    @Nullable private Set<String> checked;
    @Nullable private ItemTouchHelper dragHelper;

    public PlaylistSongAdapter(Mode mode, int coverPx, Listener listener) {
        this.mode = mode;
        this.coverPx = coverPx;
        this.listener = listener;
    }

    public void submit(List<SongCatalog.Song> list) {
        items.clear();
        items.addAll(list);
        notifyDataSetChanged();
    }

    /** PICK: hashes currently checked (shared with the caller). */
    public void setChecked(@NonNull Set<String> checked) { this.checked = checked; }

    /** EDIT: songs in their current order. */
    public List<SongCatalog.Song> getItems() { return Collections.unmodifiableList(items); }

    /** EDIT: wires drag-to-reorder from the handle. */
    public void attachDrag(RecyclerView rv) {
        dragHelper = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
            @Override public boolean onMove(@NonNull RecyclerView r,
                                            @NonNull RecyclerView.ViewHolder from,
                                            @NonNull RecyclerView.ViewHolder to) {
                int a = from.getAdapterPosition(), b = to.getAdapterPosition();
                if (a < 0 || b < 0) return false;
                Collections.swap(items, a, b);
                notifyItemMoved(a, b);
                return true;
            }
            @Override public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int dir) { }
            @Override public boolean isLongPressDragEnabled() { return false; }
            @Override public void clearView(@NonNull RecyclerView r,
                                            @NonNull RecyclerView.ViewHolder vh) {
                super.clearView(r, vh);
                listener.onListChanged();
            }
        });
        dragHelper.attachToRecyclerView(rv);
    }

    @Override public int getItemCount() { return items.size(); }

    @NonNull @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_playlist_song, parent, false);
        return new Holder(v);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        SongCatalog.Song s = items.get(position);
        h.title.setText(s.title);
        boolean hasArtist = s.artist != null && !s.artist.isEmpty();
        h.artist.setText(hasArtist ? s.artist : "");
        h.artist.setVisibility(hasArtist ? View.VISIBLE : View.GONE);
        PlaylistCovers.loadSong(h.cover, s.coverPath, coverPx);
        // Not downloaded: same grey veil as in the song list.
        h.dim.setVisibility(s.local ? View.GONE : View.VISIBLE);
        h.lock.setVisibility(!s.local && !com.jotapeveloper.fision.cloud.AuthManager
                .get(h.itemView.getContext()).isLoggedIn() ? View.VISIBLE : View.GONE);

        h.drag.setVisibility(mode == Mode.EDIT ? View.VISIBLE : View.GONE);
        switch (mode) {
            case EDIT:
                h.action.setVisibility(View.VISIBLE);
                h.action.setImageResource(R.drawable.ic_close);
                h.action.setContentDescription(
                        h.itemView.getContext().getString(R.string.playlist_remove_song));
                h.action.setImageTintList(android.content.res.ColorStateList.valueOf(0xFF8A8A8A));
                h.action.setOnClickListener(v -> {
                    int pos = h.getAdapterPosition();
                    if (pos < 0) return;
                    items.remove(pos);
                    notifyItemRemoved(pos);
                    listener.onListChanged();
                });
                h.drag.setOnTouchListener((v, e) -> {
                    if (e.getActionMasked() == MotionEvent.ACTION_DOWN && dragHelper != null) {
                        dragHelper.startDrag(h);
                    }
                    return false;
                });
                h.itemView.setOnClickListener(null);
                h.itemView.setClickable(false);
                break;
            case PICK:
                boolean on = checked != null && checked.contains(s.hash);
                h.action.setVisibility(on ? View.VISIBLE : View.INVISIBLE);
                h.action.setImageResource(R.drawable.ic_check);
                h.action.setImageTintList(android.content.res.ColorStateList.valueOf(
                        AccentColorManager.resolveAccent(h.itemView.getContext())));
                h.action.setClickable(false);
                // Non-clickable child inherits the row's pressed state: no own ripple
                h.action.setBackground(null);
                h.itemView.setOnClickListener(v -> {
                    listener.onSongClick(s);
                    int pos = h.getAdapterPosition();
                    if (pos >= 0) notifyItemChanged(pos);
                });
                break;
            default:
                h.action.setVisibility(View.GONE);
                h.itemView.setOnClickListener(v -> listener.onSongClick(s));
                break;
        }
    }

    static class Holder extends RecyclerView.ViewHolder {
        final ImageView drag, cover;
        final View dim, lock;
        final TextView title, artist;
        final ImageButton action;

        Holder(View v) {
            super(v);
            lock = v.findViewById(R.id.iv_psCoverLock);
            drag = v.findViewById(R.id.iv_psDrag);
            cover = v.findViewById(R.id.iv_psCover);
            dim = v.findViewById(R.id.v_psCoverDim);
            title = v.findViewById(R.id.tv_psTitle);
            artist = v.findViewById(R.id.tv_psArtist);
            action = v.findViewById(R.id.ib_psAction);
        }
    }
}

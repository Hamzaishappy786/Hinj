package com.hinj.player.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.hinj.player.R;
import com.hinj.player.data.Song;

import java.util.Locale;

public class SongAdapter extends ListAdapter<Song, SongAdapter.SongViewHolder> {

    public interface OnSongClickListener {
        void onSongClick(Song song, int position);
    }

    private final OnSongClickListener listener;
    private String playingPath = null;

    public SongAdapter(OnSongClickListener listener) {
        super(DIFF_CALLBACK);
        this.listener = listener;
    }

    public void setPlayingPath(String path) {
        String old = this.playingPath;
        this.playingPath = path;
        notifyItemChangedByPath(old);
        notifyItemChangedByPath(path);
    }

    private void notifyItemChangedByPath(String path) {
        if (path == null) return;
        for (int i = 0; i < getCurrentList().size(); i++) {
            if (getCurrentList().get(i).filePath.equals(path)) {
                notifyItemChanged(i);
                return;
            }
        }
    }

    @NonNull
    @Override
    public SongViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_song, parent, false);
        return new SongViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull SongViewHolder holder, int position) {
        Song song = getItem(position);
        holder.title.setText(song.title);
        holder.subtitle.setText(String.format(Locale.getDefault(), "%s • %s",
                song.artist, formatDuration(song.durationMs)));
        holder.title.setSelected(song.filePath.equals(playingPath));

        Glide.with(holder.art.getContext())
                .load(song.albumArtUri)
                .placeholder(R.drawable.ic_music_note)
                .error(R.drawable.ic_music_note)
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .centerCrop()
                .into(holder.art);

        holder.itemView.setOnClickListener(v -> listener.onSongClick(song, holder.getBindingAdapterPosition()));
    }

    private String formatDuration(long ms) {
        long totalSec = ms / 1000;
        long min = totalSec / 60;
        long sec = totalSec % 60;
        return String.format(Locale.getDefault(), "%d:%02d", min, sec);
    }

    static class SongViewHolder extends RecyclerView.ViewHolder {
        final ImageView art;
        final TextView title;
        final TextView subtitle;

        SongViewHolder(@NonNull View itemView) {
            super(itemView);
            art = itemView.findViewById(R.id.songArt);
            title = itemView.findViewById(R.id.songTitle);
            subtitle = itemView.findViewById(R.id.songSubtitle);
        }
    }

    private static final DiffUtil.ItemCallback<Song> DIFF_CALLBACK = new DiffUtil.ItemCallback<Song>() {
        @Override
        public boolean areItemsTheSame(@NonNull Song oldItem, @NonNull Song newItem) {
            return oldItem.filePath.equals(newItem.filePath);
        }

        @Override
        public boolean areContentsTheSame(@NonNull Song oldItem, @NonNull Song newItem) {
            return oldItem.title.equals(newItem.title)
                    && oldItem.artist.equals(newItem.artist)
                    && oldItem.durationMs == newItem.durationMs;
        }
    };
}

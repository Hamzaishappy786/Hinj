package com.hinj.player.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.hinj.player.R;
import com.hinj.player.data.VideoItem;

import java.util.Locale;

public class VideoAdapter extends ListAdapter<VideoItem, VideoAdapter.VideoViewHolder> {

    public interface OnExtractClickListener {
        void onExtractClick(VideoItem video);
    }

    private final OnExtractClickListener listener;

    public VideoAdapter(OnExtractClickListener listener) {
        super(DIFF_CALLBACK);
        this.listener = listener;
    }

    @NonNull
    @Override
    public VideoViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_video, parent, false);
        return new VideoViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VideoViewHolder holder, int position) {
        VideoItem video = getItem(position);
        holder.title.setText(video.displayName);
        holder.subtitle.setText(formatDuration(video.durationMs));

        Glide.with(holder.thumb.getContext())
                .load(video.contentUri)
                .placeholder(R.drawable.ic_video)
                .error(R.drawable.ic_video)
                .centerCrop()
                .into(holder.thumb);

        View.OnClickListener open = v -> listener.onExtractClick(video);
        holder.itemView.setOnClickListener(open);
        holder.extractButton.setOnClickListener(open);
    }

    private String formatDuration(long ms) {
        long totalSec = ms / 1000;
        long min = totalSec / 60;
        long sec = totalSec % 60;
        return String.format(Locale.getDefault(), "%d:%02d", min, sec);
    }

    static class VideoViewHolder extends RecyclerView.ViewHolder {
        final ImageView thumb;
        final TextView title;
        final TextView subtitle;
        final ImageButton extractButton;

        VideoViewHolder(@NonNull View itemView) {
            super(itemView);
            thumb = itemView.findViewById(R.id.videoThumb);
            title = itemView.findViewById(R.id.videoTitle);
            subtitle = itemView.findViewById(R.id.videoSubtitle);
            extractButton = itemView.findViewById(R.id.btnExtract);
        }
    }

    private static final DiffUtil.ItemCallback<VideoItem> DIFF_CALLBACK = new DiffUtil.ItemCallback<VideoItem>() {
        @Override
        public boolean areItemsTheSame(@NonNull VideoItem oldItem, @NonNull VideoItem newItem) {
            return oldItem.sourceKey.equals(newItem.sourceKey);
        }

        @Override
        public boolean areContentsTheSame(@NonNull VideoItem oldItem, @NonNull VideoItem newItem) {
            return oldItem.displayName.equals(newItem.displayName)
                    && oldItem.durationMs == newItem.durationMs;
        }
    };
}

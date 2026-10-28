package com.hhst.youtubelite.cast;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.leanback.widget.BaseGridView;
import androidx.leanback.widget.HorizontalGridView;
import androidx.recyclerview.widget.RecyclerView;
import com.hhst.youtubelite.R;
import com.squareup.picasso.Picasso;
import java.util.List;

/** Video cards and rows of cards for the TV screens. */
final class TvCards {
    private TvCards() {}
    interface Listener {
        void open(TvVideo video);
        void focused(TvVideo video);
    }
    record Row(String title, List<TvVideo> items) {}

    /** Keeps the focused item at the start edge, as YouTube on TV does. */
    static void alignStart(BaseGridView grid) {
        grid.setWindowAlignment(BaseGridView.WINDOW_ALIGN_BOTH_EDGE);
        grid.setWindowAlignmentOffset(0);
        grid.setWindowAlignmentOffsetPercent(0f);
        grid.setItemAlignmentOffset(0);
        grid.setItemAlignmentOffsetPercent(0f);
    }

    static final class CardAdapter extends RecyclerView.Adapter<CardHolder> {
        private final List<TvVideo> items;
        private final Listener listener;
        CardAdapter(List<TvVideo> items, Listener listener) { this.items = items; this.listener = listener; }
        @NonNull @Override public CardHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            return new CardHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.tv_card, parent, false));
        }
        @Override public void onBindViewHolder(@NonNull CardHolder holder, int position) { holder.bind(items.get(position), listener); }
        @Override public int getItemCount() { return items.size(); }
    }

    static final class CardHolder extends RecyclerView.ViewHolder {
        private final ImageView thumb;
        private final TextView badge, title, channel;
        CardHolder(View view) {
            super(view);
            thumb = view.findViewById(R.id.tv_card_thumb);
            badge = view.findViewById(R.id.tv_card_badge);
            title = view.findViewById(R.id.tv_card_title);
            channel = view.findViewById(R.id.tv_card_channel);
            view.findViewById(R.id.tv_card_frame).setClipToOutline(true);
        }
        void bind(TvVideo video, Listener listener) {
            title.setText(video.title());
            channel.setText(video.meta().isEmpty() ? video.channel() : video.channel() + " • " + video.meta());
            if (video.live()) { badge.setVisibility(View.VISIBLE); badge.setText("LIVE"); badge.setBackgroundResource(R.drawable.tv_live_bg); }
            else if (video.durationSec() > 0) { badge.setVisibility(View.VISIBLE); badge.setText(TvVideo.clock(video.durationSec())); badge.setBackgroundResource(R.drawable.tv_duration_bg); }
            else badge.setVisibility(View.GONE);
            thumb.setImageDrawable(null);
            Picasso.get().load(video.thumbnail()).resize(448, 252).centerCrop().into(thumb);
            itemView.setOnClickListener(v -> listener.open(video));
            itemView.setOnFocusChangeListener((v, focused) -> {
                v.animate().scaleX(focused ? 1.06f : 1f).scaleY(focused ? 1.06f : 1f).translationZ(focused ? 12f : 0f).setDuration(150).start();
                if (focused) listener.focused(video);
            });
        }
    }

    static final class RowAdapter extends RecyclerView.Adapter<RowHolder> {
        private final List<Row> rows;
        private final Listener listener;
        RowAdapter(List<Row> rows, Listener listener) { this.rows = rows; this.listener = listener; }
        @NonNull @Override public RowHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            return new RowHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.tv_row, parent, false));
        }
        @Override public void onBindViewHolder(@NonNull RowHolder holder, int position) {
            Row row = rows.get(position);
            holder.title.setText(row.title());
            holder.items.setAdapter(new CardAdapter(row.items(), listener));
        }
        @Override public int getItemCount() { return rows.size(); }
    }

    static final class RowHolder extends RecyclerView.ViewHolder {
        final TextView title;
        final HorizontalGridView items;
        RowHolder(View view) {
            super(view);
            title = view.findViewById(R.id.tv_row_title);
            items = view.findViewById(R.id.tv_row_items);
            alignStart(items);
            // Room for the focused card's zoom, which the row would otherwise clip.
            int inset = Math.round(16 * view.getResources().getDisplayMetrics().density);
            items.setPadding(inset, items.getPaddingTop(), inset, items.getPaddingBottom());
            items.setWindowAlignmentOffset(inset);
        }
    }
}

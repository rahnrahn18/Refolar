package com.media.camera.preview.adapter;

import android.graphics.Color;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.media.camera.preview.R;

import java.util.List;

public class FilterAdapter extends RecyclerView.Adapter<FilterAdapter.ViewHolder> {

    public static class FilterItem {
        String name;
        int id; // 0, 1, 2, 3
        int color; // Placeholder color for icon

        public FilterItem(String name, int id, int color) {
            this.name = name;
            this.id = id;
            this.color = color;
        }
    }

    private List<FilterItem> mItems;
    private final OnFilterSelectedListener mListener;

    public interface OnFilterSelectedListener {
        void onFilterSelected(int filterId);
    }

    public FilterAdapter(List<FilterItem> items, OnFilterSelectedListener listener) {
        mItems = items;
        mListener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_filter, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        FilterItem item = mItems.get(position);
        holder.name.setText(item.name);
        holder.icon.setBackgroundColor(item.color); // Simple placeholder
        holder.itemView.setOnClickListener(v -> mListener.onFilterSelected(item.id));
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView icon;
        TextView name;

        ViewHolder(View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.filter_icon);
            name = itemView.findViewById(R.id.filter_name);
        }
    }
}

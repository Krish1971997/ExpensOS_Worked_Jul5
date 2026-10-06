package com.expenseos.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.expenseos.R;
import com.expenseos.model.SearchResult;

import java.util.List;

/** Renders the grouped global-search list (headers + result rows). */
public class SearchResultAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public interface OnResultClick {
        void onResultClick(SearchResult r);
    }

    private final List<SearchResult> items;
    private final OnResultClick listener;

    public SearchResultAdapter(List<SearchResult> items, OnResultClick listener) {
        this.items = items;
        this.listener = listener;
    }

    @Override
    public int getItemViewType(int position) {
        return items.get(position).viewType;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inf = LayoutInflater.from(parent.getContext());
        if (viewType == SearchResult.TYPE_HEADER) {
            return new HeaderVH(inf.inflate(R.layout.item_search_header, parent, false));
        }
        return new RowVH(inf.inflate(R.layout.item_search_result, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        SearchResult r = items.get(position);

        if (holder instanceof HeaderVH) {
            ((HeaderVH) holder).tvHeader.setText(r.group);
            return;
        }

        RowVH h = (RowVH) holder;
        h.tvTitle.setText(r.title);
        if (r.subtitle == null || r.subtitle.isEmpty()) {
            h.tvSubtitle.setVisibility(View.GONE);
        } else {
            h.tvSubtitle.setVisibility(View.VISIBLE);
            h.tvSubtitle.setText(r.subtitle);
        }
        h.tvBadge.setText(r.badge == null ? "" : r.badge);
        h.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onResultClick(r);
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class HeaderVH extends RecyclerView.ViewHolder {
        final TextView tvHeader;

        HeaderVH(@NonNull View v) {
            super(v);
            tvHeader = v.findViewById(R.id.tvSearchGroup);
        }
    }

    static class RowVH extends RecyclerView.ViewHolder {
        final TextView tvTitle, tvSubtitle, tvBadge;

        RowVH(@NonNull View v) {
            super(v);
            tvTitle = v.findViewById(R.id.tvSearchTitle);
            tvSubtitle = v.findViewById(R.id.tvSearchSubtitle);
            tvBadge = v.findViewById(R.id.tvSearchBadge);
        }
    }
}

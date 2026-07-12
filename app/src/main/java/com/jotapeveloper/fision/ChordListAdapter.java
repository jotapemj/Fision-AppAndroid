package com.jotapeveloper.fision;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

public class ChordListAdapter extends RecyclerView.Adapter<ChordListAdapter.ViewHolder> {

    Context context;
    public ChordListData[] chordListData;

    public ChordListAdapter(Context context, ChordListData[] chordListData){
        this.context = context;
        this.chordListData = chordListData;
    }


    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.chord_list_item, parent, false);
        return new ViewHolder(view);
    }


    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.tv_chord.setText(chordListData[position].getChord());
    }

    @Override
    public int getItemCount() {
        return chordListData.length;
    }

    public class ViewHolder extends RecyclerView.ViewHolder {
        TextView tv_chord;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            tv_chord = itemView.findViewById(R.id.tv_chord);
        }
    }
}
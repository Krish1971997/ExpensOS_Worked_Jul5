package com.expenseos.ui;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.expenseos.R;
import com.expenseos.adapter.TransactionAdapter;
import com.expenseos.dao.TransactionDao;
import com.expenseos.db.LocalDB;
import com.expenseos.model.Transaction;

import java.math.BigDecimal;
import java.util.List;

public class DashboardFragment extends Fragment {

    private int bookId;
    private TransactionDao dao;

    @Override
    public View onCreateView(@NonNull LayoutInflater inf, ViewGroup pg, Bundle s) {
        return inf.inflate(R.layout.fragment_dashboard, pg, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle s) {
        super.onViewCreated(v, s);

        SharedPreferences prefs = requireContext()
                .getSharedPreferences("expenseos_prefs", android.content.Context.MODE_PRIVATE);
        bookId = prefs.getInt("active_book_id", 0);
        dao = new TransactionDao(requireContext());

        // Add buttons
        v.findViewById(R.id.btnAddIncomeDash).setOnClickListener(x -> {
            Intent i = new Intent(requireContext(), TransactionActivity.class);
            i.putExtra("type", "INCOME");
            startActivity(i);
        });
        v.findViewById(R.id.btnAddExpenseDash).setOnClickListener(x -> {
            Intent i = new Intent(requireContext(), TransactionActivity.class);
            i.putExtra("type", "EXPENSE");
            startActivity(i);
        });

        // View all transactions
        v.findViewById(R.id.btnViewAll).setOnClickListener(x ->
                startActivity(new Intent(requireContext(), TransactionActivity.class)));

        loadData(v);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (getView() != null) loadData(getView());
    }

    private void loadData(View v) {
        BigDecimal income = dao.sumByType("INCOME", bookId);
        BigDecimal expense = dao.sumByType("EXPENSE", bookId);
        BigDecimal balance = income.subtract(expense);

        ((TextView) v.findViewById(R.id.tvDashIncome)).setText("₹" + String.format("%,.2f", income));
        ((TextView) v.findViewById(R.id.tvDashExpense)).setText("₹" + String.format("%,.2f", expense));
        ((TextView) v.findViewById(R.id.tvDashBalance)).setText("₹" + String.format("%,.2f", balance));

        // Pending sync badge
        int unsynced = getUnsynced();
        TextView tvSync = v.findViewById(R.id.tvSyncStatus);
        tvSync.setVisibility(unsynced > 0 ? View.VISIBLE : View.GONE);
        tvSync.setText("⚠ " + unsynced + " pending sync");

        // Recent 5 transactions
        List<Transaction> recent = dao.findAll(null, 1, 5, bookId);
        RecyclerView rv = v.findViewById(R.id.rvDashRecent);
        rv.setLayoutManager(new LinearLayoutManager(requireContext()));
        rv.setAdapter(new TransactionAdapter(requireContext(), recent, null, txn -> {
            Intent i = new Intent(requireContext(), TransactionActivity.class);
            i.putExtra("id", txn.getId());
            startActivity(i);
        }));
        rv.setNestedScrollingEnabled(false);
    }

    private int getUnsynced() {
        android.database.Cursor c = LocalDB.getInstance(requireContext())
                .getReadableDatabase()
                .rawQuery("SELECT COUNT(*) FROM transactions WHERE synced=0 AND book_id=?",
                        new String[]{String.valueOf(bookId)});
        int cnt = 0;
        if (c.moveToFirst()) cnt = c.getInt(0);
        c.close();
        return cnt;
    }
}
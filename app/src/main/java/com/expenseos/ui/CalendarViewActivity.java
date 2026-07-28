package com.expenseos.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.expenseos.R;
import com.expenseos.adapter.TransactionAdapter;
import com.expenseos.dao.TransactionDao;
import com.expenseos.model.Transaction;
import com.expenseos.model.TransactionFilter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Month calendar view for the CURRENT cashbook only (scoped by the bookId
 * extra, matching how the rest of the app scopes screens to the active
 * book). Each day shows its income/expense totals; tapping a day opens
 * that day's transaction list in a dialog.
 */
public class CalendarViewActivity extends AppCompatActivity {

    private int bookId;
    private TransactionDao txnDao;
    private YearMonth currentMonth;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_calendar_view);

        bookId = getIntent().getIntExtra("bookId", 0);
        if (bookId <= 0) {
            finish();
            return;
        }

        txnDao = new TransactionDao(this);
        currentMonth = YearMonth.now();

        findViewById(R.id.btnCalBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnCalPrevMonth).setOnClickListener(v -> {
            currentMonth = currentMonth.minusMonths(1);
            loadMonth();
        });
        findViewById(R.id.btnCalNextMonth).setOnClickListener(v -> {
            currentMonth = currentMonth.plusMonths(1);
            loadMonth();
        });

        loadMonth();
    }

    private void loadMonth() {
        ((TextView) findViewById(R.id.tvCalMonth)).setText(
                currentMonth.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + currentMonth.getYear());

        List<Map<String, Object>> daily = txnDao.dailyTotals(currentMonth.getYear(), currentMonth.getMonthValue(), bookId);

        Map<LocalDate, BigDecimal[]> byDate = new HashMap<>(); // [0]=income [1]=expense
        BigDecimal monthIncome = BigDecimal.ZERO, monthExpense = BigDecimal.ZERO;
        for (Map<String, Object> row : daily) {
            LocalDate d = LocalDate.parse((String) row.get("day"));
            BigDecimal income = (BigDecimal) row.get("income");
            BigDecimal expense = (BigDecimal) row.get("expense");
            if (income == null) income = BigDecimal.ZERO;
            if (expense == null) expense = BigDecimal.ZERO;
            byDate.put(d, new BigDecimal[]{income, expense});
            monthIncome = monthIncome.add(income);
            monthExpense = monthExpense.add(expense);
        }

        ((TextView) findViewById(R.id.tvCalMonthIncome)).setText("₹" + monthIncome.toPlainString());
        ((TextView) findViewById(R.id.tvCalMonthExpense)).setText("₹" + monthExpense.toPlainString());
        BigDecimal net = monthIncome.subtract(monthExpense);
        TextView tvNet = findViewById(R.id.tvCalMonthNet);
        tvNet.setText((net.signum() < 0 ? "-₹" : "₹") + net.abs().toPlainString());
        tvNet.setTextColor(getColor(net.signum() < 0 ? R.color.red : R.color.primary));

        // Build the grid: leading blanks so day 1 lands in the correct
        // weekday column (Sunday-first), then one cell per day, then
        // trailing blanks to complete the last row.
        List<LocalDate> cells = new ArrayList<>();
        LocalDate first = currentMonth.atDay(1);
        int leading = first.getDayOfWeek().getValue() % 7; // Mon=1..Sun=7 -> Sun=0..Sat=6
        for (int i = 0; i < leading; i++) cells.add(null);
        for (int d = 1; d <= currentMonth.lengthOfMonth(); d++) cells.add(currentMonth.atDay(d));
        while (cells.size() % 7 != 0) cells.add(null);

        RecyclerView rv = findViewById(R.id.rvCalendarDays);
        rv.setLayoutManager(new GridLayoutManager(this, 7));
        rv.setAdapter(new CalendarDayAdapter(cells, byDate));
    }

    class CalendarDayAdapter extends RecyclerView.Adapter<CalendarDayAdapter.VH> {
        private final List<LocalDate> cells;
        private final Map<LocalDate, BigDecimal[]> byDate;

        CalendarDayAdapter(List<LocalDate> cells, Map<LocalDate, BigDecimal[]> byDate) {
            this.cells = cells;
            this.byDate = byDate;
        }

        class VH extends RecyclerView.ViewHolder {
            TextView tvNum, tvIncome, tvExpense;

            VH(View v) {
                super(v);
                tvNum = v.findViewById(R.id.tvDayNumber);
                tvIncome = v.findViewById(R.id.tvDayIncome);
                tvExpense = v.findViewById(R.id.tvDayExpense);
            }
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_calendar_day, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            LocalDate date = cells.get(pos);
            if (date == null) {
                h.tvNum.setText("");
                h.tvIncome.setVisibility(View.GONE);
                h.tvExpense.setVisibility(View.GONE);
                h.itemView.setOnClickListener(null);
                h.itemView.setClickable(false);
                return;
            }

            h.tvNum.setText(String.valueOf(date.getDayOfMonth()));
            boolean isToday = date.equals(LocalDate.now());
            h.tvNum.setBackgroundResource(isToday ? R.drawable.bg_today_circle : 0);
            h.tvNum.setTextColor(getColor(isToday ? android.R.color.white : R.color.text));

            BigDecimal[] amounts = byDate.get(date);
            if (amounts != null && amounts[0].signum() > 0) {
                h.tvIncome.setVisibility(View.VISIBLE);
                h.tvIncome.setText("+" + amounts[0].toPlainString());
            } else {
                h.tvIncome.setVisibility(View.GONE);
            }
            if (amounts != null && amounts[1].signum() > 0) {
                h.tvExpense.setVisibility(View.VISIBLE);
                h.tvExpense.setText("-" + amounts[1].toPlainString());
            } else {
                h.tvExpense.setVisibility(View.GONE);
            }

            h.itemView.setClickable(true);
            h.itemView.setOnClickListener(v -> showDayTransactions(date));
        }

        @Override
        public int getItemCount() {
            return cells.size();
        }
    }

    // ── Tap a day → show its transactions ─────────────────
    private void showDayTransactions(LocalDate date) {
        TransactionFilter f = new TransactionFilter();
        f.setBookId(bookId);
        f.setDateFrom(date);
        f.setDateTo(date);
        f.setPageSize(Integer.MAX_VALUE);
        List<Transaction> txns = txnDao.findByFilter(f);

        View view = LayoutInflater.from(this).inflate(R.layout.dialog_day_transactions, null);
        ((TextView) view.findViewById(R.id.tvDayDialogTitle))
                .setText(date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + ", "
                        + date.getDayOfMonth() + " " + date.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
                        + " " + date.getYear());

        RecyclerView rv = view.findViewById(R.id.rvDayTransactions);
        View emptyView = view.findViewById(R.id.tvDayEmpty);

        if (txns.isEmpty()) {
            rv.setVisibility(View.GONE);
            emptyView.setVisibility(View.VISIBLE);
        } else {
            rv.setLayoutManager(new LinearLayoutManager(this));
            rv.setAdapter(new TransactionAdapter(this, txns, null, txn -> {
                android.content.Intent i = new android.content.Intent(this, EntryDetailActivity.class);
                i.putExtra("txnId", txn.getId());
                startActivity(i);
            }));
        }

        new AlertDialog.Builder(this)
                .setView(view)
                .setPositiveButton("Close", null)
                .show();
    }
}

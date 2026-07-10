package com.expenseos.ui.books;

import android.app.AlertDialog;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.expenseos.MainActivity;
import com.expenseos.R;
import com.expenseos.adapter.BookAdapter;
import com.expenseos.dao.LocalDatabase;
import com.expenseos.model.CashBook;
import com.expenseos.util.AppConfig;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public class BooksFragment extends Fragment {

    private RecyclerView rvBooks;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_books, container, false);
        rvBooks = root.findViewById(R.id.rv_books);
        rvBooks.setLayoutManager(new LinearLayoutManager(getContext()));

        root.findViewById(R.id.btn_new_book).setOnClickListener(v -> showNewBookDialog());

        loadBooks();
        return root;
    }

    private void loadBooks() {
        List<CashBook> books = new ArrayList<>();
        SQLiteDatabase db = LocalDatabase.get(requireContext()).getReadableDatabase();
        Cursor c = db.rawQuery("SELECT id, name, description, created_at FROM cash_books", null);
        while (c.moveToNext()) {
            CashBook b = new CashBook();
            b.setId(c.getInt(0));
            b.setName(c.getString(1));
            b.setDescription(c.getString(2));
            b.setCreatedAt(c.getString(3));

            // Calculate totals
            Cursor inc = db.rawQuery(
                    "SELECT SUM(amount) FROM transactions WHERE book_id=? AND type='INCOME' AND is_deleted=0",
                    new String[]{String.valueOf(b.getId())});
            if (inc.moveToFirst() && !inc.isNull(0))
                b.setTotalIncome(BigDecimal.valueOf(inc.getDouble(0)));
            inc.close();

            Cursor exp = db.rawQuery(
                    "SELECT SUM(amount) FROM transactions WHERE book_id=? AND type='EXPENSE' AND is_deleted=0",
                    new String[]{String.valueOf(b.getId())});
            if (exp.moveToFirst() && !exp.isNull(0))
                b.setTotalExpense(BigDecimal.valueOf(exp.getDouble(0)));
            exp.close();

            books.add(b);
        }
        c.close();

        int activeId = AppConfig.get(requireContext()).getActiveBookId();
        BookAdapter adapter = new BookAdapter(books, activeId, book -> {
            AppConfig.get(requireContext()).setActiveBook(book.getId(), book.getName());
            if (getActivity() instanceof MainActivity)
                ((MainActivity) getActivity()).updateBookLabel();
            Toast.makeText(getContext(), "Switched to: " + book.getName(), Toast.LENGTH_SHORT).show();
            loadBooks();
        });
        rvBooks.setAdapter(adapter);
    }

    private void showNewBookDialog() {
        AlertDialog.Builder b = new AlertDialog.Builder(requireContext());
        View v = LayoutInflater.from(getContext()).inflate(R.layout.dialog_new_book, null);
        b.setView(v).setTitle("New Cash Book");
        EditText etName = v.findViewById(R.id.et_book_name);
        EditText etDesc = v.findViewById(R.id.et_book_desc);
        b.setPositiveButton("Create", (d, w) -> {
            String name = etName.getText().toString().trim();
            if (name.isEmpty()) {
                Toast.makeText(getContext(), "Name is required", Toast.LENGTH_SHORT).show();
                return;
            }
            android.content.ContentValues cv = new android.content.ContentValues();
            cv.put("name", name);
            cv.put("description", etDesc.getText().toString().trim());
            cv.put("created_at", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                    java.util.Locale.getDefault()).format(new java.util.Date()));
            LocalDatabase.get(requireContext()).getWritableDatabase()
                    .insert("cash_books", null, cv);
            Toast.makeText(getContext(), "Book created (sync to push to cloud)", Toast.LENGTH_SHORT).show();
            loadBooks();
        });
        b.setNegativeButton("Cancel", null).show();
    }
}

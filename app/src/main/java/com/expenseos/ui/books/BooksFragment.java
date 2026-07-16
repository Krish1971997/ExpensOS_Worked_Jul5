package com.expenseos.ui.books;

import android.app.AlertDialog;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.expenseos.R;
import com.expenseos.adapter.BookAdapter;
import com.expenseos.dao.LocalDatabase;
import com.expenseos.model.CashBook;
import com.expenseos.ui.MainActivity;
import com.expenseos.util.AppConfig;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
        Cursor c = db.rawQuery(
                "SELECT id, name, description, created_at, updated_at, is_active FROM cash_books", null);
        while (c.moveToNext()) {
            CashBook b = new CashBook();
            b.setId(c.getInt(0));
            b.setName(c.getString(1));
            b.setDescription(c.getString(2));
            b.setCreatedAt(c.getString(3));
            b.setUpdatedAt(c.isNull(4) ? null : c.getString(4));
            b.setActive(c.isNull(5) || c.getInt(5) == 1);

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
        BookAdapter adapter = new BookAdapter(books, activeId,
                book -> {
                    if (!book.isActive()) {
                        Toast.makeText(getContext(), "That book is inactive. Activate it from Edit before opening.",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    AppConfig.get(requireContext()).setActiveBook(book.getId(), book.getName());
                    if (getActivity() instanceof MainActivity)
                        ((MainActivity) getActivity()).updateBookLabel();
                    Toast.makeText(getContext(), "Switched to: " + book.getName(), Toast.LENGTH_SHORT).show();
                    loadBooks();
                },
                this::showEditBookDialog,
                this::showDeleteBookDialog);   // <-- 4th param, new
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
            ContentValues cv = new ContentValues();
            cv.put("name", name);
            cv.put("description", etDesc.getText().toString().trim());
            cv.put("created_at", nowTs());
            cv.put("is_active", 1);
            long newId = LocalDatabase.get(requireContext()).getWritableDatabase()
                    .insert("cash_books", null, cv);
            AppConfig.get(requireContext()).setActiveBook((int) newId, name);
            Toast.makeText(getContext(), "Book created (sync to push to cloud)", Toast.LENGTH_SHORT).show();
            loadBooks();
        });
        b.setNegativeButton("Cancel", null).show();
    }

    /**
     * Mirrors the web app's Edit Book modal (dialog_edit_book.xml: name, description, active toggle).
     */
    private void showEditBookDialog(CashBook book) {
        AlertDialog.Builder b = new AlertDialog.Builder(requireContext());
        View v = LayoutInflater.from(getContext()).inflate(R.layout.dialog_edit_book, null);
        b.setView(v).setTitle("Edit Cash Book");

        EditText etName = v.findViewById(R.id.etEditBookName);
        EditText etDesc = v.findViewById(R.id.etEditBookDesc);
        Switch swActive = v.findViewById(R.id.swEditBookActive);

        etName.setText(book.getName());
        etDesc.setText(book.getDescription());
        swActive.setChecked(book.isActive());

        b.setPositiveButton("Save Changes", (d, w) -> {
            String name = etName.getText().toString().trim();
            if (name.isEmpty()) {
                Toast.makeText(getContext(), "Name is required", Toast.LENGTH_SHORT).show();
                return;
            }
            boolean active = swActive.isChecked();

            ContentValues cv = new ContentValues();
            cv.put("name", name);
            cv.put("description", etDesc.getText().toString().trim());
            cv.put("is_active", active ? 1 : 0);
            cv.put("updated_at", nowTs());
            LocalDatabase.get(requireContext()).getWritableDatabase()
                    .update("cash_books", cv, "id=?", new String[]{String.valueOf(book.getId())});

            // If the book being edited is the currently active (open) one, keep AppConfig in sync
            AppConfig cfg = AppConfig.get(requireContext());
            if (cfg.getActiveBookId() == book.getId()) {
                if (!active) {
                    cfg.setActiveBook(0, null); // just turned inactive → force reselect, mirrors CashBookServlet
                } else {
                    cfg.setActiveBook(book.getId(), name);
                    if (getActivity() instanceof MainActivity)
                        ((MainActivity) getActivity()).updateBookLabel();
                }
            }

            Toast.makeText(getContext(), "Saved!", Toast.LENGTH_SHORT).show();
            loadBooks();
        });
        b.setNegativeButton("Cancel", null).show();
    }

    private String nowTs() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new java.util.Date());
    }

    private void showDeleteBookDialog(CashBook book) {
        AlertDialog.Builder b = new AlertDialog.Builder(requireContext());
        View v = LayoutInflater.from(getContext()).inflate(R.layout.dialog_delete_book, null);
        b.setView(v).setTitle("Delete " + book.getName() + " ?");

        TextView tvMsg = v.findViewById(R.id.tvDeleteConfirmMsg);
        EditText etConfirm = v.findViewById(R.id.etDeleteBookName);
        tvMsg.setText("Please type " + book.getName() + " to confirm");

        AlertDialog dialog = b.setPositiveButton("Delete", null)
                .setNegativeButton("Cancel", null)
                .create();

        dialog.setOnShowListener(d -> {
            android.widget.Button deleteBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            deleteBtn.setEnabled(false);

            etConfirm.addTextChangedListener(new android.text.TextWatcher() {
                public void afterTextChanged(android.text.Editable s) {
                    deleteBtn.setEnabled(s.toString().equals(book.getName()));
                }

                public void beforeTextChanged(CharSequence s, int a, int c, int cn) {
                }

                public void onTextChanged(CharSequence s, int a, int b2, int c) {
                }
            });

            deleteBtn.setOnClickListener(view -> {
                new com.expenseos.dao.CashBookDao(requireContext()).deleteCascade(book.getId());

                AppConfig cfg = AppConfig.get(requireContext());
                if (cfg.getActiveBookId() == book.getId()) {
                    cfg.setActiveBook(0, null);
                    if (getActivity() instanceof MainActivity)
                        ((MainActivity) getActivity()).updateBookLabel();
                }

                Toast.makeText(getContext(), "Book deleted", Toast.LENGTH_SHORT).show();
                dialog.dismiss();
                loadBooks();
            });
        });

        dialog.show();
    }
}
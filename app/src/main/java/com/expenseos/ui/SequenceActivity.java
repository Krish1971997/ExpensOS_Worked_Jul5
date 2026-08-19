package com.expenseos.ui;

import android.app.AlertDialog;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.text.InputType;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.expenseos.R;
import com.expenseos.db.LocalDB;

import java.util.ArrayList;
import java.util.List;

public class SequenceActivity extends AppCompatActivity implements SequenceAdapter.Listener {

    private LocalDB localDB;
    private SequenceAdapter adapter;
    private final List<SequenceAdapter.SequenceRow> rows = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sequence);

        localDB = LocalDB.getInstance(this);

        RecyclerView rv = findViewById(R.id.rvSequences);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.addItemDecoration(new DividerItemDecoration(this, DividerItemDecoration.VERTICAL));

        adapter = new SequenceAdapter(rows, this);
        rv.setAdapter(adapter);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnResyncAll).setOnClickListener(v -> resyncAll());

        loadRows();
    }

    private void loadRows() {
        rows.clear();
        SQLiteDatabase db = localDB.getReadableDatabase();
        try (Cursor c = db.rawQuery(
                "SELECT table_name, next_id FROM id_sequences ORDER BY table_name", null)) {
            while (c.moveToNext()) {
                rows.add(new SequenceAdapter.SequenceRow(
                        c.getString(0), c.getLong(1)));
            }
        }
        adapter.notifyDataSetChanged();
    }

    @Override
    public void onEdit(String tableName, long currentNextId) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(currentNextId));
        input.setSelection(input.getText().length());

        new AlertDialog.Builder(this)
                .setTitle("Edit next_id — " + tableName)
                .setView(input)
                .setPositiveButton("Save", (d, w) -> {
                    String val = input.getText().toString().trim();
                    if (val.isEmpty()) return;
                    long newNextId;
                    try {
                        newNextId = Long.parseLong(val);
                    } catch (NumberFormatException e) {
                        Toast.makeText(this, "Enter a valid number", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    updateNextId(tableName, newNextId);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void updateNextId(String tableName, long newNextId) {
        SQLiteDatabase wdb = localDB.getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("next_id", newNextId);
        int rowsUpdated = wdb.update("id_sequences", cv, "table_name=?", new String[]{tableName});
        if (rowsUpdated > 0) {
            adapter.updateRow(tableName, newNextId);
            Toast.makeText(this, tableName + " → next_id set to " + newNextId, Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "Update failed", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onResync(String tableName) {
        localDB.resyncSequences(tableName);
        loadRows();
        Toast.makeText(this, tableName + " resynced from data", Toast.LENGTH_SHORT).show();
    }

    private void resyncAll() {
        String[] tables = new String[rows.size()];
        for (int i = 0; i < rows.size(); i++) tables[i] = rows.get(i).tableName;
        localDB.resyncSequences(tables);
        loadRows();
        Toast.makeText(this, "All sequences resynced", Toast.LENGTH_SHORT).show();
    }
}
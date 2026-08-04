package com.expenseos.dao;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.expenseos.db.LocalDB;
import com.expenseos.model.PaymentType;

import java.util.ArrayList;
import java.util.List;

public class PaymentTypeDao {
    private final LocalDB helper;
    private final SQLiteDatabase db;

    public PaymentTypeDao(Context ctx) {
        helper = LocalDB.getInstance(ctx);
        db = helper.getWritableDatabase();
    }

    public List<PaymentType> findAll() {
        List<PaymentType> list = new ArrayList<>();
        try (Cursor c = db.rawQuery("SELECT id, name FROM payment_types ORDER BY name", null)) {
            while (c.moveToNext()) list.add(new PaymentType(c.getInt(0), c.getString(1)));
        }
        return list;
    }

    public void insert(String name) {
        long id = helper.getNextId("payment_types");
        ContentValues cv = new ContentValues();
        cv.put("id", id);
        cv.put("name", name.trim());
        db.insertWithOnConflict("payment_types", null, cv, SQLiteDatabase.CONFLICT_IGNORE);
    }

    public void update(int id, String newName) {
        ContentValues cv = new ContentValues();
        cv.put("name", newName.trim());
        cv.put("updated_at", java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        db.update("payment_types", cv, "id=?", new String[]{String.valueOf(id)});
    }

    public void delete(int id) {
        // Transactions already using this type keep their stored text value
        // (payment_type is a plain TEXT column, not a foreign key) — deleting
        // the type here only removes it from future selection.
        db.delete("payment_types", "id=?", new String[]{String.valueOf(id)});
    }
}
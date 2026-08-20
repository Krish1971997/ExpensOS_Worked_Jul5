package com.expenseos.dao;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import androidx.annotation.Nullable;

import com.expenseos.db.LocalDB;

/**
 * Local SQLite database — mirrors the PostgreSQL schema.
 * Transactions are saved here first; sync pushes them to Neon.
 */

public class LocalDatabase extends SQLiteOpenHelper {
    public LocalDatabase(@Nullable Context context, @Nullable String name, @Nullable SQLiteDatabase.CursorFactory factory, int version) {
        super(context, name, factory, version);
    }

    public static LocalDB get(Context ctx) {
        return LocalDB.getInstance(ctx);
    }

    @Override
    public void onCreate(SQLiteDatabase sqLiteDatabase) {
    }

    @Override
    public void onUpgrade(SQLiteDatabase sqLiteDatabase, int i, int i1) {
    }
}
package com.expenseos.dao;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.expenseos.db.LocalDB;
import com.expenseos.model.KeywordMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Description -> Category/Sub-category keyword mappings, used by
 * TransactionEntryActivity to auto-pick a category while the user types
 * the Remark/Note (e.g. "lunch" -> Food / Lunch). Managed from
 * Settings -> Keywords; same Common (book_id NULL) vs This-book-only
 * scoping convention as CategoryDao.
 */
public class KeywordMappingDao {
    private final LocalDB helper;
    private final SQLiteDatabase db;

    private static final String BASE_SELECT =
            "SELECT km.id, km.keyword, km.type, km.category_id, km.sub_category_id, km.book_id, " +
                    "c.name, sc.name " +
                    "FROM keyword_mappings km " +
                    "JOIN categories c ON c.id = km.category_id " +
                    "LEFT JOIN sub_categories sc ON sc.id = km.sub_category_id ";

    public KeywordMappingDao(Context ctx) {
        helper = LocalDB.getInstance(ctx);
        db = helper.getWritableDatabase();
    }

    /**
     * Common mappings + this book's mappings, for the Keywords settings list.
     */
    public List<KeywordMapping> findAll(Integer bookId) {
        String sql = BASE_SELECT + "WHERE (km.book_id IS NULL" +
                (bookId != null ? " OR km.book_id=" + bookId : "") + ") " +
                "ORDER BY km.book_id IS NOT NULL, km.keyword COLLATE NOCASE";
        List<KeywordMapping> list = new ArrayList<>();
        try (Cursor c = db.rawQuery(sql, null)) {
            while (c.moveToNext()) list.add(fromCursor(c));
        }
        return list;
    }

    /**
     * Best keyword match for a note, scoped to type/book. Longest matching
     * keyword wins (most specific); ties go to a this-book-only mapping
     * over a common one.
     */
    public KeywordMapping suggest(String note, String type, int bookId) {
        String noteLower = note.toLowerCase(Locale.ROOT);
        String sql = BASE_SELECT + "WHERE km.type=? AND (km.book_id IS NULL OR km.book_id=?)";
        KeywordMapping best = null;
        try (Cursor c = db.rawQuery(sql, new String[]{type, String.valueOf(bookId)})) {
            while (c.moveToNext()) {
                String kw = c.getString(1);
                if (kw == null || kw.trim().isEmpty()) continue;
                if (!noteLower.contains(kw.toLowerCase(Locale.ROOT))) continue;

                KeywordMapping candidate = fromCursor(c);
                if (best == null
                        || candidate.getKeyword().length() > best.getKeyword().length()
                        || (candidate.getKeyword().length() == best.getKeyword().length()
                        && candidate.getBookId() != null && best.getBookId() == null)) {
                    best = candidate;
                }
            }
        }
        return best;
    }

    public void insert(String keyword, String type, int categoryId, Integer subCategoryId, Integer bookId) {
        long id = helper.getNextId("keyword_mappings");
        ContentValues cv = new ContentValues();
        cv.put("id", id);
        cv.put("keyword", keyword.trim());
        cv.put("type", type);
        cv.put("category_id", categoryId);
        if (subCategoryId != null) cv.put("sub_category_id", subCategoryId);
        else cv.putNull("sub_category_id");
        if (bookId != null) cv.put("book_id", bookId);
        else cv.putNull("book_id");
        db.insertWithOnConflict("keyword_mappings", null, cv, SQLiteDatabase.CONFLICT_IGNORE);
    }

    /**
     * Existing mappings with the same keyword text (case-insensitive) and
     * same type, in overlapping scope — used to block/flag Case-2 duplicates
     * (same keyword pointing at two different category/sub-category pairs).
     */
    public List<KeywordMapping> findByKeyword(String keyword, String type, Integer bookId, Integer excludeId) {
        String sql = BASE_SELECT + "WHERE LOWER(km.keyword)=LOWER(?) AND km.type=? " +
                "AND (km.book_id IS NULL" + (bookId != null ? " OR km.book_id=" + bookId : "") + ")" +
                (excludeId != null ? " AND km.id<>" + excludeId : "");
        List<KeywordMapping> list = new ArrayList<>();
        try (Cursor c = db.rawQuery(sql, new String[]{keyword.trim(), type})) {
            while (c.moveToNext()) list.add(fromCursor(c));
        }
        return list;
    }

    // NEW
    public void update(int id, String keyword, int categoryId, Integer subCategoryId, Integer bookId) {
        ContentValues cv = new ContentValues();
        cv.put("keyword", keyword.trim());
        cv.put("category_id", categoryId);
        if (subCategoryId != null) cv.put("sub_category_id", subCategoryId);
        else cv.putNull("sub_category_id");
        if (bookId != null) cv.put("book_id", bookId);
        else cv.putNull("book_id");
        db.update("keyword_mappings", cv, "id=?", new String[]{String.valueOf(id)});
    }

    public void delete(int id) {
        db.delete("keyword_mappings", "id=?", new String[]{String.valueOf(id)});
    }

    private KeywordMapping fromCursor(Cursor c) {
        KeywordMapping k = new KeywordMapping(
                c.getInt(0), c.getString(1), c.getString(2), c.getInt(3),
                c.isNull(4) ? null : c.getInt(4),
                c.isNull(5) ? null : c.getInt(5));
        k.setCategoryName(c.getString(6));
        k.setSubCategoryName(c.getString(7));
        return k;
    }
}
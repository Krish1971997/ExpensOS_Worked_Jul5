package com.expenseos.dao;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.expenseos.db.LocalDB;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SettlementLinkDao {

    public static class Link {
        public int id;
        public int settlementTxnId;
        public int linkedTxnId;
        public BigDecimal amount;

        /**
         * The OTHER transaction's id, given one side of the link.
         */
        public int otherSide(int txnId) {
            return settlementTxnId == txnId ? linkedTxnId : settlementTxnId;
        }
    }

    private final LocalDB helper;
    private final SQLiteDatabase db;

    public SettlementLinkDao(Context ctx) {
        helper = LocalDB.getInstance(ctx);
        db = helper.getWritableDatabase();
    }

    /**
     * All links touching this transaction, whichever side it's on.
     */
    public List<Link> findForTransaction(int txnId) {
        List<Link> list = new ArrayList<>();
        String sql = "SELECT id, settlement_txn_id, linked_txn_id, amount FROM settlement_links " +
                "WHERE settlement_txn_id=? OR linked_txn_id=?";
        try (Cursor c = db.rawQuery(sql, new String[]{String.valueOf(txnId), String.valueOf(txnId)})) {
            while (c.moveToNext()) {
                Link l = new Link();
                l.id = c.getInt(0);
                l.settlementTxnId = c.getInt(1);
                l.linkedTxnId = c.getInt(2);
                l.amount = BigDecimal.valueOf(c.getDouble(3));
                list.add(l);
            }
        }
        return list;
    }

    /**
     * Total already linked away from this transaction (both directions).
     */
    public BigDecimal sumLinkedFor(int txnId) {
        BigDecimal sum = BigDecimal.ZERO;
        for (Link l : findForTransaction(txnId)) sum = sum.add(l.amount);
        return sum;
    }

    public long insert(int fromTxnId, int toTxnId, BigDecimal amount) {
        long id = helper.getNextId("settlement_links");
        ContentValues cv = new ContentValues();
        cv.put("id", id);
        cv.put("settlement_txn_id", fromTxnId);
        cv.put("linked_txn_id", toTxnId);
        cv.put("amount", amount.doubleValue());
        db.insertWithOnConflict("settlement_links", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
        return id;
    }

    public void delete(int linkId) {
        db.delete("settlement_links", "id=?", new String[]{String.valueOf(linkId)});
    }

    /**
     * StatsActivity "Net Settlements" checkbox — for every category (of the
     * given type) in this book, how much of that category's total has been
     * linked away via settlements. Cross-book links (linked transaction in
     * a different book) still count, matched on WHICHEVER side sits in
     * this book. Subtract the returned amount from each category's raw
     * total to get the "net of settlements" figure.
     */
    public Map<Integer, BigDecimal> sumLinkedByCategory(int bookId, String type) {
        Map<Integer, BigDecimal> result = new HashMap<>();
        String sql = "SELECT t.category_id, SUM(sl.amount) FROM settlement_links sl " +
                "JOIN transactions t ON (t.id = sl.settlement_txn_id OR t.id = sl.linked_txn_id) " +
                "WHERE t.book_id=? AND t.type=? " +
                "GROUP BY t.category_id";
        try (Cursor c = db.rawQuery(sql, new String[]{String.valueOf(bookId), type})) {
            while (c.moveToNext()) {
                if (c.isNull(0)) continue;
                result.put(c.getInt(0), BigDecimal.valueOf(c.getDouble(1)));
            }
        }
        return result;
    }
}
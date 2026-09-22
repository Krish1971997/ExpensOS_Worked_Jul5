package com.expenseos.util;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;

import com.expenseos.dao.CashBookDao;
import com.expenseos.dao.CategoryDao;
import com.expenseos.dao.SubCategoryDao;
import com.expenseos.dao.TransactionDao;
import com.expenseos.model.CashBook;
import com.expenseos.model.Category;
import com.expenseos.model.SubCategory;
import com.expenseos.model.Transaction;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Server-side counterpart for the in-app FoodTrackerActivity PDF generator —
 * produces a multi-page A4 PDF reporting per-day breakfast/lunch/dinner
 * totals for the requested month, plus a per-meal summary at the bottom.
 * Same visuals iTextless as PdfReportGenerator — uses android.graphics.pdf.
 */
public final class FoodTrackerReportGenerator {

    private FoodTrackerReportGenerator() {
    }

    // Default active-month report (used by the in-app Food Tracker Email button):
    public static String writeCurrentMonth(Context ctx, OutputStream out) {
        return writeFor(ctx, LocalDate.now().withDayOfMonth(1), out);
    }

    public static String writeLastMonth(Context ctx, OutputStream out) {
        return writeFor(ctx, LocalDate.now().minusMonths(1).withDayOfMonth(1), out);
    }

    public static String writeFor(Context ctx, LocalDate monthAnchor, OutputStream out) {
        try {
            CashBookDao bookDao = new CashBookDao(ctx);
            CategoryDao categoryDao = new CategoryDao(ctx);
            SubCategoryDao subDao = new SubCategoryDao(ctx);
            TransactionDao txnDao = new TransactionDao(ctx);

            String monthName = monthAnchor.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH));
            CashBook foodBook = ensureFoodBook(bookDao, monthName);
            Category foodCategory = ensureCategory(categoryDao, subDao, foodBook);

            // Collect every day in this month with the three sub-category sums.
            Map<LocalDate, BigDecimal[]> rows = new LinkedHashMap<>(); // [break, lunch, dinner]
            int totalBreakfast = 0, totalLunch = 0, totalDinner = 0;
            int daysTouched = 0;
            int daysInMonth = monthAnchor.lengthOfMonth();
            for (int d = 1; d <= daysInMonth; d++) {
                LocalDate date = monthAnchor.withDayOfMonth(d);
                rows.put(date, new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
            }

            Collection<Transaction> txns = foodBook != null && foodCategory != null
                    ? txnDao.findFoodEntriesForBook(foodBook.getId(), foodCategory.getId()).values()
                    : List.of();
            int foodCatId = foodCategory != null ? foodCategory.getId() : -1;
            int bId = firstSubId(subDao, foodCatId, "Breakfast");
            int lId = firstSubId(subDao, foodCatId, "Lunch");
            int dId = firstSubId(subDao, foodCatId, "Dinner");

            for (Transaction t : txns) {
                if (t.getCategoryId() != foodCatId) continue;
                LocalDate when = t.getDateTime() == null ? null : t.getDateTime().toLocalDate();
                if (when == null || when.getMonthValue() != monthAnchor.getMonthValue()
                        || when.getYear() != monthAnchor.getYear()) continue;
                BigDecimal amt = t.getAmount() == null ? BigDecimal.ZERO : t.getAmount();
                BigDecimal[] row = rows.get(when);
                if (row == null) continue;
                int sid = t.getSubCategoryId();
                if (sid == bId) {
                    row[0] = row[0].add(amt);
                    totalBreakfast++;
                } else if (sid == lId) {
                    row[1] = row[1].add(amt);
                    totalLunch++;
                } else if (sid == dId) {
                    row[2] = row[2].add(amt);
                    totalDinner++;
                }
                if (amt.signum() > 0) daysTouched++;
            }

            int pageW = 595, pageH = 842;
            PdfDocument doc = new PdfDocument();

            Paint titleP = new Paint(Paint.ANTI_ALIAS_FLAG);
            titleP.setColor(Color.parseColor("#111827"));
            titleP.setTextSize(20);
            titleP.setFakeBoldText(true);

            Paint headP = new Paint(Paint.ANTI_ALIAS_FLAG);
            headP.setColor(Color.parseColor("#FFFFFF"));
            headP.setTextSize(11);
            headP.setFakeBoldText(true);

            Paint bodyP = new Paint(Paint.ANTI_ALIAS_FLAG);
            bodyP.setColor(Color.parseColor("#111827"));
            bodyP.setTextSize(11);

            Paint metaP = new Paint(Paint.ANTI_ALIAS_FLAG);
            metaP.setColor(Color.parseColor("#6B7280"));
            metaP.setTextSize(9);

            Paint fillHead = new Paint();
            fillHead.setColor(Color.parseColor("#2563EB"));
            Paint fillAlt = new Paint();
            fillAlt.setColor(Color.parseColor("#F9FAFB"));

            int x0 = 36, xDate = x0 + 10, xDay = x0 + 110, xB = x0 + 180, xL = x0 + 290, xD = x0 + 400, xT = x0 + 510;
            int rowH = 22;
            int headerY = 110 + rowH;

            PdfDocument.Page page = doc.startPage(new PdfDocument.PageInfo.Builder(pageW, pageH, 1).create());
            android.graphics.Canvas c = page.getCanvas();

            c.drawText("Food Tracker — " + monthName, x0, 60, titleP);
            c.drawText("Cash book: " + (foodBook != null ? foodBook.getName() : "N/A"), x0, 80, metaP);
            c.drawText("Generated: " + java.time.LocalDateTime.now()
                            .format(DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm")),
                    x0, 96, metaP);

            c.drawRect(x0, 110, pageW - x0, headerY, fillHead);
            c.drawText("#", xDate + 6, 110 + 15, headP);
            c.drawText("Date", xDate + 26, 110 + 15, headP);
            c.drawText("Day", xDay + 6, 110 + 15, headP);
            c.drawText("Breakfast", xB + 6, 110 + 15, headP);
            c.drawText("Lunch", xL + 6, 110 + 15, headP);
            c.drawText("Dinner", xD + 6, 110 + 15, headP);
            c.drawText("Total", xT + 6, 110 + 15, headP);

            BigDecimal sumB = BigDecimal.ZERO, sumL = BigDecimal.ZERO, sumD = BigDecimal.ZERO;
            int y = headerY;
            int idx = 1;
            for (Map.Entry<LocalDate, BigDecimal[]> e : rows.entrySet()) {
                if (y > pageH - 80) break; // safe guard; rows is small per month
                if (idx % 2 == 0) c.drawRect(x0, y, pageW - x0, y + rowH, fillAlt);
                BigDecimal[] v = e.getValue();
                sumB = sumB.add(v[0]);
                sumL = sumL.add(v[1]);
                sumD = sumD.add(v[2]);
                BigDecimal total = v[0].add(v[1]).add(v[2]);

                c.drawText(String.valueOf(idx), xDate + 6, y + 15, bodyP);
                c.drawText(e.getKey().format(DateTimeFormatter.ofPattern("dd/MM/yyyy")), xDate + 26, y + 15, bodyP);
                c.drawText(e.getKey().getDayOfWeek()
                                .getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH),
                        xDay + 6, y + 15, bodyP);
                c.drawText(format(v[0]), xB + 6, y + 15, bodyP);
                c.drawText(format(v[1]), xL + 6, y + 15, bodyP);
                c.drawText(format(v[2]), xD + 6, y + 15, bodyP);
                c.drawText(format(total), xT + 6, y + 15, bodyP);
                y += rowH;
                idx++;
            }

            y += 12;
            c.drawText("Monthly totals:", xDate + 6, y, headP);
            y += 18;
            c.drawText("Breakfast: " + format(sumB), xDate + 6, y, bodyP);
            y += 14;
            c.drawText("Lunch: " + format(sumL), xDate + 6, y, bodyP);
            y += 14;
            c.drawText("Dinner: " + format(sumD), xDate + 6, y, bodyP);
            y += 14;
            c.drawText("Grand total: " + format(sumB.add(sumL).add(sumD)), xDate + 6, y, bodyP);
            y += 14;
            c.drawText("Days with food spends: " + daysTouched, xDate + 6, y, bodyP);

            doc.finishPage(page);
            doc.writeTo(out);
            doc.close();
            return monthName;
        } catch (Exception e) {
            // Bubble up exception so caller (e.g. SchedulerWorker) reports failure
            throw new RuntimeException(e);
        }
    }

    private static String format(BigDecimal v) {
        if (v == null || v.signum() == 0) return "₹0";
        return "₹" + v.stripTrailingZeros().toPlainString();
    }

    private static LocalDate parseLocalDate(Object date) {
        if (date == null) return null;
        try {
            String s = date.toString().substring(0, 10);
            return LocalDate.parse(s);
        } catch (Exception ignored) {
            return null;
        }
    }

    /* helpers */
    private static CashBook ensureFoodBook(CashBookDao dao, String monthLabel) {
        String bookName = monthLabel + " Food";
        for (CashBook b : dao.findAll()) {
            if (bookName.equalsIgnoreCase(b.getName())) return b;
        }
        return null;
    }

    private static Category ensureCategory(CategoryDao catDao, SubCategoryDao subDao, CashBook foodBook) {
        if (foodBook == null) return null;
        for (Category c : catDao.findByType("EXPENSE", foodBook.getId())) {
            if ("Food".equalsIgnoreCase(c.getName())) return c;
        }
        return null;
    }

    private static int firstSubId(SubCategoryDao dao, int catId, String name) {
        if (catId <= 0) return -1;
        try {
            for (SubCategory s : dao.findByCategoryId(catId)) {
                if (name.equalsIgnoreCase(s.getName())) return s.getId();
            }
        } catch (Exception ignored) {
        }
        return -1;
    }
}
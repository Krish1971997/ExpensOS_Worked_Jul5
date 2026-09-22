package com.expenseos.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.expenseos.R;
import com.expenseos.dao.CashBookDao;
import com.expenseos.dao.CategoryDao;
import com.expenseos.dao.SubCategoryDao;
import com.expenseos.dao.TransactionDao;
import com.expenseos.model.CashBook;
import com.expenseos.model.Category;
import com.expenseos.model.SubCategory;
import com.expenseos.model.Transaction;
import com.expenseos.util.GmailSender;
import com.itextpdf.text.BaseColor;
import com.itextpdf.text.Document;
import com.itextpdf.text.Element;
import com.itextpdf.text.Font;
import com.itextpdf.text.PageSize;
import com.itextpdf.text.Paragraph;
import com.itextpdf.text.pdf.PdfPCell;
import com.itextpdf.text.pdf.PdfPTable;
import com.itextpdf.text.pdf.PdfWriter;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Daily Breakfast/Lunch/Dinner expense grid for the current month's
 * auto-created "<Month> <Year> Food" cashbook (SchedulerWorker.runCashBook()
 * creates it — same pattern as the other 3 monthly books). Category
 * "Food" and its Breakfast/Lunch/Dinner sub-categories are COMMON
 * (book_id IS NULL), shared across every cashbook, per how this app
 * already sets up categories.
 * <p>
 * Fixed transaction times per meal: Breakfast 9:00 AM, Lunch 1:00 PM,
 * Dinner 8:30 PM. Payment type always "Cash". Save upserts the matching
 * transaction per cell (create if none exists for that date+meal, update
 * the amount if one does, delete it if cleared back to 0). Refresh
 * re-reads from the DB, so entries added directly via the normal
 * Add-Entry screen for this cashbook show up here too.
 */
public class FoodTrackerActivity extends AppCompatActivity {

    private static final DateTimeFormatter MONTH_NAME_FMT = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final LocalTime BREAKFAST_TIME = LocalTime.of(9, 0);
    private static final LocalTime LUNCH_TIME = LocalTime.of(13, 0);
    private static final LocalTime DINNER_TIME = LocalTime.of(20, 30);

    private TransactionDao txnDao;
    private CashBook foodBook;
    private int foodCategoryId;
    private int breakfastSubId, lunchSubId, dinnerSubId;

    private final List<DayRow> rows = new ArrayList<>();
    private FoodGridAdapter adapter;
    private TextView tvMonthTitle, tvGrandTotal;
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private static final String XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_food_tracker);

        txnDao = new TransactionDao(this);
        tvMonthTitle = findViewById(R.id.tvFoodTrackerMonth);
        tvGrandTotal = findViewById(R.id.tvFoodTrackerGrandTotal);

        RecyclerView rv = findViewById(R.id.rvFoodTracker);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new FoodGridAdapter();
        rv.setAdapter(adapter);

        findViewById(R.id.btnFoodTrackerBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnFoodTrackerRefresh).setOnClickListener(v -> loadData());
        findViewById(R.id.btnFoodTrackerSave).setOnClickListener(v -> saveAll());
        findViewById(R.id.btnFoodTrackerMenu).setOnClickListener(this::showExportMenu);

        ensureCategoriesAndBook();
        loadData();
    }

    // ── Ensure "Food" category + Breakfast/Lunch/Dinner sub-categories
    // (common, book_id NULL) and this month's "<Month> <Year> Food"
    // cashbook all exist — auto-creates anything missing so this screen
    // works even before the scheduler's next 12:05 AM run. ──────────
    private void ensureCategoriesAndBook() {
        CategoryDao catDao = new CategoryDao(this);
        SubCategoryDao subDao = new SubCategoryDao(this);
        CashBookDao bookDao = new CashBookDao(this);

        Category food = findCommonCategoryByName(catDao, "Food");
        if (food == null) {
            catDao.insert("Food", "EXPENSE", null);
            food = findCommonCategoryByName(catDao, "Food");
        }
        foodCategoryId = food.getId();

        breakfastSubId = ensureSubCategory(subDao, foodCategoryId, "Breakfast");
        lunchSubId = ensureSubCategory(subDao, foodCategoryId, "Lunch");
        dinnerSubId = ensureSubCategory(subDao, foodCategoryId, "Dinner");

        String bookName = YearMonth.now().atDay(1).format(MONTH_NAME_FMT) + " Food";
        CashBook existing = null;
        for (CashBook b : bookDao.findAll())
            if (bookName.equalsIgnoreCase(b.getName())) {
                existing = b;
                break;
            }
        if (existing == null) {
            long id = bookDao.insert(bookName, "Auto-created — daily food tracker");
            existing = bookDao.findById((int) id);
        }
        foodBook = existing;
        tvMonthTitle.setText(bookName);
    }

    private Category findCommonCategoryByName(CategoryDao dao, String name) {
        List<Category> matches = dao.findByName(name, "EXPENSE", null, null);
        return matches.isEmpty() ? null : matches.get(0);
    }

    private int ensureSubCategory(SubCategoryDao dao, int catId, String name) {
        for (SubCategory sc : dao.findByCategoryId(catId))
            if (name.equalsIgnoreCase(sc.getName())) return sc.getId();
        dao.insert(name, catId);
        for (SubCategory sc : dao.findByCategoryId(catId))
            if (name.equalsIgnoreCase(sc.getName())) return sc.getId();
        return 0;
    }

    // ── Load / Refresh ──────────────────────────────────────────────
    private void loadData() {
        Map<String, Transaction> existing = txnDao.findFoodEntriesForBook(foodBook.getId(), foodCategoryId);

        YearMonth ym = YearMonth.now();
        rows.clear();
        BigDecimal grand = BigDecimal.ZERO;
        for (int day = 1; day <= ym.lengthOfMonth(); day++) {
            LocalDate date = ym.atDay(day);
            DayRow row = new DayRow();
            row.date = date;
            row.breakfast = existing.get(keyFor(date, "BREAKFAST"));
            row.lunch = existing.get(keyFor(date, "LUNCH"));
            row.dinner = existing.get(keyFor(date, "DINNER"));
            rows.add(row);
            grand = grand.add(amt(row.breakfast)).add(amt(row.lunch)).add(amt(row.dinner));
        }
        adapter.notifyDataSetChanged();
        tvGrandTotal.setText("₹" + grand.toPlainString());
    }

    private String keyFor(LocalDate date, String meal) {
        return date + "|" + meal;
    }

    private BigDecimal amt(Transaction t) {
        return t != null ? t.getAmount() : BigDecimal.ZERO;
    }

    // ── Save — walks every row, upserts/deletes as needed per cell ────
    private void saveAll() {
        for (DayRow row : rows) {
            upsertCell(row.date, "Breakfast", breakfastSubId, BREAKFAST_TIME, row.breakfast, row.editedBreakfast);
            upsertCell(row.date, "Lunch", lunchSubId, LUNCH_TIME, row.lunch, row.editedLunch);
            upsertCell(row.date, "Dinner", dinnerSubId, DINNER_TIME, row.dinner, row.editedDinner);
        }
        Toast.makeText(this, "✓ Saved!", Toast.LENGTH_SHORT).show();
        loadData(); // fresh state — resolves ids for newly-inserted cells
    }

    private void upsertCell(LocalDate date, String mealName, int subCategoryId, LocalTime time,
                            Transaction existing, BigDecimal editedValue) {
        if (editedValue == null) return; // cell never touched this session

        boolean isZero = editedValue.compareTo(BigDecimal.ZERO) == 0;

        if (existing == null) {
            if (isZero) return;
            Transaction t = new Transaction();
            t.setType(Transaction.Type.EXPENSE);
            t.setDateTime(LocalDateTime.of(date, time));
            t.setAmount(editedValue);
            t.setCategoryId(foodCategoryId);
            t.setSubCategoryId(subCategoryId);
            t.setNote(mealName);
            t.setBookId(foodBook.getId());
            t.setPaymentType("Cash");
            txnDao.insert(t);
        } else {
            if (isZero) {
                txnDao.delete(existing.getId());
                return;
            }
            if (existing.getAmount().compareTo(editedValue) == 0) return; // unchanged
            Transaction updatedT = new Transaction();
            updatedT.setId(existing.getId());
            updatedT.setType(Transaction.Type.EXPENSE);
            updatedT.setDateTime(LocalDateTime.of(date, time));
            updatedT.setAmount(editedValue);
            updatedT.setCategoryId(foodCategoryId);
            updatedT.setSubCategoryId(subCategoryId);
            updatedT.setNote(mealName);
            updatedT.setBookId(foodBook.getId());
            updatedT.setPaymentType("Cash");
            txnDao.update(existing, updatedT);
        }
    }

    // ── Row model — editedX persists at row-level (not ViewHolder-level)
    // so scroll-recycling never loses an unsaved edit. ────────────────
    private static class DayRow {
        LocalDate date;
        Transaction breakfast, lunch, dinner;
        BigDecimal editedBreakfast, editedLunch, editedDinner; // null = untouched this session
    }

    // ── Adapter ─────────────────────────────────────────────────────
    private class FoodGridAdapter extends RecyclerView.Adapter<FoodGridAdapter.VH> {

        class VH extends RecyclerView.ViewHolder {
            TextView tvSNo, tvDate, tvDay, tvTotal;
            EditText etBreakfast, etLunch, etDinner;

            VH(View v) {
                super(v);
                tvSNo = v.findViewById(R.id.tvFtSNo);
                tvDate = v.findViewById(R.id.tvFtDate);
                tvDay = v.findViewById(R.id.tvFtDay);
                etBreakfast = v.findViewById(R.id.etFtBreakfast);
                etLunch = v.findViewById(R.id.etFtLunch);
                etDinner = v.findViewById(R.id.etFtDinner);
                tvTotal = v.findViewById(R.id.tvFtTotal);
            }
        }

        @Override
        public VH onCreateViewHolder(ViewGroup p, int t) {
            return new VH(LayoutInflater.from(p.getContext()).inflate(R.layout.item_food_tracker_row, p, false));
        }

        @Override
        public void onBindViewHolder(VH h, int pos) {
            DayRow row = rows.get(pos);

            h.tvSNo.setText(String.valueOf(pos + 1));
            h.tvDate.setText(row.date.format(DATE_FMT));
            h.tvDay.setText(row.date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH));

            // Sat/Sun — vishual-ah mattum differentiate pannradhukku, oru
            // light background color (edit pannalam, save logic-ku idhu affect pannadhu).
            boolean isWeekend = row.date.getDayOfWeek() == java.time.DayOfWeek.SATURDAY
                    || row.date.getDayOfWeek() == java.time.DayOfWeek.SUNDAY;
            h.itemView.setBackgroundColor(androidx.core.content.ContextCompat.getColor(h.itemView.getContext(), isWeekend ? R.color.ft_row_weekend_bg : R.color.ft_row_bg));  //0xFFFFF3E0

            bindAmountCell(h.etBreakfast, row.breakfast, row.editedBreakfast, v -> {
                row.editedBreakfast = v;
                updateRowTotal(h, row);
            });
            bindAmountCell(h.etLunch, row.lunch, row.editedLunch, v -> {
                row.editedLunch = v;
                updateRowTotal(h, row);
            });
            bindAmountCell(h.etDinner, row.dinner, row.editedDinner, v -> {
                row.editedDinner = v;
                updateRowTotal(h, row);
            });

            updateRowTotal(h, row);
        }

        private void updateRowTotal(VH h, DayRow row) {
            BigDecimal b = row.editedBreakfast != null ? row.editedBreakfast : amt(row.breakfast);
            BigDecimal l = row.editedLunch != null ? row.editedLunch : amt(row.lunch);
            BigDecimal d = row.editedDinner != null ? row.editedDinner : amt(row.dinner);
            h.tvTotal.setText("₹" + b.add(l).add(d).toPlainString());
        }

        // Row reuse-la old listener thirumba fire aagama irukka, bind
        // panna munnadi previous watcher-ah remove pannitu puthusa attach pannurom.
        private void bindAmountCell(EditText et, Transaction existing, BigDecimal edited, Consumer<BigDecimal> onChange) {
            Object prevTag = et.getTag();
            if (prevTag instanceof TextWatcher) et.removeTextChangedListener((TextWatcher) prevTag);

            String display = edited != null ? plain(edited) : (existing != null ? plain(existing.getAmount()) : "0");
            et.setText(display);
            et.setSelection(et.getText().length());

            // Highlight Cell if value is non-zero
            updateCellHighlight(et, display);

            TextWatcher watcher = new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int a, int b, int c) {
                }

                @Override
                public void onTextChanged(CharSequence s, int a, int b, int c) {
                }

                @Override
                public void afterTextChanged(Editable s) {
                    BigDecimal val;
                    try {
                        val = s.length() == 0 ? BigDecimal.ZERO : new BigDecimal(s.toString());
                    } catch (NumberFormatException e) {
                        val = BigDecimal.ZERO;
                    }

                    // Value change ஆகும் போது background color-ஐ update செய்ய
                    updateCellHighlight(et, s.toString());
                    onChange.accept(val);
                }
            };
            et.addTextChangedListener(watcher);
            et.setTag(watcher);
        }

        // Cell background highlight helper method
        private void updateCellHighlight(EditText et, String textVal) {
            try {
                BigDecimal val = (textVal == null || textVal.trim().isEmpty()) ? BigDecimal.ZERO : new BigDecimal(textVal.trim());
                if (val.compareTo(BigDecimal.ZERO) > 0) {
                    et.setBackgroundResource(R.drawable.bg_input_box_yellow); // Non-zero -> Yellow
                } else {
                    et.setBackgroundResource(R.drawable.bg_input_box); // Zero -> Default Box
                }
            } catch (NumberFormatException e) {
                et.setBackgroundResource(R.drawable.bg_input_box);
            }
        }

        private String plain(BigDecimal b) {
            return b.stripTrailingZeros().toPlainString();
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }
    }

    // class-க்கு கீழே (before the last closing brace) இதை முழுசா சேருங்க
    @Override
    protected void onDestroy() {
        super.onDestroy();
        exec.shutdown();
    }

    // ══════════════════════════════════════════════════════
    // Export — Email / PDF / Excel, always the full month's table
    // ══════════════════════════════════════════════════════
// NEW — same three actions, icon-capable bottom sheet instead of PopupMenu
    private void showExportMenu(View anchor) {
        com.google.android.material.bottomsheet.BottomSheetDialog sheet =
                new com.google.android.material.bottomsheet.BottomSheetDialog(this);
        android.widget.LinearLayout container = new android.widget.LinearLayout(this);
        container.setOrientation(android.widget.LinearLayout.VERTICAL);
        container.setPadding(0, dp(8), 0, dp(16));

        container.addView(iconRow(R.drawable.ic_email, "Email", () -> {
            sheet.dismiss();
            showEmailDialog();
        }));
        container.addView(iconRow(R.drawable.ic_pdf, "PDF", () -> {
            sheet.dismiss();
            exportPdf();
        }));
        container.addView(iconRow(R.drawable.ic_excel, "Excel", () -> {
            sheet.dismiss();
            exportExcel();
        }));

        sheet.setContentView(container);
        sheet.show();
    }

    private View iconRow(int iconRes, String label, Runnable onClick) {
        android.widget.LinearLayout row = new android.widget.LinearLayout(this);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dp(20), dp(14), dp(20), dp(14));
        row.setClickable(true);
        row.setFocusable(true);
        android.util.TypedValue outValue = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outValue, true);
        row.setBackgroundResource(outValue.resourceId);

        android.widget.ImageView icon = new android.widget.ImageView(this);
        icon.setImageResource(iconRes);
        icon.setLayoutParams(new android.widget.LinearLayout.LayoutParams(dp(28), dp(28)));
        row.addView(icon);

        TextView tvLabel = new TextView(this);
        tvLabel.setText(label);
        tvLabel.setTextSize(16);
        tvLabel.setTextColor(getColor(R.color.text_primary));
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = dp(16);
        tvLabel.setLayoutParams(lp);
        row.addView(tvLabel);

        row.setOnClickListener(v -> onClick.run());
        return row;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private String monthTitle() {
        return tvMonthTitle.getText().toString();
    }

    private void exportPdf() {
        exec.execute(() -> {
            try {
                File dir = new File(getCacheDir(), "reports");
                if (!dir.exists()) dir.mkdirs();
                File pdfFile = new File(dir, "food_tracker_" + System.currentTimeMillis() + ".pdf");
                try (FileOutputStream out = new FileOutputStream(pdfFile)) {
                    writeFoodTrackerPdf(out);
                }
                runOnUiThread(() -> {
                    // In-app zoomable preview — no more external PDF viewer.
                    Intent intent = new Intent(FoodTrackerActivity.this, ZoomablePdfPreviewActivity.class);
                    intent.putExtra("pdfPath", pdfFile.getAbsolutePath());
                    intent.putExtra("suggestedFileName", "food_tracker_" + monthTitle() + ".pdf");
                    intent.putExtra("title", "Food Tracker — " + monthTitle());
                    intent.putExtra("sourceTag", "food-tracker");
                    startActivity(intent);
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "PDF failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    // Preview, not a silent save — writes to the app's cache and opens it
    // via ACTION_VIEW so the user's spreadsheet app (Sheets, Excel, WPS,
    // etc.) shows it first; they choose to save/share from there.
    private void exportExcel() {
        exec.execute(() -> {
            try {
                File dir = new File(getCacheDir(), "reports");
                if (!dir.exists()) dir.mkdirs();
                File xlsxFile = new File(dir, "food_tracker_" + System.currentTimeMillis() + ".xlsx");
                try (FileOutputStream out = new FileOutputStream(xlsxFile)) {
                    writeFoodTrackerXlsx(out);
                }
                Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", xlsxFile);
                runOnUiThread(() -> {
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setDataAndType(uri, XLSX_MIME);
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    try {
                        startActivity(intent);
                    } catch (android.content.ActivityNotFoundException e) {
                        Toast.makeText(this, "No app found to preview Excel files — install Google Sheets, Excel, or WPS Office.", Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Excel failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    // Sends straight to the address configured in Config
    // (scheduler.alert.email) — no confirm dialog, no manual typing.
    private void showEmailDialog() {
        String configuredEmail = com.expenseos.util.AppConfig.get(this).getSchedulerAlertEmail();
        if (configuredEmail == null || configuredEmail.isBlank()) {
            Toast.makeText(this,
                    "No email configured — set it under Config first.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        sendEmail(configuredEmail);
    }

    private void sendEmail(String toAddress) {
        Toast.makeText(this, "Sending…", Toast.LENGTH_SHORT).show();
        exec.execute(() -> {
            try {
                ByteArrayOutputStream pdfBytes = new ByteArrayOutputStream();
                writeFoodTrackerPdf(pdfBytes);

                String subject = monthTitle();
                String html = buildFoodTrackerEmailHtml(subject);
                GmailSender.Attachment attachment = new GmailSender.Attachment(
                        "food_tracker.pdf", pdfBytes.toByteArray(), "application/pdf");
                GmailSender.send(this, toAddress, subject, html, attachment);
                runOnUiThread(() -> Toast.makeText(this, "✔ Email sent!", Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                runOnUiThread(() -> Toast.makeText(this, "✘ Send failed: " + msg, Toast.LENGTH_LONG).show());
            }
        });
    }

    // Full day-by-day breakdown rendered directly in the email body —
    // matches what writeFoodTrackerXlsx/Pdf show. PDF still attached
    // separately for the printable/formatted version.
    private String buildFoodTrackerEmailHtml(String subject) {
        StringBuilder sb = new StringBuilder();
        sb.append("<html><body style='font-family:Arial,sans-serif;'>");
        sb.append("<h2>").append(subject).append("</h2>");

        sb.append("<table style='border-collapse:collapse;width:100%;max-width:560px;'>");
        sb.append("<tr style='background:#2563EB;color:#fff;'>")
                .append("<th style='padding:6px;text-align:left;'>#</th>")
                .append("<th style='padding:6px;text-align:left;'>Date</th>")
                .append("<th style='padding:6px;text-align:left;'>Day</th>")
                .append("<th style='padding:6px;text-align:right;'>Breakfast</th>")
                .append("<th style='padding:6px;text-align:right;'>Lunch</th>")
                .append("<th style='padding:6px;text-align:right;'>Dinner</th>")
                .append("<th style='padding:6px;text-align:right;'>Total</th>")
                .append("</tr>");

        BigDecimal grand = BigDecimal.ZERO;
        for (int i = 0; i < rows.size(); i++) {
            DayRow row = rows.get(i);
            BigDecimal b = amt(row.breakfast), l = amt(row.lunch), d = amt(row.dinner);
            BigDecimal total = b.add(l).add(d);
            grand = grand.add(total);

            boolean isWeekend = row.date.getDayOfWeek() == java.time.DayOfWeek.SATURDAY
                    || row.date.getDayOfWeek() == java.time.DayOfWeek.SUNDAY;
            String rowBg = isWeekend ? "background:#E3F2FD;" : "";
            String bCellBg = b.compareTo(BigDecimal.ZERO) > 0 ? "background:#FFF9C4;" : rowBg;
            String lCellBg = l.compareTo(BigDecimal.ZERO) > 0 ? "background:#FFF9C4;" : rowBg;
            String dCellBg = d.compareTo(BigDecimal.ZERO) > 0 ? "background:#FFF9C4;" : rowBg;

            sb.append("<tr style='border-bottom:1px solid #eee;").append(rowBg).append("'>")
                    .append("<td style='padding:6px;'>").append(i + 1).append("</td>")
                    .append("<td style='padding:6px;'>").append(row.date.format(DATE_FMT)).append("</td>")
                    .append("<td style='padding:6px;'>").append(row.date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH)).append("</td>")
                    .append("<td style='padding:6px;text-align:right;").append(bCellBg).append("'>₹").append(b.toPlainString()).append("</td>")
                    .append("<td style='padding:6px;text-align:right;").append(lCellBg).append("'>₹").append(l.toPlainString()).append("</td>")
                    .append("<td style='padding:6px;text-align:right;").append(dCellBg).append("'>₹").append(d.toPlainString()).append("</td>")
                    .append("<td style='padding:6px;text-align:right;font-weight:bold;'>₹").append(total.toPlainString()).append("</td>")
                    .append("</tr>");
        }

        sb.append("<tr style='background:#f5f5f5;font-weight:bold;'>")
                .append("<td colspan='6' style='padding:6px;'>Month Total</td>")
                .append("<td style='padding:6px;text-align:right;'>₹").append(grand.toPlainString()).append("</td>")
                .append("</tr>");
        sb.append("</table>");

        sb.append("<p style='color:#888;font-size:12px;margin-top:16px;'>A PDF copy of this report is attached.</p>");
        sb.append("</body></html>");
        return sb.toString();
    }

    private void writeFoodTrackerPdf(OutputStream out) throws Exception {
        Document doc = new Document(PageSize.A4, 24, 24, 32, 32);
        PdfWriter.getInstance(doc, out);
        doc.open();

        Font titleFont = new Font(Font.FontFamily.HELVETICA, 16, Font.BOLD);
        Font headFont = new Font(Font.FontFamily.HELVETICA, 9, Font.BOLD, BaseColor.WHITE);
        Font cellFont = new Font(Font.FontFamily.HELVETICA, 9, Font.NORMAL);

        Paragraph title = new Paragraph(monthTitle(), titleFont);
        title.setAlignment(Element.ALIGN_CENTER);
        title.setSpacingAfter(16);
        doc.add(title);

        PdfPTable table = new PdfPTable(new float[]{0.6f, 1.6f, 1.4f, 1f, 1f, 1f, 1f});
        table.setWidthPercentage(100);
        for (String h : new String[]{"#", "Date", "Day", "Breakfast", "Lunch", "Dinner", "Total"}) {
            PdfPCell cell = new PdfPCell(new Paragraph(h, headFont));
            cell.setBackgroundColor(new BaseColor(37, 99, 235));
            cell.setPadding(5);
            table.addCell(cell);
        }

        BaseColor weekendBg = new BaseColor(0xE3, 0xF2, 0xFD); // matches the app grid's Sat/Sun row color
        BaseColor amountBg = new BaseColor(0xFF, 0xF9, 0xC4);  // matches bg_input_box_yellow for non-zero amounts

        BigDecimal grand = BigDecimal.ZERO;
        for (int i = 0; i < rows.size(); i++) {
            DayRow row = rows.get(i);
            BigDecimal b = amt(row.breakfast), l = amt(row.lunch), d = amt(row.dinner);
            BigDecimal total = b.add(l).add(d);
            grand = grand.add(total);

            boolean isWeekend = row.date.getDayOfWeek() == java.time.DayOfWeek.SATURDAY
                    || row.date.getDayOfWeek() == java.time.DayOfWeek.SUNDAY;
            BaseColor rowBg = isWeekend ? weekendBg : null;

            addPdfCell(table, String.valueOf(i + 1), cellFont, rowBg);
            addPdfCell(table, row.date.format(DATE_FMT), cellFont, rowBg);
            addPdfCell(table, row.date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH), cellFont, rowBg);
            addPdfCell(table, b.toPlainString(), cellFont, b.compareTo(BigDecimal.ZERO) > 0 ? amountBg : rowBg);
            addPdfCell(table, l.toPlainString(), cellFont, l.compareTo(BigDecimal.ZERO) > 0 ? amountBg : rowBg);
            addPdfCell(table, d.toPlainString(), cellFont, d.compareTo(BigDecimal.ZERO) > 0 ? amountBg : rowBg);
            addPdfCell(table, total.toPlainString(), cellFont, rowBg);
        }
        doc.add(table);

        Paragraph totalP = new Paragraph("Month Total: ₹" + grand.toPlainString(),
                new Font(Font.FontFamily.HELVETICA, 12, Font.BOLD));
        totalP.setSpacingBefore(12);
        doc.add(totalP);
        doc.close();
    }

    private void addPdfCell(PdfPTable table, String text, Font font) {
        addPdfCell(table, text, font, null);
    }

    private void addPdfCell(PdfPTable table, String text, Font font, BaseColor bg) {
        PdfPCell cell = new PdfPCell(new Paragraph(text, font));
        cell.setPadding(5);
        if (bg != null) cell.setBackgroundColor(bg);
        table.addCell(cell);
    }

    private void writeFoodTrackerXlsx(OutputStream out) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Food Tracker");
            CellStyle headerStyle = wb.createCellStyle();
            headerStyle.setFillForegroundColor(IndexedColors.BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            org.apache.poi.ss.usermodel.Font headerFont = wb.createFont();
            headerFont.setBold(true);
            headerFont.setColor(IndexedColors.WHITE.getIndex());
            headerStyle.setFont(headerFont);

            // Weekend row style — matches the app grid's light-blue Sat/Sun rows.
            CellStyle weekendStyle = wb.createCellStyle();
            weekendStyle.setFillForegroundColor(IndexedColors.PALE_BLUE.getIndex());
            weekendStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            // Non-zero amount cell style — matches bg_input_box_yellow.
            CellStyle amountStyle = wb.createCellStyle();
            amountStyle.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
            amountStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            // Weekend + non-zero amount together — yellow takes priority
            // since it's the more specific signal (amount was actually entered).
            CellStyle weekendAmountStyle = wb.createCellStyle();
            weekendAmountStyle.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
            weekendAmountStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            Row header = sheet.createRow(0);
            String[] cols = {"#", "Date", "Day", "Breakfast", "Lunch", "Dinner", "Total"};
            for (int i = 0; i < cols.length; i++) {
                Cell c = header.createCell(i);
                c.setCellValue(cols[i]);
                c.setCellStyle(headerStyle);
            }

            BigDecimal grand = BigDecimal.ZERO;
            for (int i = 0; i < rows.size(); i++) {
                DayRow row = rows.get(i);
                BigDecimal b = amt(row.breakfast), l = amt(row.lunch), d = amt(row.dinner);
                BigDecimal total = b.add(l).add(d);
                grand = grand.add(total);

                boolean isWeekend = row.date.getDayOfWeek() == java.time.DayOfWeek.SATURDAY
                        || row.date.getDayOfWeek() == java.time.DayOfWeek.SUNDAY;
                CellStyle rowStyle = isWeekend ? weekendStyle : null;

                Row xr = sheet.createRow(i + 1);
                setCellWithStyle(xr, 0, i + 1, rowStyle);
                setCellWithStyle(xr, 1, row.date.format(DATE_FMT), rowStyle);
                setCellWithStyle(xr, 2, row.date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH), rowStyle);
                setCellWithStyle(xr, 3, b.doubleValue(),
                        b.compareTo(BigDecimal.ZERO) > 0 ? (isWeekend ? weekendAmountStyle : amountStyle) : rowStyle);
                setCellWithStyle(xr, 4, l.doubleValue(),
                        l.compareTo(BigDecimal.ZERO) > 0 ? (isWeekend ? weekendAmountStyle : amountStyle) : rowStyle);
                setCellWithStyle(xr, 5, d.doubleValue(),
                        d.compareTo(BigDecimal.ZERO) > 0 ? (isWeekend ? weekendAmountStyle : amountStyle) : rowStyle);
                setCellWithStyle(xr, 6, total.doubleValue(), rowStyle);
            }
            Row totalRow = sheet.createRow(rows.size() + 1);
            totalRow.createCell(0).setCellValue("Month Total");
            totalRow.createCell(6).setCellValue(grand.doubleValue());

            // autoSizeColumn() needs java.awt.font.FontRenderContext for text
            // measurement — AWT isn't available on Android, so it crashes
            // with NoClassDefFoundError. Fixed widths instead (POI widths
            // are in 1/256 of a character width).
            int[] colWidths = {5, 12, 12, 12, 12, 12, 12}; // #,Date,Day,Breakfast,Lunch,Dinner,Total
            for (int i = 0; i < colWidths.length; i++) {
                sheet.setColumnWidth(i, colWidths[i] * 256);
            }
            wb.write(out);
        }
    }

    private void setCellWithStyle(Row row, int col, Object value, CellStyle style) {
        Cell c = row.createCell(col);
        if (value instanceof Number n) c.setCellValue(n.doubleValue());
        else c.setCellValue(String.valueOf(value));
        if (style != null) c.setCellStyle(style);
    }
}
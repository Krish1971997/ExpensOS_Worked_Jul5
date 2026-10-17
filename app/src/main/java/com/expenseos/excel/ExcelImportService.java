package com.expenseos.excel;

import android.content.Context;

import com.expenseos.dao.CashBookDao;
import com.expenseos.dao.CategoryDao;
import com.expenseos.dao.ImportLogDao;
import com.expenseos.dao.PaymentTypeDao;
import com.expenseos.dao.SubCategoryDao;
import com.expenseos.dao.TransactionDao;
import com.expenseos.model.CashBook;
import com.expenseos.model.Category;
import com.expenseos.model.PaymentType;
import com.expenseos.model.SubCategory;
import com.expenseos.model.Transaction;
import com.expenseos.sync.WorkDriveApiService;
import com.expenseos.sync.ZohoSheetApiService;
import com.expenseos.util.AppConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

public class ExcelImportService {

    private static final String XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String IMPORT_SHEET = "Import";
    private static final String REFERENCE_SHEET = "Reference";
    private String cachedRidName, cachedRid;


    public static class Preview {
        public List<ImportRow> rows = new ArrayList<>();
        public int valid, invalid;
        public int fingerprint;      // Sheet mode: content hash of the sheet rows at preview time
        public long modifiedMillis;  // xlsx mode: WorkDrive file modified time at preview
    }

    public static class Result {
        public int total, success, failed;
        public boolean cleanupOk = true;
        public String cleanupError;
    }

    private final Context ctx;
    private final WorkDriveApiService drive;
    private final ZohoSheetApiService sheet;
    private final CashBookDao bookDao;
    private final PaymentTypeDao ptDao;
    private final CategoryDao catDao;
    private final SubCategoryDao subDao;
    private final TransactionDao txnDao;
    private final ImportLogDao logDao;

    public ExcelImportService(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        drive = new WorkDriveApiService(this.ctx);
        sheet = new ZohoSheetApiService(this.ctx);
        bookDao = new CashBookDao(this.ctx);
        ptDao = new PaymentTypeDao(this.ctx);
        catDao = new CategoryDao(this.ctx);
        subDao = new SubCategoryDao(this.ctx);
        txnDao = new TransactionDao(this.ctx);
        logDao = new ImportLogDao(this.ctx);
    }

    // ── 1. Update Excel — refreshes the "Reference" sheet (dropdown source lists); Import rows untouched ──
    private String updateSheet() throws Exception {
        String rid = resolveResourceId();
        ReferenceData ref = loadReference();

        int pending = sheet.fetchRecords(rid, IMPORT_SHEET).size();   // also proves Sheet API + 'Import' sheet work

        List<Integer> oldRows = new ArrayList<>();
        for (Map<String, String> m : sheet.fetchRecords(rid, REFERENCE_SHEET)) {
            int ri = rowIndexOf(m);
            if (ri > 0) oldRows.add(ri);
        }
        sheet.deleteRows(rid, REFERENCE_SHEET, oldRows);

        int n = Math.max(Math.max(ref.cashbooks.size(), ref.paymentTypes.size()),
                Math.max(ref.categories.size(), ref.subCategories.size()));
        JSONArray rows = new JSONArray();
        for (int i = 0; i < n; i++) {
            JSONObject o = new JSONObject();
            putAt(o, "Cashbooks", ref.cashbooks, i);
            putAt(o, "Payment Types", ref.paymentTypes, i);
            putAt(o, "Categories", ref.categories, i);
            putAt(o, "Subcategories", ref.subCategories, i);
            rows.put(o);
        }
        if (n > 0) sheet.addRecords(rid, REFERENCE_SHEET, rows);

        String msg = "Reference updated — " + ref.cashbooks.size() + " cashbooks, " + ref.paymentTypes.size()
                + " payment types, " + ref.categories.size() + " categories, " + ref.subCategories.size()
                + " subcategories. Rows waiting in Import: " + pending;
        logDao.insert("UPDATE_EXCEL", pending, 0, 0, "SUCCESS", msg);
        return msg;
    }

    // ── 2. Fetch + validate (nothing is saved yet) ──
    private Preview fetchSheet() throws Exception {
        String rid = resolveResourceId();
        Preview p = new Preview();
        p.rows = readImportRows(rid);
        validate(p.rows);
        for (ImportRow r : p.rows)
            if (r.valid) p.valid++;
            else p.invalid++;
        p.fingerprint = fingerprint(p.rows);
        return p;
    }

    // ── 3. Save valid rows; Excel keeps only the not-imported rows (same order) with Status + Error ──
    public Result importSheet(Preview p) throws Exception {
        String rid = resolveResourceId();
        if (fingerprint(readImportRows(rid)) != p.fingerprint)
            throw new IllegalStateException("Excel changed after preview — tap 'Fetch & Preview' again");

        Result res = new Result();
        res.total = p.rows.size();
        List<ImportRow> remaining = new ArrayList<>();
        for (ImportRow r : p.rows) {
            if (!r.valid) {
                remaining.add(r);
                res.failed++;
                continue;
            }
            long id = -1;
            try {
                id = txnDao.insert(toTransaction(r));
            } catch (Exception ignored) {
            }
            if (id == -1) {
                r.valid = false;
                r.status = "FAILED";
                r.error = "Could not save to database";
                remaining.add(r);
                res.failed++;
            } else res.success++;
        }

        // Cleanup order matters: first append the not-imported rows (with Status/Error) at the bottom,
        // only then delete every old row. If the add fails nothing is deleted, so no row is ever lost.
        List<Integer> oldRows = new ArrayList<>();
        for (ImportRow r : p.rows) oldRows.add(r.sheetRow);
        try {
            if (!remaining.isEmpty()) {
                JSONArray arr = toJson(remaining);
                try {
                    sheet.addRecords(rid, IMPORT_SHEET, arr);
                } catch (Exception first) {
                    Thread.sleep(1500);
                    sheet.addRecords(rid, IMPORT_SHEET, arr);
                }
            }
            sheet.deleteRows(rid, IMPORT_SHEET, oldRows);   // not retried: a retry could hit shifted rows
        } catch (Exception e) {
            res.cleanupOk = false;
            res.cleanupError = e.getMessage();
        }

        String status = res.failed == 0 ? "SUCCESS" : (res.success == 0 ? "FAILED" : "PARTIAL");
        String msg = res.cleanupOk ? null : "Rows saved but Excel cleanup failed: " + res.cleanupError;
        logDao.insert("IMPORT", res.total, res.success, res.failed, status, msg);
        return res;
    }

    private List<ImportRow> readImportRows(String rid) throws Exception {
        List<ImportRow> rows = new ArrayList<>();
        for (Map<String, String> rec : sheet.fetchRecords(rid, IMPORT_SHEET)) {
            ImportRow r = ExcelImportReader.fromRecord(rec);
            if (r != null) rows.add(r);
        }
        rows.sort(Comparator.comparingInt(r -> r.sheetRow));
        return rows;
    }

    private static int fingerprint(List<ImportRow> rows) {
        StringBuilder sb = new StringBuilder();
        for (ImportRow r : rows)
            sb.append(r.sheetRow).append('|').append(r.cashbook).append('|').append(r.type).append('|')
                    .append(r.dateRaw).append('|').append(r.timeRaw).append('|').append(r.amountRaw).append('|')
                    .append(r.paymentType).append('|').append(r.category).append('|').append(r.subCategory)
                    .append('|').append(r.description).append('\n');
        return sb.toString().hashCode();
    }

    private static JSONArray toJson(List<ImportRow> rows) throws Exception {
        JSONArray arr = new JSONArray();
        for (ImportRow r : rows) {
            JSONObject o = new JSONObject();
            o.put("Cashbook", r.cashbook);
            o.put("Type", r.type);
            o.put("Date", r.dateRaw);
            o.put("Time", r.timeRaw);
            o.put("Amount", r.amountRaw);
            o.put("Payment Type", r.paymentType);
            o.put("Category", r.category);
            o.put("Subcategory", r.subCategory);
            o.put("Description", r.description);
            o.put("Status", r.status);
            o.put("Error", r.error);
            arr.put(o);
        }
        return arr;
    }

    private static void putAt(JSONObject o, String key, List<String> list, int i) throws Exception {
        if (i < list.size()) o.put(key, list.get(i));
    }

    private static int rowIndexOf(Map<String, String> rec) {
        try {
            return (int) Double.parseDouble(rec.get("row_index"));
        } catch (Exception e) {
            return 0;
        }
    }

    // ── validation ──
    public void validate(List<ImportRow> rows) {
        Map<String, CashBook> books = new HashMap<>();
        for (CashBook b : bookDao.findAll()) books.put(key(b.getName()), b);
        Map<String, String> payments = new HashMap<>();
        for (PaymentType pt : ptDao.findAll()) payments.put(key(pt.getName()), pt.getName());
        Map<String, List<Category>> cats = new HashMap<>();
        for (String type : new String[]{"INCOME", "EXPENSE"})
            for (Category c : catDao.findAllByType(type))
                cats.computeIfAbsent(type + "|" + key(c.getName()), k -> new ArrayList<>()).add(c);
        Map<Integer, List<SubCategory>> subs = new HashMap<>();
        for (SubCategory s : subDao.findAll())
            subs.computeIfAbsent(s.getParentCategoryId(), k -> new ArrayList<>()).add(s);

        for (ImportRow r : rows) {
            List<String> err = new ArrayList<>();
            r.bookId = 0;
            r.categoryId = 0;
            r.subCategoryId = 0;
            r.txnType = null;
            r.canonicalPaymentType = null;

            CashBook book = null;
            if (r.cashbook.isEmpty()) err.add("Cashbook is required");
            else {
                book = books.get(key(r.cashbook));
                if (book == null) err.add("Cashbook '" + r.cashbook + "' not found");
                else if (!book.isActive()) {
                    err.add("Cashbook '" + book.getName() + "' is inactive");
                    book = null;
                } else r.bookId = book.getId();
            }

            String type = r.type.trim().toUpperCase(Locale.ROOT);
            boolean typeOk = type.equals("INCOME") || type.equals("EXPENSE");
            if (type.isEmpty()) err.add("Type is required (INCOME or EXPENSE)");
            else if (!typeOk) err.add("Type must be INCOME or EXPENSE");
            else r.txnType = type;

            if (r.dateRaw.isEmpty()) err.add("Date is required");
            else if (r.date == null || r.date.getYear() < 2000 || r.date.getYear() > 2100)
                err.add("Invalid date '" + r.dateRaw + "'");

            if (r.timeRaw.isEmpty()) err.add("Time is required");
            else if (r.time == null) err.add("Invalid time '" + r.timeRaw + "'");

            if (r.amountRaw.isEmpty()) err.add("Amount is required");
            else if (r.amount == null) err.add("Amount must be a number");
            else if (r.amount.signum() <= 0) err.add("Amount must be greater than 0");

            if (r.paymentType.isEmpty()) err.add("Payment type is required");
            else {
                String canon = payments.get(key(r.paymentType));
                if (canon == null) err.add("Payment type '" + r.paymentType + "' not found");
                else {
                    r.canonicalPaymentType = canon;
                    if (book != null) {
                        String m = AppConfig.validatePaymentTypeForBook(book.getName(), canon);
                        if (m != null) err.add(m);
                    }
                }
            }

            if (r.category.isEmpty()) err.add("Category is required");
            else if (book != null && typeOk) {
                Category chosen = null;
                List<Category> cand = cats.get(type + "|" + key(r.category));
                if (cand != null) {
                    for (Category c : cand) {
                        if (c.getBookId() == null) {
                            if (chosen == null) chosen = c;
                        } else if (c.getBookId() == book.getId()) {
                            chosen = c;
                            break;
                        }
                    }
                }
                if (chosen == null)
                    err.add("Category '" + r.category + "' not found for " + type + " in '" + book.getName() + "'");
                else {
                    r.categoryId = chosen.getId();
                    List<SubCategory> sl = subs.get(chosen.getId());
                    if (sl != null && !sl.isEmpty()) {
                        if (r.subCategory.isEmpty())
                            err.add("Subcategory is required for '" + chosen.getName() + "'");
                        else {
                            SubCategory m = null;
                            for (SubCategory s : sl)
                                if (key(s.getName()).equals(key(r.subCategory))) {
                                    m = s;
                                    break;
                                }
                            if (m == null)
                                err.add("Subcategory '" + r.subCategory + "' does not belong to '" + chosen.getName() + "'");
                            else r.subCategoryId = m.getId();
                        }
                    } else if (!r.subCategory.isEmpty())
                        err.add("Category '" + chosen.getName() + "' has no subcategories — leave Subcategory blank");
                }
            }

            r.valid = err.isEmpty();
            r.status = r.valid ? "" : "INVALID";
            r.error = String.join("; ", err);
        }
    }

    // ── helpers ──
    public ReferenceData loadReference() {
        ReferenceData ref = new ReferenceData();
        Map<Integer, String> bookNames = new HashMap<>();
        for (CashBook b : bookDao.findAll()) {
            bookNames.put(b.getId(), b.getName());
            if (b.isActive()) ref.cashbooks.add(b.getName());
        }
        ref.cashbooks.sort(String.CASE_INSENSITIVE_ORDER);
        for (PaymentType p : ptDao.findAll()) ref.paymentTypes.add(p.getName());

        Map<Integer, List<String>> subsByCat = new HashMap<>();
        TreeSet<String> subNames = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (SubCategory s : subDao.findAll()) {
            subsByCat.computeIfAbsent(s.getParentCategoryId(), k -> new ArrayList<>()).add(s.getName());
            subNames.add(s.getName());
        }
        TreeSet<String> catNames = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (String type : new String[]{"EXPENSE", "INCOME"}) {
            for (Category c : catDao.findAllByType(type)) {
                catNames.add(c.getName());
                String scope = c.getBookId() == null ? "Common"
                        : bookNames.getOrDefault(c.getBookId(), "Book #" + c.getBookId());
                List<String> sl = subsByCat.get(c.getId());
                ref.categoryInfo.add(new String[]{c.getName(), type, scope, sl == null ? "" : String.join(", ", sl)});
            }
        }
        ref.categoryInfo.sort(Comparator.comparing((String[] a) -> a[0].toLowerCase(Locale.ROOT)).thenComparing(a -> a[1]));
        ref.categories.addAll(catNames);
        ref.subCategories.addAll(subNames);
        return ref;
    }

    /**
     * Excel file name comes from Config (same WorkDrive folder as backups).
     */
    private String fileName() {
        return AppConfig.get(ctx).getImportExcelFileName();
    }

    /**
     * Zoho Sheet's resource_id = the WorkDrive file id of the sheet in the Backup folder.
     */
    private String resolveResourceId() throws Exception {
        final String name = fileName();
        if (name.equals(cachedRidName) && cachedRid != null) return cachedRid;
        final String want = stripExt(name);
        WorkDriveApiService.WorkDriveFileInfo best = null;
        for (WorkDriveApiService.WorkDriveFileInfo f : drive.listFiles()) {
            if (f.name != null && stripExt(f.name).equalsIgnoreCase(want)) {
                if (best == null || f.modifiedTimeMillis > best.modifiedTimeMillis) best = f;
            }
        }
        if (best == null)
            throw new IllegalStateException("'" + name + "' WorkDrive Backup folder la illa — Config la file name check pannunga");
        cachedRidName = name;
        cachedRid = best.id;
        return best.id;
    }

    private static String stripExt(String s) {
        return s.trim().replaceAll("\\.[A-Za-z0-9]{2,10}$", "");
    }

    private Transaction toTransaction(ImportRow r) {
        Transaction t = new Transaction();
        t.setType(Transaction.Type.valueOf(r.txnType));
        t.setDateTime(LocalDateTime.of(r.date, r.time));
        t.setAmount(r.amount);
        t.setCategoryId(r.categoryId);
        t.setSubCategoryId(r.subCategoryId);
        t.setNote(r.description.isEmpty() ? null : r.description);
        t.setBookId(r.bookId);
        t.setPaymentType(r.canonicalPaymentType);
        return t;
    }

    private static String key(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    private enum Mode {SHEET, XLSX}

    private Mode mode() {
        String n = fileName().toLowerCase(Locale.ROOT);
        if (n.endsWith(".zsheetlnk")) return Mode.SHEET;
        if (n.endsWith(".xlsx")) return Mode.XLSX;
        throw new IllegalStateException("Config la file name .xlsx illa .zsheetlnk la mudiyanum (ippo: '" + fileName() + "')");
    }

    public String updateExcel() throws Exception {
        return mode() == Mode.SHEET ? updateSheet() : updateXlsx();
    }

    public Preview fetch() throws Exception {
        return mode() == Mode.SHEET ? fetchSheet() : fetchXlsx();
    }

    public Result importValid(Preview p) throws Exception {
        return mode() == Mode.SHEET ? importSheet(p) : importXlsx(p);
    }

    // ── .xlsx mode: whole file downloaded / uploaded through the WorkDrive Backup folder ──
    private String updateXlsx() throws Exception {
        ReferenceData ref = loadReference();
        List<ImportRow> carry = new ArrayList<>();
        WorkDriveApiService.WorkDriveFileInfo f = findXlsxFile();
        if (f != null) carry = ExcelImportReader.read(drive.downloadFile(f.id));
        uploadWorkbook(ref, carry);
        String msg = "Excel updated — " + ref.cashbooks.size() + " cashbooks, " + ref.paymentTypes.size()
                + " payment types, " + ref.categories.size() + " categories. Existing rows kept: " + carry.size();
        logDao.insert("UPDATE_EXCEL", carry.size(), 0, 0, "SUCCESS", msg);
        return msg;
    }

    private Preview fetchXlsx() throws Exception {
        WorkDriveApiService.WorkDriveFileInfo f = findXlsxFile();
        if (f == null)
            throw new IllegalStateException("Excel not found in WorkDrive — tap 'Update Excel' first");
        Preview p = new Preview();
        p.modifiedMillis = f.modifiedTimeMillis;
        p.rows = ExcelImportReader.read(drive.downloadFile(f.id));
        validate(p.rows);
        for (ImportRow r : p.rows)
            if (r.valid) p.valid++;
            else p.invalid++;
        return p;
    }

    private Result importXlsx(Preview p) throws Exception {
        WorkDriveApiService.WorkDriveFileInfo now = findXlsxFile();
        if (now == null || now.modifiedTimeMillis != p.modifiedMillis)
            throw new IllegalStateException("Excel changed after preview — tap 'Fetch & Preview' again");

        Result res = new Result();
        res.total = p.rows.size();
        List<ImportRow> remaining = new ArrayList<>();
        for (ImportRow r : p.rows) {
            if (!r.valid) {
                remaining.add(r);
                res.failed++;
                continue;
            }
            long id = -1;
            try {
                id = txnDao.insert(toTransaction(r));
            } catch (Exception ignored) {
            }
            if (id == -1) {
                r.valid = false;
                r.status = "FAILED";
                r.error = "Could not save to database";
                remaining.add(r);
                res.failed++;
            } else res.success++;
        }

        // rewrite the xlsx: only the not-imported rows, same order, with Status + Error
        ReferenceData ref = loadReference();
        try {
            uploadWorkbook(ref, remaining);
        } catch (Exception first) {
            try {
                Thread.sleep(1500);
                uploadWorkbook(ref, remaining);
            } catch (Exception second) {
                res.cleanupOk = false;
                res.cleanupError = second.getMessage();
            }
        }

        String status = res.failed == 0 ? "SUCCESS" : (res.success == 0 ? "FAILED" : "PARTIAL");
        String msg = res.cleanupOk ? null : "Rows saved but Excel cleanup failed: " + res.cleanupError;
        logDao.insert("IMPORT", res.total, res.success, res.failed, status, msg);
        return res;
    }

    private WorkDriveApiService.WorkDriveFileInfo findXlsxFile() throws Exception {
        final String name = fileName();
        String base = name.substring(0, name.lastIndexOf('.'));
        WorkDriveApiService.WorkDriveFileInfo best = null;
        for (WorkDriveApiService.WorkDriveFileInfo f : drive.listFiles()) {
            if (f.name != null && (f.name.equalsIgnoreCase(name) || f.name.equalsIgnoreCase(base))) {
                if (best == null || f.modifiedTimeMillis > best.modifiedTimeMillis) best = f;
            }
        }
        return best;
    }

    private void uploadWorkbook(ReferenceData ref, List<ImportRow> rows) throws Exception {
        byte[] bytes = ExcelImportTemplate.build(ref, rows);
        File tmp = new File(ctx.getCacheDir(), fileName());
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            fos.write(bytes);
        }
        try {
            drive.uploadFile(tmp, XLSX_MIME);
        } finally {
            tmp.delete();
        }
    }
}
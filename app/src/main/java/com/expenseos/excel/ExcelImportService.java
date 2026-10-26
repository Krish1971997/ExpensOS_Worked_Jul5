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

    private final String fileNameOverride;

    public ExcelImportService(Context ctx) {
        this(ctx, null);
    }

    public ExcelImportService(Context ctx, String fileName) {
        this.fileNameOverride = fileName;
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

        refreshReferenceSheet(rid, ref);
        ensureDropdowns(rid, ref, true);   // manual "Update Excel" always re-applies dropdowns
        rememberRefHash(ref);              // so the hourly scheduler doesn't redo the same work

        String msg = "Reference + dropdowns updated — " + ref.cashbooks.size() + " cashbooks, " + ref.paymentTypes.size()
                + " payment types, " + ref.categories.size() + " categories, " + ref.subCategories.size()
                + " subcategories. Rows waiting in Import: " + pending + " [sheet id " + rid + "]";
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
        int lastOldRow = 1;
        for (ImportRow r : p.rows) lastOldRow = Math.max(lastOldRow, r.sheetRow);
        try {
            List<String[]> body = toCsvRows(remaining);
            try {
                sheet.rewriteRows(rid, IMPORT_SHEET, 2, 11, body, lastOldRow);
            } catch (Exception first) {
                Thread.sleep(1500);
                sheet.rewriteRows(rid, IMPORT_SHEET, 2, 11, body, lastOldRow); // idempotent, safe to retry
            }
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

    // column order = ExcelImportTemplate.HEADERS (11 columns A..K)
    private static List<String[]> toCsvRows(List<ImportRow> rows) {
        List<String[]> out = new ArrayList<>();
        for (ImportRow r : rows)
            out.add(new String[]{r.cashbook, r.type, r.dateRaw, r.timeRaw, r.amountRaw, r.paymentType,
                    r.category, r.subCategory, r.description, r.status, r.error});
        return out;
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

    // ── Hourly scheduler entry: reads + refreshes Reference lists ONLY.
    //    Never imports rows, never deletes/edits the user's Import rows. ──
    public static class SyncReport {
        public String fileName, mode = "?", error, stackTrace;
        public int valid, invalid;
        public boolean referenceUpdated, skippedBusy;
        public List<ImportRow> rows = new ArrayList<>();
        public byte[] rawXlsx;

        public boolean ok() {
            return error == null;
        }
    }

    public SyncReport sync() {
        SyncReport rep = new SyncReport();
        rep.fileName = fileName();
        try {
            Mode m = mode();
            rep.mode = m.name();
            ReferenceData ref = loadReference();
            String hash = String.valueOf(referenceHash(ref));
            android.content.SharedPreferences sp = ctx.getSharedPreferences("excel_sync", Context.MODE_PRIVATE);
            String hashKey = "refhash:" + rep.fileName;
            boolean changed = !hash.equals(sp.getString(hashKey, null));
            boolean done = !changed;

            if (m == Mode.SHEET) {
                String rid = resolveResourceId();
                rep.rows = readImportRows(rid);                 // proves the file is readable
                if (changed) {
                    refreshReferenceSheet(rid, ref);
                    rep.referenceUpdated = true;
                    done = true;
                }
                if (ensureDropdowns(rid, ref, false)) rep.referenceUpdated = true;
            } else {
                WorkDriveApiService.WorkDriveFileInfo f = findXlsxFile();
                if (f == null)
                    throw new IllegalStateException("Excel not found in WorkDrive: " + rep.fileName);
                rep.rawXlsx = drive.downloadFile(f.id);
                rep.rows = ExcelImportReader.read(rep.rawXlsx);
                if (changed) {
                    WorkDriveApiService.WorkDriveFileInfo again = findXlsxFile();
                    if (again == null || again.modifiedTimeMillis != f.modifiedTimeMillis) {
                        rep.skippedBusy = true;                 // edited in the last seconds: retry next hour
                    } else {
                        uploadWorkbook(ref, rep.rows);           // rows carried over untouched
                        rep.referenceUpdated = true;
                        done = true;
                    }
                }
            }
            if (changed && done) sp.edit().putString(hashKey, hash).apply();

            validate(rep.rows);                                  // in-memory only, for the report
            for (ImportRow r : rep.rows)
                if (r.valid) rep.valid++;
                else rep.invalid++;
        } catch (Exception e) {
            rep.error = e.getMessage() != null ? e.getMessage() : e.toString();
            java.io.StringWriter sw = new java.io.StringWriter();
            e.printStackTrace(new java.io.PrintWriter(sw));
            rep.stackTrace = sw.toString().length() > 4000 ? sw.toString().substring(0, 4000) : sw.toString();
        }
        return rep;
    }

    private static final int IMPORT_LAST_ROW = 1000;
    private static final int DV_VERSION = 1; // bump to force re-applying dropdowns to every file

    /**
     * Re-applies Import-sheet dropdowns + validation when the lists changed (or were never applied).
     */
    private boolean ensureDropdowns(String rid, ReferenceData ref, boolean force) throws Exception {
        android.content.SharedPreferences sp = ctx.getSharedPreferences("excel_sync", Context.MODE_PRIVATE);
        String key = "dvhash:" + fileName();
        String val = referenceHash(ref) + ":" + DV_VERSION;
        if (!force && val.equals(sp.getString(key, null))) return false;
        applyDropdowns(rid, ref);
        sp.edit().putString(key, val).apply();
        return true;
    }

    private void applyDropdowns(String rid, ReferenceData ref) throws Exception {
        final String last = String.valueOf(IMPORT_LAST_ROW);
        final String A = "A2:A" + last, B = "B2:B" + last, C = "C2:C" + last, D = "D2:D" + last,
                E = "E2:E" + last, F = "F2:F" + last, G = "G2:G" + last, H = "H2:H" + last;

        try {   // wipe old picklists first; fails harmlessly when there is nothing to remove
            sheet.removePicklists(rid, IMPORT_SHEET, java.util.Arrays.asList(A, B, F, G, H));
        } catch (Exception ignored) {
        }

        pickFromRef(rid, A, "A", ref.cashbooks.size());      // Reference col A = Cashbooks
        pickFromRef(rid, F, "B", ref.paymentTypes.size());   // B = Payment Types
        pickFromRef(rid, G, "C", ref.categories.size());     // C = Categories
        pickFromRef(rid, H, "D", ref.subCategories.size());  // D = Subcategories

        JSONArray types = new JSONArray()
                .put(new JSONObject().put("value", "INCOME").put("font_color", "#000000").put("fill_color", "#C8E6C9"))
                .put(new JSONObject().put("value", "EXPENSE").put("font_color", "#000000").put("fill_color", "#FFCDD2"));
        sheet.setPicklistItems(rid, IMPORT_SHEET, B, types);

        sheet.setDataValidation(rid, IMPORT_SHEET, C,
                new JSONObject().put("type", "date").put("operator", "is_valid_date").put("values", new JSONArray()),
                "Enter a valid date", "Invalid date");
        sheet.setDataValidation(rid, IMPORT_SHEET, D,
                new JSONObject().put("type", "decimal").put("operator", "between")
                        .put("values", new JSONArray().put("0").put("0.99998842592")),
                "Enter a time, e.g. 13:45", "Invalid time");
        sheet.setDataValidation(rid, IMPORT_SHEET, E,
                new JSONObject().put("type", "decimal").put("operator", "greater_than")
                        .put("values", new JSONArray().put("0")),
                "Enter an amount greater than 0", "Amount must be a number greater than 0");
    }

    private void pickFromRef(String rid, String importRange, String refCol, int count) throws Exception {
        if (count <= 0) return;
        sheet.setPicklistFromRange(rid, IMPORT_SHEET, importRange,
                REFERENCE_SHEET, refCol + "2:" + refCol + (1 + count));
    }

    private void refreshReferenceSheet(String rid, ReferenceData ref) throws Exception {
        int oldLast = 1;
        for (Map<String, String> m : sheet.fetchRecords(rid, REFERENCE_SHEET))
            oldLast = Math.max(oldLast, rowIndexOf(m));

        int n = Math.max(Math.max(ref.cashbooks.size(), ref.paymentTypes.size()),
                Math.max(ref.categories.size(), ref.subCategories.size()));
        List<String[]> rows = new ArrayList<>();
        for (int i = 0; i < n; i++)
            rows.add(new String[]{cell(ref.cashbooks, i), cell(ref.paymentTypes, i),
                    cell(ref.categories, i), cell(ref.subCategories, i)});

        try {
            sheet.rewriteRows(rid, REFERENCE_SHEET, 2, 4, rows, oldLast);
        } catch (Exception first) {
            Thread.sleep(1500);
            sheet.rewriteRows(rid, REFERENCE_SHEET, 2, 4, rows, oldLast); // idempotent, safe to retry
        }
    }

    private static String cell(List<String> l, int i) {
        return i < l.size() ? l.get(i) : "";
    }

    private static int referenceHash(ReferenceData r) {
        StringBuilder sb = new StringBuilder();
        sb.append(r.cashbooks).append(r.paymentTypes).append(r.categories)
                .append(r.subCategories).append(r.subsByCategory);
        for (String[] a : r.categoryInfo) sb.append(String.join("\u0001", a)).append('\u0002');
        return sb.toString().hashCode();
    }

    private void rememberRefHash(ReferenceData ref) {
        ctx.getSharedPreferences("excel_sync", Context.MODE_PRIVATE).edit()
                .putString("refhash:" + fileName(), String.valueOf(referenceHash(ref))).apply();
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
                if (sl != null && !sl.isEmpty()) {
                    List<String> acc = ref.subsByCategory.computeIfAbsent(c.getName(), k -> new ArrayList<>());
                    for (String sn : sl) if (!acc.contains(sn)) acc.add(sn);
                }
            }
        }
        ref.categoryInfo.sort(Comparator.comparing((String[] a) -> a[0].toLowerCase(Locale.ROOT)).thenComparing(a -> a[1]));
        ref.categories.addAll(catNames);
        for (List<String> l : ref.subsByCategory.values()) l.sort(String.CASE_INSENSITIVE_ORDER);
        ref.subCategories.addAll(subNames);
        return ref;
    }

    /**
     * Excel file name comes from Config (same WorkDrive folder as backups).
     */
    private String fileName() {
        return fileNameOverride != null ? fileNameOverride : AppConfig.get(ctx).getImportExcelFileName();
    }

    public String getFileName() {
        return fileName();
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
            if (f.name == null || isUploadedSpreadsheet(f.name))
                continue; // never take the .xlsx twin
            if (stripExt(f.name).equalsIgnoreCase(want)) {
                if (best == null || f.modifiedTimeMillis > best.modifiedTimeMillis) best = f;
            }
        }
        if (best == null)
            throw new IllegalStateException("Zoho Sheet '" + want + "' not found in the WorkDrive Backup folder — check the file name in Config");
        cachedRidName = name;
        cachedRid = best.id;
        return best.id;
    }

    private static String stripExt(String s) {
        return s.trim().replaceAll("\\.[A-Za-z0-9]{2,10}$", "");
    }

    private static boolean isUploadedSpreadsheet(String n) {
        String l = n.trim().toLowerCase(Locale.ROOT);
        return l.endsWith(".xlsx") || l.endsWith(".xls") || l.endsWith(".xlsm")
                || l.endsWith(".csv") || l.endsWith(".ods") || l.endsWith(".zip");
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
        throw new IllegalStateException("File name in Config must end with .xlsx or .zsheetlnk (current: '" + fileName() + "')");
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
        rememberRefHash(ref);
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
        WorkDriveApiService.WorkDriveFileInfo exact = null, loose = null;
        for (WorkDriveApiService.WorkDriveFileInfo f : drive.listFiles()) {
            if (f.name == null) continue;
            if (f.name.equalsIgnoreCase(name)) {
                if (exact == null || f.modifiedTimeMillis > exact.modifiedTimeMillis) exact = f;
            } else if (f.name.equalsIgnoreCase(base)) {   // only if the API returns names without extension
                if (loose == null || f.modifiedTimeMillis > loose.modifiedTimeMillis) loose = f;
            }
        }
        return exact != null ? exact : loose;
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
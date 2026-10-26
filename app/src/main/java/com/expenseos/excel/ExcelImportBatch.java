package com.expenseos.excel;

import android.content.Context;

import com.expenseos.util.AppConfig;

import java.util.ArrayList;
import java.util.List;

public final class ExcelImportBatch {
    private ExcelImportBatch() {
    }

    public static class FilePreview {
        public String fileName;
        public ExcelImportService service;
        public ExcelImportService.Preview preview; // null when error != null
        public String error;
    }

    public static class BatchResult {
        public int success, failed;
        public final List<String> problems = new ArrayList<>();
    }

    public static List<ExcelImportService> services(Context ctx) {
        List<ExcelImportService> out = new ArrayList<>();
        for (String name : AppConfig.get(ctx).getImportExcelFileNames())
            out.add(new ExcelImportService(ctx, name));
        return out;
    }

    public static List<FilePreview> fetchAll(Context ctx) {
        List<FilePreview> out = new ArrayList<>();
        for (ExcelImportService svc : services(ctx)) {
            FilePreview fp = new FilePreview();
            fp.service = svc;
            fp.fileName = svc.getFileName();
            try {
                fp.preview = svc.fetch();
            } catch (Exception e) {
                fp.error = msg(e);
            }
            out.add(fp);
        }
        return out;
    }

    public static BatchResult importAll(List<FilePreview> previews) {
        BatchResult res = new BatchResult();
        for (FilePreview fp : previews) {
            if (fp.preview == null || fp.preview.valid == 0) continue;
            try {
                ExcelImportService.Result r = fp.service.importValid(fp.preview);
                res.success += r.success;
                res.failed += r.failed;
                if (!r.cleanupOk)
                    res.problems.add(fp.fileName + ": rows saved but Excel cleanup failed (" + r.cleanupError
                            + "). Don't import again — remove the imported rows manually.");
            } catch (Exception e) {
                res.problems.add(fp.fileName + ": " + msg(e));
            }
        }
        return res;
    }

    public static String updateAll(Context ctx) {
        StringBuilder sb = new StringBuilder();
        for (ExcelImportService svc : services(ctx)) {
            if (sb.length() > 0) sb.append('\n');
            try {
                sb.append("✓ ").append(svc.getFileName()).append(": ").append(svc.updateExcel());
            } catch (Exception e) {
                sb.append("✗ ").append(svc.getFileName()).append(": ").append(msg(e));
            }
        }
        return sb.toString();
    }

    private static String msg(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.toString();
    }
}
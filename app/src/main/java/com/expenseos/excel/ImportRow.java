package com.expenseos.excel;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

public class ImportRow {
    public int sheetRow;
    public String cashbook = "", type = "", paymentType = "";
    public String category = "", subCategory = "", description = "";
    public LocalDate date;
    public LocalTime time;
    public BigDecimal amount;
    public String dateRaw = "", timeRaw = "", amountRaw = "";
    public String status = "", error = "";

    // filled by ExcelImportService.validate()
    public boolean valid;
    public int bookId, categoryId, subCategoryId;
    public String txnType;
    public String canonicalPaymentType;
}
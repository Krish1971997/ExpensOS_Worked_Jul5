package com.expenseos.model;

import java.math.BigDecimal;

public class PassbookEntry {
    private long smsId;
    private String type;        // DEBIT | CREDIT
    private BigDecimal amount;
    private String sender;      // bank/sms sender id, e.g. "AX-FEDBNK"
    private String rawBody;
    private long timestampMillis;
    private boolean copied;     // already copied to a cashbook?
    private String remark;
    private String paymentType; // parsed from SMS body — UPI/NEFT/IMPS/Card/etc., null if not detected

    // getters/setters
    public long getSmsId() {
        return smsId;
    }

    public void setSmsId(long v) {
        smsId = v;
    }

    public String getType() {
        return type;
    }

    public void setType(String v) {
        type = v;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal v) {
        amount = v;
    }

    public String getSender() {
        return sender;
    }

    public void setSender(String v) {
        sender = v;
    }

    public String getRawBody() {
        return rawBody;
    }

    public void setRawBody(String v) {
        rawBody = v;
    }

    public long getTimestampMillis() {
        return timestampMillis;
    }

    public void setTimestampMillis(long v) {
        timestampMillis = v;
    }

    public boolean isCopied() {
        return copied;
    }

    public void setCopied(boolean v) {
        copied = v;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String v) {
        remark = v;
    }

    // getter/setter block-க்கு கீழே சேருங்க
    public String getPaymentType() {
        return paymentType;
    }

    public void setPaymentType(String v) {
        paymentType = v;
    }
}
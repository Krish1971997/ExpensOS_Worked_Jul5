package com.expenseos.util;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.util.Properties;

import javax.activation.DataHandler;
import javax.activation.DataSource;
import javax.mail.BodyPart;
import javax.mail.Message;
import javax.mail.Multipart;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import javax.mail.util.ByteArrayDataSource;

/**
 * Gmail SMTP sender using an App Password (not OAuth) — matches the
 * GMAIL_FROM / GMAIL_APP_PASS fields already in Config. Runs synchronously,
 * so callers must already be on a background thread (Worker.doWork(), an
 * ExecutorService task, etc.) — never call from the main thread.
 */
public class GmailSender {

    public static class Attachment {
        public final String fileName;
        public final byte[] data;
        public final String mimeType;

        public Attachment(String fileName, byte[] data, String mimeType) {
            this.fileName = fileName;
            this.data = data;
            this.mimeType = mimeType;
        }
    }

    /**
     * @param toAddress   recipient — defaults to the configured Gmail address
     *                    itself if null/blank (send-to-self is the common case)
     * @param htmlBody    email body, rendered as HTML
     * @param attachment  optional single attachment (PDF/XLSX), null for none
     */
    public static void send(Context ctx, String toAddress, String subject, String htmlBody, Attachment attachment) throws Exception {
        com.expenseos.util.AppConfig cfg = com.expenseos.util.AppConfig.get(ctx);
        String from = cfg.getGmailFrom();
        String appPass = cfg.getGmailAppPass();

        if (from == null || from.isBlank() || appPass == null || appPass.isBlank())
            throw new IllegalStateException("Gmail not configured — set GMAIL_FROM and GMAIL_APP_PASS in Config first.");

        String to = (toAddress == null || toAddress.isBlank()) ? from : toAddress;

        Properties props = new Properties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.host", "smtp.gmail.com");
        props.put("mail.smtp.port", "587");

        Session session = Session.getInstance(props, new javax.mail.Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(from, appPass);
            }
        });

        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress(from));
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
        message.setSubject(subject);

        MimeBodyPart htmlPart = new MimeBodyPart();
        htmlPart.setContent(htmlBody, "text/html; charset=utf-8");

        Multipart multipart = new MimeMultipart();
        multipart.addBodyPart(htmlPart);

        if (attachment != null) {
            MimeBodyPart attachPart = new MimeBodyPart();
            DataSource ds = new ByteArrayDataSource(attachment.data, attachment.mimeType);
            attachPart.setDataHandler(new DataHandler(ds));
            attachPart.setFileName(attachment.fileName);
            multipart.addBodyPart(attachPart);
        }

        message.setContent(multipart);
        Transport.send(message);
    }
}

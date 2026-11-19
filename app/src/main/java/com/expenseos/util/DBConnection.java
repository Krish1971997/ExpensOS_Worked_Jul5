package com.expenseos.util;

import android.content.Context;
import android.util.Log;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/**
 * Direct JDBC connection to Neon (remote PostgreSQL).
 * Mirrors the web app's DBConnection utility.
 * <p>
 * IMPORTANT: Always call on a background thread — never on the main thread.
 */
public class DBConnection {

    private static final String TAG = "DBConnection";
    private boolean strictTls;   // read from AppConfig, default false

    /**
     * Removes "sslmode=..." from the JDBC URL query (Neon connection strings usually carry ?sslmode=require).
     */
    private static String stripParam(String url, String name) {
        String u = url.replaceAll("([?&])" + name + "=[^&]*(&|$)", "$1");
        return u.replaceAll("[?&]$", "");
    }

    private static DBConnection instance;
    private String jdbcUrl;
    private String user;
    private String password;

    private DBConnection() {
    }

    public static DBConnection getInstance() {
        if (instance == null) instance = new DBConnection();
        return instance;
    }

    public void configure(String jdbcUrl, String user, String password) {
        this.jdbcUrl = jdbcUrl;
        this.user = user;
        this.password = password;
    }

    public void configureFromAppConfig(Context ctx) {
        AppConfig cfg = AppConfig.get(ctx);
        configure(cfg.getDbUrl(), cfg.getDbUser(), cfg.getDbPassword());
        this.strictTls = cfg.isStrictTls();
    }

    // Used by the Test Connection button so it tests the switch state before saving
    public void setStrictTls(boolean on) {
        this.strictTls = on;
    }

    /**
     * Open a fresh JDBC connection to Neon PostgreSQL.
     * Must be called on a background thread.
     */
    public Connection getConnection() throws SQLException {
        if (jdbcUrl == null || jdbcUrl.isEmpty()) {
            throw new SQLException("DB_URL not configured. Please set it in Configuration.");
        }
        try {
            Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException e) {
            throw new SQLException("PostgreSQL JDBC driver not found", e);
        }

        Properties props = new Properties();
        props.setProperty("user", user != null ? user : "");
        props.setProperty("password", password != null ? password : "");
        // Neon requires SSL. STRICT_TLS: verify the server certificate chain (Android system CAs) AND the host name,
        // so a fake server on hostile Wi-Fi can't steal the DB password. If it breaks the connection, set false.
        props.setProperty("ssl", "true");
        if (strictTls) {
            props.setProperty("sslmode", "verify-full");
            props.setProperty("sslfactory", "org.postgresql.ssl.DefaultJavaSSLFactory");
        } else {
            props.setProperty("sslmode", "require");
        }

        props.setProperty("socketTimeout", "30");
        props.setProperty("connectTimeout", "15");

        String url = strictTls ? stripParam(jdbcUrl, "sslmode") : jdbcUrl;
        Log.d(TAG, "Connecting to: " + url.replaceAll("password=[^&]*", "password=***"));
        return DriverManager.getConnection(url, props);
    }

    /**
     * Test the connection — returns null on success, error message on failure.
     */
    public String testConnection() {
        try (Connection conn = getConnection()) {
            if (conn != null && !conn.isClosed()) {
                return null; // success
            }
            return "Connection returned null";
        } catch (Exception e) {
            Log.e(TAG, "Connection test failed", e);
            return e.getMessage();
        }
    }
}
package com.expenseos.util;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ApplicationInfo;

import com.zoho.accounts.externalframework.ZohoErrorCodes;
import com.zoho.accounts.externalframework.ZohoSDK;
import com.zoho.accounts.externalframework.ZohoToken;
import com.zoho.accounts.externalframework.ZohoTokenCallback;

/**
 * Thin wrapper over Zoho's mobile SDK. Tokens are stored/refreshed by the SDK itself.
 */
public final class AuthManager {

    // From Zoho API Console (Mobile Based client)
    private static final String CLIENT_ID = "1000.1T0QDDYM29F50RSBMY8AB2DGJXOOVY";
    private static final String CLIENT_SECRET = "087e8aac55544009a69d274b9597222806e965a8f4";
    // Backend verifies the caller's identity with this scope
    private static final String SCOPES = "aaaserver.profile.READ";

    private static boolean inited = false;

    public interface LoginCallback {
        void onSuccess();

        void onFailure(String reason);
    }

    private AuthManager() {
    }

    private static synchronized ZohoSDK sdk(Context ctx) {
        Context app = ctx.getApplicationContext();
        ZohoSDK sdk = ZohoSDK.getInstance(app);
        if (!inited) {
            boolean debug = (app.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
            sdk.init(CLIENT_ID, CLIENT_SECRET, SCOPES, debug);
            inited = true;
        }
        return sdk;
    }

    public static boolean isLoggedIn(Context ctx) {
        return sdk(ctx).isUserSignedIn();
    }

    public static void presentLogin(Activity a, LoginCallback cb) {
        sdk(a).presentLoginScreen(a, new ZohoTokenCallback() {
            @Override
            public void onTokenFetchInitiated() {
            }

            @Override
            public void onTokenFetchComplete(ZohoToken token) {
                cb.onSuccess();
            }

            @Override
            public void onTokenFetchFailed(ZohoErrorCodes code) {
                cb.onFailure(String.valueOf(code));
            }
        }, null);
    }

    /**
     * Fresh access token (SDK refreshes if expired). BACKGROUND THREAD ONLY.
     */
    public static String getAccessToken(Context ctx) {
        ZohoToken t = sdk(ctx).getToken();
        return t == null ? null : t.getToken();
    }
}
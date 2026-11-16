package com.expenseos.ui;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.zoho.accounts.externalframework.ZohoSDK;

public class HandleRedirectActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ZohoSDK.getInstance(this).handleRedirection(this);
    }
}
package com.expenseos.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.text.InputFilter;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;

import com.expenseos.util.AppLockManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;

public class LockActivity extends AppCompatActivity {

    public static final String EXTRA_MODE = "mode";
    public static final int MODE_UNLOCK = 0;
    public static final int MODE_SETUP = 1;    // set a new PIN / change PIN
    public static final int MODE_DISABLE = 2;  // turn app lock off

    private static final int STAGE_VERIFY = 0, STAGE_NEW = 1, STAGE_CONFIRM = 2;

    public static Intent unlockIntent(Context c) {
        return new Intent(c, LockActivity.class).putExtra(EXTRA_MODE, MODE_UNLOCK);
    }

    public static Intent setupIntent(Context c) {
        return new Intent(c, LockActivity.class).putExtra(EXTRA_MODE, MODE_SETUP);
    }

    public static Intent disableIntent(Context c) {
        return new Intent(c, LockActivity.class).putExtra(EXTRA_MODE, MODE_DISABLE);
    }

    private int mode;
    private int stage;
    private String firstPin;

    private TextView title, subtitle, error;
    private EditText pinInput;
    private MaterialButton primary, biometricBtn;
    private CountDownTimer lockoutTimer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE);

        mode = getIntent().getIntExtra(EXTRA_MODE, MODE_UNLOCK);
        boolean enabled = AppLockManager.isEnabled(this);
        stage = (mode == MODE_SETUP && !enabled) ? STAGE_NEW : STAGE_VERIFY;

        buildUi();
        render();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (mode == MODE_UNLOCK) {
                    moveTaskToBack(true);   // never fall through to locked content
                } else {
                    setResult(RESULT_CANCELED);
                    finish();
                }
            }
        });

        if (mode == MODE_UNLOCK && stage == STAGE_VERIFY && canUseBiometric()) showBiometric();
    }

    // ── UI ───────────────────────────────────────────────────
    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(24), dp(24), dp(24), dp(24));
        root.setBackgroundColor(MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorSurface, 0xFFFFFFFF));

        title = new TextView(this);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.CENTER);

        subtitle = new TextView(this);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, dp(8), 0, dp(24));

        pinInput = new EditText(this);
        pinInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pinInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6)});
        pinInput.setGravity(Gravity.CENTER);
        pinInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        pinInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
        pinInput.setOnEditorActionListener((v, actionId, ev) -> {
            onPrimary();
            return true;
        });

        error = new TextView(this);
        error.setGravity(Gravity.CENTER);
        error.setTextColor(0xFFD32F2F);
        error.setPadding(0, dp(12), 0, dp(12));

        primary = new MaterialButton(this);
        primary.setOnClickListener(v -> onPrimary());

        biometricBtn = new MaterialButton(this, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        biometricBtn.setText("Use fingerprint / face");
        biometricBtn.setOnClickListener(v -> showBiometric());

        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(dp(220),
                LinearLayout.LayoutParams.WRAP_CONTENT);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(dp(220),
                LinearLayout.LayoutParams.WRAP_CONTENT);
        btnLp.topMargin = dp(8);

        root.addView(title);
        root.addView(subtitle);
        root.addView(pinInput, inputLp);
        root.addView(error);
        root.addView(primary, btnLp);
        root.addView(biometricBtn, btnLp);

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.MATCH_PARENT));
        setContentView(sv);
    }

    private void render() {
        boolean verify = stage == STAGE_VERIFY;
        if (verify) {
            title.setText(mode == MODE_DISABLE ? "Turn off app lock"
                    : mode == MODE_SETUP ? "Enter current PIN" : "ExpensOS is locked");
            subtitle.setText("Enter your PIN to continue");
            primary.setText(mode == MODE_UNLOCK ? "Unlock" : "Continue");
        } else if (stage == STAGE_NEW) {
            title.setText("Set a new PIN");
            subtitle.setText("Choose a 4–6 digit PIN");
            primary.setText("Next");
        } else {
            title.setText("Confirm PIN");
            subtitle.setText("Enter the same PIN again");
            primary.setText("Save");
        }
        error.setText("");
        pinInput.setText("");
        biometricBtn.setVisibility(verify && mode != MODE_SETUP
                && AppLockManager.isBiometricEnabled(this) && canUseBiometric()
                ? View.VISIBLE : View.GONE);
        pinInput.requestFocus();
        if (verify) applyLockoutIfAny();
    }

    private void onPrimary() {
        String pin = pinInput.getText().toString().trim();
        if (pin.length() < 4) {
            error.setText("Enter at least 4 digits");
            return;
        }
        if (stage == STAGE_VERIFY) {
            AppLockManager.Result r = AppLockManager.verifyPin(this, pin);
            if (r == AppLockManager.Result.OK) {
                onVerified();
            } else if (r == AppLockManager.Result.LOCKED_OUT) {
                pinInput.setText("");
                applyLockoutIfAny();
            } else {
                pinInput.setText("");
                int left = AppLockManager.attemptsLeft(this);
                error.setText(left > 0 ? "Wrong PIN — " + left + " attempt(s) left" : "Wrong PIN");
            }
        } else if (stage == STAGE_NEW) {
            firstPin = pin;
            stage = STAGE_CONFIRM;
            render();
        } else {
            if (!pin.equals(firstPin)) {
                firstPin = null;
                stage = STAGE_NEW;
                render();
                error.setText("PINs did not match — try again");
                return;
            }
            AppLockManager.setPin(this, pin);
            AppLockManager.setBiometricEnabled(this, canUseBiometric());
            AppLockManager.markUnlocked(this);
            setResult(RESULT_OK);
            finish();
        }
    }

    private void onVerified() {
        if (mode == MODE_UNLOCK) {
            AppLockManager.markUnlocked(this);
            finish();
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        } else if (mode == MODE_DISABLE) {
            AppLockManager.disable(this);
            setResult(RESULT_OK);
            finish();
        } else { // MODE_SETUP: current PIN verified → ask for the new one
            stage = STAGE_NEW;
            render();
        }
    }

    // ── Lockout countdown ────────────────────────────────────
    private void applyLockoutIfAny() {
        if (lockoutTimer != null) lockoutTimer.cancel();
        long left = AppLockManager.lockoutRemainingMs(this);
        if (left <= 0) {
            setInputEnabled(true);
            return;
        }
        setInputEnabled(false);
        lockoutTimer = new CountDownTimer(left, 1000) {
            @Override
            public void onTick(long ms) {
                error.setText("Too many attempts. Try again in " + (ms / 1000 + 1) + "s");
            }

            @Override
            public void onFinish() {
                error.setText("");
                setInputEnabled(true);
            }
        }.start();
    }

    private void setInputEnabled(boolean on) {
        pinInput.setEnabled(on);
        primary.setEnabled(on);
    }

    // ── Biometric ────────────────────────────────────────────
    private boolean canUseBiometric() {
        return BiometricManager.from(this)
                .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                == BiometricManager.BIOMETRIC_SUCCESS;
    }

    private void showBiometric() {
        if (!canUseBiometric()) return;
        BiometricPrompt prompt = new BiometricPrompt(this, ContextCompat.getMainExecutor(this),
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                        onVerified();
                    }
                });
        BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock ExpensOS")
                .setNegativeButtonText("Use PIN")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .build();
        prompt.authenticate(info);
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    @Override
    protected void onDestroy() {
        if (lockoutTimer != null) lockoutTimer.cancel();
        if (isFinishing() && mode == MODE_UNLOCK) AppLockManager.setLockScreenShowing(false);
        super.onDestroy();
    }
}
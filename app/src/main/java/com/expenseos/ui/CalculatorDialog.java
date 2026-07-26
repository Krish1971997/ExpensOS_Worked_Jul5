package com.expenseos.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import com.expenseos.R;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Basic +/-/×/÷ calculator shown as a popup from the Amount field. Standard
 * operator precedence (× and ÷ evaluated before + and −), left-to-right
 * within each precedence level. No parentheses — not needed for quick
 * "12+8*2"-style amount math.
 *
 * "=" evaluates in place (like a normal calculator, keeps the dialog open so
 * you can keep computing from the result). "Enter Result" is the separate
 * confirm action that writes the current value back into the Amount field
 * and closes the dialog.
 */
public class CalculatorDialog {

    public interface OnResult {
        void onResult(String amountText);
    }

    public static void show(Context ctx, String initialValue, OnResult callback) {
        View v = LayoutInflater.from(ctx).inflate(R.layout.dialog_calculator, null);
        TextView display = v.findViewById(R.id.tvCalcDisplay);
        TextView preview = v.findViewById(R.id.tvCalcPreview);

        String seed = (initialValue != null && !initialValue.trim().isEmpty()
                && !"0".equals(initialValue.trim())) ? initialValue.trim() : "";
        StringBuilder expr = new StringBuilder(seed);
        display.setText(expr.length() == 0 ? "0" : prettify(expr.toString()));
        updatePreview(preview, expr.toString());

        AlertDialog dialog = new AlertDialog.Builder(ctx).setView(v).setCancelable(true).create();
        if (dialog.getWindow() != null)
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

        // ── Digits + decimal point ───────────────────────────────
        View.OnClickListener digitClick = view -> {
            expr.append(((TextView) view).getText().toString());
            display.setText(prettify(expr.toString()));
            updatePreview(preview, expr.toString());
        };
        int[] digitIds = {R.id.btnCalc0, R.id.btnCalc00, R.id.btnCalc1, R.id.btnCalc2, R.id.btnCalc3,
                R.id.btnCalc4, R.id.btnCalc5, R.id.btnCalc6, R.id.btnCalc7, R.id.btnCalc8, R.id.btnCalc9,
                R.id.btnCalcDot};
        for (int id : digitIds) v.findViewById(id).setOnClickListener(digitClick);

        // ── Operators — replace a trailing operator instead of stacking one ──
        View.OnClickListener opClick = view -> {
            String sym = ((TextView) view).getText().toString();
            char op = symbolToOp(sym);
            if (expr.length() == 0) return; // no leading operator
            char last = expr.charAt(expr.length() - 1);
            if (isOp(last)) expr.setLength(expr.length() - 1);
            expr.append(op);
            display.setText(prettify(expr.toString()));
            updatePreview(preview, expr.toString());
        };
        v.findViewById(R.id.btnCalcPlus).setOnClickListener(opClick);
        v.findViewById(R.id.btnCalcMinus).setOnClickListener(opClick);
        v.findViewById(R.id.btnCalcMul).setOnClickListener(opClick);
        v.findViewById(R.id.btnCalcDiv).setOnClickListener(opClick);

        v.findViewById(R.id.btnCalcBackspace).setOnClickListener(view -> {
            if (expr.length() > 0) expr.setLength(expr.length() - 1);
            display.setText(expr.length() == 0 ? "0" : prettify(expr.toString()));
            updatePreview(preview, expr.toString());
        });

        v.findViewById(R.id.btnCalcClear).setOnClickListener(view -> {
            expr.setLength(0);
            display.setText("0");
            updatePreview(preview, "");
        });

        // "=" — evaluate in place, keep dialog open so you can keep computing
        v.findViewById(R.id.btnCalcEquals).setOnClickListener(view -> {
            try {
                double result = evaluate(expr.toString());
                String resultStr = formatResult(result);
                expr.setLength(0);
                expr.append(resultStr);
                display.setText(resultStr);
                updatePreview(preview, resultStr);
            } catch (Exception e) {
                display.setText("Error");
            }
        });

        // "Enter Result" — confirm + write back into the Amount field
        v.findViewById(R.id.btnCalcEnterResult).setOnClickListener(view -> {
            try {
                double result = evaluate(expr.toString());
                if (callback != null) callback.onResult(formatResult(result));
                dialog.dismiss();
            } catch (Exception e) {
                display.setText("Error");
            }
        });

        dialog.show();
    }

    private static void updatePreview(TextView preview, String expr) {
        try {
            if (expr == null || expr.isEmpty()) {
                preview.setText("= 0");
                return;
            }
            preview.setText("= " + formatResult(evaluate(expr)));
        } catch (Exception e) {
            preview.setText("");
        }
    }

    private static boolean isOp(char c) {
        return c == '+' || c == '-' || c == '*' || c == '/';
    }

    private static char symbolToOp(String sym) {
        switch (sym) {
            case "÷": return '/';
            case "×": return '*';
            case "−": return '-';
            default: return '+';
        }
    }

    // For display only — keeps the pretty ÷ × − symbols even though the
    // internal expr StringBuilder stores plain + - * / for parsing.
    private static String prettify(String expr) {
        return expr.replace("/", "÷").replace("*", "×").replace("-", "−");
    }

    /** Two-pass evaluator: × and ÷ first, then + and −, both left-to-right. */
    private static double evaluate(String rawExpr) {
        String expr = rawExpr;
        if (expr.isEmpty()) return 0;
        char last = expr.charAt(expr.length() - 1);
        if (isOp(last)) expr = expr.substring(0, expr.length() - 1);
        if (expr.isEmpty()) return 0;

        List<Double> numbers = new ArrayList<>();
        List<Character> ops = new ArrayList<>();
        StringBuilder numBuf = new StringBuilder();
        for (int i = 0; i < expr.length(); i++) {
            char c = expr.charAt(i);
            if (isOp(c)) {
                numbers.add(Double.parseDouble(numBuf.toString()));
                numBuf.setLength(0);
                ops.add(c);
            } else {
                numBuf.append(c);
            }
        }
        numbers.add(Double.parseDouble(numBuf.toString()));

        // Pass 1: × and ÷
        List<Double> nums2 = new ArrayList<>();
        List<Character> ops2 = new ArrayList<>();
        nums2.add(numbers.get(0));
        for (int i = 0; i < ops.size(); i++) {
            char op = ops.get(i);
            double next = numbers.get(i + 1);
            if (op == '*' || op == '/') {
                double prev = nums2.remove(nums2.size() - 1);
                nums2.add(op == '*' ? prev * next : prev / next);
            } else {
                nums2.add(next);
                ops2.add(op);
            }
        }

        // Pass 2: + and −
        double result = nums2.get(0);
        for (int i = 0; i < ops2.size(); i++) {
            double next = nums2.get(i + 1);
            result = ops2.get(i) == '+' ? result + next : result - next;
        }
        return result;
    }

    private static String formatResult(double d) {
        BigDecimal bd = BigDecimal.valueOf(d).setScale(2, RoundingMode.HALF_UP);
        if (bd.remainder(BigDecimal.ONE).compareTo(BigDecimal.ZERO) == 0)
            return bd.setScale(0, RoundingMode.HALF_UP).toPlainString();
        return bd.toPlainString();
    }
}

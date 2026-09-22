package com.expenseos.util;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Central orchestrator: one user turn → possibly several candidate attempts
 * (provider → model → key), following the configured priority order.
 * <p>
 * Error policy:
 * - NETWORK / SERVER / timeout: up to {@link #NET_RETRIES} bounded retries on
 * the SAME candidate (short backoff), then move to the next candidate.
 * - RATE_LIMIT (429/quota/credit): no same-key retry — cool the key down,
 * move to the next key/model immediately.
 * - AUTH (invalid/expired key): long cooldown, move on — never re-hammered.
 * - MODEL_UNAVAILABLE: cool the model down, move on.
 * - BAD_REQUEST / UNKNOWN: not a key/model problem — abort without burning
 * other keys.
 * <p>
 * Cooldowns are process-lifetime: a cooled key is skipped on subsequent turns
 * until its cooldown elapses, then rechecked naturally.
 * Exactly one turn runs at any time ({@link #inFlight} guard).
 */
public class AiFailoverManager {

    private static final int NET_RETRIES = 2;          // extra attempts after the first failure
    private static final long NET_BACKOFF_MS = 1200;

    /**
     * Progress callback bridge (called on a worker thread).
     */
    public interface UiHooks {
        default void onFailover(String maskedCandidateLabel) {
        }
    }

    // Process-wide cooldown state shared across turns
    private static final Object COOLDOWN_LOCK = new Object();
    private static final java.util.HashMap<String, Long> keyCooldown = new java.util.HashMap<>();
    private static final java.util.HashMap<String, Long> modelCooldown = new java.util.HashMap<>();

    private static final long KEY_COOLDOWN_MS = 60_000;           // 1 min after 429/quota
    private static final long KEY_AUTH_COOLDOWN_MS = 30 * 60_000; // 30 min after invalid key
    private static final long MODEL_COOLDOWN_MS = 60_000;         // 1 min after model-404

    private static String keyId(AiCandidate c) {
        return c.provider + "|" + c.model + "|" + c.apiKey;
    }

    private static boolean keyCooling(AiCandidate c, long now) {
        synchronized (COOLDOWN_LOCK) {
            Long until = keyCooldown.get(keyId(c));
            return until != null && now < until;
        }
    }

    private static boolean modelCooling(AiCandidate c, long now) {
        synchronized (COOLDOWN_LOCK) {
            Long until = modelCooldown.get(c.provider + "|" + c.model);
            return until != null && now < until;
        }
    }

    private static void markFailed(AiCandidate c, AiException.Kind kind, long now) {
        synchronized (COOLDOWN_LOCK) {
            switch (kind) {
                case RATE_LIMIT:
                    keyCooldown.put(keyId(c), now + KEY_COOLDOWN_MS);
                    break;
                case AUTH:
                    keyCooldown.put(keyId(c), now + KEY_AUTH_COOLDOWN_MS);
                    break;
                case MODEL_UNAVAILABLE:
                    modelCooldown.put(c.provider + "|" + c.model, now + MODEL_COOLDOWN_MS);
                    break;
                default:
                    // SERVER / NETWORK / BAD_REQUEST / UNKNOWN — no cooldown;
                    // failover advances past this candidate for this turn only.
                    break;
            }
        }
    }

    private final List<AiCandidate> candidates;
    private final Context ctx;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean inFlight = new AtomicBoolean(false);

    // Chart/image output of the successful attempt (read by the chat after runTurn).
    private List<String> lastChartPaths = new ArrayList<>();
    private String lastImagePath;

    public AiFailoverManager(Context ctx, List<AiCandidate> candidates) {
        this.ctx = ctx.getApplicationContext();
        this.candidates = candidates != null ? candidates : new ArrayList<>();
    }

    public boolean isInFlight() {
        return inFlight.get();
    }

    public List<String> getLastChartPaths() {
        return lastChartPaths;
    }

    public String getLastImagePath() {
        return lastImagePath;
    }

    /**
     * Runs the turn, blocking the calling (background) thread until success or
     * final failure.
     *
     * @return the assistant answer text
     * @throws AiException carrying the last user-safe error if everything failed
     */
    public String runTurn(AiRequest request, AiProvider.Callback cb, UiHooks hooks) throws AiException {
        if (!inFlight.compareAndSet(false, true)) {
            throw new AiException(AiException.Kind.UNKNOWN, "A request is already running — wait for it to finish.");
        }
        try {
            return runTurnInternal(request, cb, hooks);
        } finally {
            inFlight.set(false);
        }
    }

    private static final long TOTAL_TURN_BUDGET_MS = 75_000; // stay under ChatActivity's 90s watchdog

    private String runTurnInternal(AiRequest request, AiProvider.Callback cb, UiHooks hooks) throws AiException {
        if (candidates.isEmpty()) {
            throw new AiException(AiException.Kind.UNKNOWN,
                    "No AI model configured — add a model and API key in Config.");
        }

        long turnStart = System.currentTimeMillis();
        long now = turnStart;
        AiException last = null;
        boolean anyTried = false;

        // Single deterministic pass in priority order: keys of model 1, then
        // model 2, … Cooled keys/models are skipped for this turn. Stop
        // early (rather than let ChatActivity's watchdog time us out mid-
        // candidate) if we're eating into the 90s UI budget — an orphaned
        // background attempt is exactly what corrupts a later turn's UI.
        for (int idx = 0; idx < candidates.size(); idx++) {
            if (System.currentTimeMillis() - turnStart > TOTAL_TURN_BUDGET_MS) {
                break;
            }
            AiCandidate cand = candidates.get(idx);
            if (keyCooling(cand, now) || modelCooling(cand, now)) continue;
            anyTried = true;

            int attempts = 0;
            while (true) {
                attempts++;
                AiProvider client = AiClientFactory.create(ctx);
                try {
                    cb.onProgress(!anyTried && attempts == 1
                            ? "Thinking…"
                            : "Trying " + cand.maskedLabel() + "…");
                    final String[] answerArr = new String[1];
                    final Exception[] errArr = new Exception[1];
                    final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);

                    client.ask(request.userMessage, request.imagePath, request.history, new AiProvider.Callback() {
                        @Override
                        public void onResult(String answer) {
                            answerArr[0] = answer;
                            latch.countDown();
                        }

                        @Override
                        public void onError(String message) {
                            errArr[0] = new Exception(message);
                            latch.countDown();
                        }

                        @Override
                        public void onProgress(String stage) {
                            cb.onProgress(stage);
                        }
                    });

                    try {
                        latch.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }

                    if (errArr[0] != null) {
                        throw new AiException(AiException.Kind.UNKNOWN, errArr[0].getMessage());
                    }
                    String answer = answerArr[0];
                    captureOutput(client);
                    rememberActive(cand);
                    return answer;
                } catch (AiException ae) {
                    last = ae;
                    if (ae.kind == AiException.Kind.NETWORK || ae.kind == AiException.Kind.SERVER) {
                        if (attempts <= NET_RETRIES) {
                            sleep(NET_BACKOFF_MS * attempts);
                            continue; // bounded retry on the same candidate
                        }
                    } else if (ae.kind == AiException.Kind.BAD_REQUEST || ae.kind == AiException.Kind.UNKNOWN) {
                        throw ae; // not a key/model problem — don't burn other keys
                    }
                    markFailed(cand, ae.kind, System.currentTimeMillis());
                    notifyFailover(cb, hooks, cand);
                    break; // next candidate
                } catch (Exception e) {
                    last = AiErrorClassifier.fromUnexpected(cand.providerLabel(), e, cand.apiKey);
                    notifyFailover(cb, hooks, cand);
                    break; // defensive: move on
                }
            }
        }

        if (!anyTried && last == null) {
            throw new AiException(AiException.Kind.RATE_LIMIT,
                    "All configured API keys are in cooldown (rate/quota limits). Try again in a minute.");
        }
        throw last != null ? last
                : new AiException(AiException.Kind.UNKNOWN, "AI request failed — all configured models/keys are unavailable.");
    }

    private void captureOutput(AiProvider client) {
        try {
            List<String> charts = new ArrayList<>();
            lastChartPaths = charts != null ? charts : new ArrayList<>();
            lastImagePath = client.getLastImagePath();
        } catch (Exception ignored) {
            lastChartPaths = new ArrayList<>();
            lastImagePath = null;
        }
    }

    private void notifyFailover(AiProvider.Callback cb, UiHooks hooks, AiCandidate failed) {
        if (hooks != null) {
            String label = failed.maskedLabel();
            mainHandler.post(() -> {
                try {
                    hooks.onFailover(label);
                } catch (Exception ignored) {
                }
            });
        }
    }

    private void rememberActive(AiCandidate cand) {
        try {
            AiConfigStore store = new AiConfigStore(ctx);
            AiConfigStore.AiConfig cfg = store.load();
            cfg.activeProvider = cand.provider;
            store.save(cfg);
        } catch (Exception ignored) {
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}

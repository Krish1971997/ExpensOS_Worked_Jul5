package com.expenseos.sync;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.expenseos.dao.TaskDao;
import com.expenseos.model.Task;
import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.Scope;
import com.google.api.client.extensions.android.http.AndroidHttp;
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.CalendarScopes;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventDateTime;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class GoogleCalendarSyncManager {

    private static final String CALENDAR_ID = "primary";
    private static final DateTimeFormatter TASK_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface Callback {
        void onDone(boolean ok, String message);
    }

    public static GoogleSignInClient getSignInClient(Context ctx) {
        GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestScopes(new Scope(CalendarScopes.CALENDAR))
                .build();
        return GoogleSignIn.getClient(ctx, gso);
    }

    public static boolean isSignedIn(Context ctx) {
        return GoogleSignIn.getLastSignedInAccount(ctx) != null;
    }

    public static String signedInEmail(Context ctx) {
        GoogleSignInAccount acc = GoogleSignIn.getLastSignedInAccount(ctx);
        return acc != null ? acc.getEmail() : null;
    }

    public static void signOut(Context ctx, Runnable after) {
        getSignInClient(ctx).signOut().addOnCompleteListener(t -> {
            if (after != null) after.run();
        });
    }

    private static Calendar buildService(Context ctx) {
        GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(ctx);
        if (account == null || account.getAccount() == null) return null;

        GoogleAccountCredential credential = GoogleAccountCredential.usingOAuth2(
                ctx, Collections.singleton(CalendarScopes.CALENDAR));
        credential.setSelectedAccount(account.getAccount());

        return new Calendar.Builder(AndroidHttp.newCompatibleTransport(), GsonFactory.getDefaultInstance(), credential)
                .setApplicationName("ExpenseOS")
                .build();
    }

    /**
     * Insert or update this task as a Google Calendar event; stores the returned event id back on the task.
     */
    public static void upsert(Context ctx, long taskId, Callback cb) {
        executor.execute(() -> {
            try {
                Calendar service = buildService(ctx);
                if (service == null) {
                    post(cb, false, "Not signed in to Google");
                    return;
                }

                TaskDao dao = new TaskDao(ctx);
                Task task = dao.findById(taskId);
                if (task == null) {
                    post(cb, false, "Task not found");
                    return;
                }

                LocalDateTime dt = LocalDateTime.parse(task.getTaskDateTime(), TASK_FMT);
                String zone = ZoneId.systemDefault().getId();
                DateTime start = new DateTime(dt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
                DateTime end = new DateTime(dt.plusHours(1).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());

                Event event = new Event()
                        .setSummary(task.getName())
                        .setDescription(task.getDescription())
                        .setStart(new EventDateTime().setDateTime(start).setTimeZone(zone))
                        .setEnd(new EventDateTime().setDateTime(end).setTimeZone(zone));

                Event result;
                if (task.getGoogleEventId() != null && !task.getGoogleEventId().isEmpty()) {
                    result = service.events().update(CALENDAR_ID, task.getGoogleEventId(), event).execute();
                } else {
                    result = service.events().insert(CALENDAR_ID, event).execute();
                    dao.updateGoogleEventId(taskId, result.getId());
                }
                post(cb, true, "Synced to Google Calendar");
            } catch (Exception e) {
                post(cb, false, e.getMessage() != null ? e.getMessage() : e.toString());
            }
        });
    }

    public static void delete(Context ctx, String googleEventId, Callback cb) {
        if (googleEventId == null || googleEventId.isEmpty()) {
            post(cb, true, "Nothing to delete");
            return;
        }
        executor.execute(() -> {
            try {
                Calendar service = buildService(ctx);
                if (service == null) {
                    post(cb, false, "Not signed in to Google");
                    return;
                }
                service.events().delete(CALENDAR_ID, googleEventId).execute();
                post(cb, true, "Removed from Google Calendar");
            } catch (Exception e) {
                post(cb, false, e.getMessage() != null ? e.getMessage() : e.toString());
            }
        });
    }

    private static void post(Callback cb, boolean ok, String msg) {
        if (cb != null) mainHandler.post(() -> cb.onDone(ok, msg));
    }
}
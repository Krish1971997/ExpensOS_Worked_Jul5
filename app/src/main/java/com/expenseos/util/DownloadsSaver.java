package com.expenseos.util;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/**
 * Saves a generated report to the device's Downloads folder.
 *
 * API 29+ (targetSdk 34 enforces scoped storage): goes through
 * MediaStore.Downloads — no permission needed, works out of the box.
 *
 * API 26–28: scoped storage doesn't apply yet, but writing to the public
 * Downloads directory still needs WRITE_EXTERNAL_STORAGE, which the calling
 * Activity must check/request *before* calling save() — this class doesn't
 * request permissions itself since that requires an Activity, not just a
 * Context.
 */
public class DownloadsSaver {

    public static class Result {
        public final Uri uri;
        public final String displayLocation;

        Result(Uri uri, String displayLocation) {
            this.uri = uri;
            this.displayLocation = displayLocation;
        }
    }

    public interface Writer {
        void write(OutputStream out) throws Exception;
    }

    public static Result save(Context ctx, String fileName, String mimeType, Writer writer) throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return saveViaMediaStore(ctx, fileName, mimeType, writer);
        } else {
            return saveLegacy(fileName, writer);
        }
    }

    private static Result saveViaMediaStore(Context ctx, String fileName, String mimeType, Writer writer) throws Exception {
        ContentResolver resolver = ctx.getContentResolver();
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
        cv.put(MediaStore.Downloads.MIME_TYPE, mimeType);
        cv.put(MediaStore.Downloads.IS_PENDING, 1);

        Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
        Uri item = resolver.insert(collection, cv);
        if (item == null) throw new Exception("Could not create file in Downloads");

        try (OutputStream out = resolver.openOutputStream(item)) {
            writer.write(out);
        }

        cv.clear();
        cv.put(MediaStore.Downloads.IS_PENDING, 0);
        resolver.update(item, cv, null, null);

        return new Result(item, "Downloads/" + fileName);
    }

    // Requires WRITE_EXTERNAL_STORAGE granted by the caller on API 26–28.
    private static Result saveLegacy(String fileName, Writer writer) throws Exception {
        File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (!dir.exists()) dir.mkdirs();
        File file = new File(dir, fileName);
        try (OutputStream out = new FileOutputStream(file)) {
            writer.write(out);
        }
        return new Result(Uri.fromFile(file), file.getAbsolutePath());
    }
}

package app.morphe.extension.shared.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.preference.Preference;
import android.provider.MediaStore;
import android.util.AttributeSet;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * A preference that saves the recent Morphe log messages to a file.
 * <p>
 * On Android 10+ the file is written to the public Download folder
 * (Download/Morphe) using MediaStore, so it can easily be shared.
 * On older devices it is written to the app specific external files directory.
 */
@SuppressWarnings({"unused", "deprecation"})
public class SaveLogFilePreference extends Preference implements Preference.OnPreferenceClickListener {

    public SaveLogFilePreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init();
    }
    public SaveLogFilePreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }
    public SaveLogFilePreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }
    public SaveLogFilePreference(Context context) {
        super(context);
        init();
    }

    private void init() {
        setOnPreferenceClickListener(this);
    }

    @Override
    public boolean onPreferenceClick(Preference preference) {
        try {
            saveLogsToFile();
        } catch (Exception ex) {
            Logger.printException(() -> "SaveLogFilePreference failure", ex);
            Utils.showToastLong("Failed to save log file: " + ex.getMessage());
        }
        return true;
    }

    private static void saveLogsToFile() {
        final Context context = Utils.getContext();
        final String logs = Logger.getFilteredLogs();
        if (logs == null || logs.trim().isEmpty()) {
            Utils.showToastShort(str("morphe_debug_logs_none_found"));
            return;
        }

        final String fileName = "Morphe_log_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".txt";

        // Run off the main thread, since writing the file is blocking IO.
        Utils.runOnBackgroundThread(() -> {
            try {
                final String location;
                if (Utils.isSDKAbove(29)) {
                    location = writeUsingMediaStore(context, fileName, logs);
                } else {
                    location = writeToAppSpecificDirectory(context, fileName, logs);
                }
                Utils.showToastLong("Log saved to: " + location);
                Logger.printInfo(() -> "Saved log file to: " + location);
            } catch (Exception ex) {
                Logger.printException(() -> "saveLogsToFile failure", ex);
                Utils.showToastLong("Failed to save log file: " + ex.getMessage());
            }
        });
    }

    /**
     * Isolated into it's own method, so older devices never verify
     * the MediaStore.Downloads API references (added in API 29).
     */
    private static String writeUsingMediaStore(Context context, String fileName, String logs) throws Exception {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Morphe");

        Uri uri = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            throw new IllegalStateException("MediaStore insert failed");
        }
        try (OutputStream out = context.getContentResolver().openOutputStream(uri, "rwt")) {
            if (out == null) {
                throw new IllegalStateException("Could not open output stream");
            }
            out.write(logs.getBytes(StandardCharsets.UTF_8));
        }
        return Environment.DIRECTORY_DOWNLOADS + "/Morphe/" + fileName;
    }

    private static String writeToAppSpecificDirectory(Context context, String fileName, String logs) throws Exception {
        File dir = context.getExternalFilesDir(null);
        if (dir == null) {
            dir = context.getFilesDir();
        }
        final File file = new File(dir, fileName);
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(logs.getBytes(StandardCharsets.UTF_8));
        }
        return file.getAbsolutePath();
    }
}

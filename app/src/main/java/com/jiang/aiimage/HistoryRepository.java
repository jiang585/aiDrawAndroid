package com.jiang.aiimage;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class HistoryRepository {
    private static final int MAX_HISTORY_ITEMS = 20;
    private static final String HISTORY = "ai_image_history";

    private final Context context;
    private final SharedPreferences preferences;

    public HistoryRepository(Context context, SharedPreferences preferences) {
        this.context = context.getApplicationContext();
        this.preferences = preferences;
    }

    public File storeImage(byte[] bytes) throws IOException {
        File dir = new File(context.getFilesDir(), "generated_images");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("无法创建历史目录");
        }
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
        File file = new File(dir, "ai_image_" + stamp + ".png");
        try (FileOutputStream outputStream = new FileOutputStream(file)) {
            outputStream.write(bytes);
        }
        return file;
    }

    public void add(File imageFile, String mode, String prompt) throws JSONException {
        JSONObject item = new JSONObject();
        item.put("filePath", imageFile.getAbsolutePath());
        item.put("mode", mode);
        item.put("prompt", prompt);
        item.put("createdAt", new Date().getTime());

        JSONArray oldItems = load();
        JSONArray newItems = new JSONArray();
        newItems.put(item);
        for (int i = 0; i < oldItems.length() && i < MAX_HISTORY_ITEMS - 1; i++) {
            newItems.put(oldItems.optJSONObject(i));
        }

        // 历史只保留最近 20 条，避免图片文件无限占用手机空间。
        for (int i = MAX_HISTORY_ITEMS - 1; i < oldItems.length(); i++) {
            JSONObject stale = oldItems.optJSONObject(i);
            if (stale != null) {
                deleteQuietly(stale.optString("filePath", ""));
            }
        }

        preferences.edit().putString(HISTORY, newItems.toString()).apply();
    }

    public JSONArray load() {
        try {
            return new JSONArray(preferences.getString(HISTORY, "[]"));
        } catch (JSONException error) {
            return new JSONArray();
        }
    }

    public void clear() {
        JSONArray items = load();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null) {
                deleteQuietly(item.optString("filePath", ""));
            }
        }
        preferences.edit().putString(HISTORY, "[]").apply();
    }

    public byte[] readImageBytes(String path) throws IOException {
        try (FileInputStream inputStream = new FileInputStream(path)) {
            return readBytes(inputStream);
        }
    }

    private byte[] readBytes(InputStream inputStream) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = inputStream.read(buffer)) != -1) {
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private void deleteQuietly(String path) {
        if (path == null || path.isEmpty()) {
            return;
        }
        try {
            File file = new File(path);
            if (file.exists()) {
                file.delete();
            }
        } catch (Exception ignored) {
        }
    }
}

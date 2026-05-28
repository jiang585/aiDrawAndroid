package com.jiang.aiimage;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class ImageHostUploader {
    public static final String HOST_PICUI = "picui";
    public static final String HOST_CATBOX = "catbox";

    private static final int MAX_REFERENCE_IMAGE_BYTES = 10 * 1024 * 1024;
    private static final String PICUI_UPLOAD_URL = "https://www.picui.cn/api/v1/upload";
    private static final String CATBOX_UPLOAD_URL = "https://catbox.moe/user/api.php";

    public String upload(Context context, Uri uri, String host) throws IOException {
        if (HOST_CATBOX.equals(host)) {
            return uploadToCatbox(context, uri);
        }
        return uploadToPicui(context, uri);
    }

    private String uploadToPicui(Context context, Uri uri) throws IOException {
        UploadedImage image = readUploadImage(context, uri);
        String boundary = "AiImageAndroidBoundary" + System.currentTimeMillis();
        HttpURLConnection connection = openMultipart(PICUI_UPLOAD_URL, boundary, "application/json");

        try (OutputStream output = connection.getOutputStream()) {
            writeMultipartFile(output, boundary, "file", image.fileName, image.mime, image.bytes);
            writeUtf8(output, "--" + boundary + "--\r\n");
        }

        String responseText = readResponseOrThrow(connection, "PICUI 上传失败");
        try {
            JSONObject response = new JSONObject(responseText);
            JSONObject data = response.optJSONObject("data");
            JSONObject links = data == null ? null : data.optJSONObject("links");
            String url = links == null ? "" : links.optString("url", "");
            if (isHttpUrl(url)) {
                return url;
            }
            throw new IOException(extractMessage(response, "PICUI 没有返回图片链接"));
        } catch (JSONException error) {
            throw new IOException("PICUI 返回格式无法解析：" + responseText);
        }
    }

    private String uploadToCatbox(Context context, Uri uri) throws IOException {
        UploadedImage image = readUploadImage(context, uri);
        String boundary = "AiImageAndroidBoundary" + System.currentTimeMillis();
        HttpURLConnection connection = openMultipart(CATBOX_UPLOAD_URL, boundary, "text/plain");

        try (OutputStream output = connection.getOutputStream()) {
            writeMultipartText(output, boundary, "reqtype", "fileupload");
            writeMultipartFile(output, boundary, "fileToUpload", image.fileName, image.mime, image.bytes);
            writeUtf8(output, "--" + boundary + "--\r\n");
        }

        String responseText = readResponseOrThrow(connection, "Catbox 上传失败");
        if (!isHttpUrl(responseText)) {
            throw new IOException("Catbox 没有返回图片链接：" + responseText);
        }
        return responseText;
    }

    private HttpURLConnection openMultipart(String targetUrl, String boundary, String accept) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(targetUrl).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(60000);
        connection.setReadTimeout(120000);
        connection.setDoOutput(true);
        connection.setUseCaches(false);
        connection.setRequestProperty("Accept", accept);
        connection.setRequestProperty("User-Agent", "AIImageAndroid/1.01");
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        return connection;
    }

    // 图床接口都需要 multipart 文件流，这里统一把 Android Uri 转成安全的文件名、MIME 和字节数组。
    private UploadedImage readUploadImage(Context context, Uri uri) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        String mime = resolver.getType(uri);
        if (mime == null || mime.trim().isEmpty()) {
            mime = "image/png";
        }

        byte[] imageBytes;
        try (InputStream inputStream = resolver.openInputStream(uri)) {
            if (inputStream == null) {
                throw new IOException("读取参考图失败");
            }
            imageBytes = readBytes(inputStream, MAX_REFERENCE_IMAGE_BYTES);
        }
        return new UploadedImage(imageBytes, mime, safeFileName(getDisplayName(context, uri), mime));
    }

    private void writeMultipartText(OutputStream output, String boundary, String name, String value) throws IOException {
        writeUtf8(output, "--" + boundary + "\r\n");
        writeUtf8(output, "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n");
        writeUtf8(output, value + "\r\n");
    }

    private void writeMultipartFile(OutputStream output, String boundary, String name, String fileName, String mime, byte[] bytes) throws IOException {
        writeUtf8(output, "--" + boundary + "\r\n");
        writeUtf8(output, "Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + fileName + "\"\r\n");
        writeUtf8(output, "Content-Type: " + mime + "\r\n\r\n");
        output.write(bytes);
        writeUtf8(output, "\r\n");
    }

    private String readResponseOrThrow(HttpURLConnection connection, String errorPrefix) throws IOException {
        int code = connection.getResponseCode();
        InputStream stream = code >= 200 && code < 300
                ? connection.getInputStream()
                : connection.getErrorStream();
        String responseText = stream == null ? "" : readText(stream).trim();
        if (code < 200 || code >= 300) {
            throw new IOException(errorPrefix + "，HTTP " + code + "：" + responseText);
        }
        return responseText;
    }

    private byte[] readBytes(InputStream inputStream, int maxBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int count;
        while ((count = inputStream.read(buffer)) != -1) {
            total += count;
            if (maxBytes > 0 && total > maxBytes) {
                throw new IOException("参考图过大，请选择小于 10MB 的图片");
            }
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private String readText(InputStream inputStream) throws IOException {
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
        }
        return builder.toString();
    }

    private void writeUtf8(OutputStream output, String value) throws IOException {
        output.write(value.getBytes(StandardCharsets.UTF_8));
    }

    private String getDisplayName(Context context, Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    return cursor.getString(index);
                }
            }
        } catch (Exception ignored) {
        }
        String fallback = uri.getLastPathSegment();
        return fallback == null ? "reference" : fallback;
    }

    private String safeFileName(String displayName, String mime) {
        String cleaned = displayName == null ? "" : displayName.trim();
        cleaned = cleaned.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        if (cleaned.isEmpty()) {
            cleaned = "reference";
        }
        if (!cleaned.contains(".")) {
            cleaned = cleaned + extensionForMime(mime);
        }
        return cleaned;
    }

    private String extensionForMime(String mime) {
        if ("image/jpeg".equalsIgnoreCase(mime) || "image/jpg".equalsIgnoreCase(mime)) {
            return ".jpg";
        }
        if ("image/webp".equalsIgnoreCase(mime)) {
            return ".webp";
        }
        return ".png";
    }

    private String extractMessage(JSONObject data, String fallback) {
        String message = data.optString("message", "");
        return message.isEmpty() ? fallback : message;
    }

    private boolean isHttpUrl(String value) {
        return value != null && (value.startsWith("http://") || value.startsWith("https://"));
    }

    private static class UploadedImage {
        final byte[] bytes;
        final String mime;
        final String fileName;

        UploadedImage(byte[] bytes, String mime, String fileName) {
            this.bytes = bytes;
            this.mime = mime;
            this.fileName = fileName;
        }
    }
}

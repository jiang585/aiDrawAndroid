package com.jiang.aiimage;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class DalleImageClient {
    private static final int CONNECT_TIMEOUT_MS = 60000;
    private static final int GENERATION_SUBMIT_READ_TIMEOUT_MS = 300000;
    private static final int POLL_READ_TIMEOUT_MS = 120000;
    private static final int DOWNLOAD_READ_TIMEOUT_MS = 180000;

    public GenerationTaskResult submitGeneration(String apiKey, String apiBase, ImageGenerationRequest request) throws IOException, JSONException {
        // 图生图和高分辨率任务有时会在提交接口同步等待模型结果，60 秒容易误判为失败。
        JSONObject response = requestJson(
                "POST",
                apiBase + "/v1/images/generations",
                request.toJson(),
                apiKey,
                GENERATION_SUBMIT_READ_TIMEOUT_MS,
                "提交绘图任务"
        );
        String imageUrl = extractImageUrl(response);
        String resultUrl = extractResultUrl(response);
        if (isBlank(imageUrl) && isBlank(resultUrl)) {
            throw new IOException(extractApiMessage(response, "任务创建失败"));
        }
        return new GenerationTaskResult(imageUrl, resultUrl);
    }

    public String pollResult(String resultUrl, String apiKey, ProgressReporter reporter) throws Exception {
        if (isBlank(resultUrl)) {
            throw new IOException("没有获取到结果查询地址");
        }

        for (int attempt = 1; attempt <= 40; attempt++) {
            Thread.sleep(3000);
            int percent = Math.min(90, 52 + attempt);
            notifyProgress(reporter, "轮询生成结果", percent, "正在查询生成状态 " + attempt + "/40");

            JSONObject response = requestJson("GET", resultUrl, null, apiKey, POLL_READ_TIMEOUT_MS, "查询生成结果");
            String status = extractStatus(response);
            String imageUrl = extractImageUrl(response);

            if ("failed".equalsIgnoreCase(status)) {
                throw new IOException("生成失败，请调整提示词后重试");
            }
            if ("completed".equalsIgnoreCase(status)
                    || "success".equalsIgnoreCase(status)
                    || !isBlank(imageUrl)) {
                if (isBlank(imageUrl)) {
                    throw new IOException("未获取到图片数据");
                }
                notifyProgress(reporter, "绘图完成", 90, "已获取图片地址，准备下载");
                return imageUrl;
            }

            String label = isBlank(status) ? "排队" : status;
            notifyProgress(reporter, "模型生成中", percent, "当前状态：" + label + "，第 " + attempt + "/40 次查询");
        }

        throw new IOException("生成超时，请稍后在历史或接口后台查看结果");
    }

    public byte[] loadImageBytes(String imageRef, String apiKey) throws IOException {
        if (imageRef.startsWith("data:")) {
            return decodeDataUrl(imageRef);
        }

        HttpURLConnection connection = (HttpURLConnection) new URL(imageRef).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(DOWNLOAD_READ_TIMEOUT_MS);
        connection.setRequestProperty("Authorization", "Bearer " + apiKey);
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new IOException("下载图片失败，HTTP " + code);
        }
        return readBytes(connection.getInputStream(), -1);
    }

    private JSONObject requestJson(String method, String targetUrl, JSONObject body, String apiKey, int readTimeoutMs, String actionLabel) throws IOException, JSONException {
        HttpURLConnection connection = (HttpURLConnection) new URL(targetUrl).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(readTimeoutMs);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Authorization", "Bearer " + apiKey);

        try {
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (OutputStream outputStream = connection.getOutputStream();
                     OutputStreamWriter writer = new OutputStreamWriter(outputStream, StandardCharsets.UTF_8)) {
                    writer.write(body.toString());
                }
            }

            int code = connection.getResponseCode();
            InputStream stream = code >= 200 && code < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream();
            String responseText = stream == null ? "" : readText(stream);
            JSONObject response = parseJsonOrWrap(responseText);

            if (code < 200 || code >= 300) {
                throw new IOException(extractApiMessage(response, "请求失败，HTTP " + code));
            }
            return response;
        } catch (SocketTimeoutException error) {
            throw new IOException(actionLabel + "超时，已等待约 " + (readTimeoutMs / 1000)
                    + " 秒。图生图或大尺寸任务可能较慢，可以重试或降低尺寸。", error);
        }
    }

    private String extractImageUrl(JSONObject data) {
        Object rawData = data.opt("data");
        if (rawData instanceof JSONArray) {
            JSONArray items = (JSONArray) rawData;
            JSONObject item = items.optJSONObject(0);
            if (item != null) {
                String b64 = item.optString("b64_json", "");
                if (!b64.isEmpty()) {
                    return normalizeImageRef(b64);
                }
                String url = item.optString("url", "");
                if (!url.isEmpty()) {
                    return url;
                }
            }
        }

        if (rawData instanceof JSONObject) {
            JSONObject dataObject = (JSONObject) rawData;
            String fromOutputs = extractFromOutputs(dataObject.optJSONArray("outputs"));
            if (!fromOutputs.isEmpty()) {
                return fromOutputs;
            }
            String image = dataObject.optString("image", "");
            if (!image.isEmpty()) {
                return normalizeImageRef(image);
            }
        }

        String topLevelOutputs = extractFromOutputs(data.optJSONArray("outputs"));
        if (!topLevelOutputs.isEmpty()) {
            return topLevelOutputs;
        }

        String image = data.optString("image", "");
        if (!image.isEmpty()) {
            return normalizeImageRef(image);
        }

        return "";
    }

    // 兼容不同中转接口的返回结构：OpenAI data[]、异步 outputs[]、顶层 image 都在这里统一处理。
    private String extractFromOutputs(JSONArray outputs) {
        if (outputs == null || outputs.length() == 0) {
            return "";
        }

        Object first = outputs.opt(0);
        if (first instanceof String) {
            return normalizeImageRef((String) first);
        }
        if (first instanceof JSONObject) {
            JSONObject output = (JSONObject) first;
            String b64 = output.optString("b64_json", "");
            if (!b64.isEmpty()) {
                return normalizeImageRef(b64);
            }
            String url = output.optString("url", "");
            if (!url.isEmpty()) {
                return url;
            }
            String image = output.optString("image", "");
            if (!image.isEmpty()) {
                return normalizeImageRef(image);
            }
        }
        return "";
    }

    private String extractResultUrl(JSONObject data) {
        Object rawData = data.opt("data");
        if (rawData instanceof JSONObject) {
            JSONObject urls = ((JSONObject) rawData).optJSONObject("urls");
            if (urls != null) {
                String get = urls.optString("get", "");
                if (!get.isEmpty()) {
                    return get;
                }
            }
        }

        JSONObject urls = data.optJSONObject("urls");
        if (urls != null) {
            return urls.optString("get", "");
        }
        return "";
    }

    private String extractStatus(JSONObject data) {
        Object rawData = data.opt("data");
        if (rawData instanceof JSONObject) {
            String status = ((JSONObject) rawData).optString("status", "");
            if (!status.isEmpty()) {
                return status;
            }
        }
        return data.optString("status", "");
    }

    private String extractApiMessage(JSONObject data, String fallback) {
        Object error = data.opt("error");
        if (error instanceof String && !((String) error).isEmpty()) {
            return (String) error;
        }
        if (error instanceof JSONObject) {
            String message = ((JSONObject) error).optString("message", "");
            if (!message.isEmpty()) {
                return message;
            }
        }
        String message = data.optString("message", "");
        return message.isEmpty() ? fallback : message;
    }

    private String normalizeImageRef(String value) {
        if (isBlank(value)) {
            return "";
        }
        String cleaned = value.trim();
        if (cleaned.startsWith("http://") || cleaned.startsWith("https://")) {
            return cleaned;
        }
        if (cleaned.startsWith("data:")) {
            return "data:image/png;base64," + sanitizeBase64(stripDataUrlPrefixes(cleaned));
        }
        return "data:image/png;base64," + sanitizeBase64(cleaned);
    }

    private byte[] decodeDataUrl(String imageRef) throws IOException {
        String payload = extractBase64Payload(imageRef);
        try {
            return Base64.decode(payload, Base64.DEFAULT);
        } catch (IllegalArgumentException error) {
            throw new IOException("图片 Base64 解码失败，接口返回的数据不是有效图片。", error);
        }
    }

    private String extractBase64Payload(String imageRef) throws IOException {
        if (isBlank(imageRef)) {
            throw new IOException("图片数据为空");
        }
        String payload = imageRef.trim();

        // 有些中转接口会返回 data URL，有些会把 data URL 再塞进 b64_json。
        // 用循环剥掉所有 data:*;base64, 前缀，避免重复前缀导致 bad base-64。
        while (payload.startsWith("data:")) {
            int comma = payload.indexOf(',');
            if (comma < 0 || comma == payload.length() - 1) {
                throw new IOException("图片数据格式不正确");
            }
            payload = payload.substring(comma + 1).trim();
        }
        return sanitizeBase64(payload);
    }

    private String stripDataUrlPrefixes(String value) {
        String payload = value == null ? "" : value.trim();
        while (payload.startsWith("data:")) {
            int comma = payload.indexOf(',');
            if (comma < 0 || comma == payload.length() - 1) {
                return payload;
            }
            payload = payload.substring(comma + 1).trim();
        }
        return payload;
    }

    private String sanitizeBase64(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "");
    }

    private JSONObject parseJsonOrWrap(String text) throws JSONException {
        if (isBlank(text)) {
            return new JSONObject().put("error", "空响应");
        }
        try {
            return new JSONObject(text);
        } catch (JSONException error) {
            return new JSONObject().put("error", text);
        }
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

    private byte[] readBytes(InputStream inputStream, int maxBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int count;
        while ((count = inputStream.read(buffer)) != -1) {
            total += count;
            if (maxBytes > 0 && total > maxBytes) {
                throw new IOException("图片过大");
            }
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private void notifyProgress(ProgressReporter reporter, String stage, int percent, String detail) {
        if (reporter != null) {
            reporter.onProgress(stage, percent, detail);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}

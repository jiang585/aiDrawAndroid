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
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class DalleImageClient {
    public GenerationTaskResult submitGeneration(String apiKey, String apiBase, ImageGenerationRequest request) throws IOException, JSONException {
        JSONObject response = requestJson("POST", apiBase + "/v1/images/generations", request.toJson(), apiKey);
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

            JSONObject response = requestJson("GET", resultUrl, null, apiKey);
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
            int comma = imageRef.indexOf(',');
            if (comma < 0) {
                throw new IOException("图片数据格式不正确");
            }
            return Base64.decode(imageRef.substring(comma + 1), Base64.DEFAULT);
        }

        HttpURLConnection connection = (HttpURLConnection) new URL(imageRef).openConnection();
        connection.setConnectTimeout(60000);
        connection.setReadTimeout(60000);
        connection.setRequestProperty("Authorization", "Bearer " + apiKey);
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new IOException("下载图片失败，HTTP " + code);
        }
        return readBytes(connection.getInputStream(), -1);
    }

    private JSONObject requestJson(String method, String targetUrl, JSONObject body, String apiKey) throws IOException, JSONException {
        HttpURLConnection connection = (HttpURLConnection) new URL(targetUrl).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(60000);
        connection.setReadTimeout(60000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Authorization", "Bearer " + apiKey);

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
    }

    private String extractImageUrl(JSONObject data) {
        Object rawData = data.opt("data");
        if (rawData instanceof JSONArray) {
            JSONArray items = (JSONArray) rawData;
            JSONObject item = items.optJSONObject(0);
            if (item != null) {
                String b64 = item.optString("b64_json", "");
                if (!b64.isEmpty()) {
                    return "data:image/png;base64," + b64;
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
                return "data:image/png;base64," + b64;
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
        if (value.startsWith("http://") || value.startsWith("https://")) {
            return value;
        }
        if (value.startsWith("data:")) {
            return value.replaceFirst("^(data:image/png;base64,)+", "data:image/png;base64,");
        }
        return "data:image/png;base64," + value;
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

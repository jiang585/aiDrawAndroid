package com.jiang.aiimage;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQUEST_IMAGE_ONE = 2101;
    private static final int REQUEST_IMAGE_TWO = 2102;
    private static final int REQUEST_WRITE_STORAGE = 2201;
    private static final int MAX_REFERENCE_IMAGE_BYTES = 10 * 1024 * 1024;
    private static final int MAX_HISTORY_ITEMS = 20;
    private static final String SETTINGS = "ai_image_settings";
    private static final String HISTORY = "ai_image_history";
    private static final String CATBOX_UPLOAD_URL = "https://catbox.moe/user/api.php";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final String[] ratioValues = {"1:1", "16:9", "9:16"};
    private final String[] imageSizeValues = {"auto", "1024x1024", "1536x1024", "1024x1536", "2048x2048", "3840x2160", "2160x3840"};
    private final String[] qualityValues = {"auto", "low", "medium", "high"};

    private SharedPreferences preferences;
    private EditText apiKeyInput;
    private EditText apiBaseInput;
    private EditText modelInput;
    private EditText promptInput;
    private EditText editPromptInput;
    private EditText imageUrlOneInput;
    private EditText imageUrlTwoInput;
    private Spinner ratioSpinner;
    private Spinner imageSizeSpinner;
    private Spinner qualitySpinner;
    private LinearLayout textForm;
    private LinearLayout imageForm;
    private TextView imageOneLabel;
    private TextView imageTwoLabel;
    private ImageView imageOnePreview;
    private ImageView imageTwoPreview;
    private TextView statusText;
    private TextView historyEmpty;
    private Button generateButton;
    private Button saveImageButton;
    private ProgressBar progressBar;
    private ImageView outputImage;
    private GridLayout historyGrid;

    private Uri imageUriOne;
    private Uri imageUriTwo;
    private File currentImageFile;
    private String currentMode = "text";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = getSharedPreferences(SETTINGS, MODE_PRIVATE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
        getWindow().setStatusBarColor(color("#F5F8F3"));
        getWindow().setNavigationBarColor(color("#F5F8F3"));

        buildUi();
        renderHistory();
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }

        Uri selected = data.getData();
        int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            getContentResolver().takePersistableUriPermission(selected, flags);
        } catch (SecurityException ignored) {
            // Some pickers return temporary permissions only, which are still enough for this session.
        }

        if (requestCode == REQUEST_IMAGE_ONE) {
            imageUriOne = selected;
            imageOneLabel.setText("参考图 1：" + getDisplayName(selected));
            showReferencePreview(selected, imageOnePreview);
        } else if (requestCode == REQUEST_IMAGE_TWO) {
            imageUriTwo = selected;
            imageTwoLabel.setText("参考图 2：" + getDisplayName(selected));
            showReferencePreview(selected, imageTwoPreview);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_WRITE_STORAGE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                saveCurrentImageToGallery();
            } else {
                toast("没有存储权限，无法保存到相册");
            }
        }
    }

    private void buildUi() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(color("#F5F8F3"));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(22), dp(18), dp(34));
        scrollView.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        root.addView(title("AI Image Studio", 31, true));
        TextView subtitle = text("文生图、图生图、历史保存，一套更适合手机端的 1.01。", 14, "#52615A");
        subtitle.setPadding(0, dp(5), 0, dp(16));
        root.addView(subtitle);

        root.addView(buildSettingsCard());
        root.addView(space(14));
        root.addView(buildModeCard());
        root.addView(space(14));
        root.addView(buildResultCard());
        root.addView(space(14));
        root.addView(buildHistoryCard());

        setContentView(scrollView);
    }

    private View buildSettingsCard() {
        LinearLayout card = card();
        card.addView(sectionTitle("接口"));

        apiKeyInput = input("输入 API Key");
        apiKeyInput.setSingleLine(true);
        apiKeyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        apiKeyInput.setText(preferences.getString("api_key", ""));
        card.addView(labeled("API Key", apiKeyInput));

        apiBaseInput = input(BuildConfig.DEFAULT_API_BASE);
        apiBaseInput.setSingleLine(true);
        apiBaseInput.setText(preferences.getString("api_base", BuildConfig.DEFAULT_API_BASE));
        card.addView(labeled("API Base", apiBaseInput));

        modelInput = input("gpt-image-2");
        modelInput.setSingleLine(true);
        modelInput.setText(preferences.getString("model", "gpt-image-2"));
        card.addView(labeled("模型", modelInput));

        qualitySpinner = spinner(new String[]{"自动质量", "低质量", "中等质量", "高质量"});
        qualitySpinner.setSelection(preferences.getInt("quality_index", 0));
        card.addView(labeled("质量", qualitySpinner));

        Button saveButton = primaryButton("保存设置");
        saveButton.setOnClickListener(view -> {
            preferences.edit()
                    .putString("api_key", apiKeyInput.getText().toString().trim())
                    .putString("api_base", cleanApiBase(apiBaseInput.getText().toString()))
                    .putString("model", cleanModel(modelInput.getText().toString()))
                    .putInt("quality_index", qualitySpinner.getSelectedItemPosition())
                    .apply();
            hideKeyboard();
            toast("设置已保存");
        });
        card.addView(saveButton);

        TextView hint = text("密钥只保存在本机。默认按 Dalle 兼容的 /v1/images/generations 请求。", 12, "#637067");
        hint.setPadding(0, dp(10), 0, 0);
        card.addView(hint);
        return card;
    }

    private View buildModeCard() {
        LinearLayout card = card();
        card.addView(sectionTitle("创作"));

        RadioGroup modes = new RadioGroup(this);
        modes.setOrientation(RadioGroup.HORIZONTAL);
        modes.setGravity(Gravity.CENTER_VERTICAL);

        RadioButton textMode = modeButton("文生图");
        textMode.setId(View.generateViewId());
        RadioButton imageMode = modeButton("图生图");
        imageMode.setId(View.generateViewId());
        modes.addView(textMode, weighted());
        modes.addView(imageMode, weighted());
        modes.check(textMode.getId());
        modes.setOnCheckedChangeListener((group, checkedId) -> {
            currentMode = checkedId == textMode.getId() ? "text" : "image";
            textForm.setVisibility("text".equals(currentMode) ? View.VISIBLE : View.GONE);
            imageForm.setVisibility("image".equals(currentMode) ? View.VISIBLE : View.GONE);
        });
        card.addView(modes);

        textForm = new LinearLayout(this);
        textForm.setOrientation(LinearLayout.VERTICAL);
        textForm.setPadding(0, dp(12), 0, 0);
        promptInput = input("例如：雨夜的霓虹街道，电影感，镜头虚化");
        promptInput.setMinLines(4);
        promptInput.setGravity(Gravity.TOP);
        textForm.addView(labeled("画面描述", promptInput));
        ratioSpinner = spinner(new String[]{"1:1 方形", "16:9 横屏", "9:16 竖屏"});
        textForm.addView(labeled("长宽比", ratioSpinner));
        card.addView(textForm);

        imageForm = new LinearLayout(this);
        imageForm.setOrientation(LinearLayout.VERTICAL);
        imageForm.setPadding(0, dp(12), 0, 0);
        imageForm.setVisibility(View.GONE);
        editPromptInput = input("例如：将人物换成赛博风格，背景更梦幻");
        editPromptInput.setMinLines(4);
        editPromptInput.setGravity(Gravity.TOP);
        imageForm.addView(labeled("编辑说明", editPromptInput));

        imageSizeSpinner = spinner(new String[]{"自动尺寸", "1024x1024 方形", "1536x1024 横屏", "1024x1536 竖屏", "2048x2048 2K", "3840x2160 4K 横屏", "2160x3840 4K 竖屏"});
        imageForm.addView(labeled("图生图尺寸", imageSizeSpinner));

        imageUrlOneInput = urlInput("https://example.com/reference-1.png");
        imageForm.addView(labeled("参考图链接 1（可选，优先使用）", imageUrlOneInput));
        Button chooseOne = secondaryButton("选择本地参考图 1");
        chooseOne.setOnClickListener(view -> pickImage(REQUEST_IMAGE_ONE));
        imageForm.addView(chooseOne);
        imageOneLabel = smallMuted("未选择本地参考图 1");
        imageForm.addView(imageOneLabel);
        imageOnePreview = previewBox();
        imageForm.addView(imageOnePreview, previewParams());

        imageUrlTwoInput = urlInput("https://example.com/reference-2.png");
        imageForm.addView(labeled("参考图链接 2（可选）", imageUrlTwoInput));
        Button chooseTwo = secondaryButton("选择本地参考图 2");
        chooseTwo.setOnClickListener(view -> pickImage(REQUEST_IMAGE_TWO));
        imageForm.addView(chooseTwo);
        imageTwoLabel = smallMuted("未选择本地参考图 2");
        imageForm.addView(imageTwoLabel);
        imageTwoPreview = previewBox();
        imageForm.addView(imageTwoPreview, previewParams());

        TextView imageHint = smallMuted("本地图片会自动上传到 Catbox 匿名图床后再提交；也可以直接填写自己的公网图片链接。");
        imageHint.setPadding(0, dp(6), 0, 0);
        imageForm.addView(imageHint);
        card.addView(imageForm);

        generateButton = primaryButton("开始生成");
        generateButton.setOnClickListener(view -> startGeneration());
        card.addView(generateButton);
        return card;
    }

    private View buildResultCard() {
        LinearLayout card = card();
        card.addView(sectionTitle("结果"));

        statusText = text("等待生成任务", 15, "#17201D");
        statusText.setGravity(Gravity.CENTER);
        statusText.setPadding(0, dp(8), 0, dp(8));
        card.addView(statusText, fullWidth());

        progressBar = new ProgressBar(this);
        progressBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(dp(44), dp(44));
        progressParams.gravity = Gravity.CENTER_HORIZONTAL;
        progressParams.setMargins(0, dp(4), 0, dp(12));
        card.addView(progressBar, progressParams);

        outputImage = new ImageView(this);
        outputImage.setAdjustViewBounds(true);
        outputImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        outputImage.setBackground(strokeDrawable("#DCE5DD", "#FBFCFA"));
        outputImage.setPadding(dp(8), dp(8), dp(8), dp(8));
        LinearLayout.LayoutParams imageParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(360)
        );
        card.addView(outputImage, imageParams);

        saveImageButton = secondaryButton("保存到相册");
        saveImageButton.setEnabled(false);
        saveImageButton.setOnClickListener(view -> saveCurrentImageToGallery());
        card.addView(saveImageButton);
        return card;
    }

    private View buildHistoryCard() {
        LinearLayout card = card();
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = sectionTitle("生成历史");
        header.addView(title, weighted());

        Button clear = secondaryButton("清空");
        clear.setOnClickListener(view -> clearHistory());
        header.addView(clear, new LinearLayout.LayoutParams(dp(86), dp(42)));
        card.addView(header);

        historyEmpty = smallMuted("暂无历史记录");
        historyEmpty.setPadding(0, dp(10), 0, dp(4));
        card.addView(historyEmpty);

        historyGrid = new GridLayout(this);
        historyGrid.setColumnCount(2);
        historyGrid.setUseDefaultMargins(true);
        card.addView(historyGrid, fullWidth());
        return card;
    }

    private void startGeneration() {
        String apiKey = apiKeyInput.getText().toString().trim();
        String apiBase = cleanApiBase(apiBaseInput.getText().toString());
        if (apiKey.isEmpty()) {
            toast("请先填写 API Key");
            return;
        }

        preferences.edit()
                .putString("api_key", apiKey)
                .putString("api_base", apiBase)
                .putString("model", cleanModel(modelInput.getText().toString()))
                .putInt("quality_index", qualitySpinner.getSelectedItemPosition())
                .apply();

        hideKeyboard();
        setBusy(true);
        statusText.setText("正在提交任务...");

        executor.execute(() -> {
            try {
                GenerationResult result = submitTask(apiKey, apiBase);
                String imageRef = result.imageUrl;
                if (imageRef == null || imageRef.isEmpty()) {
                    updateStatus("任务已提交，正在生成图像...");
                    imageRef = pollResult(result.resultUrl, apiKey);
                }

                byte[] imageBytes = loadImageBytes(imageRef, apiKey);
                String prompt = "text".equals(currentMode)
                        ? promptInput.getText().toString().trim()
                        : editPromptInput.getText().toString().trim();
                File stored = storeHistoryImage(imageBytes);
                addHistory(stored, currentMode, prompt);

                Bitmap bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
                mainHandler.post(() -> {
                    currentImageFile = stored;
                    outputImage.setImageBitmap(bitmap);
                    statusText.setText("生成完成");
                    saveImageButton.setEnabled(true);
                    setBusy(false);
                    renderHistory();
                });
            } catch (Exception error) {
                mainHandler.post(() -> {
                    statusText.setText(error.getMessage() == null ? "生成失败" : error.getMessage());
                    setBusy(false);
                });
            }
        });
    }

    private GenerationResult submitTask(String apiKey, String apiBase) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("model", cleanModel(modelInput.getText().toString()));
        payload.put("response_format", "b64_json");
        payload.put("quality", qualityValues[qualitySpinner.getSelectedItemPosition()]);

        if ("text".equals(currentMode)) {
            String prompt = promptInput.getText().toString().trim();
            if (prompt.isEmpty()) {
                throw new IOException("请填写画面描述");
            }
            payload.put("prompt", prompt);
            payload.put("size", mapAspectRatioToSize(ratioValues[ratioSpinner.getSelectedItemPosition()]));
        } else {
            String prompt = editPromptInput.getText().toString().trim();
            if (prompt.isEmpty()) {
                throw new IOException("请填写编辑说明");
            }

            JSONArray images = new JSONArray();
            ReferenceInput referenceOne = buildReference(imageUrlOneInput, imageUriOne, "参考图 1");
            ReferenceInput referenceTwo = buildReference(imageUrlTwoInput, imageUriTwo, "参考图 2");
            if (referenceOne.value != null) {
                images.put(referenceOne.value);
            }
            if (referenceTwo.value != null) {
                images.put(referenceTwo.value);
            }
            if (images.length() == 0) {
                throw new IOException("请至少选择一张本地参考图，或填写一个参考图链接");
            }

            payload.put("prompt", prompt);
            payload.put("size", imageSizeValues[imageSizeSpinner.getSelectedItemPosition()]);
            payload.put("image", images);
        }

        JSONObject response = requestJson("POST", apiBase + "/v1/images/generations", payload, apiKey);
        String imageUrl = extractImageUrl(response);
        String resultUrl = extractResultUrl(response);
        if ((imageUrl == null || imageUrl.isEmpty()) && (resultUrl == null || resultUrl.isEmpty())) {
            throw new IOException(extractApiMessage(response, "任务创建失败"));
        }
        return new GenerationResult(imageUrl, resultUrl);
    }

    private String pollResult(String resultUrl, String apiKey) throws Exception {
        if (resultUrl == null || resultUrl.isEmpty()) {
            throw new IOException("没有获取到结果查询地址");
        }

        for (int attempt = 1; attempt <= 40; attempt++) {
            Thread.sleep(3000);
            JSONObject response = requestJson("GET", resultUrl, null, apiKey);
            String status = extractStatus(response);
            String imageUrl = extractImageUrl(response);

            if ("failed".equalsIgnoreCase(status)) {
                throw new IOException("生成失败，请调整提示词后重试");
            }
            if ("completed".equalsIgnoreCase(status)
                    || "success".equalsIgnoreCase(status)
                    || (imageUrl != null && !imageUrl.isEmpty())) {
                if (imageUrl == null || imageUrl.isEmpty()) {
                    throw new IOException("未获取到图片数据");
                }
                return imageUrl;
            }

            String label = status == null || status.isEmpty() ? "排队" : status;
            updateStatus("正在生成中... (" + label + ", " + attempt + "/40)");
        }

        throw new IOException("生成超时，请稍后在历史或接口后台查看结果");
    }

    private ReferenceInput buildReference(EditText urlInput, Uri localUri, String label) throws IOException {
        String url = cleanOptionalUrl(urlInput == null ? "" : urlInput.getText().toString());
        if (!url.isEmpty()) {
            return new ReferenceInput(url);
        }
        if (localUri != null) {
            updateStatus("正在上传" + label + "到图床...");
            return new ReferenceInput(uploadImageToCatbox(localUri));
        }
        return new ReferenceInput(null);
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
        if (value == null || value.isEmpty()) {
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

    private byte[] loadImageBytes(String imageRef, String apiKey) throws IOException {
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

    private String uploadImageToCatbox(Uri uri) throws IOException {
        ContentResolver resolver = getContentResolver();
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

        String boundary = "AiImageAndroidBoundary" + System.currentTimeMillis();
        HttpURLConnection connection = (HttpURLConnection) new URL(CATBOX_UPLOAD_URL).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(60000);
        connection.setReadTimeout(120000);
        connection.setDoOutput(true);
        connection.setUseCaches(false);
        connection.setRequestProperty("Accept", "text/plain");
        connection.setRequestProperty("User-Agent", "AIImageAndroid/1.01");
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

        try (OutputStream output = connection.getOutputStream()) {
            writeMultipartText(output, boundary, "reqtype", "fileupload");
            writeMultipartFile(output, boundary, "fileToUpload", safeFileName(getDisplayName(uri), mime), mime, imageBytes);
            writeUtf8(output, "--" + boundary + "--\r\n");
        }

        int code = connection.getResponseCode();
        InputStream stream = code >= 200 && code < 300
                ? connection.getInputStream()
                : connection.getErrorStream();
        String responseText = stream == null ? "" : readText(stream).trim();
        if (code < 200 || code >= 300) {
            throw new IOException("图床上传失败，HTTP " + code + "：" + responseText);
        }
        if (!responseText.startsWith("http://") && !responseText.startsWith("https://")) {
            throw new IOException("图床没有返回图片链接：" + responseText);
        }
        return responseText;
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

    private void writeUtf8(OutputStream output, String value) throws IOException {
        output.write(value.getBytes(StandardCharsets.UTF_8));
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

    private JSONObject parseJsonOrWrap(String text) throws JSONException {
        if (text == null || text.isEmpty()) {
            return new JSONObject().put("error", "空响应");
        }
        try {
            return new JSONObject(text);
        } catch (JSONException error) {
            return new JSONObject().put("error", text);
        }
    }

    private String mapAspectRatioToSize(String aspectRatio) {
        if ("1:1".equals(aspectRatio)) {
            return "1024x1024";
        }
        if ("16:9".equals(aspectRatio)) {
            return "1536x1024";
        }
        if ("9:16".equals(aspectRatio)) {
            return "1024x1536";
        }
        return "auto";
    }

    private File storeHistoryImage(byte[] bytes) throws IOException {
        File dir = new File(getFilesDir(), "generated_images");
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

    private void addHistory(File imageFile, String mode, String prompt) throws JSONException {
        JSONObject item = new JSONObject();
        item.put("filePath", imageFile.getAbsolutePath());
        item.put("mode", mode);
        item.put("prompt", prompt);
        item.put("createdAt", new Date().getTime());

        JSONArray oldItems = loadHistory();
        JSONArray newItems = new JSONArray();
        newItems.put(item);
        for (int i = 0; i < oldItems.length() && i < MAX_HISTORY_ITEMS - 1; i++) {
            newItems.put(oldItems.optJSONObject(i));
        }

        for (int i = MAX_HISTORY_ITEMS - 1; i < oldItems.length(); i++) {
            JSONObject stale = oldItems.optJSONObject(i);
            if (stale != null) {
                deleteQuietly(stale.optString("filePath", ""));
            }
        }

        preferences.edit().putString(HISTORY, newItems.toString()).apply();
    }

    private JSONArray loadHistory() {
        try {
            return new JSONArray(preferences.getString(HISTORY, "[]"));
        } catch (JSONException error) {
            return new JSONArray();
        }
    }

    private void renderHistory() {
        historyGrid.removeAllViews();
        JSONArray items = loadHistory();
        historyEmpty.setVisibility(items.length() == 0 ? View.VISIBLE : View.GONE);

        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) {
                continue;
            }
            historyGrid.addView(historyItem(item));
        }
    }

    private View historyItem(JSONObject item) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(8), dp(8), dp(8), dp(8));
        layout.setBackground(strokeDrawable("#EFE9DF", "#FFFFFF"));
        layout.setClickable(true);

        GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = 0;
        params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        params.setMargins(0, dp(8), dp(8), 0);
        layout.setLayoutParams(params);

        String path = item.optString("filePath", "");
        Bitmap bitmap = BitmapFactory.decodeFile(path);
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setImageBitmap(bitmap);
        layout.addView(image, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(118)
        ));

        String mode = "text".equals(item.optString("mode")) ? "文生图" : "图生图";
        String prompt = item.optString("prompt", "无提示词");
        TextView meta = text(mode + "\n" + ellipsize(prompt, 18), 12, "#5F5B54");
        meta.setPadding(0, dp(6), 0, 0);
        layout.addView(meta);

        layout.setOnClickListener(view -> executor.execute(() -> {
            try {
                byte[] bytes = readBytes(new FileInputStream(path), -1);
                Bitmap selected = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                File selectedFile = new File(path);
                mainHandler.post(() -> {
                    currentImageFile = selectedFile;
                    outputImage.setImageBitmap(selected);
                    statusText.setText(prompt.isEmpty() ? "历史记录" : "历史：" + prompt);
                    saveImageButton.setEnabled(true);
                });
            } catch (IOException error) {
                mainHandler.post(() -> toast("读取历史图片失败"));
            }
        }));

        return layout;
    }

    private void clearHistory() {
        JSONArray items = loadHistory();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null) {
                deleteQuietly(item.optString("filePath", ""));
            }
        }
        preferences.edit().putString(HISTORY, "[]").apply();
        currentImageFile = null;
        saveImageButton.setEnabled(false);
        renderHistory();
        toast("历史已清空");
    }

    private void saveCurrentImageToGallery() {
        if (currentImageFile == null || !currentImageFile.exists()) {
            toast("还没有可保存的图片");
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_WRITE_STORAGE);
            return;
        }

        executor.execute(() -> {
            try {
                copyCurrentImageToGallery();
                mainHandler.post(() -> toast("已保存到相册"));
            } catch (IOException error) {
                mainHandler.post(() -> toast("保存失败：" + error.getMessage()));
            }
        });
    }

    private void copyCurrentImageToGallery() throws IOException {
        String fileName = "AI_Image_" + System.currentTimeMillis() + ".png";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/AI Image");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);

            Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                throw new IOException("无法创建相册文件");
            }

            try (OutputStream output = getContentResolver().openOutputStream(uri);
                 FileInputStream input = new FileInputStream(currentImageFile)) {
                if (output == null) {
                    throw new IOException("无法写入相册文件");
                }
                copy(input, output);
            }

            values.clear();
            values.put(MediaStore.Images.Media.IS_PENDING, 0);
            getContentResolver().update(uri, values, null, null);
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "AI Image");
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IOException("无法创建相册目录");
            }
            File target = new File(dir, fileName);
            try (FileInputStream input = new FileInputStream(currentImageFile);
                 FileOutputStream output = new FileOutputStream(target)) {
                copy(input, output);
            }
            sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(target)));
        }
    }

    private void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
        }
    }

    private void pickImage(int requestCode) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, requestCode);
    }

    private void showReferencePreview(Uri uri, ImageView preview) {
        if (preview == null) {
            return;
        }
        try (InputStream inputStream = getContentResolver().openInputStream(uri)) {
            Bitmap bitmap = BitmapFactory.decodeStream(inputStream);
            preview.setImageBitmap(bitmap);
            preview.setVisibility(View.VISIBLE);
        } catch (IOException error) {
            toast("参考图预览失败");
        }
    }

    private String getDisplayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    return cursor.getString(index);
                }
            }
        } catch (Exception ignored) {
        }
        String fallback = uri.getLastPathSegment();
        return fallback == null ? "已选择图片" : fallback;
    }

    private String cleanApiBase(String value) {
        String cleaned = value == null ? "" : value.trim();
        if (cleaned.endsWith("/")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        return cleaned.isEmpty() ? BuildConfig.DEFAULT_API_BASE : cleaned;
    }

    private String cleanModel(String value) {
        String cleaned = value == null ? "" : value.trim();
        return cleaned.isEmpty() ? "gpt-image-2" : cleaned;
    }

    private String cleanOptionalUrl(String value) throws IOException {
        String cleaned = value == null ? "" : value.trim();
        if (cleaned.isEmpty()) {
            return "";
        }
        if (!cleaned.startsWith("http://") && !cleaned.startsWith("https://")) {
            throw new IOException("参考图链接必须以 http:// 或 https:// 开头");
        }
        return cleaned;
    }

    private void setBusy(boolean busy) {
        progressBar.setVisibility(busy ? View.VISIBLE : View.GONE);
        generateButton.setEnabled(!busy);
    }

    private void updateStatus(String message) {
        mainHandler.post(() -> statusText.setText(message));
    }

    private LinearLayout card() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(16), dp(16), dp(16), dp(16));
        layout.setBackground(strokeDrawable("#DCE5DD", "#FFFFFF"));
        return layout;
    }

    private TextView title(String value, int sp, boolean bold) {
        TextView view = text(value, sp, "#17201D");
        if (bold) {
            view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }
        return view;
    }

    private TextView sectionTitle(String value) {
        TextView view = title(value, 18, true);
        view.setPadding(0, 0, 0, dp(10));
        return view;
    }

    private TextView text(String value, int sp, String color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color(color));
        view.setLineSpacing(dp(2), 1.0f);
        return view;
    }

    private TextView smallMuted(String value) {
        return text(value, 12, "#637067");
    }

    private EditText input(String hint) {
        EditText editText = new EditText(this);
        editText.setHint(hint);
        editText.setTextSize(14);
        editText.setTextColor(color("#17201D"));
        editText.setHintTextColor(color("#7D8A82"));
        editText.setBackground(strokeDrawable("#D9E3DC", "#FBFCFA"));
        editText.setPadding(dp(12), dp(9), dp(12), dp(9));
        return editText;
    }

    private EditText urlInput(String hint) {
        EditText editText = input(hint);
        editText.setSingleLine(true);
        editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        return editText;
    }

    private ImageView previewBox() {
        ImageView imageView = new ImageView(this);
        imageView.setVisibility(View.GONE);
        imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        imageView.setBackground(strokeDrawable("#D9E3DC", "#FBFCFA"));
        imageView.setPadding(dp(4), dp(4), dp(4), dp(4));
        return imageView;
    }

    private LinearLayout.LayoutParams previewParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(126)
        );
        params.setMargins(0, dp(8), 0, dp(2));
        return params;
    }

    private Spinner spinner(String[] items) {
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        return spinner;
    }

    private View labeled(String label, View field) {
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setPadding(0, dp(8), 0, dp(4));
        TextView labelView = text(label, 13, "#17201D");
        labelView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        labelView.setPadding(0, 0, 0, dp(6));
        wrapper.addView(labelView);
        wrapper.addView(field, fullWidth());
        return wrapper;
    }

    private RadioButton modeButton(String value) {
        RadioButton button = new RadioButton(this);
        button.setText(value);
        button.setTextSize(15);
        button.setTextColor(color("#17201D"));
        button.setGravity(Gravity.CENTER);
        return button;
    }

    private Button primaryButton(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        button.setTextColor(Color.WHITE);
        button.setTextSize(15);
        GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{color("#196F63"), color("#D76459")}
        );
        background.setCornerRadius(dp(8));
        button.setBackground(background);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)
        );
        params.setMargins(0, dp(14), 0, 0);
        button.setLayoutParams(params);
        return button;
    }

    private Button secondaryButton(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTextColor(color("#196F63"));
        button.setBackground(strokeDrawable("#CFE3DD", "#FFFFFF"));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(44)
        );
        params.setMargins(0, dp(10), 0, 0);
        button.setLayoutParams(params);
        return button;
    }

    private GradientDrawable strokeDrawable(String stroke, String fill) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color(fill));
        drawable.setCornerRadius(dp(8));
        drawable.setStroke(dp(1), color(stroke));
        return drawable;
    }

    private View space(int heightDp) {
        View view = new View(this);
        view.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(heightDp)
        ));
        return view;
    }

    private LinearLayout.LayoutParams fullWidth() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int color(String value) {
        return Color.parseColor(value);
    }

    private String ellipsize(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "...";
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

    private void hideKeyboard() {
        View view = getCurrentFocus();
        if (view == null) {
            return;
        }
        InputMethodManager manager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (manager != null) {
            manager.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private static class GenerationResult {
        final String imageUrl;
        final String resultUrl;

        GenerationResult(String imageUrl, String resultUrl) {
            this.imageUrl = imageUrl;
            this.resultUrl = resultUrl;
        }
    }

    private static class ReferenceInput {
        final String value;

        ReferenceInput(String value) {
            this.value = value;
        }
    }
}

package com.jiang.aiimage;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
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
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQUEST_IMAGE_ONE = 2101;
    private static final int REQUEST_IMAGE_TWO = 2102;
    private static final int REQUEST_WRITE_STORAGE = 2201;
    private static final int HISTORY_PAGE_SIZE = 4;
    private static final String SETTINGS = "ai_image_settings";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final DalleImageClient imageClient = new DalleImageClient();
    private final ImageHostUploader imageHostUploader = new ImageHostUploader();

    private final String[] textSizeLabels = {"1024x1024 方形", "1536x1024 横屏", "1024x1536 竖屏"};
    private final String[] textSizeValues = {"1024x1024", "1536x1024", "1024x1536"};
    private final String[] imageSizeLabels = {"自动尺寸", "1024x1024 方形", "1536x1024 横屏", "1024x1536 竖屏", "2048x2048 2K", "3840x2160 4K 横屏", "2160x3840 4K 竖屏"};
    private final String[] imageSizeValues = {"auto", "1024x1024", "1536x1024", "1024x1536", "2048x2048", "3840x2160", "2160x3840"};
    private final String[] qualityLabels = {"自动质量", "低质量", "中等质量", "高质量"};
    private final String[] qualityValues = {"auto", "low", "medium", "high"};
    private final String[] imageHostLabels = {"PICUI 国内免费图床", "Catbox 国际备用"};
    private final String[] imageHostValues = {ImageHostUploader.HOST_PICUI, ImageHostUploader.HOST_CATBOX};

    private SharedPreferences preferences;
    private HistoryRepository historyRepository;
    private EditText apiKeyInput;
    private EditText apiBaseInput;
    private EditText modelInput;
    private EditText textPromptInput;
    private EditText imagePromptInput;
    private EditText imageUrlOneInput;
    private EditText imageUrlTwoInput;
    private Spinner outputSizeSpinner;
    private Spinner qualitySpinner;
    private Spinner imageHostSpinner;
    private TextView outputSizeLabel;
    private LinearLayout textForm;
    private LinearLayout imageForm;
    private TextView imageOneLabel;
    private TextView imageTwoLabel;
    private ImageView imageOnePreview;
    private ImageView imageTwoPreview;
    private TextView statusText;
    private TextView progressDetailText;
    private ProgressBar progressBar;
    private ImageView outputImage;
    private Button generateButton;
    private Button saveImageButton;
    private TextView historyEmpty;
    private GridLayout historyGrid;
    private LinearLayout historyPager;
    private TextView historyPageText;
    private EditText historyPageInput;
    private Button historyPrevButton;
    private Button historyNextButton;
    private ScrollView mainScrollView;
    private FrameLayout screenRoot;
    private ImageView fullScreenImage;

    private Uri imageUriOne;
    private Uri imageUriTwo;
    private File currentImageFile;
    private String currentMode = ImageGenerationRequest.MODE_TEXT;
    private Bitmap currentResultBitmap;
    private boolean imageFullScreen = false;
    private int currentHistoryPage = 0;
    private volatile String currentProgressStage = "等待生成";
    private volatile int currentProgressPercent = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = getSharedPreferences(SETTINGS, MODE_PRIVATE);
        historyRepository = new HistoryRepository(this, preferences);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
        getWindow().setStatusBarColor(color("#F5F8F3"));
        getWindow().setNavigationBarColor(color("#F5F8F3"));

        buildUi();
        setMode(ImageGenerationRequest.MODE_TEXT);
        resetProgress();
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
            // 部分相册只给临时权限，本次会话内仍可读取。
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
        screenRoot = new FrameLayout(this);

        mainScrollView = new ScrollView(this);
        mainScrollView.setFillViewport(true);
        mainScrollView.setBackgroundColor(color("#F5F8F3"));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(22), dp(18), dp(34));
        mainScrollView.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        root.addView(title("AI Image Studio", 31, true));
        TextView subtitle = text("文生图、图生图、图床上传和生成进度都在手机端完成。", 14, "#52615A");
        subtitle.setPadding(0, dp(5), 0, dp(16));
        root.addView(subtitle);

        root.addView(buildSettingsCard());
        root.addView(space(14));
        root.addView(buildCreationCard());
        root.addView(space(14));
        root.addView(buildResultCard());
        root.addView(space(14));
        root.addView(buildHistoryCard());

        screenRoot.addView(mainScrollView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        screenRoot.addView(buildFullScreenImageView());

        setContentView(screenRoot);
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

        qualitySpinner = spinner(qualityLabels);
        qualitySpinner.setSelection(preferences.getInt("quality_index", 0));
        card.addView(labeled("质量", qualitySpinner));

        Button saveButton = primaryButton("保存设置");
        saveButton.setOnClickListener(view -> {
            saveSettings();
            hideKeyboard();
            toast("设置已保存");
        });
        card.addView(saveButton);

        TextView hint = smallMuted("密钥只保存在本机，生成请求按 Dalle 兼容 /v1/images/generations 提交。");
        hint.setPadding(0, dp(10), 0, 0);
        card.addView(hint);
        return card;
    }

    private View buildCreationCard() {
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
        card.addView(modes);

        outputSizeLabel = fieldLabel("文生图尺寸");
        outputSizeLabel.setPadding(0, dp(12), 0, dp(6));
        card.addView(outputSizeLabel);
        outputSizeSpinner = spinner(textSizeLabels);
        card.addView(outputSizeSpinner, fullWidth());

        textForm = buildTextForm();
        imageForm = buildImageForm();
        card.addView(textForm);
        card.addView(imageForm);

        generateButton = primaryButton("开始生成");
        generateButton.setOnClickListener(view -> startGeneration());
        card.addView(generateButton);
        card.addView(buildProgressPanel());

        modes.setOnCheckedChangeListener((group, checkedId) ->
                setMode(checkedId == imageMode.getId() ? ImageGenerationRequest.MODE_IMAGE : ImageGenerationRequest.MODE_TEXT));
        modes.check(textMode.getId());
        return card;
    }

    private LinearLayout buildTextForm() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(0, dp(10), 0, 0);

        textPromptInput = input("例如：雨夜的霓虹街道，电影感，镜头虚化");
        textPromptInput.setMinLines(4);
        textPromptInput.setGravity(Gravity.TOP);
        form.addView(labeled("画面描述", textPromptInput));
        return form;
    }

    private LinearLayout buildImageForm() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(0, dp(10), 0, 0);

        imageHostSpinner = spinner(imageHostLabels);
        imageHostSpinner.setSelection(preferences.getInt("image_host_index", 0));
        form.addView(labeled("参考图图床", imageHostSpinner));

        imagePromptInput = input("例如：将人物换成赛博风格，背景更梦幻");
        imagePromptInput.setMinLines(4);
        imagePromptInput.setGravity(Gravity.TOP);
        form.addView(labeled("编辑说明", imagePromptInput));

        imageUrlOneInput = urlInput("https://example.com/reference-1.png");
        form.addView(labeled("参考图链接 1（可选，优先使用）", imageUrlOneInput));
        Button chooseOne = secondaryButton("选择本地参考图 1");
        chooseOne.setOnClickListener(view -> pickImage(REQUEST_IMAGE_ONE));
        form.addView(chooseOne);
        imageOneLabel = smallMuted("未选择本地参考图 1");
        form.addView(imageOneLabel);
        imageOnePreview = previewBox();
        form.addView(imageOnePreview, previewParams());

        imageUrlTwoInput = urlInput("https://example.com/reference-2.png");
        form.addView(labeled("参考图链接 2（可选）", imageUrlTwoInput));
        Button chooseTwo = secondaryButton("选择本地参考图 2");
        chooseTwo.setOnClickListener(view -> pickImage(REQUEST_IMAGE_TWO));
        form.addView(chooseTwo);
        imageTwoLabel = smallMuted("未选择本地参考图 2");
        form.addView(imageTwoLabel);
        imageTwoPreview = previewBox();
        form.addView(imageTwoPreview, previewParams());

        TextView imageHint = smallMuted("本地图片会先上传到所选图床，再把公网直链提交给绘图接口。");
        imageHint.setPadding(0, dp(6), 0, 0);
        form.addView(imageHint);
        return form;
    }

    private View buildProgressPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(0, dp(12), 0, 0);

        statusText = text("0% · 等待生成", 15, "#17201D");
        statusText.setGravity(Gravity.CENTER);
        statusText.setPadding(0, dp(6), 0, dp(4));
        panel.addView(statusText, fullWidth());

        progressDetailText = smallMuted("选择模式并点击开始生成");
        progressDetailText.setGravity(Gravity.CENTER);
        progressDetailText.setPadding(0, 0, 0, dp(8));
        panel.addView(progressDetailText, fullWidth());

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        panel.addView(progressBar, progressParams());
        return panel;
    }

    private View buildResultCard() {
        LinearLayout card = card();
        card.addView(sectionTitle("结果"));

        outputImage = new ImageView(this);
        outputImage.setAdjustViewBounds(true);
        outputImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        outputImage.setBackground(strokeDrawable("#DCE5DD", "#FBFCFA"));
        outputImage.setPadding(dp(8), dp(8), dp(8), dp(8));
        outputImage.setClickable(true);
        outputImage.setOnClickListener(view -> showImageFullScreen());
        card.addView(outputImage, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(360)
        ));

        saveImageButton = secondaryButton("保存到相册");
        saveImageButton.setEnabled(false);
        saveImageButton.setOnClickListener(view -> saveCurrentImageToGallery());
        card.addView(saveImageButton);
        return card;
    }

    private View buildFullScreenImageView() {
        fullScreenImage = new ImageView(this);
        fullScreenImage.setVisibility(View.GONE);
        fullScreenImage.setBackgroundColor(Color.BLACK);
        fullScreenImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        fullScreenImage.setPadding(0, 0, 0, 0);
        fullScreenImage.setClickable(true);
        fullScreenImage.setOnClickListener(view -> hideImageFullScreen());
        return fullScreenImage;
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

        historyPager = buildHistoryPager();
        card.addView(historyPager);

        historyGrid = new GridLayout(this);
        historyGrid.setColumnCount(2);
        historyGrid.setUseDefaultMargins(true);
        card.addView(historyGrid, fullWidth());
        return card;
    }

    private LinearLayout buildHistoryPager() {
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setPadding(0, dp(8), 0, dp(4));

        LinearLayout navRow = new LinearLayout(this);
        navRow.setOrientation(LinearLayout.HORIZONTAL);
        navRow.setGravity(Gravity.CENTER_VERTICAL);

        historyPrevButton = compactButton("上一页");
        historyPrevButton.setOnClickListener(view -> {
            currentHistoryPage = Math.max(0, currentHistoryPage - 1);
            renderHistory();
        });
        navRow.addView(historyPrevButton, new LinearLayout.LayoutParams(dp(88), dp(40)));

        historyPageText = text("第 1 / 1 页", 13, "#52615A");
        historyPageText.setGravity(Gravity.CENTER);
        navRow.addView(historyPageText, weighted());

        historyNextButton = compactButton("下一页");
        historyNextButton.setOnClickListener(view -> {
            currentHistoryPage++;
            renderHistory();
        });
        navRow.addView(historyNextButton, new LinearLayout.LayoutParams(dp(88), dp(40)));
        wrapper.addView(navRow, fullWidth());

        LinearLayout jumpRow = new LinearLayout(this);
        jumpRow.setOrientation(LinearLayout.HORIZONTAL);
        jumpRow.setGravity(Gravity.CENTER_VERTICAL);
        jumpRow.setPadding(0, dp(8), 0, 0);

        historyPageInput = input("页码");
        historyPageInput.setSingleLine(true);
        historyPageInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        jumpRow.addView(historyPageInput, new LinearLayout.LayoutParams(0, dp(42), 1f));

        Button jumpButton = compactButton("跳转");
        jumpButton.setOnClickListener(view -> jumpHistoryPage());
        LinearLayout.LayoutParams jumpParams = new LinearLayout.LayoutParams(dp(88), dp(40));
        jumpParams.setMargins(dp(8), 0, 0, 0);
        jumpRow.addView(jumpButton, jumpParams);
        wrapper.addView(jumpRow, fullWidth());
        return wrapper;
    }

    private void setMode(String mode) {
        currentMode = mode;
        if (textForm == null || imageForm == null || outputSizeSpinner == null) {
            return;
        }

        boolean imageMode = ImageGenerationRequest.MODE_IMAGE.equals(mode);
        textForm.setVisibility(imageMode ? View.GONE : View.VISIBLE);
        imageForm.setVisibility(imageMode ? View.VISIBLE : View.GONE);
        outputSizeLabel.setText(imageMode ? "图生图尺寸" : "文生图尺寸");
        replaceSpinnerItems(outputSizeSpinner, imageMode ? imageSizeLabels : textSizeLabels);
        outputSizeSpinner.setSelection(0);
    }

    private void startGeneration() {
        String apiKey = apiKeyInput.getText().toString().trim();
        if (apiKey.isEmpty()) {
            toast("请先填写 API Key");
            return;
        }

        final String apiBase = cleanApiBase(apiBaseInput.getText().toString());
        final GenerationInput input = captureGenerationInput();
        saveSettings();
        hideKeyboard();
        setBusy(true);
        updateProgress("准备生成", 2, "正在保存设置并启动任务");

        executor.execute(() -> runGeneration(apiKey, apiBase, input));
    }

    private void runGeneration(String apiKey, String apiBase, GenerationInput input) {
        try {
            updateProgress("检查参数", 5, "正在检查提示词、尺寸和参考图");
            ImageGenerationRequest request = buildGenerationRequest(input);

            long submitStartedAtMs = System.currentTimeMillis();
            updateProgress("提交任务", 45, "正在请求绘图接口，图生图或高分辨率可能需要数分钟");
            GenerationTaskResult result = imageClient.submitGeneration(apiKey, apiBase, request, this::updateProgress);

            String imageRef = result.getImageUrl();
            if (isBlank(imageRef)) {
                updateProgress("等待绘图", 52, "任务已提交，正在等待模型返回图片");
                imageRef = imageClient.pollResult(result.getResultUrl(), apiKey, this::updateProgress);
            } else {
                updateProgress("绘图完成", 90, "接口已直接返回图片");
            }

            updateProgress("下载图片", 92, "正在下载生成结果");
            byte[] imageBytes = imageClient.loadImageBytes(imageRef, apiKey);

            updateProgress("保存历史", 96, "正在写入本机历史记录");
            File stored = historyRepository.storeImage(imageBytes);
            historyRepository.add(stored, input.mode, input.prompt);

            updateProgress("渲染结果", 99, "正在显示图片");
            Bitmap bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
            mainHandler.post(() -> {
                currentImageFile = stored;
                currentResultBitmap = bitmap;
                outputImage.setImageBitmap(bitmap);
                saveImageButton.setEnabled(true);
                setBusy(false);
                String elapsed = formatDuration(System.currentTimeMillis() - submitStartedAtMs);
                updateProgress("生成完成", 100, "生成用时：" + elapsed + "，可以保存到相册或继续生成");
                currentHistoryPage = 0;
                renderHistory();
            });
        } catch (Exception error) {
            showGenerationFailure(error);
        }
    }

    private GenerationInput captureGenerationInput() {
        String mode = currentMode;
        String prompt = ImageGenerationRequest.MODE_TEXT.equals(mode)
                ? textPromptInput.getText().toString().trim()
                : imagePromptInput.getText().toString().trim();
        return new GenerationInput(
                mode,
                prompt,
                cleanModel(modelInput.getText().toString()),
                selectedOutputSize(mode),
                qualityValues[qualitySpinner.getSelectedItemPosition()],
                imageHostValues[imageHostSpinner.getSelectedItemPosition()],
                imageUrlOneInput.getText().toString().trim(),
                imageUrlTwoInput.getText().toString().trim(),
                imageUriOne,
                imageUriTwo
        );
    }

    private ImageGenerationRequest buildGenerationRequest(GenerationInput input) throws IOException {
        if (isBlank(input.prompt)) {
            throw new IOException(ImageGenerationRequest.MODE_TEXT.equals(input.mode) ? "请填写画面描述" : "请填写编辑说明");
        }

        List<String> references = new ArrayList<>();
        if (ImageGenerationRequest.MODE_IMAGE.equals(input.mode)) {
            // 图生图必须把本地 Uri 先转成公网图片链接，再交给 Dalle 兼容接口。
            addReferenceIfPresent(references, input.urlOne, input.uriOne, input.imageHost, "参考图 1", 18);
            addReferenceIfPresent(references, input.urlTwo, input.uriTwo, input.imageHost, "参考图 2", 32);
            if (references.isEmpty()) {
                throw new IOException("请至少选择一张本地参考图，或填写一个参考图链接");
            }
        }

        return new ImageGenerationRequest(input.mode, input.model, input.prompt, input.size, input.quality, references);
    }

    private void addReferenceIfPresent(List<String> references, String url, Uri localUri, String imageHost, String label, int percent) throws IOException {
        String cleanUrl = cleanOptionalUrl(url);
        if (!cleanUrl.isEmpty()) {
            updateProgress("读取" + label, percent, "正在使用手动填写的图片链接");
            references.add(cleanUrl);
            return;
        }
        if (localUri == null) {
            return;
        }

        updateProgress("上传" + label, percent, "正在上传到所选图床");
        String uploadedUrl = imageHostUploader.upload(this, localUri, imageHost);
        updateProgress(label + "上传完成", Math.min(40, percent + 8), "已拿到公网图片链接");
        references.add(uploadedUrl);
    }

    private void saveSettings() {
        preferences.edit()
                .putString("api_key", apiKeyInput.getText().toString().trim())
                .putString("api_base", cleanApiBase(apiBaseInput.getText().toString()))
                .putString("model", cleanModel(modelInput.getText().toString()))
                .putInt("quality_index", qualitySpinner.getSelectedItemPosition())
                .putInt("image_host_index", imageHostSpinner.getSelectedItemPosition())
                .apply();
    }

    private void renderHistory() {
        historyGrid.removeAllViews();
        JSONArray items = historyRepository.load();
        int total = items.length();
        int totalPages = Math.max(1, (int) Math.ceil(total / (double) HISTORY_PAGE_SIZE));
        currentHistoryPage = Math.max(0, Math.min(currentHistoryPage, totalPages - 1));
        historyEmpty.setVisibility(total == 0 ? View.VISIBLE : View.GONE);
        historyPager.setVisibility(total == 0 ? View.GONE : View.VISIBLE);
        updateHistoryPager(total, totalPages);

        int start = currentHistoryPage * HISTORY_PAGE_SIZE;
        int end = Math.min(total, start + HISTORY_PAGE_SIZE);
        for (int i = start; i < end; i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null) {
                historyGrid.addView(historyItem(item));
            }
        }
    }

    private void updateHistoryPager(int total, int totalPages) {
        historyPageText.setText("第 " + (currentHistoryPage + 1) + " / " + totalPages + " 页，共 " + total + " 张");
        historyPageInput.setHint(String.valueOf(currentHistoryPage + 1));
        historyPrevButton.setEnabled(currentHistoryPage > 0);
        historyNextButton.setEnabled(currentHistoryPage < totalPages - 1);
    }

    private void jumpHistoryPage() {
        String raw = historyPageInput.getText().toString().trim();
        if (raw.isEmpty()) {
            toast("请输入页码");
            return;
        }
        try {
            int target = Integer.parseInt(raw) - 1;
            int total = historyRepository.load().length();
            int totalPages = Math.max(1, (int) Math.ceil(total / (double) HISTORY_PAGE_SIZE));
            currentHistoryPage = Math.max(0, Math.min(target, totalPages - 1));
            historyPageInput.setText("");
            hideKeyboard();
            renderHistory();
        } catch (NumberFormatException error) {
            toast("页码格式不正确");
        }
    }

    private View historyItem(JSONObject item) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(8), dp(8), dp(8), dp(8));
        layout.setBackground(strokeDrawable("#DCE5DD", "#FFFFFF"));
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

        String mode = ImageGenerationRequest.MODE_TEXT.equals(item.optString("mode")) ? "文生图" : "图生图";
        String prompt = item.optString("prompt", "无提示词");
        TextView meta = text(mode + "\n" + ellipsize(prompt, 18), 12, "#52615A");
        meta.setPadding(0, dp(6), 0, 0);
        layout.addView(meta);

        layout.setOnClickListener(view -> executor.execute(() -> {
            try {
                byte[] bytes = historyRepository.readImageBytes(path);
                Bitmap selected = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                File selectedFile = new File(path);
                mainHandler.post(() -> {
                    currentImageFile = selectedFile;
                    currentResultBitmap = selected;
                    outputImage.setImageBitmap(selected);
                    saveImageButton.setEnabled(true);
                    updateProgress("历史记录", 100, prompt.isEmpty() ? "已打开历史图片" : prompt);
                });
            } catch (IOException error) {
                mainHandler.post(() -> toast("读取历史图片失败"));
            }
        }));

        return layout;
    }

    private void clearHistory() {
        historyRepository.clear();
        currentImageFile = null;
        currentResultBitmap = null;
        hideImageFullScreen();
        currentHistoryPage = 0;
        saveImageButton.setEnabled(false);
        renderHistory();
        toast("历史已清空");
    }

    private void showImageFullScreen() {
        if (currentResultBitmap == null) {
            toast("还没有可全屏查看的图片");
            return;
        }
        imageFullScreen = true;
        fullScreenImage.setImageBitmap(currentResultBitmap);
        fullScreenImage.setVisibility(View.VISIBLE);
        mainScrollView.setVisibility(View.GONE);
        hideKeyboard();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN);
        }
    }

    private void hideImageFullScreen() {
        if (fullScreenImage == null || mainScrollView == null) {
            return;
        }
        imageFullScreen = false;
        fullScreenImage.setVisibility(View.GONE);
        mainScrollView.setVisibility(View.VISIBLE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
    }

    @Override
    public void onBackPressed() {
        if (imageFullScreen) {
            hideImageFullScreen();
            return;
        }
        super.onBackPressed();
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
                 OutputStream output = new java.io.FileOutputStream(target)) {
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

    private String selectedOutputSize(String mode) {
        int index = outputSizeSpinner.getSelectedItemPosition();
        String[] values = ImageGenerationRequest.MODE_IMAGE.equals(mode) ? imageSizeValues : textSizeValues;
        if (index < 0 || index >= values.length) {
            return values[0];
        }
        return values[index];
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

    private void resetProgress() {
        updateProgress("等待生成", 0, "选择模式并点击开始生成");
    }

    private void updateProgress(String stage, int percent, String detail) {
        int normalized = Math.max(0, Math.min(100, percent));
        currentProgressStage = stage;
        currentProgressPercent = normalized;

        Runnable update = () -> {
            progressBar.setProgress(normalized);
            statusText.setText(normalized + "% · " + stage);
            progressDetailText.setText(isBlank(detail) ? stage : detail);
        };

        if (Looper.myLooper() == Looper.getMainLooper()) {
            update.run();
        } else {
            mainHandler.post(update);
        }
    }

    private void showGenerationFailure(Exception error) {
        String message = error.getMessage() == null ? "生成失败" : error.getMessage();
        mainHandler.post(() -> {
            progressBar.setProgress(currentProgressPercent);
            statusText.setText("失败 · " + currentProgressPercent + "% · " + currentProgressStage);
            progressDetailText.setText("失败位置：" + currentProgressStage + "。原因：" + message);
            setBusy(false);
        });
    }

    private void setBusy(boolean busy) {
        generateButton.setEnabled(!busy);
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
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

    private TextView fieldLabel(String value) {
        TextView label = text(value, 13, "#17201D");
        label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return label;
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

    private Spinner spinner(String[] items) {
        Spinner spinner = new Spinner(this);
        replaceSpinnerItems(spinner, items);
        return spinner;
    }

    private void replaceSpinnerItems(Spinner spinner, String[] items) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
    }

    private View labeled(String label, View field) {
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setPadding(0, dp(8), 0, dp(4));
        TextView labelView = fieldLabel(label);
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

    private Button compactButton(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        button.setTextSize(13);
        button.setTextColor(color("#196F63"));
        button.setBackground(strokeDrawable("#CFE3DD", "#FFFFFF"));
        return button;
    }

    private ImageView previewBox() {
        ImageView imageView = new ImageView(this);
        imageView.setVisibility(View.GONE);
        imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        imageView.setBackground(strokeDrawable("#D9E3DC", "#FBFCFA"));
        imageView.setPadding(dp(4), dp(4), dp(4), dp(4));
        return imageView;
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

    private LinearLayout.LayoutParams progressParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(8)
        );
        params.setMargins(0, dp(4), 0, 0);
        return params;
    }

    private LinearLayout.LayoutParams previewParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(126)
        );
        params.setMargins(0, dp(8), 0, dp(2));
        return params;
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

    private String formatDuration(long durationMs) {
        long totalSeconds = Math.max(0, durationMs / 1000);
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        if (minutes > 0) {
            return minutes + "分" + seconds + "秒";
        }
        return seconds + "秒";
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

    private static class GenerationInput {
        final String mode;
        final String prompt;
        final String model;
        final String size;
        final String quality;
        final String imageHost;
        final String urlOne;
        final String urlTwo;
        final Uri uriOne;
        final Uri uriTwo;

        GenerationInput(String mode, String prompt, String model, String size, String quality, String imageHost,
                        String urlOne, String urlTwo, Uri uriOne, Uri uriTwo) {
            this.mode = mode;
            this.prompt = prompt;
            this.model = model;
            this.size = size;
            this.quality = quality;
            this.imageHost = imageHost;
            this.urlOne = urlOne;
            this.urlTwo = urlTwo;
            this.uriOne = uriOne;
            this.uriTwo = uriTwo;
        }
    }
}

package com.jiang.aiimage;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class ImageGenerationRequest {
    public static final String MODE_TEXT = "text";
    public static final String MODE_IMAGE = "image";

    private final String mode;
    private final String model;
    private final String prompt;
    private final String size;
    private final String quality;
    private final List<String> referenceImages;

    public ImageGenerationRequest(String mode, String model, String prompt, String size, String quality, List<String> referenceImages) {
        this.mode = mode;
        this.model = model;
        this.prompt = prompt;
        this.size = size;
        this.quality = quality;
        this.referenceImages = referenceImages == null ? new ArrayList<>() : new ArrayList<>(referenceImages);
    }

    public String getMode() {
        return mode;
    }

    public String getPrompt() {
        return prompt;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject payload = new JSONObject();
        payload.put("model", model);
        payload.put("prompt", prompt);
        payload.put("size", size);
        payload.put("quality", quality);
        payload.put("response_format", "b64_json");

        if (MODE_IMAGE.equals(mode)) {
            JSONArray images = new JSONArray();
            for (String reference : referenceImages) {
                images.put(reference);
            }
            payload.put("image", images);
        }

        return payload;
    }
}

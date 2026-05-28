package com.jiang.aiimage;

public class GenerationTaskResult {
    private final String imageUrl;
    private final String resultUrl;

    public GenerationTaskResult(String imageUrl, String resultUrl) {
        this.imageUrl = imageUrl;
        this.resultUrl = resultUrl;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public String getResultUrl() {
        return resultUrl;
    }
}

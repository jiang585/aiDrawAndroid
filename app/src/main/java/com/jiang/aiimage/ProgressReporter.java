package com.jiang.aiimage;

public interface ProgressReporter {
    void onProgress(String stage, int percent, String detail);
}

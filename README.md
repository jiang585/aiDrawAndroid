# AI Image Android 1.01

这是 Android 端 1.01 重建版，保留初版的文生图、图生图、历史记录和保存相册能力，并把界面整理成更适合手机使用的轻量 Material 风格。

## 主要变化

- 新建项目目录：`E:\ai-image-android1.01版本`
- 版本号升级到 `1.01`
- 新增模型输入，默认 `gpt-image-2`
- 新增质量选项：`auto` / `low` / `medium` / `high`
- 图生图继续走 Dalle 兼容的 `/v1/images/generations`
- 本地参考图会先上传到所选图床，再把公网直链放入 `image` 数组
- 图生图支持单独选择输出尺寸：自动、1024 方图、横屏、竖屏、2K、4K
- 图床支持 PICUI 国内免费图床（默认）和 Catbox 备用
- 界面仍提供参考图链接输入，方便改用自己的图床直链
- 新增本地参考图预览
- 保留生成结果历史和保存到相册

## 图生图说明

你给的 Dalle 兼容文档里，Generations 的 `image` 字段是 `string[]`，并注明传递文件链接；Edits 才是文件流。当前 1.01 的策略是：

1. 用户填写参考图链接时，直接把链接放入 `image` 数组。
2. 用户选择本地图片时，先上传到界面中选择的图床，默认使用 PICUI 国内免费图床。
3. 把图床直链放入 `image` 数组后请求 `/v1/images/generations`。

这样可以避开 Base64 JSON 过大导致网关 504、上游没有被调用的问题。若后续要换成自己的图床，只需要在界面里填入已上传好的图片链接。

## 构建

用 Android Studio 打开：

```text
E:\ai-image-android1.01版本
```

命令行构建：

```powershell
.\gradlew.bat assembleDebug
```

默认 API Base：

```text
https://ai.t8star.cn
```

# AI Image Android 1.01

这是 Android 端 1.01 重建版，保留初版的文生图、图生图、历史记录和保存相册能力，并把界面整理成更适合手机使用的轻量 Material 风格。

## 主要变化

- 新建项目目录：`E:\ai-image-android1.01版本`
- 版本号升级到 `1.01`
- 新增模型输入，默认 `gpt-image-2`
- 新增质量选项：`auto` / `low` / `medium` / `high`
- 图生图继续走 Dalle 兼容的 `/v1/images/generations`
- 本地参考图会先转成 `data:image/...;base64` 放入 `image` 数组，优先尝试不借助图床
- 如果接口拒绝本地 Base64，界面提供参考图链接输入作为兜底
- 新增本地参考图预览
- 保留生成结果历史和保存到相册

## 图生图说明

你给的 Dalle 兼容文档里，Generations 的 `image` 字段是 `string[]`，并注明传递文件链接；Edits 才是文件流。当前 1.01 的策略是：

1. 用户填写参考图链接时，直接把链接放入 `image` 数组。
2. 用户选择本地图片时，先转为 data URL 放入 `image` 数组，尝试不借助图床。
3. 如果服务端返回图片、URL、Base64 相关错误，App 会提示改用可访问图片链接。

这能覆盖“接口兼容 data URL”的情况，也保留“接口只接受公网链接”的兜底入口。

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

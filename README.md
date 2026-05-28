# Ai Image Android

这是从 `E:\ai-image-web` 转换出的原生 Android 版本，保留了网页项目的核心流程：

- 文生图
- 图生图，最多两张参考图
- 1:1 / 16:9 / 9:16 比例
- `gpt-image-2` 请求结构
- Base64 图片结果展示
- 异步结果轮询
- 本机生成历史
- 保存结果到相册

## 重要说明

网页版本用 Node/Worker 后端隐藏 `API_KEY`。Android App 不能直接复用这个后端进程，所以这里改成 App 内输入 API Key，并保存在本机 `SharedPreferences`。源码没有写入 `E:\ai-image-web\.env` 里的密钥。

默认 API Base 是：

```text
https://ai.t8star.cn
```

首次运行时，在顶部设置区输入 API Key，点“保存设置”后再生成图片。

## 打开项目

用 Android Studio 打开当前目录：

```text
C:\Users\HP\Documents\ai-image-andriod
```

首次打开时 Android Studio 会生成 `local.properties`。如果提示缺少 SDK / Build Tools，按提示安装对应组件即可；当前项目配置为 `compileSdk 36`。

也可以命令行构建：

```powershell
.\gradlew.bat assembleDebug
```

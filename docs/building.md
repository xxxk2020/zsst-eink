# 构建说明

## 环境

- JDK 17
- Android SDK：`platforms;android-35`、`build-tools;35.0.0`
- Gradle Wrapper：8.9，下载启用 SHA-256 校验
- Android Gradle Plugin：8.7.3
- Python 3.10+：仅用于检查与资源包工具，标准库即可

使用 Android Studio 导入 `android/`，或配置 `ANDROID_HOME`；也可在不提交的 `android/local.properties` 中设置 `sdk.dir`。

```sh
cd android
./gradlew --no-daemon assembleDebug
```

Windows 对应 `gradlew.bat`。输出为 `android/app/build/outputs/apk/debug/app-debug.apk`。源码支持中文工作路径；部分构建工具仍可能有 Windows 路径长度限制，可使用较短的 SDK 路径。

## 持续集成

GitHub Actions 在 push、pull request 或手动触发后运行源码检查、电脑端回归、演示包校验及调试构建。工作流使用仓库读取权限，成功后提供调试 APK 和原创演示资源包。

调试 APK 使用构建环境生成的调试签名。不同电脑或 CI 环境生成的签名可能不同，无法承诺互相覆盖安装。正式分发请自行管理稳定签名，签名文件和密码不要提交到仓库。

最低系统为 API 22，目标 API 28 是对旧系统兼容的选择。本项目面向自行安装，不宣称符合应用商店当前上架要求。

## 检查边界

`test_native_storage.py` 检查资源包原子更新、失败回滚、回收、作答与续读保存、共享图片引用、进度解析边界，以及原创样例导入、图片导出再导入。`test_content_model.py` 检查富文本与表格模型，`test_eink_frame_gate.py` 检查页面准备后呈现的调度逻辑。

这些不是设备 UI 测试，不衡量真机内存或性能。所有文件样例均位于电脑临时目录，不通过 ADB 操作个人题库。

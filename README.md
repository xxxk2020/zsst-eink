# zsst-eink · 纸上刷题

面向 Android 墨水屏设备的原生离线刷题工具。以题为舟，终抵上岸。

原生 Android View 实现，最低支持 Android 5.1（API 22）。适合在低配置设备上做题、读材料，把需要深入整理的复盘留给其他设备。

## 能做什么

- 导入本地 JSON 或带独立图片的 ZIP 资源包，按行测、申论和练习集管理。
- 刷题模式选后自动进入下一题，交卷后查看答案和解析；未作答题同样显示已有解析。练习模式按题核对。
- 答题卡每行 10 题、5+5 分组，显示自己的选择与对错，提供可复制的分组答案。
- 共用材料与题目分别滚动，拖动调整分区；申论分别浏览材料与题目。
- 字体导入、字号与字重调整、横竖屏、全屏、右侧点按翻页、细进度条及设备翻页键。
- 本机保存作答、收藏、阅读位置与历史；支持归档、删除题库及导入导出学习进度。

采用 Android 原生界面，题库与学习进度保存在本机，通过系统文件选择器导入和导出。关于页提供开源项目入口。

## 开始使用

1. 自行构建，或从本仓库 [Actions](https://github.com/xxxk2020/zsst-eink/actions) 的成功构建中下载调试 APK。
2. 首页右上角资源入口导入自己的 JSON 或资源包 ZIP。
3. 点按资料继续学习，长按可切换模式、归档、导出或删除。设置中可备份进度。

仓库提供原创演示材料，可作为制作本地题库的参考。试题、图片与字体的使用权由内容提供者决定，MIT 源码许可与导入内容的授权分别处理。

应用标识为 `com.xxxk2020.zssteink`，可与其他版本并存。它不会自动读取其他应用的私有目录；转移题库与记录时需手动导出、导入。卸载前请备份，导入相同资料应保留稳定的 `id` 和题号。

## 构建与验证

需要 JDK 17、Android SDK Platform 35、Build Tools 35.0.0。Gradle 8.9 与 Android Gradle Plugin 8.7.3 已固定。

```sh
cd android
./gradlew --no-daemon assembleDebug
```

Windows 使用 `gradlew.bat --no-daemon assembleDebug`。首次构建需联网下载官方构建工具与依赖，应用运行可离线。

```sh
python tools/audit_public_source.py
python tools/test_native_storage.py
python tools/test_content_model.py
python tools/test_eink_frame_gate.py
python tools/make_resource_packs.py --source examples/source --out build/example-packs --single-pack
python tools/validate_resource_pack.py build/example-packs/zsst-all-resources.zip
```

源码检查与存储回归在电脑临时目录执行，不接触设备资料。构建成功不等于已覆盖每种墨水屏系统，显示、旋转与翻页键仍建议在目标设备上确认。

详见 [导入格式](docs/import-format.md)、[构建说明](docs/building.md) 与 [功能与开源说明](docs/open-source-scope.md)。

## 许可证

原创代码、文档、几何图标与演示题采用 [MIT](LICENSE)。测试用 AOSP JSON 源码与 Gradle Wrapper 保留各自授权，见 [第三方声明](THIRD_PARTY_NOTICES.md)。

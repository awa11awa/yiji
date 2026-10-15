# APK 发布约定

正常交付的新版本 APK 默认同步发布 GitHub Release，包含 APK、源码 ZIP、更新说明和 SHA-256 校验文件。

## 新版本发布

1. 在 `app/build.gradle.kts` 中更新 `versionName`，并递增 `versionCode`。
2. 添加或更新 `RELEASE_NOTES_v<版本号>.md`，说明功能、验证结果和升级注意事项。
3. 将改动提交到 `main`；`.github/workflows/release.yml` 自动运行单元/集成测试、Android Lint 和 APK 构建。
4. 只有检查通过、APK 版本与签名校验通过后，才生成源码包并上传附件，最后公开 Release。
5. 发布后检查 Release 附件齐全，并确认 SHA256SUMS.txt 对应本次发布文件。

已发布的同版本 Release 不会被自动覆盖。版本未递增时，后续提交不会替换旧安装包。构建或上传失败时，可在 GitHub Actions 页面重跑该版本流程；已创建的草稿会补齐附件后再公开。

当前流程交付 `debug.apk`，Release 标记为开发测试版。正式生产签名和正式版打包应单独配置，不应将测试包标成生产签名正式版。

CI 会重新构建发布包，最终校验值以 Release 附带的 SHA256SUMS.txt 为准。调试包签名可能与此前安装包不同，升级前先导出并保存备份，如提示签名不一致，再卸载旧版、安装新版并恢复。

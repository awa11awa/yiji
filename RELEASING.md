# APK 发布约定

正常交付的新版本 APK 同步发布 GitHub Release，包含 APK、源码 ZIP、独立的消息分类模型 ZIP、更新说明和 SHA-256 校验文件。模型 ZIP 与 APK 使用同一份已评估的权重，由 scripts/package_message_model.py 打包。

## 新版本发布

1. 在 `app/build.gradle.kts` 中更新 `versionName`，并递增 `versionCode`。
2. 添加或更新 `RELEASE_NOTES_v<版本号>.md`，说明功能、验证结果和升级注意事项。
3. 将改动提交到 `main`。更新 `app/build.gradle.kts` 会触发 `.github/workflows/release.yml`，执行 release 变体测试、Android Lint 和 APK 构建，并在核验通过后为该提交创建 `v<版本号>` 标签并发布。仍支持推送版本标签或在 Actions 页面手动运行；主动推送的标签必须与 versionName 一致。
4. 流程从 Repository Secrets 恢复固定签名。只有测试和 Lint 通过、APK 版本与固定签名证书校验通过后，才生成源码包、上传附件并公开 Release。
5. 发布后检查附件齐全，确认 `SHA256SUMS.txt` 对应本次发布文件。

已发布的同版本 Release 不会被自动覆盖。版本未递增时，后续提交不会替换旧安装包。构建或上传失败时，可在 GitHub Actions 页面重跑该版本流程；已创建的草稿会补齐附件后再公开。

从 v2.1.2/v2.1.3 的此次正式发布起，交付 `yiji-v<版本号>.apk`，使用 release 构建并校验不可调试标记；GitHub Release 不再标为预发布。固定签名保持不变，debug 与 release 构建使用同一密钥，支持覆盖升级。测试和实机验证的具体边界仍见各版本验证记录。

CI 使用 `testReleaseUnitTest lintRelease assembleRelease`，核验签名、包名、versionName、versionCode 与不可调试标记。发布较旧版本时不会将其设为比已发布的新版本更优先的 Latest。私有密钥仍只从加密 Repository Secrets 恢复。

## 固定签名

从 v2.1.0 起使用同一 PKCS12 签名密钥，别名 `yiji`。固定证书 SHA-256：

`041f228046082db2aaf03367fc29417b0620aadc45a4c5c05d42a08c579f0fea`

在仓库 Settings → Secrets and variables → Actions 中保存两个 Repository Secrets：

- `YIJI_KEYSTORE_BASE64`：私有签名备份中 PKCS12 文件的 Base64。
- `YIJI_KEYSTORE_PASSWORD`：该密钥库与密钥条目的密码。

CI 仅在运行器临时目录恢复密钥与配置，强制使用固定签名，验证上述证书指纹，结束后清理。密钥不存在、密码无效或证书不匹配均应终止发布，不能回退临时调试签名。不要将私有备份、密钥文件、密码或 Secrets JSON 提交到源码或 Release；这些路径已加入 `.gitignore`。

本地恢复时，将私有备份中的 `yiji-signing.p12` 与 `signing.properties` 放在 `~/.config/yiji/`，或设置 `YIJI_SIGNING_PROPERTIES` 指向该配置文件；相对 `storeFile` 按配置文件目录解析。发布构建必须设置 `YIJI_REQUIRE_STABLE_SIGNING=true`。未配置固定密钥的普通本地开发构建仍使用 Android 默认开发签名，不能作为公开升级包。

此前交付的 v2.1.0 APK 与本次 CI 使用相同签名，后续版本保持同一密钥即可覆盖升级。从 v2.0.0 及更早版本首次迁移时，若签名不同，须先导出并保存备份，再卸载、安装新版并恢复。

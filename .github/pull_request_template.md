## 改了什么

<!-- 一两句话说明改动；解析类改动请写清影响了哪些输入 -->

## 关联 issue

<!-- 例如 Fixes #12；没有就写「无」 -->

## 是否加了测试

<!-- 解析类改动必须在 tools/offline-tests/<name>-test/ 里加用例；纯 UI 改动请说明为什么没有 -->

- [ ] 已在对应离线测试套件里加了用例
- [ ] 不需要（纯 UI / 文档改动，原因：）

## 是否本地跑过离线测试

<!-- 运行 .\tools\offline-tests\run-all.ps1 后把汇总贴上来，例如「通过 11 套，失败 0 套」 -->

- [ ] 跑过 `.\tools\offline-tests\run-all.ps1`
- [ ] 也本地编译过（`.\gradlew.bat assembleDebug`）

## 截图（界面改动请附）

<!-- 改前 / 改后对比最好 -->

## 自查

- [ ] 没有提交 `local.properties`、`_env/`、签名文件、任何 API Key
- [ ] 新增解析逻辑放在纯 Kotlin、不依赖 Android 的文件里

# 贡献指南

感谢你愿意给「日程助手」提 PR。这是一个**本地优先**的安卓日程 App:纯 Kotlin + Jetpack Compose(Material3)+ 自己写的 SQLite(`SQLiteOpenHelper`),没有 Room / Hilt / Retrofit。为了保持这个体积和依赖,下面的约定请尽量遵守。

---

## 一、开发环境

| 组件 | 要求 | 说明 |
|---|---|---|
| JDK | **17** | `JAVA_HOME` 指向 JDK 17 目录(编译与离线测试都用它) |
| Android SDK | **Platform 34** | `ANDROID_HOME`(或 `ANDROID_SDK_ROOT`)指向 SDK 目录;离线测试需要 `platforms/android-34` 里的 `android.jar` |
| Gradle | **不用自己装** | 用仓库自带的 wrapper(`gradlew` / `gradlew.bat`,8.7) |
| Node.js | 可选 | 只有 `tools/offline-tests/migrate-test`(数据库升级用例)用到 |

- 依赖走**阿里云 Maven 镜像**(`settings.gradle.kts`),Gradle 发行包走华为云镜像 —— 不要擅自改成别的镜像(清华 TUNA 实测没有 gradle/androidx 镜像)。
- **工程路径不能含中文**:AGP 拒绝非 ASCII 路径。本地目录名带中文时,用 `subst` 或目录联接(junction)映射一个纯 ASCII 路径再编译。
- 用 Android Studio 也可以:直接 Open 工程根目录,首次同步会自动拉依赖。

---

## 二、编译

```powershell
# Windows
.\gradlew.bat assembleDebug
# macOS / Linux
./gradlew assembleDebug
```

产物:`app/build/outputs/apk/debug/app-debug.apk`。

> 首次编译会顺便把 Kotlin 编译器的 jar 拉进 Gradle 缓存 —— 离线测试脚本正是从这里取编译器,所以**建议先编译一次再跑测试**。

---

## 三、改代码之前:先跑离线测试

```powershell
.\tools\offline-tests\run-all.ps1
```

**11 套、375 条断言,不需要手机/模拟器**,只依赖 `JAVA_HOME` + `ANDROID_HOME`。当前覆盖:

| 套件目录 | 覆盖内容 |
|---|---|
| `parser-test` | 中文时间语义解析 |
| `recur-test` | 重复规则展开与查询 |
| `holiday-test` | 节假日 / 农历 |
| `cal-test` | 月历标签 |
| `course-test` | 课程解析与配色 |
| `course-text-test` | 课表文字解析 |
| `grid-test` | 课表截图**网格还原** |
| `ics-test` | `.ics` 导入 |
| `todo-test` | 待办抽取 |
| `color-test` | 课程配色 |
| `migrate-test` | 数据库升级不丢数据(Node.js 脚本) |

### 新增解析逻辑时必须加用例

测试套件都在 `tools/offline-tests/<name>-test/`,每个套件两个文件:

- `build-run.ps1` —— 用 Gradle 缓存里的 Kotlin 编译器把「被测源文件 + 用例文件」一起编译再跑;
- `XxxTest.kt` —— 纯 Kotlin `main()` 函数,用 `check(名称, 条件, 详情)` 逐条断言。

写法就是(各套件里 `check` 签名略有差异,照抄同目录现成的即可):

```kotlin
private fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) { pass++; log.append("[PASS] ").append(name) }
    else { fail++; log.append("[FAIL] ").append(name).append("  -> ").append(detail) }
    log.append('\n')
}

fun main() {
    check("明天下午3点 解析出开始时间", e.startMillis == expected, "实际=${e.startMillis}")
    // ... 结束时把 pass/fail 与「ALL TESTS PASSED」写进 result.txt
}
```

约定:

- 断言名称用中文,写清**期望什么**;失败时 `detail` 要给能一眼定位的实参值;
- **不要用系统当前时间**:固定一个基准时间(如 `parser-test` 里的 `now = 2026-09-13T21:31+08:00`);
- 每个用例尽量钉住一个真实 bug 或真实场景,注释里写清「为什么这么写」;
- 若给某个套件新增了源文件,记得同步改它的 `build-run.ps1` 编译列表。

---

## 四、代码风格

- **跟随现有文件的风格**:中文注释、4 空格缩进、`data class` + 顶层函数为主,不引入新的抽象层。
- **解析类逻辑必须放在纯 Kotlin、不依赖 Android 的文件里**(如 `parse/ChineseDateParser.kt`、`parse/TimetableGridParser.kt`、`util/`),这样才能被离线测试直接编译覆盖。碰 `android.*` 的代码写不进离线测试 —— 请把纯计算部分抽出来。
- 注释解释**为什么这么写**,不要复述代码在做什么。这个项目里的注释习惯记录**踩过的坑**(例:「实测时钟自己的闹钟曾被当成一条新日程」「常驻通知涨到 4.5 万条 / 8.7MB」)。改动如果推翻了旧结论,顺手把旧注释更新掉。
- 不引入重型框架(见下文「明确不做的事」)。新增依赖请在 PR 里说明理由。

---

## 五、提交信息

- **用中文**,第一行简短说明**做了什么**(例:`修复 ICS 导入把下午的课算成上午节次`);需要时下面空一行再写细节。

---

## 六、PR 要求

提 PR 时请在描述里写清:

1. **改了哪个模块**(解析 / 通知 / 闹钟 / 课表 / 数据库 / UI…),以及为什么改;
2. **有测试用例** —— 解析类改动必须带用例;纯 UI 改动可以说明为什么没有;
3. **本地跑过 `.\tools\offline-tests\run-all.ps1`**,把结果(通过 X 套 / 失败 0 套)贴上来;
4. 界面改动请附**截图**;行为改动请写清**怎么验证**;
5. 关联的 issue 编号(如 `Fixes #12`)。

CI(`.github/workflows/tests.yml`)会在 Windows 上跑同一套离线测试,本地先过一遍能省一轮。

---

## 七、提交前请自己确认:这些绝对不能进仓库

仓库有 `.gitignore`,但**别指望它兜底**,`git status` 看一眼再提交:

- `local.properties`(本机 SDK 路径);
- `_env/`(本地脚手架,里面有真机数据:课表 DB 快照、通知日志、截图,以及**调试签名文件**);
- 任何签名文件与密钥:`*.jks` / `*.keystore` / `keystore.properties` / `signing.properties`;
- **任何 API Key**(大模型 Key 只应存在手机本机 `SharedPreferences`,不要写进代码、注释、测试、截图);
- `build/` 产物、`.gradle/`、`*.db`、`*.log`、`进度与待办.md`。

---

## 八、项目明确不做的事(避免无效 PR)

- **不做「输学号密码自动爬教务系统」**:各校教务系统(正方/URP/强智/青果/一网通办…)差异极大,还常有 **WebVPN + 钉钉扫码**这类登录方式,内置 WebView 打不开,把账号密码交给第三方 App 也有真实风险。正确姿势是**让专门的课表 App 去登录,我们只读它的导出**(`.ics` 导入)。
- **不做短信提醒**:已彻底移除短信功能,不申请 `SEND_SMS` / `READ_SMS` / `RECEIVE_SMS` 任何权限。提醒由**聊天式通知 + 闹钟 + 倒计时**实现。
- **不引入重型框架**:Room / Hilt / Retrofit 等一律不加。数据库就是自己写的 `SQLiteOpenHelper`,网络就是 `HttpURLConnection`,状态就是 `StateFlow`。

提 issue 也欢迎,请用仓库里的表单模板。

# 本 fork 的修改

基于 [`herbiewalker/tryst`](https://github.com/herbiewalker/tryst) v0.6.0（`versionCode` 16）的
个人修改分支。上游为 GPL-3.0，本 fork 同样以 GPL-3.0 发布。

所有修改都保持上游的**零网络**承诺：manifest 不含 `INTERNET`，
`CheckNoNetworkPermissionTask` 构建期守卫未被触碰。

---

## 1. 修复备份导入失败，且让失败原因可见

**问题**：任何备份都导不进来，包括刚导出的文件；界面只显示固定文案
「Import failed — wrong password or not a Tryst backup.」，把所有失败都伪装成"密码错"。

**根因**（两个）：

- `BackupViewModel.import` 的 `catch` 丢弃了 `e.message`，真实异常只进 logcat。
  同文件的 export 路径本就带 `e.message`——这是不对称遗漏，不是有意设计。
- `suppressAutoLock()` 只在点击导入时 armed 一次，而 `SessionManager.onAppBackgrounded()`
  是**一次性消费**的。文件选择器已消耗掉这次抑制；用户在密码框切去别的应用查密码
  （很自然的操作）→ 默认 `autoLockTimeoutMs = 0` 立即锁定 → 恢复时
  `session.database()` 抛 `Database accessed while locked`。

**改动**：

- `ui/settings/BackupViewModel.kt` — 新增 `describe(e)` 归类，界面显示真实原因。
- `res/values/strings.xml` — `backup_status_import_failed` 改为带 `%1$s`；新增 5 条 `backup_err_*`。
- `ui/settings/SettingsScreen.kt` — 文件选择器返回时重新 arm 锁定抑制。

**已排除的可能**：加密层无辜。已用项目同款 Tink 1.15.0 独立验证：头部格式、
小文件往返、**跨 1 MiB 分段的大 payload（1/3/8 MiB）**、迭代次数边界全部正常；
`BackupManager.TABLES` 与 Room schema v16 完全一致；仓库自带的
`samples/tryst-test-dataset.tryst` 可正常解包（113 个 media）。

## 2. 修复 Insights 柱状图不反映数值高度

**问题**：柱状图不论数字多少都显得一样高。

**根因**：`ui/insights/InsightsCharts.kt` 的 `VerticalBarChart` 用
`Modifier.weight(fraction)` 控制柱高。**`weight` 是 flex 分配，不是长度缩放**——它瓜分的是
"扣掉固定高子元素（数字标签 16dp + 轴标签 16dp + 间距 6dp = 38dp）后剩余的 102dp"，
还要和同 Column 内所有柱子的权重总和分摊。实测：12 桶月度数据下最高柱只有可用轨道的
**14%**；5 桶评分数据（48:1 偏斜）下按权重算出 **454%**，比例彻底失真。

注意：**相对比例其实是保留的**（12:1 仍是 12:1），真正的毛病是整体被压缩，
加上 `coerceAtLeast(0.012f)` 的权重兜底让零值柱与小值柱几乎无法区分。

同文件的 `RankedBars`（横条，固定 `height` + `fillMaxWidth`）和 `LineAreaChart`
（绝对坐标）都是对的，**只有 `VerticalBarChart` 用错了工具**。

**改动**：改为固定 96dp 轨道 + `fillMaxHeight(fraction)`，与 `RankedBars` 同一套做法。
数字标签移入轨道内部、叠在柱子上方一起底对齐——否则所有数字会被顶到轨道上边缘排成一行
（在固定的兄弟节点槽位里，与柱子高度无关）。

## 3. 新增：最长/最短时长可点击色块

在 `Avg duration` 之后新增 `Longest` / `Shortest` 两个 Overview 色块，点击跳到对应记录。

- `data/stats/InsightsEngine.kt` — 新增 `DurationExtreme(minutes, encounterId, date)`；
  `Insights` 加 `longestDuration` / `shortestDuration`；并列时**取较新**的那条
  （`maxWithOrNull(compareBy({ -it.minutes }, { it.date }))`，负号反转——直接用
  `minWithOrNull` 会取到最旧的，与意图相反）。
- `ui/insights/StatTiles.kt` — `StatTile` 加 `target`，放在 `value` **之前**以保持既有
  位置型尾 lambda 仍绑定 `value`。
- `ui/insights/InsightsScreen.kt` — 可点击色块用 `secondaryContainer` 配色 + `ArrowForwardIos`
  箭头以区别于只读色块；**编辑模式禁用点击**（该模式手势属于拖拽排序）。
- `ui/TrystApp.kt` — 接既有 `Routes.encounterEdit(id)` 范式。

新增 5 个单测：极值与 id 正确性、无时长记录时为 null、并列取新、单条记录同为极值、
**时间窗口内不越界取到窗口外记录**。

## 4. 新增：预设导出密码 + 默认导出目录

走 **SAF 路线**，未推翻零网络决策。OneDrive 在 Android 上作为 SAF 提供程序出现，
选它即可，由 OneDrive 自身同步。

### 预设导出密码

**`core/prefs/BackupPreferences.kt`（新增）** — 密码经**独立 Keystore 密钥**
（`SecureKeyStore("tryst_backup_pw")`，AES-256-GCM，硬件绑定不可导出）加密后存 prefs。

- **刻意不复用 vault 的 `tryst_vault_kek`**：预设密码需在**锁屏状态下**可读才有意义，
  复用 vault KEK 要么做不到、要么把两个密钥绑死。轮换/清空 vault 不影响预设，反之亦然。
- **不提供明文降级**：keystore 异常时跳过保存（等于没记住），而非退化为明文存储。
- 读取失败（如设备 keystore 重置、数据跨设备移植）返回 null 并清掉不可用密文，
  UI 回落到弹密码框。
- **导入路径不提供记忆**——记住一个只用于读取的密码没有收益。
- 编辑框**不回填**已存密码，避免上屏或进入截图。

### 默认导出目录

`OpenDocumentTree` 选目录后必须 `takePersistableUriPermission(READ|WRITE)`，
否则进程死亡后 URI 失效。写入用
`DocumentsContract.buildChildDocumentsUriUsingTree` + `DocumentsContract.createDocument`
（同名文件被替换而非静默重复；注意**不是** `contentResolver.createDocument`，该方法不存在）。

**文件名带分钟级时间戳**（`tryst-backup-2026-10-09-1543.tryst`）：一键导出路径没有保存对话框
让用户改名确认，只用日期会**静默覆盖**当天上一次的备份。

### 安全性

`data_extraction_rules.xml` 的 `<exclude domain="sharedpref" />` 整类排除，
新 store 自动不进系统云备份与设备传输。`PrefsBackup` 只覆盖 4 个明文 store，
预设密码**不会**随备份外传。

## 5. 已知遗留：Insights 轴标签的中英混排

**未修复**——需要你先决定方向。

`monthLabel()` 和 `DayOfWeek.getDisplayName()` 用 `Locale.getDefault()`，
在中文系统上得到 `1月` / `周一`；而色块标签（硬编码英文）、分类名（`"Anonymous"`/`"Solo"`）、
各章节标题（`strings.xml`，仓库无 `values-zh`）保持英文。全项目仅 3 处用 `Locale.getDefault()`。

这不只是观感问题——它使**轴标签不受 `strings.xml` 管理**，无法本地化。也正是它导致
`InsightsEngineTest` 里两个断言英文 `"Nov"`/`"Jan"` 的测试在中文系统上失败
（跑测试需加 `-Duser.language=en -Duser.country=US`）。

两个方向：**A** 固定英文（`Locale.ENGLISH`，与现有风格一致，改动小）；
**B** 走资源本地化（轴标签也进 `strings.xml` + 新增 `values-zh`，正解，工作量大）。

## 已知遗留：仓库样例文件无法解包

`samples/tryst-sample-backup.tryst` 用 `tools/dataset/README.md` 文档给的密码
`Tryst-Test-2026` 解不开（`Tag mismatch`），而 `tryst-test-dataset.tryst` 同一密码正常。
头部合法、大小合理，故是该文件自身与文档不符。**上游问题，未修改**。

---

## 已知遗留：仓库归档文件

`archive/v0.6.0/mapping.txt`（52 MB）是**上游自带**的 v0.6.0 R8 混淆映射表。
它已存在于继承的历史中，故**保持原样不删**——删掉只会在 diff 里多出一条 52 MB 的删除记录。
注意它描述的是**上游那次构建**，本 fork 已改动 `InsightsCharts.kt` / `InsightsScreen.kt` /
`BackupViewModel.kt` / `SettingsScreen.kt`，因此它与本仓库产出的任何 APK 都不对应，
仅在逆向上游发布版 APK 时有用。详见 [archive/README.md](archive/README.md)。

---

## 验证

每次改动均通过 `ktlintCheck` + `detekt` + `testDebugUnitTest`（36 passed）+ `assembleDebug`。

## 复现构建

见 `docs/SETUP_WINDOWS.md`。注意本 fork 的两处本地适配**不需要**（它们只是本机网络环境所致，
已在上文说明为何必须改）：

- `gradle/gradle-daemon-jvm.properties` 的 `toolchainVendor=JETBRAINS`
  ——若用非 JetBrains 的 JDK 21 且无法下载 JBR，需注释掉该行使其命中本地工具链。
- Maven 仓库镜像（本机 `repo1.maven.org` / `plugins.gradle.org` 不可达）。

# Emojichan

一款专注于表情包管理和快捷发送的 Android 应用。

## 功能特性

- **表情网格** - 三列网格浏览，单击看大图与详情，长按进多选
- **多种导入方式** - 从相册（可一次多选）、文件、文件夹导入，支持 JPG / PNG / GIF / WebP / BMP（含动图 WebP）；名字取原文件名，重名自动加序号，拿不到原名时用导入时间命名；从文件夹导入会按文件夹名自动打标签
- **搜索** - 名字与标签一起搜，支持 `$标签名$` 按标签、`@关键词@` 只看名字，`&` 同时满足、`/` 任一满足；输入 `$` 直接弹出标签候选
- **标签整理** - 一个表情可挂多个标签；筛选条上每颗标签带着自己的合并方式（`&` 同时 / `|` 任一 / `!` 不含），点一下循环切换
- **批量操作** - 多选后批量加 / 减标签、删除、导出（存到文件夹或打成 zip）
- **收藏** - 常用表情一键收藏
- **悬浮球快捷发送** - 屏幕上常驻一个球，点开选中表情即可发到微信 / QQ：静态图走剪贴板自动粘贴并发送，动图走系统分享，也可以走「＋ → 相册」保留动画；认不出刚放进相册的那一张时会停下来让你自己点，发送途中离开微信 / QQ 这一轮立刻收手
- **自动选中聊天对象** - 发送前读一次当前聊天窗口的名字，在「选择聊天」列表里选中同名的那一项，认不出来只需自己点一下
- **最后一步可以自己点** - 「最后一步自动确认」默认开着，关掉则流程停在发送按钮前，由你自己点
- **悬浮球出现范围** - 设置里挑哪些应用显示悬浮球（默认微信 + QQ，清空 = 所有应用）
- **状态栏磁贴** - 下拉状态栏点一下就能开 / 收悬浮球（磁贴要自己拖进面板一次）
- **发送日志** - 记录每次发送走的路线与结果，按普通 / 警告 / 错误三级显示，可复制、导出 TXT；出错自动留一份，聊天内容一律打码
- **存储管理** - 体积概览（环形图 + 各格式占用）、大表情压缩（不改分辨率，预览后再替换）、重复与相似检索（内容完全相同 + 画面相近，删哪张由你定）
- **表情库迁移** - 导出 / 导入带标签与收藏的 `.emcpack` 包，换手机时用；旧版 `.zip` 包也能导入
- **其他** - 浅色 / 深色主题与两套主题色、四种悬浮球外观、首次使用引导、复制到剪贴板、危险操作二次确认

## 技术栈

- **开发语言**：Kotlin
- **最低版本**：Android 8.0 (API 26)
- **架构模式**：MVVM
- **数据库**：Room 2.6.0（DB v5：标签多对多结构 + 图片指纹缓存表，含 v1→v2、v2→v3、v3→v4、v4→v5 迁移）
- **图片加载**：Glide 4.16.0
- **UI组件**：Material Design 1.11.0
- **异步处理**：Coroutines 1.7.3
- **依赖管理**：Gradle Version Catalog
- **测试**：JUnit 4 + Robolectric 4.11.1 + Room in-memory

## 项目结构

```
app/src/main/java/com/aris/emojichan/
├── EmojiChanApp.kt          # Application：深色模式与相册残留清理
├── MainActivity.kt          # 主界面
├── EmojiDetailActivity.kt   # 详情页
├── EmojiGridAdapter.kt      # 网格适配器
├── UiPrefs.kt               # 外观偏好（深色模式 / 主题色 / 悬浮球样式）
├── ImportPrefs.kt           # 导入偏好（源文件处理策略 / zip 存放目录）
├── CompressPrefs.kt         # 压缩偏好（有损还是无损 / 压缩页要不要列出动图；v0.1.409 起默认无损）
├── SimilarPrefs.kt          # 相近判定底线（v0.1.412：相似度 94% / 88% / 81%，默认 88%）
├── OnboardingPrefs.kt       # 首次使用引导是否已经看过（v0.1.414）
├── AdvancedSettingsActivity.kt  # 高级设置页（原文件 / 大表情压缩 / 发送方式 / 诊断：日志与使用引导）
├── OnboardingActivity.kt    # 首次使用引导页（v0.1.414：四步说明 + 权限入口 + 跳过二次确认）
├── VersionActivity.kt        # 版本页（应用图标 / 名字 / 版本 / GitHub 入口）
├── data/                    # 数据层
│   ├── EmojiEntity.kt       # 表情实体（v4 起不含分类列）
│   ├── TagEntity.kt         # 标签实体
│   ├── EmojiTagCrossRef.kt  # 表情 ↔ 标签 挂载表（多对多）
│   ├── ImageFeatureEntity.kt # 图片指纹缓存（sha256 / dhash，v0.1.409）
│   ├── EmojiDefaults.kt     # 内置标签常量（图片 / 动图）
│   ├── EmojiNaming.kt       # 导入重名去重
│   ├── EmojiFilter.kt       # 过滤条件 + 搜索表达式（$标签$ / @名字@ / & / /）解析
│   ├── EmojiQuery.kt        # 过滤条件 → SQL
│   ├── EmojiDao.kt          # 数据访问
│   ├── EmojiDatabase.kt     # 数据库（含 v1→v2、v2→v3、v3→v4、v4→v5 迁移）
│   ├── EmojiRepository.kt   # 数据仓库
│   ├── EmojiOrder.kt        # 「最近」这一档的排序规则（纯函数，v0.2.007）
│   ├── TagOrder.kt          # 标签下拉里各颗标签的先后顺序（纯函数，v0.2.013）
│   └── TagCount.kt          # 一个标签挂着多少张表情（只为标签排序而读，v0.2.013）
├── viewmodel/               # ViewModel层
│   └── EmojiViewModel.kt
├── util/                    # 工具类
│   ├── ImageUtil.kt         # 文件拷贝 / 取原文件名
│   ├── EmojiImport.kt       # 三条导入路径共用的 Uri → 实体链路
│   ├── FolderImporter.kt    # 文件夹递归遍历，按文件夹名分组成批
│   ├── EmojiArchive.kt      # 表情库打包导出 / 导入（.emcpack 包，zip 里带 JSON 清单）
│   ├── SourceFiles.kt       # 导入源文件的打包与删除（只碰本次选中的 Uri）
│   ├── ImageTypes.kt        # 文件类型与 MIME（jpg/png/gif/webp/bmp + 动图 WebP 头判定）
│   ├── FileHash.kt          # 文件 sha256（v0.1.409）
│   ├── PerceptualHash.kt    # 感知哈希 dHash（灰度 / 裁留白 / 9×8 比对，v0.1.409）
│   ├── BusyDialog.kt        # 不能取消的进度框（压缩 / 导出共用）
│   ├── ImageFit.kt          # 详情页图片按原比例完整显示（纯函数，可单测）
│   ├── ImportOrder.kt       # 批量导入的顺序：按源文件时间从早到晚（v0.2.013）
│   └── RecentsHider.kt      # 按设置把「最近任务」里的本应用隐藏（v0.2.013）
├── storage/                 # 存储管理（三级页）
│   ├── StorageActivity.kt   # 一级：概览（含清除缓存）+ 压缩 / 检索 / 长时间不用三个入口
│   ├── CompressActivity.kt  # 二级：大表情压缩（三列网格 / 按体积分段 / 导出原图）
│   ├── CompressPreviewActivity.kt  # 三级：压缩后预览（逐张对比、替换或放弃）
│   ├── CompressPreviewAdapter.kt   # 预览页网格
│   ├── CompressAdapter.kt   # 压缩页网格（分段 / 选择集 / 可压判定）
│   ├── CompressSession.kt   # 压缩结果的中转（进程内）
│   ├── SearchActivity.kt    # 三级：重复与相似检索（一页两段：完全相同 + 画面相近，v0.1.411；v0.2.007 起要自己点「开始检测」）
│   ├── SearchAdapter.kt     # 合并页列表（两段小标题 + 两种分组行，v0.1.411；v0.2.006 起组底多一颗「忽略这组」）
│   ├── SimilarIgnore.kt     # 忽略名单的键（无序对 `a:b` / 组内两两组合 / 失效剪枝，v0.2.006）
│   ├── IgnoreListAdapter.kt # 忽略列表页的行（两张缩略图 + 名字 + 不再忽略，v0.2.006；v0.2.008 起列表搬成独立一页）
│   ├── SimilarIgnoreActivity.kt # 三级：忽略列表（一条一次忽略，可逐条撤销或全部恢复，v0.2.008）
│   ├── DuplicateFinder.kt   # 完全相同判定（体积分桶 + sha256，纯函数，v0.1.409）
│   ├── SimilarFinder.kt     # 相似分组（跟最近的组代表比，v0.1.412 起按相似度由高到低排，v0.1.413 起页面显示百分比；v0.2.006 起分组时跳过被忽略的对）
│   ├── ImageDHash.kt        # 从文件算 dHash（缩到 64 像素再解码，v0.1.409）
│   ├── FeatureCache.kt      # 指纹行的写回规则（体积 + 修改时间当版本号，v0.1.409）
│   ├── CacheCleaner.kt      # 清除缓存（先 Glide 再删 cacheDir 子项，v0.1.409）
│   ├── ImageExporter.kt     # 导出（zip 或写进用户选的文件夹）
│   ├── StorageUsage.kt      # 体积统计（磁盘实际占用 / 格式分组 / 数据库与缓存）
│   ├── StoragePieView.kt    # 环形图（自己画，不引图表库）
│   ├── EmojiCompressor.kt   # 压缩（EXIF 转正 + 只换编码不缩分辨率，可切有损 / 无损）
│   ├── StaleRule.kt         # 「多久没发过」的起算点（纯函数，v0.2.013）
│   ├── StaleEmojiActivity.kt # 三级：长时间不使用的表情（选范围 → 勾选 → 删除 / 导出，v0.2.013）
│   └── StaleEmojiAdapter.kt # 这一页的网格（勾选框常显 + 名字下一行「多久没发过」，v0.2.013）
└── sender/                  # 发送模块（悬浮球 / 无障碍 / 分享）
    ├── FloatingBallService.kt   # 悬浮球窗口与表情面板
    ├── SendPanelController.kt   # 面板视图控制
    ├── AutoSendService.kt       # 无障碍服务：前台应用判定、读聊天标题、选中会话
    ├── EmojiShare.kt            # 剪贴板 / 直接分享 / FileProvider
    ├── LoggingFileProvider.kt   # 带取图流水的 FileProvider（谁按什么类型来取）
    ├── AlbumPublish.kt          # 写入相册（动图走「＋ → 相册」时用）
    ├── ClipForensics.kt         # 剪贴板取证（复制 / 粘贴出问题时看交出去了什么）
    ├── SenderPrefs.kt           # 发送模块的开关与设置（含悬浮球显示范围）
    ├── BallScope.kt             # 悬浮球显示范围判定（纯函数，可单测）
    ├── BallTileService.kt       # 下拉状态栏快捷开关（QS 磁贴）
    ├── TileLaunch.kt            # 磁贴启动的分界线（API 34 起必须走 PendingIntent，纯函数，v0.2.015）
    ├── SendLog.kt               # 发送日志写入（三级显示，出错自动存一份）
    └── SendLogActivity.kt       # 发送日志界面（v0.1.413 起去剪贴板自检、加导出 TXT 与错误日志列表）

app/src/main/java/com/google/android/accessibility/selecttospeak/
└── SelectToSpeakService.kt  # 读屏组件名的空壳类

app/src/test/java/com/aris/emojichan/          # 共 191 条，全部通过（v0.2.221）
├── data/EmojiDaoTest.kt          # 数据层回归测试（Robolectric + Room in-memory，36 条）
├── data/EmojiDatabaseMigrationTest.kt  # 迁移测试 v2→v3、v3→v4、v4→v5（6 条）
├── data/EmojiNamingTest.kt       # 批量导入时批内不重名（5 条，v0.2.215）
├── data/EmojiOrderTest.kt        # 「最近」那一档的排序规则（3 条，v0.2.007）
├── data/TagOrderTest.kt          # 标签下拉的排序规则（自动标签在前，5 条，v0.2.013）
├── util/PerceptualHashTest.kt    # 感知哈希（灰度 / 裁留白 / dHash / 距离，13 条）
├── util/ImageFitTest.kt          # 详情页完整显示的尺寸（11 条）
├── util/ImageTypesTest.kt        # 扩展名 / MIME / 动图 WebP 头（9 条）
├── util/FileHashTest.kt          # sha256（6 条）
├── util/ImageNamingTest.kt       # 导入命名判据（6 条）
├── util/EmojiArchiveTest.kt      # .emcpack 清单与解包的判据（4 条）
├── storage/SimilarFinderTest.kt  # 相似分组、底线、排序、相似度换算与忽略判定（18 条）
├── storage/SimilarIgnoreTest.kt  # 忽略名单的键、两两组合、整条忽略与失效剪枝（14 条，v0.2.006–v0.2.009）
├── storage/DuplicateFinderTest.kt # 完全相同判定（体积分桶 / 保留对象，10 条）
├── storage/FeatureCacheTest.kt   # 指纹行的版本判据（6 条）
├── storage/StaleRuleTest.kt      # 「多久没发过」的起算点（6 条，v0.2.013）
├── storage/ImageDHashTest.kt     # 从文件算 dHash（5 条）
├── storage/StorageUsageTest.kt   # 存储统计（格式判定 / 占比 / 目录体积，5 条）
├── storage/EmojiCompressorTest.kt # 压缩（堆占用判定 / 无损落哪种格式，4 条）
├── SimilarPrefsTest.kt           # 相近底线与忽略名单的存取（4 条，v0.2.006）
├── CompressPrefsTest.kt          # 压缩偏好的默认值（3 条）
├── OnboardingPrefsTest.kt        # 首次使用引导的看过标记（3 条）
├── sender/BallScopeTest.kt       # 悬浮球显示范围（6 条）
└── sender/TileLaunchTest.kt      # 磁贴启动的分界线：API 34 起必须走 PendingIntent（3 条，v0.2.015）
```

## 开发文档

项目开发文档（**本地维护，未入库**，都放在 `md/` 目录下，因此下面的路径在仓库里点不开）：

- `md/核心内容.md` - 技术栈、目录结构、分层要点、开发红线与已实现功能清单
- `md/Bug清单.md` - 待解决的问题（编号格式 `emc-<级别>-<序号>`）
- `md/已修Bug.md` - 已修复问题的根因与修法记录，编号与 Bug清单 一致
- `md/日志.md` - 按时间记录的开发与排查过程
- `md/插件系统.md` - 插件化路线调研（编译期模块 / 独立 APK / 脚本沙箱 / 动态 dex）与待定问题
- `md/表情发送模块.md` - 悬浮球发送模块的需求与决策、悬浮窗 / 无障碍约束、Android 13+ 受限设置、判定原理与实测记录
- `md/未来计划书.md` - 还没定下来怎么做的事（简称 **FPN**）：搜索联想与 tag 检索、新用户引导页、存储管理里剩下的重复相似检索

## 版本与发布

- 当前版本 **v0.2.221**（versionCode 2221）
- 每出一版打一个标签（`v0.2.221` 这类），标签注释里记着安装包的字节数与 SHA-256；安装包不进仓库，Release 页由维护者手动挂
- `main` 只放真机验证过、真出过包的提交；风险大的改动走 `exp/<主题>` 分支，验证通过再合回

## 安装说明

### 从源码编译

1. 克隆项目
```bash
git clone https://github.com/guiche0139/emojichan.git
```

2. 用 Android Studio 打开项目

3. 等待 Gradle 同步完成

4. 命令行打 release 包（需 JDK 17）：`./gradlew :app:testDebugUnitTest :app:assembleRelease`（Windows 上是 `gradlew.bat`），产物在 `app/build/outputs/apk/release/`；签名信息从仓库根之外的 `keystore.properties` 读，没有就出未签名包
5. 连接手机或启动模拟器，点击 Run

### 直接安装 APK

1. 下载 Release 版本的 APK 文件
2. 在手机上安装（需开启"允许安装未知来源应用"）

## 使用说明

> 第一次打开会走一遍使用引导（应用做什么 / 悬浮球权限 / 自动发送服务 / 相册读权限），之后想重看：「设置 → 高级设置 → 诊断 → 使用引导」。

1. **导入** - 点底部「导入」，先选从哪里导：相册（可多选）、文件（显示名就是磁盘上的真名）、文件夹（递归导入并按文件夹名自动打标签）。导入完可以「再导入一批」接着挑。
2. **搜索** - 顶部搜索框输入关键词即可；要按标签搜写 `$标签名$`、只搜名字写 `@关键词@`，`&` 同时满足、`/` 任一满足，例如 `$TAG=猫$ & @生气@ / $TAG=狗$`。
3. **标签** - 点筛选条最左侧「标签」拉开面板：单击标签即筛选（可多选），长按重命名 / 删除，面板底部可「新建」「管理」。
4. **发送** - 点开悬浮球选一张表情，静态图自动粘到微信 / QQ 输入框并发送；动图走系统分享，或在面板里切到「＋ → 相册」保留动画。
5. **整理与导出** - 长按进入多选，可批量加标签、删除，或把选中的表情存到文件夹 / 打包成 zip；详情页也能单独导出、复制到剪贴板。
6. **存储管理** - 「设置 → 存储」：先看体积概览；「大表情压缩」压完先进预览页对比（按住看原图），确认后才替换；「重复与相似检索」点「开始检测」才比对，结果只当候选，删哪张由你决定。
7. **备份换机** - 「设置 → 表情库迁移 → 导出表情库」存成 `.emcpack`，新手机上导回：选「添加」只补缺的，选「覆盖」会清空后重来。
8. **排查** - 发送不对时看「设置 → 高级设置 → 发送日志」；导入名字不对时找 tag 为 `导入` 的行，能看到这一张图在各来源里的原始名字。

## 开发环境

- Android Studio Hedgehog (2023.1) 或更高版本
- JDK 17
- Android SDK 34

## 隐私说明

- 本应用**不申请联网权限**，清单里只有「显示在其他应用上层」（悬浮球）一条权限；另有一项**可选**的相册读权限，只为读出导入文件的原始文件名，不授权也能正常导入。表情、标签、日志都只存在本机。
- 「表情自动发送」无障碍服务只在三个时机读屏幕：判断前台是不是微信 / QQ、点选表情后读一次聊天标题用来选中同名会话、发送过程中找按钮与格子。这些文本只用于当次操作，落盘一律打码，不采集、不保存、不上传聊天内容。
- 悬浮球需要悬浮窗权限；Android 13 及以上如果开关是灰的，需要在系统设置的「受限设置」里允许本应用。

## 许可证

MIT License

## 联系方式

如有问题或建议，欢迎提 Issue。

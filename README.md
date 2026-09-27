# 表情包助手 Emojichan

一款专注于表情包管理和快捷发送的 Android 应用。

## 功能特性

- **表情包网格展示** - 3列网格布局，清晰浏览所有表情包
- **多格式导入** - 相册导入图片/GIF/PNG/WebP，自动保留原始格式
- **实时搜索** - 支持按名称、标签搜索，可限定在当前分类内
- **分类筛选** - 顶部标签栏按分类聚合切换，可与搜索组合使用
- **分类管理** - 新建 / 重命名 / 删除自定义分类，空分类也会保留
- **收藏功能** - 常用表情一键收藏
- **多选删除** - 批量删除多个表情包（自动清理本地文件）
- **详情预览** - 大图预览，查看分类/大小/尺寸等详细信息，支持重命名
- **悬浮球快捷发送** - 屏幕上常驻一个悬浮球，点开选中表情即可发到微信 / QQ：静态图走剪贴板自动粘贴并发送，动图走系统分享（也可切到「＋ → 相册」以保留动画）
- **自动选中聊天对象** - 发送前读一次当前聊天窗口的名字，在微信的「选择聊天」列表里自动选中同名的那一项；认不出来时只需自己点一下
- **主界面复制到剪贴板** - 网格里每个表情右下角有「复制」按钮，可把图片（含 GIF）放进系统剪贴板，供任意应用粘贴
- **发送日志** - 记录每次发送走了哪条路线、结果如何，出问题时可以直接复制出来

## 技术栈

- **开发语言**：Kotlin
- **最低版本**：Android 8.0 (API 26)
- **架构模式**：MVVM
- **数据库**：Room 2.6.0
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
├── data/                    # 数据层
│   ├── EmojiEntity.kt       # 表情实体
│   ├── CategoryEntity.kt    # 分类实体
│   ├── EmojiDao.kt          # 数据访问
│   ├── EmojiDatabase.kt     # 数据库（含 v1→v2 迁移）
│   └── EmojiRepository.kt   # 数据仓库
├── viewmodel/               # ViewModel层
│   └── EmojiViewModel.kt
├── util/                    # 工具类
│   └── ImageUtil.kt
└── sender/                  # 发送模块（悬浮球 / 无障碍 / 分享）
    ├── FloatingBallService.kt   # 悬浮球窗口与表情面板
    ├── SendPanelController.kt   # 面板视图控制
    ├── AutoSendService.kt       # 无障碍服务：前台应用判定、读聊天标题、选中会话
    ├── EmojiShare.kt            # 剪贴板 / 直接分享 / FileProvider
    ├── LoggingFileProvider.kt   # 带取图流水的 FileProvider（谁按什么类型来取）
    ├── AlbumPublish.kt          # 写入相册（动图走「＋ → 相册」时用）
    ├── ClipForensics.kt         # 剪贴板取证（复制 / 粘贴出问题时看交出去了什么）
    ├── SenderPrefs.kt           # 发送模块的开关与设置
    ├── SendLog.kt               # 发送日志写入
    └── SendLogActivity.kt       # 发送日志界面

app/src/test/java/com/aris/emojichan/data/
└── EmojiDaoTest.kt          # 数据层回归测试（Robolectric + Room in-memory）
```

## 开发文档

项目开发文档（**本地维护，未入库**，因此下面的文件名在仓库里点不开）：

- `核心内容.md` - 技术栈、目录结构、分层要点、开发红线与已实现功能清单
- `Bug清单.md` - 待解决的问题（编号格式 `emc-<级别>-<序号>`）
- `已修Bug.md` - 已修复问题的根因与修法记录，编号与 Bug清单 一致
- `日志.md` - 按时间记录的开发与排查过程
- `插件系统.md` - 插件化路线调研（编译期模块 / 独立 APK / 脚本沙箱 / 动态 dex）与待定问题
- `表情发送模块.md` - 悬浮球发送模块的需求与决策、悬浮窗 / 无障碍约束、Android 13+ 受限设置、判定原理与实测记录

## 安装说明

### 从源码编译

1. 克隆项目
```bash
git clone https://github.com/guiche0139/emojichan.git
```

2. 用 Android Studio 打开项目

3. 等待 Gradle 同步完成

4. 连接手机或启动模拟器，点击 Run

### 直接安装 APK

1. 下载 Release 版本的 APK 文件
2. 在手机上安装（需开启"允许安装未知来源应用"）

## 使用说明

1. **导入表情包** - 点击底部"导入"按钮，从相册选择图片
2. **搜索** - 在顶部搜索框输入关键词
3. **分类** - 点击标签切换不同分类；点击标签栏末尾的"＋ 管理"可新建、重命名、删除分类
   - 删除分类时，该分类下的表情会移动到"默认"分类，不会丢失
4. **收藏** - 点击星标图标收藏/取消收藏
5. **删除** - 长按进入多选模式，选择后点击删除
6. **查看** - 单击表情包查看大图和详细信息

## 开发环境

- Android Studio Hedgehog (2023.1) 或更高版本
- JDK 17
- Android SDK 34

## 隐私说明

- 本应用**没有申请联网权限**，清单里只申请了「显示在其他应用上层」（悬浮球）一条权限；导入图片走系统自带的图片选择器，不需要读相册权限。所有表情、分类、日志都只存在本机。
- 「表情自动发送」无障碍服务只在两个时机读取屏幕内容：① 判断前台应用是不是微信 / QQ；② 你点选表情后读一次聊天窗口标题。它不采集、不保存、不上传聊天内容。
- 悬浮球需要「显示在其他应用上层」权限；Android 13 及以上如果开关是灰的，需要在系统设置的「受限设置」里允许本应用。

## 许可证

MIT License

## 联系方式

如有问题或建议，欢迎提 Issue。

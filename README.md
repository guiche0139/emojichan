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
├── MainActivity.kt          # 主界面
├── EmojiDetailActivity.kt   # 详情页
├── EmojiGridAdapter.kt      # 网格适配器
├── data/                    # 数据层
│   ├── EmojiEntity.kt       # 表情实体
│   ├── CategoryEntity.kt    # 分类实体
│   ├── EmojiDao.kt          # 数据访问
│   ├── EmojiDatabase.kt     # 数据库（含 v1→v2 迁移）
│   └── EmojiRepository.kt   # 数据仓库
├── viewmodel/               # ViewModel层
│   └── EmojiViewModel.kt
└── util/                    # 工具类
    ├── ImageUtil.kt
    └── PermissionUtil.kt

app/src/test/java/com/aris/emojichan/data/
└── EmojiDaoTest.kt          # 数据层回归测试（Robolectric + Room in-memory）
```

## 开发文档

项目开发文档（本地维护，不入库）：

- [核心内容](核心内容.md) - 技术栈、目录结构、数据层/ViewModel/工具层/UI 层要点、开发红线，以及当前已实现的功能清单
- [Bug清单](Bug清单.md) - 待解决的问题（编号格式 `emc-<级别>-<序号>` 与优先级约定见文档头部）
- [已修Bug](已修Bug.md) - 已修复问题的根因与修法记录，编号与 Bug清单 一致
- [日志](日志.md) - 按时间记录的开发与排查过程
- [插件系统](插件系统.md) - 插件化路线调研（编译期模块 / 独立 APK / 脚本沙箱 / 动态 dex）与待定问题

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

## 许可证

MIT License

## 联系方式

如有问题或建议，欢迎提 Issue。

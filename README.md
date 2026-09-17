# 表情包助手 Emojichan

一款专注于表情包管理和快捷发送的 Android 应用。

## 功能特性

- **表情包网格展示** - 3列网格布局，清晰浏览所有表情包
- **多格式导入** - 相册导入图片/GIF/PNG/WebP，自动保留原始格式
- **实时搜索** - 支持按名称、标签、来源搜索，可限定在当前分类内
- **分类管理** - 自定义分类，轻松整理
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

## 项目结构

```
app/src/main/java/com/aris/emojichan/
├── MainActivity.kt          # 主界面
├── EmojiDetailActivity.kt   # 详情页
├── EmojiGridAdapter.kt      # 网格适配器
├── data/                    # 数据层
│   ├── EmojiEntity.kt       # 实体类
│   ├── EmojiDao.kt          # 数据访问
│   ├── EmojiDatabase.kt     # 数据库
│   └── EmojiRepository.kt   # 数据仓库
├── viewmodel/               # ViewModel层
│   └── EmojiViewModel.kt
└── util/                    # 工具类
    ├── ImageUtil.kt
    └── PermissionUtil.kt
```

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
3. **分类** - 点击标签切换不同分类
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

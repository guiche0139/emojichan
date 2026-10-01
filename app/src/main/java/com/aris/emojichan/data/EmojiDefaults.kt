package com.aris.emojichan.data

/**
 * 数据层里那些有固定字面量的名字。
 *
 * 这些字符串会直接写进数据库（标签名），所以只在这里定义一份：
 * 迁移脚本、默认值、UI 文案都从这里取，避免「一处改了、另一处还写旧字面量」。
 */
object EmojiDefaults {

    /** 导入静态图时自动挂上的标签。 */
    const val TAG_IMAGE = "图片"

    /** 导入动图（GIF）时自动挂上的标签。 */
    const val TAG_ANIMATED = "动图"

    /** 导入时自动挂上的那两个标签。 */
    val AUTO_TAGS = setOf(TAG_IMAGE, TAG_ANIMATED)

    /**
     * 是不是导入时自动挂上的标签。
     *
     * 「图片」「动图」表达的是这张表情本身是静态图还是动图（导入时按文件类型判定），
     * 与用户自己建的标签不是一类东西：允许改名或删除，筛选和导入逻辑就对不上了。
     */
    fun isAutoTag(name: String?): Boolean = name != null && name.trim() in AUTO_TAGS
}

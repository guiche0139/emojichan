package com.aris.emojichan.data

/**
 * 表情名字的去重规则。
 *
 * 导入时名字保持原样（不再改成「表情 + 时间戳」）；重名时后来的在名字后面加
 * 括号和序号：`猫猫.gif` → `猫猫(1)` → `猫猫(2)`。
 *
 * 纯函数、不碰数据库，方便直接用单元测试压边界（空名字、已经带括号的名字、
 * 序号被占用等）。
 */
object EmojiNaming {

    /** 名字为空时兜底用的字面量。 */
    const val FALLBACK = "未命名"

    /** 序号最多试到多少；再撞下去就在后面接时间戳，绝不返回重复名字。 */
    private const val MAX_SUFFIX = 999

    /**
     * @param base 想要的名字（调用方已经 trim 过）
     * @param taken 库里已存在的名字
     * @return 一定不在 [taken] 里的名字
     */
    fun unique(base: String, taken: Collection<String>): String {
        val name = base.trim().ifEmpty { FALLBACK }
        if (name !in taken) return name
        for (index in 1..MAX_SUFFIX) {
            val candidate = name + "(" + index + ")"
            if (candidate !in taken) return candidate
        }
        return name + "(" + System.currentTimeMillis() + ")"
    }
}

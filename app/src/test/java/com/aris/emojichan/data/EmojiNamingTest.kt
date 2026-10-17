package com.aris.emojichan.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 批量导入的命名判据（v0.2.215）。
 *
 * 导入从「每条都去查一次全表」改成「先取一次名字、在内存里逐条算」，性能收益的前提是
 * 结果与旧路径**逐字一致** —— 命名是用户可见行为（猫猫 → 猫猫(1) → 猫猫(2)），
 * 批量算错就会出现两个同名表情，或者序号跳号。
 */
class EmojiNamingTest {

    /** 库里已有 A，批内又来两条 A：第二条要看到第一条占掉的 A(1)... 不能只看库里。 */
    @Test
    fun batchKeepsSiblingsUnique() {
        assertEquals(
            listOf("A", "A(1)", "A(2)"),
            EmojiNaming.uniqueBatch(listOf("A", "A", "A"), emptyList())
        )
    }

    /** 库里已有 A 与 A(1) 时，批内两条 A 必须落到 A(2) / A(3)。 */
    @Test
    fun batchSkipsNamesAlreadyTakenInDatabase() {
        assertEquals(
            listOf("A(2)", "A(3)"),
            EmojiNaming.uniqueBatch(listOf("A", "A"), listOf("A", "A(1)"))
        )
    }

    /** 库里 A + A(1)，批内第一条就叫 A(1)：它自己在后面接序号，随后那条裸 A 只能落到 A(2)。 */
    @Test
    fun batchHandlesBasesThatAlreadyLookLikeSuffixes() {
        assertEquals(
            listOf("A(1)(1)", "A(2)"),
            EmojiNaming.uniqueBatch(listOf("A(1)", "A"), listOf("A", "A(1)"))
        )
    }

    /** 批里混着不同名字、空名字、已带括号的名字：结果与逐条调用 unique 完全一致。 */
    @Test
    fun batchMatchesSequentialResult() {
        val bases = listOf("猫猫", "猫猫", "", "猫猫(1)", "狗", "  ", "猫猫")
        val existing = listOf("猫猫", "狗(1)")

        val sequential = mutableListOf<String>()
        val taken = existing.toMutableList()
        bases.forEach { base ->
            val name = EmojiNaming.unique(base, taken)
            taken += name
            sequential += name
        }

        assertEquals(sequential, EmojiNaming.uniqueBatch(bases, existing))
    }

    /** 不传任何已占用名字时，第一条保持原样（导入不打乱用户看得懂的名字）。 */
    @Test
    fun firstOneKeepsOriginalName() {
        assertEquals(listOf("猫猫"), EmojiNaming.uniqueBatch(listOf("猫猫"), emptyList()))
    }
}

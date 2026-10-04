package com.aris.emojichan.storage

/**
 * 「多久没发过」的口径（v0.2.013 用户 m11668 第 5 条；起算点按用户 m11937 修正）。
 *
 * 发过的表情看最后一次发送时间；从未发过的从入库那天算起 —— 用户 m11937：刚加进来的表情不该立刻
 * 掉进「90 / 180 / 365 天没发过」里，得先躺够这个天数。所以「入库多久」是这类表情唯一的时间依据，
 * 少了它那三档就等于把整个新导入的相册一次全列出来。
 *
 * 「从未发过」那一档不看到库时间：那一档问的是「有没有发过」，与躺了多久无关。
 *
 * 纯函数、不碰 Android，判定与它为什么长这样都落在 单测 里（见 StaleRuleTest）。
 */
object StaleRule {

    const val DAY_MS = 24L * 60L * 60L * 1000L

    /** 从未发过：0（或负数）表示一次都没发过，usageCount 也一直是 0。 */
    fun neverUsed(lastUsedTime: Long): Boolean = lastUsedTime <= 0L

    /** 起算时刻：发过的看最后一次发送时间，从未发过的看入库时间。 */
    fun since(lastUsedTime: Long, createTime: Long): Long =
        if (lastUsedTime > 0L) lastUsedTime else createTime

    /**
     * 这张表情在 [now] 时刻算不算某一档：[days] = 0 是「从未发过」那一档，其余档要求起算时刻
     * 严格早于 `now - days 天`（正好卡在阈值上还不算，得过了才进）。
     */
    fun matches(lastUsedTime: Long, createTime: Long, days: Int, now: Long): Boolean {
        if (days <= 0) return neverUsed(lastUsedTime)
        return since(lastUsedTime, createTime) < now - days * DAY_MS
    }
}

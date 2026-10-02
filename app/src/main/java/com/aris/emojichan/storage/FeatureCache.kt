package com.aris.emojichan.storage

import com.aris.emojichan.data.ImageFeatureEntity

/**
 * 指纹行的写回规则（v0.1.409）。
 *
 * 一行记录的是「某个版本的这张文件」算出来的特征，体积 + 修改时间就是那个版本号：
 * - 两个都对得上：另一项特征还算数，留着；
 * - 对不上：文件换过了，另一项特征对新版本已经无效，必须丢掉 ——
 *   不然「完全相同」页会拿旧摘要去比已经换过的文件，能比出根本不存在的重复。
 *
 * 两个页面共用这一份规则：谁先算就写谁，另一项照旧。
 */
object FeatureCache {

    /**
     * 组装要写回的一行。
     *
     * @param sha256 这一轮算出来的内容摘要；没算就给 null（沿用旧的，前提是版本没变）
     * @param dhash 这一轮算出来的感知哈希；没算就给 null（同上）
     */
    fun merged(
        item: DuplicateFinder.Item,
        old: ImageFeatureEntity?,
        sha256: String? = null,
        dhash: Long? = null
    ): ImageFeatureEntity {
        val sameVersion = old != null && old.bytes == item.bytes && old.modifiedAt == item.modifiedAt
        return ImageFeatureEntity(
            emojiId = item.id,
            bytes = item.bytes,
            modifiedAt = item.modifiedAt,
            sha256 = sha256 ?: if (sameVersion) old?.sha256 else null,
            dhash = dhash ?: if (sameVersion) old?.dhash else null,
            computedAt = System.currentTimeMillis()
        )
    }

    /** 旧行里的内容摘要还能用吗（体积与修改时间都跟当前文件对得上）。 */
    fun sha256Of(old: ImageFeatureEntity?, item: DuplicateFinder.Item): String? =
        old?.takeIf { it.bytes == item.bytes && it.modifiedAt == item.modifiedAt }?.sha256

    /** 旧行里的感知哈希还能用吗。 */
    fun dhashOf(old: ImageFeatureEntity?, item: DuplicateFinder.Item): Long? =
        old?.takeIf { it.bytes == item.bytes && it.modifiedAt == item.modifiedAt }?.dhash
}

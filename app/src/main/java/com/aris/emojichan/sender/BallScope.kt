package com.aris.emojichan.sender

/**
 * 悬浮球该不该出现 —— 纯判断，单独放一个文件是为了能写单测
 * （Service 里那些逻辑都得真机跑才有反馈）。
 *
 * 这里刻意不看 [AutoSendService.WATCHED_PACKAGES]：自动发送只认微信 / QQ，
 * 球的显示范围由用户自己勾（[SenderPrefs.ballPackages]），两者互不牵连。
 */
object BallScope {

    /**
     * 前台是 [foregroundApp] 时要不要显示悬浮球。
     *
     * - [ballPackages] 为空 = 用户一个都没勾 = 不限制应用，哪里都显示；
     * - [foregroundApp] 为 null = 认不出前台是谁（没开无障碍服务，或刚切完页面还没落定），
     *   这时宁可显示：球多留一会儿用户没有损失，该出现时不出现才会被当成 bug。
     */
    fun shouldShow(ballPackages: Set<String>, foregroundApp: String?): Boolean =
        ballPackages.isEmpty() || foregroundApp == null || foregroundApp in ballPackages

    /** 设置页显示用的一句话：一个都没勾时说 [fallback]，否则把应用名列出来。 */
    fun describe(packages: Set<String>, fallback: String, labelOf: (String) -> String): String =
        if (packages.isEmpty()) fallback else packages.joinToString("、", transform = labelOf)
}

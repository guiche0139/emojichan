package com.aris.emojichan

import android.content.Context

/**
 * 「使用引导」看过没有（v0.1.414，用户 m09215）。
 *
 * 只存一个布尔值：第一次正常启动时自动出现一次，之后不再自己冒出来。
 * 「跳过」也算看过 —— 那是用户明确的决定，下次启动再弹一遍就成了骚扰。
 * 想再看一遍的入口在「设置 → 高级设置 → 诊断 → 使用引导」。
 *
 * 跟界面长相无关，所以不塞进 [UiPrefs]。
 */
object OnboardingPrefs {

    private const val PREFS = "onboarding"
    private const val KEY_DONE = "done"

    fun isDone(context: Context): Boolean = prefs(context).getBoolean(KEY_DONE, false)

    fun markDone(context: Context) {
        prefs(context).edit().putBoolean(KEY_DONE, true).apply()
    }

    /** 清掉标记：下次启动又会走一遍引导（「重新看一遍」那条路要用）。 */
    fun reset(context: Context) {
        prefs(context).edit().remove(KEY_DONE).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

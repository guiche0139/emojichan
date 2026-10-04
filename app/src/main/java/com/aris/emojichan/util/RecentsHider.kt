package com.aris.emojichan.util

import android.app.Activity
import android.app.ActivityManager
import com.aris.emojichan.UiPrefs

/**
 * 「在最近任务里隐藏」（用户 m11668 第 3 条）：防止手滑把 EmojiChan 从最近任务里划掉。
 *
 * 用的是 ActivityManager.AppTask.setExcludeFromRecents —— API 21 就有的公开接口，
 * 往这个 task 的根 Intent 上打 FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS，不用改 manifest，
 * 也不用 activity-alias 那类取巧写法。
 *
 * 两个要留意的点：
 *  1. 它只作用于**当前这个 task 实例**。从最近任务里划掉之后重新点图标启动，是个全新的 task，
 *     标志回到 manifest 的默认值 —— 所以每次回前台都要按设置再套一遍（见 MainActivity.onResume）。
 *  2. 关掉开关时必须显式传 false：这个标志不会自己消失。
 */
object RecentsHider {

    /** 按设置把当前 task 的标志套一遍；读不到 task 就什么都不做（不弹错、不崩）。 */
    fun apply(activity: Activity) {
        val hide = UiPrefs.hideFromRecents(activity)
        val tasks = activity.getSystemService(ActivityManager::class.java)?.appTasks ?: return
        tasks.firstOrNull { it.taskInfo?.taskId == activity.taskId }
            ?.setExcludeFromRecents(hide)
    }
}

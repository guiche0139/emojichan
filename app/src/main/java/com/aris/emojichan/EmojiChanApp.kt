package com.aris.emojichan

import android.app.Application

/**
 * 两件事：① 在第一个界面出现之前把用户选的深浅色交给 AppCompat —— 放在 Application
 * 而不是 Activity 里，是因为这时候主题还没被任何窗口定下来，晚一步第一帧就会闪一下浅色；
 * ② 顺手看一眼相册里那个固定槽位（上次写到一半的要放出来，旧版本留下的临时图数一数）。
 */
class EmojiChanApp : Application() {
    override fun onCreate() {
        super.onCreate()
        UiPrefs.applyNightMode(this)
        // 要读系统相册数据库，丢到后台线程；这里只是自检和恢复，不删任何东西。
        // v0.2.215（性能 T1-10）：再降到后台优先级 —— 冷启动时它跟第一帧抢 CPU，而这件事一点都不急。
        // 依旧是裸线程而不是协程：AlbumPublish.onStart 要在进程没有任何界面时也把暂存位恢复完。
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            com.aris.emojichan.sender.AlbumPublish.onStart(this)
        }, "album-check").start()
    }
}

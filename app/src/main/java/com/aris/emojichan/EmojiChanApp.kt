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
        Thread({ com.aris.emojichan.sender.AlbumPublish.onStart(this) }, "album-check").start()
    }
}

package com.aris.emojichan

import android.app.Application

/**
 * 两件事：① 在第一个界面出现之前把用户选的深浅色交给 AppCompat —— 放在 Application
 * 而不是 Activity 里，是因为这时候主题还没被任何窗口定下来，晚一步第一帧就会闪一下浅色；
 * ② 顺手清掉上次被杀进程时没删掉的相册临时图。
 */
class EmojiChanApp : Application() {
    override fun onCreate() {
        super.onCreate()
        UiPrefs.applyNightMode(this)
        // 相册临时图平时由 AlbumPublish.removeLast() 负责删；进程被杀时它没机会跑，
        // 这里在启动时补一刀（要读系统相册数据库，丢到后台线程）。
        Thread({ com.aris.emojichan.sender.AlbumPublish.cleanStale(this) }, "album-cleanup").start()
    }
}

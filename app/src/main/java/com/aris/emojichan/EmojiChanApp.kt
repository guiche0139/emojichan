package com.aris.emojichan

import android.app.Application
import android.content.ComponentCallbacks2

/**
 * 三件事：① 在第一个界面出现之前把用户选的深浅色交给 AppCompat —— 放在 Application
 * 而不是 Activity 里，是因为这时候主题还没被任何窗口定下来，晚一步第一帧就会闪一下浅色；
 * ② 顺手看一眼相册里那个固定槽位（上次写到一半的要放出来，旧版本留下的临时图数一数）；
 * ③ 系统内存吃紧时把缩略图的内存缓存放掉（v0.2.221）。
 *
 * 第 ③ 条为什么放在这里：悬浮球和无障碍服务把进程一直留在后台，而 Glide 的内存缓存
 * 默认要等到 TRIM_MEMORY_MODERATE 才自己放。我们这个进程的「后台」是常态，
 * 缓存占着的那几 MB 就是低内存杀手挑上门的理由 —— 进程被杀掉时球直接消失，
 * 比丢几张缩略图严重得多。
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

    /**
     * 内存分级回调（v0.2.221）。只在这几级动手：
     *
     * - TRIM_MEMORY_UI_HIDDEN：所有界面都不在了 —— 我们最想要的那个时刻；
     * - TRIM_MEMORY_BACKGROUND / TRIM_MEMORY_MODERATE / TRIM_MEMORY_COMPLETE：已经全在后台，
     *   越往后越接近被回收；
     * - onLowMemory：系统已经没得退了，直接放。
     *
     * 不动 TRIM_MEMORY_RUNNING_LOW 那几级：那时界面还在，清完下一次滚动就要重新解码，
     * 换来的是更密的磁盘读；而且到了那种压力，Glide 自己的 ComponentCallbacks2 已经在放缓存了。
     *
     * 必须调 super：Application 会把这次回调继续分发给 registerComponentCallbacks 注册的对象
     * （Glide 就是其中之一），吞掉它等于把库自己的回收也一起挡了。
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        when (level) {
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN,
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
            ComponentCallbacks2.TRIM_MEMORY_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> clearImageCache(level)
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        clearImageCache(-1)
    }

    /**
     * 放掉 Glide 的内存缓存。清内存必须在主线程（见 CacheCleaner 里那段线程说明）；
     * onTrimMemory / onLowMemory 本来就在主线程，这里只是兜一下不常见的调用方。
     */
    private fun clearImageCache(level: Int) {
        val app = this
        val clear = Runnable {
            runCatching { com.bumptech.glide.Glide.get(app).clearMemory() }
            // 压力真正上来时才留一行（UI_HIDDEN / BACKGROUND 太常见，记了只会把日志刷满）。
            if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE || level < 0) {
                com.aris.emojichan.sender.SendLog.d(
                    "服务",
                    "低内存 lv=" + level + " 已释放缩略图内存缓存"
                )
            }
        }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) clear.run()
        else android.os.Handler(android.os.Looper.getMainLooper()).post(clear)
    }
}

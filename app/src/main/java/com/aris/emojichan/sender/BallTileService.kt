package com.aris.emojichan.sender

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.aris.emojichan.MainActivity
import com.aris.emojichan.R

/**
 * 下拉状态栏里的「表情悬浮球」快捷开关。
 *
 * 为什么是磁贴而不是常驻通知：用户明确否决了常驻通知，而磁贴是唯一「在状态栏里点一下就能
 * 开关悬浮球、又不占通知栏」的形态。代价是要用户自己在状态栏编辑面板里加一次，
 * 设置页那一行负责引导（Android 13+ 能直接弹系统的添加确认框）。
 *
 * 点一下要做的事：球在跑就收起来，没跑就挂上。挂的时候有两道坎：
 * ① 没有「显示在其他应用上层」权限 → 开主界面去授权（授权回来主界面会接着开球）；
 * ② Android 8 起后台应用不能随便 startService → 真被系统拒了就退化成开主界面，在那里开。
 */
class BallTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        SendLog.init(applicationContext)
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (FloatingBallService.isRunning) {
            SendLog.d("磁贴", "点了一下：收起悬浮球")
            FloatingBallService.stop(this)
        } else {
            SendLog.d("磁贴", "点了一下：开启悬浮球")
            startBall()
        }
        updateTile()
    }

    private fun startBall() {
        if (!Settings.canDrawOverlays(this)) {
            SendLog.w("磁贴", "没有「显示在其他应用上层」权限，开界面去授权")
            openApp(startBall = false)
            return
        }
        try {
            startService(Intent(this, FloatingBallService::class.java))
            SendLog.d("磁贴", "已让悬浮球服务启动")
        } catch (e: Exception) {
            // 后台起服务被拒（各 ROM 判断不一）：退回主界面，那里是前台，起服务一定合法。
            SendLog.w("磁贴", "后台起服务被拒（" + e.javaClass.simpleName + "），改开主界面")
            openApp(startBall = true)
        }
    }

    private fun openApp(startBall: Boolean) {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(EXTRA_FROM_TILE, true)
            putExtra(EXTRA_START_BALL, startBall)
        }
        runCatching { startActivityAndCollapse(intent) }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val on = FloatingBallService.isRunning
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(if (on) R.string.tile_subtitle_on else R.string.tile_subtitle_off)
        }
        runCatching { tile.updateTile() }
    }

    companion object {
        /** 磁贴点开后丢给主界面的两个标记：从磁贴来的、要不要顺手把球开起来。 */
        const val EXTRA_FROM_TILE = "extra_from_tile"
        const val EXTRA_START_BALL = "extra_start_ball"

        /** 球的开关状态变了之后叫一声，让磁贴的长相跟上。 */
        fun requestRefresh(context: Context) {
            runCatching {
                TileService.requestListeningState(
                    context,
                    ComponentName(context, BallTileService::class.java)
                )
            }
        }
    }
}

package com.aris.emojichan

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.aris.emojichan.sender.AutoSendService
import com.aris.emojichan.sender.SendLog
import com.aris.emojichan.util.ImageUtil
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 使用引导页（v0.1.414，用户 m09215）。
 *
 * 第一次正常启动时自动出现一次（由 MainActivity 起）。四步：先讲清这个应用做什么、
 * 不做什么，再分三步把可选权限的入口摆出来 —— 悬浮球要的「显示在其他应用上层」、
 * 自动发送服务（无障碍）、相册读权限。三样都不给也能用，所以每一步都写明「不给会怎样」，
 * 并且只把人送到系统设置页门口，不替用户点任何东西。
 *
 * 「跳过」要过一道二次确认：跳过等于「以后不再自动出现」，是个决定而不是取消，
 * 用户得知道这一点，也得知道之后去哪儿再看（「设置 → 高级设置 → 使用引导」）。
 * 第 1 步按返回键同理，只是那种退出不记「看过」，下次启动还会出现。
 */
class OnboardingActivity : AppCompatActivity() {

    private companion object {
        const val STEP_COUNT = 4
        const val STEP_INTRO = 0
        const val STEP_OVERLAY = 1
        const val STEP_A11Y = 2
        const val STEP_MEDIA = 3
        const val TAG = "引导"
    }

    private lateinit var stepView: TextView
    private lateinit var titleView: TextView
    private lateinit var blockIntro: View
    private lateinit var blockOverlay: View
    private lateinit var blockA11y: View
    private lateinit var blockMedia: View
    private lateinit var overlayStatus: TextView
    private lateinit var a11yStatus: TextView
    private lateinit var mediaStatus: TextView
    private lateinit var mediaAction: MaterialButton
    private lateinit var backButton: MaterialButton
    private lateinit var nextButton: MaterialButton

    private var step = STEP_INTRO

    /** 相册权限的结果决定第 4 步那行状态，回调里刷一次就够。 */
    private val mediaPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UiPrefs.applyTheme(this)
        setContentView(R.layout.activity_onboarding)
        SendLog.init(this)
        SendLog.d(TAG, "打开使用引导")

        stepView = findViewById(R.id.onboardingStep)
        titleView = findViewById(R.id.onboardingTitle)
        blockIntro = findViewById(R.id.onboardingIntro)
        blockOverlay = findViewById(R.id.onboardingBlockOverlay)
        blockA11y = findViewById(R.id.onboardingBlockA11y)
        blockMedia = findViewById(R.id.onboardingBlockMedia)
        overlayStatus = findViewById(R.id.onboardingOverlayStatus)
        a11yStatus = findViewById(R.id.onboardingA11yStatus)
        mediaStatus = findViewById(R.id.onboardingMediaStatus)
        mediaAction = findViewById(R.id.onboardingMediaAction)
        backButton = findViewById(R.id.onboardingBack)
        nextButton = findViewById(R.id.onboardingNext)

        findViewById<MaterialToolbar>(R.id.onboardingToolbar)
            .setNavigationOnClickListener { goBack() }
        findViewById<View>(R.id.onboardingSkip).setOnClickListener { confirmSkip() }
        findViewById<MaterialButton>(R.id.onboardingOverlayAction)
            .setOnClickListener { openOverlaySettings() }
        findViewById<MaterialButton>(R.id.onboardingA11yAction)
            .setOnClickListener { openA11ySettings() }
        mediaAction.setOnClickListener { requestMediaPermission() }

        backButton.setOnClickListener { previous() }
        nextButton.setOnClickListener {
            if (step < STEP_COUNT - 1) {
                step += 1
                render()
            } else {
                finishGuide()
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goBack()
        })

        render()
    }

    override fun onResume() {
        super.onResume()
        // 刚从系统设置回来：权限给没给，只有在这里问才准。
        refreshStatus()
    }

    // ---------------- 步骤 ----------------

    private fun render() {
        stepView.text = getString(R.string.onboarding_step_format, step + 1, STEP_COUNT)
        titleView.setText(
            when (step) {
                STEP_OVERLAY -> R.string.onboarding_overlay_title
                STEP_A11Y -> R.string.onboarding_a11y_title
                STEP_MEDIA -> R.string.onboarding_media_title
                else -> R.string.onboarding_intro_title
            }
        )
        blockIntro.visibility = if (step == STEP_INTRO) View.VISIBLE else View.GONE
        blockOverlay.visibility = if (step == STEP_OVERLAY) View.VISIBLE else View.GONE
        blockA11y.visibility = if (step == STEP_A11Y) View.VISIBLE else View.GONE
        blockMedia.visibility = if (step == STEP_MEDIA) View.VISIBLE else View.GONE
        // 「上一步」用 invisible 占着位：按钮一显一隐，底部那行会跟着跳。
        backButton.visibility = if (step == STEP_INTRO) View.INVISIBLE else View.VISIBLE
        nextButton.setText(
            if (step == STEP_COUNT - 1) R.string.onboarding_done else R.string.onboarding_next
        )
        refreshStatus()
    }

    private fun previous() {
        if (step <= STEP_INTRO) return
        step -= 1
        render()
    }

    private fun goBack() {
        if (step > STEP_INTRO) previous() else confirmExit()
    }

    private fun finishGuide() {
        OnboardingPrefs.markDone(this)
        SendLog.d(TAG, "引导走完了")
        finish()
    }

    // ---------------- 三个权限的现状 ----------------

    private fun refreshStatus() {
        markStatus(
            overlayStatus,
            Settings.canDrawOverlays(this),
            R.string.onboarding_overlay_status_on,
            R.string.onboarding_overlay_status_off
        )
        markStatus(
            a11yStatus,
            AutoSendService.isConnected,
            R.string.onboarding_a11y_status_on,
            R.string.onboarding_a11y_status_off
        )

        // 相册读权限是三档（全部 / 仅选中的照片 / 无，见 ImageUtil.mediaAccessLevel）：
        // 「仅选中的照片」也算给了一部分，画成警示色而不是红色。
        val level = ImageUtil.mediaAccessLevel(this)
        val textRes: Int
        val colorRes: Int
        when (level) {
            "全部" -> {
                textRes = R.string.onboarding_media_status_all
                colorRes = R.color.status_on
            }
            "部分" -> {
                textRes = R.string.onboarding_media_status_partial
                colorRes = R.color.warning
            }
            else -> {
                textRes = R.string.onboarding_media_status_off
                colorRes = R.color.status_off
            }
        }
        mediaStatus.setText(textRes)
        mediaStatus.setTextColor(ContextCompat.getColor(this, colorRes))
        // 已经给过就不再弹一次系统框（系统自己也会秒拒），按钮留着当状态说明。
        mediaAction.isEnabled = level == "无"
    }

    private fun markStatus(view: TextView, ok: Boolean, onRes: Int, offRes: Int) {
        view.setText(if (ok) onRes else offRes)
        view.setTextColor(
            ContextCompat.getColor(this, if (ok) R.color.status_on else R.color.status_off)
        )
    }

    // ---------------- 三步的入口 ----------------

    /**
     * 「显示在其他应用上层」只能由用户在系统设置里打开，应用自己开不了，代码里
     * 只能把人送过去。带上 package 是为了让系统直接落在本应用那一行上
     * （跟设置页的悬浮球开关走同一条路，见 MainActivity.setOverlayEnabled）。
     */
    private fun openOverlaySettings() {
        SendLog.d(TAG, "打开「显示在其他应用上层」设置")
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:" + packageName)
        )
        runCatching { startActivity(intent) }.onFailure {
            Toast.makeText(this, R.string.overlay_permission_settings_unavailable, Toast.LENGTH_LONG)
                .show()
        }
    }

    /** 无障碍服务的开关在系统设置里，这里只负责把人送到那一页。 */
    private fun openA11ySettings() {
        SendLog.d(TAG, "打开无障碍设置")
        runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }.onFailure {
            Toast.makeText(this, R.string.sender_status_settings_unavailable, Toast.LENGTH_LONG)
                .show()
        }
    }

    /**
     * 相册读权限：13+ 是 READ_MEDIA_IMAGES，14+ 连「仅选择的照片」一起要。
     * 跟导入那边的申请逻辑保持一致（MainActivity.requestMediaPermission）。
     */
    private fun requestMediaPermission() {
        val wanted = when {
            Build.VERSION.SDK_INT >= 34 -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            )
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        mediaPermissions.launch(wanted)
    }

    // ---------------- 跳过 / 退出 ----------------

    /** 「跳过」为什么还要问一次：跳过等于「以后不再自动出现」，得让用户知道这一点。 */
    private fun confirmSkip() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.onboarding_skip_title)
            .setMessage(R.string.onboarding_skip_message)
            .setPositiveButton(R.string.onboarding_skip_confirm) { _, _ ->
                OnboardingPrefs.markDone(this)
                SendLog.d(TAG, "跳过引导")
                finish()
            }
            .setNegativeButton(R.string.onboarding_skip_continue, null)
            .show()
    }

    /** 第 1 步按返回键：这种退出不算「看过了」，下次启动还会出现，得说清楚。 */
    private fun confirmExit() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.onboarding_exit_title)
            .setMessage(R.string.onboarding_exit_message)
            .setPositiveButton(R.string.onboarding_exit_confirm) { _, _ -> finish() }
            .setNegativeButton(R.string.onboarding_skip_continue, null)
            .show()
    }
}

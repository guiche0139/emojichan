package com.aris.emojichan

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import androidx.appcompat.app.AppCompatDelegate
import java.io.File

/**
 * 「UI 设置」里用户能自己调的那几项：主题色、深浅色、悬浮球的长相和大小。
 *
 * 单独一份 SharedPreferences（不跟发送模块的混在一起），而且读取只依赖 Context，
 * 所以 Application、每个 Activity、悬浮球 Service 都能直接问它要值：
 * 主题色在界面出来之前先套 overlay，深浅色在 Application.onCreate 里定下来，
 * 悬浮球的样式和大小在挂球那一刻读。
 */
object UiPrefs {

    private const val PREFS = "ui_settings"
    private const val KEY_THEME = "theme_color"
    private const val KEY_NIGHT = "night_mode"
    private const val KEY_BALL_STYLE = "ball_style"
    private const val KEY_BALL_SIZE = "ball_size_dp"

    // ---------------- 主题色 ----------------

    /** 默认主题色：应用图标里的天空蓝，排在选择列表第一位。 */
    const val THEME_BLUE = "blue"

    /** 应用图标里的樱花粉。 */
    const val THEME_PINK = "pink"

    /** 顺序就是设置页单选框的顺序；加主题色时这里和 themes.xml 一起加。 */
    val THEMES = listOf(THEME_BLUE, THEME_PINK)

    /**
     * 老版本存过 navy / purple / green / orange 四种色，那些调色板已经删了，
     * 存的值不再出现在 [THEMES] 里 —— 一律回落到默认的天空蓝，不然套不上 overlay。
     */
    fun themeKey(context: Context): String {
        val saved = prefs(context).getString(KEY_THEME, THEME_BLUE) ?: THEME_BLUE
        return if (saved in THEMES) saved else THEME_BLUE
    }

    fun setThemeKey(context: Context, key: String) {
        prefs(context).edit().putString(KEY_THEME, key).apply()
    }

    private fun overlayOf(key: String): Int = when (key) {
        THEME_PINK -> R.style.ThemeOverlay_EmojiChan_Pink
        else -> R.style.ThemeOverlay_EmojiChan_Blue
    }

    /** 必须在 setContentView 之前调用，否则已经 inflate 出来的 View 拿的还是上一次的颜色。 */
    fun applyTheme(activity: Activity) {
        activity.setTheme(R.style.Theme_EmojiChan)
        activity.theme.applyStyle(overlayOf(themeKey(activity)), true)
    }

    /**
     * 悬浮球和面板不归 Activity 管，得自己造一个带主题的 Context 才拿得到用户选的色。
     *
     * 深浅色也得自己动手：AppCompatDelegate 的深浅色覆盖只作用于 AppCompatActivity，
     * Service 拿到的仍是系统那一份配置，所以「应用里选了深色、系统是浅色」时
     * values-night 那套资源压根不会生效 —— 悬浮球不随深浅色变就是这个原因。
     * 这里按用户的选择把 uiMode 掰一下，再套主题。
     */
    fun themedContext(base: Context): Context {
        val config = Configuration(base.resources.configuration)
        val mask = Configuration.UI_MODE_NIGHT_MASK
        val night =
            if (isNight(base)) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        config.uiMode = (config.uiMode and mask.inv()) or night
        val wrapper = ContextThemeWrapper(base.createConfigurationContext(config), R.style.Theme_EmojiChan)
        wrapper.theme.applyStyle(overlayOf(themeKey(base)), true)
        return wrapper
    }

    // ---------------- 深浅色 ----------------

    const val NIGHT_FOLLOW = 0
    const val NIGHT_LIGHT = 1
    const val NIGHT_DARK = 2

    fun nightChoice(context: Context): Int = prefs(context).getInt(KEY_NIGHT, NIGHT_FOLLOW)

    /**
     * 当前实际要显示的深浅色：把用户的选择和系统设置合成一个结果。
     * 悬浮球那边拿不到 AppCompat 的覆盖，换深浅色后靠它判断「球该不该重画」。
     */
    fun isNight(context: Context): Boolean = when (nightChoice(context)) {
        NIGHT_LIGHT -> false
        NIGHT_DARK -> true
        else -> (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }

    private fun nightModeOf(choice: Int): Int = when (choice) {
        NIGHT_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
        NIGHT_DARK -> AppCompatDelegate.MODE_NIGHT_YES
        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }

    /** Application.onCreate 里先定下来，免得第一帧用错深浅色。 */
    fun applyNightMode(context: Context) {
        AppCompatDelegate.setDefaultNightMode(nightModeOf(nightChoice(context)))
    }

    /** 改完立刻生效：AppCompat 会把已经开着的界面按新配置重建一遍。 */
    fun setNightChoice(context: Context, choice: Int) {
        prefs(context).edit().putInt(KEY_NIGHT, choice).apply()
        AppCompatDelegate.setDefaultNightMode(nightModeOf(choice))
    }

    // ---------------- 悬浮球 ----------------

    /** 默认：扁平猫脸（应用图标那张，drawable/ic_cat_face.xml 的几何 + ic_ball_flat.xml 的取景），设置页写作「默认」。 */
    const val BALL_DEFAULT = "default"

    /** 游戏开发部：像素猫（drawable-nodpi/game.png），设置页写作「游戏开发部」。0.1.401 之前它一直是默认那一版。 */
    const val BALL_CLASSIC = "classic"

    const val BALL_DOT = "dot"
    const val BALL_CUSTOM = "custom"

    fun ballStyle(context: Context): String =
        prefs(context).getString(KEY_BALL_STYLE, BALL_DEFAULT) ?: BALL_DEFAULT

    fun setBallStyle(context: Context, style: String) {
        prefs(context).edit().putString(KEY_BALL_STYLE, style).apply()
    }

    /** 用户自己挑的球图：复制进私有目录，不看相册权限的脸色，也不怕原图被删。 */
    fun ballFile(context: Context): File = File(context.filesDir, "ball/ball.png")

    /** 悬浮球直径（dp）。原来写死 56dp，用户反馈太大，所以给 28~64 的滑杆。 */
    const val BALL_SIZE_MIN_DP = 28
    const val BALL_SIZE_MAX_DP = 64
    const val BALL_SIZE_DEFAULT_DP = 44

    fun ballSizeDp(context: Context): Int =
        prefs(context).getInt(KEY_BALL_SIZE, BALL_SIZE_DEFAULT_DP)
            .coerceIn(BALL_SIZE_MIN_DP, BALL_SIZE_MAX_DP)

    fun setBallSizeDp(context: Context, dp: Int) {
        prefs(context).edit()
            .putInt(KEY_BALL_SIZE, dp.coerceIn(BALL_SIZE_MIN_DP, BALL_SIZE_MAX_DP))
            .apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

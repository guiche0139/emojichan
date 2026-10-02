package com.aris.emojichan

import android.content.Context

/**
 * 「导入进来的原文件怎么办」这一条偏好，外加压缩包的落点目录。
 *
 * 为什么单开一份偏好、不塞进 [UiPrefs]：它管的不是界面长相，而是**用户相册 / 文件管理器里
 * 的那些文件**——删错了没法撤销，值得单独一处存，也方便以后审计。
 */
object ImportPrefs {

    private const val PREFS = "import_settings"
    private const val KEY_POLICY = "source_policy"
    private const val KEY_ZIP_TREE = "zip_tree_uri"

    /** 保留：导入只是复制一份进来，源文件一个不动（默认，也是唯一不碰用户文件的一项）。 */
    const val POLICY_KEEP = 0

    /** 压缩：先把这次导入选中的源文件收进一个 zip 存到用户指定的目录，再从原处删掉。 */
    const val POLICY_ZIP = 1

    /** 删除：导入成功后直接删掉源文件（不可恢复）。 */
    const val POLICY_DELETE = 2

    fun policy(context: Context): Int = prefs(context).getInt(KEY_POLICY, POLICY_KEEP)

    fun setPolicy(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_POLICY, value).apply()
    }

    /** 用户挑的「zip 存到哪个目录」——SAF 的 tree Uri 字符串；没挑过就是 null。 */
    fun zipTreeUri(context: Context): String? = prefs(context).getString(KEY_ZIP_TREE, null)

    fun setZipTreeUri(context: Context, uri: String?) {
        prefs(context).edit().putString(KEY_ZIP_TREE, uri).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

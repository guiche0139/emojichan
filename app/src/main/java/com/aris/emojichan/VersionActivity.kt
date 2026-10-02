package com.aris.emojichan

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 版本页：上面是应用自己（图标 + 名字 + 版本号），下面是 GitHub 入口。
 *
 * 「检查更新」只做一件事：把人送到 GitHub 发布页。应用至今没有 INTERNET 权限 ——
 * 联网是浏览器干的活，系统只查浏览器自己的权限，跟我们无关，所以「应用信息」里的
 * 权限清单一个网络权限都没有。代价是「线上是哪个版本」应用答不出来，只能把当前
 * 版本号摆出来让人自己比。
 *
 * 主题：跟主界面同一套（overlay 要在 setContentView 之前套）。
 */
class VersionActivity : AppCompatActivity() {

    private val appVersion: String
        get() = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull() ?: "?"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UiPrefs.applyTheme(this)
        setContentView(R.layout.activity_version)

        findViewById<MaterialToolbar>(R.id.versionToolbar)
            .setNavigationOnClickListener { finish() }

        findViewById<TextView>(R.id.versionValue).text =
            getString(R.string.settings_version_sub, appVersion)

        findViewById<View>(R.id.rowGithub).setOnClickListener { showUpdateDialog() }
    }

    private fun showUpdateDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_update_title)
            .setMessage(getString(R.string.settings_update_message, appVersion))
            .setPositiveButton(R.string.settings_update_open) { _, _ -> openReleasePage() }
            .setNeutralButton(R.string.settings_update_copy) { _, _ -> copyReleaseLink() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * 地址是 /releases 而不是 /releases/latest：latest 的语义是「最近的正式发布」，
     * 会跳过 prerelease，而本仓库的包全是 prerelease —— 实测那个地址返回 404。
     * 列表页任何发布形态都看得到。
     */
    private fun openReleasePage() {
        val url = getString(R.string.settings_update_url)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        // <queries> 里声明过 VIEW + https，这里 resolveActivity 才查得到浏览器。
        if (intent.resolveActivity(packageManager) == null) {
            Toast.makeText(this, getString(R.string.settings_update_no_browser, url), Toast.LENGTH_LONG).show()
            return
        }
        runCatching { startActivity(intent) }.onFailure {
            Toast.makeText(this, getString(R.string.settings_update_no_browser, url), Toast.LENGTH_LONG).show()
        }
    }

    /** 不想跳浏览器的人，把链接放进剪贴板也能拿到。 */
    private fun copyReleaseLink() {
        val url = getString(R.string.settings_update_url)
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("emojichan-releases", url))
        Toast.makeText(this, R.string.settings_update_copied, Toast.LENGTH_SHORT).show()
    }
}

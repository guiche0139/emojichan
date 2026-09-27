package com.aris.emojichan.sender

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.aris.emojichan.R
import com.aris.emojichan.data.EmojiEntity
import java.io.File

/**
 * 把一个表情图片通过系统分享（ACTION_SEND）交给微信。
 *
 * 微信的分享链路是「选完联系人即发送」，不需要再点发送按钮 —— 这正是本方案
 * 依赖的机制：我们只负责把图片交出去，发给谁由 [AutoSendService] 在微信自己的
 * 「选择聊天」页里点，点不到就留给用户手点。
 */
object EmojiShare {

    private const val WECHAT_PACKAGE = "com.tencent.mm"

    /** 微信接收单张图片分享的界面（实测这台手机上就是这个）。会随版本变化，所以只是候选之一。 */
    private const val WECHAT_SHARE_ACTIVITY = "com.tencent.mm.ui.tools.ShareImgUI"

    /** 老版本微信用过这个名字，留着当第二候选（实测 8.x 已经找不到它了）。 */
    private const val WECHAT_SHARE_ACTIVITY_OLD = "com.tencent.mm.ui.tools.ShareImgToWXActivity"

    private const val QQ_PACKAGE = "com.tencent.mobileqq"

    /** QQ 接收分享的界面（现成的分享入口）。同样只是候选之一。 */
    private const val QQ_SHARE_ACTIVITY = "com.tencent.mobileqq.activity.JumpActivity"

    sealed class Result {
        /** 已把目标应用直接拉起来，接下来是它的「选择聊天 / 选择好友」页。 */
        object SentToApp : Result()

        /** 目标应用没能直接拉起，退回到了系统分享面板；[directError] 是每一次失败的原因。 */
        data class ShowedChooser(val directError: String?) : Result()

        /** 文件已不存在（记录还在，图片被删了）。 */
        object FileMissing : Result()

        /** 连系统分享面板都拉不起来。 */
        object NoTarget : Result()
    }

    /**
     * @param appPackage 球是从哪个应用里点开的（微信 / QQ）—— 分享就发给它，
     *   否则在 QQ 里点表情会莫名其妙跳到微信的选人页。
     *   null 表示前台是哪个应用都没读出来：这时不去猜（猜错的后果是把图塞进微信的选人页，
     *   而用户当时可能根本不在微信里），直接用系统分享面板（emc-1-026）。
     */
    fun send(context: Context, emoji: EmojiEntity, appPackage: String?): Result {
        val file = File(emoji.filePath)
        if (!file.exists()) {
            SendLog.d("分享", "图片文件不在了：" + emoji.filePath)
            return Result.FileMissing
        }
        SendLog.d("分享", "开始分享：" + file.name + "，fileType=" + emoji.fileType)

        val uri = try {
            FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        } catch (e: Exception) {
            e.printStackTrace()
            SendLog.d("分享", "取 FileProvider uri 失败：" + describe(e))
            return Result.NoTarget
        }

        // GIF 先按精确类型发（微信据此决定当动图还是静图）；万一对不上，
        // 再用最宽泛的 image/* 试一遍 —— 接收方本来也是按文件内容识别的。
        val types = if (emoji.fileType.equals("gif", ignoreCase = true)) {
            listOf("image/gif", "image/*")
        } else {
            listOf("image/*")
        }

        // 从 Service 启动 Activity 必须带 FLAG_ACTIVITY_NEW_TASK；
        // 同时本应用持有 SYSTEM_ALERT_WINDOW 且悬浮球可见，不受 Android 10 起
        // 「后台启动 Activity」的限制。
        val errors = mutableListOf<String>()

        if (appPackage != null) {
            for (type in types) {
                for (target in shareTargets(context, appPackage)) {
                    val intent = buildSendIntent(uri, type).apply {
                        if (target != null) component = target else setPackage(appPackage)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    val label = target?.flattenToShortString() ?: ("只给包名 " + appPackage)
                    try {
                        context.startActivity(intent)
                        SendLog.d("分享", "拉起成功：" + label + "（type=" + type + "）")
                        return Result.SentToApp
                    } catch (e: Exception) {
                        val reason = describe(e)
                        SendLog.d("分享", "拉起失败：" + label + " type=" + type + " ⇒ " + reason)
                        if (reason !in errors) errors += reason
                    }
                }
            }
        } else {
            SendLog.d("分享", "不知道前台是哪个应用，直接走系统分享面板")
        }

        // 微信没装 / 被系统拦下 —— 退回系统分享面板，把每次尝试的原因一并带回去。
        val chooser = Intent.createChooser(
            buildSendIntent(uri, types.last()),
            context.getString(R.string.overlay_chooser_title)
        ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        SendLog.d("分享", "直达没成，退系统分享面板。之前的失败：" + errors.joinToString(" ｜ "))
        if (start(context, chooser)) {
            val detail = errors.joinToString(" ｜ ").take(220)
            return Result.ShowedChooser(if (detail.isEmpty()) null else detail)
        }

        SendLog.d("分享", "系统分享面板也拉不起来")
        return Result.NoTarget
    }

    /**
     * 把表情图片放进系统剪贴板（ClipData 的 URI 形式），供微信输入框的「粘贴」使用。
     *
     * 返回 false 表示连剪贴板都没写进去，调用方应直接走分享路线。
     */
    fun copyToClipboard(context: Context, emoji: EmojiEntity): Boolean {
        val file = File(emoji.filePath)
        if (!file.exists()) {
            SendLog.d("剪贴板", "图片文件不在了：" + emoji.filePath)
            return false
        }
        val uri = try {
            FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        } catch (e: Exception) {
            SendLog.d("剪贴板", "取 FileProvider uri 失败：" + describe(e))
            return false
        }
        return try {
            // 没有实现图片剪贴板的 App 会用 coerceToText() 读第 0 项，URI 就会被当成文字
            // 粘进输入框（聊天框里留下一串 content://）。所以这一项要显式给空文本 ——
            // ClipData.Item 的 text 是只读的，只能在构造时就写成 empty，不能事后改。
            val type = context.contentResolver.getType(uri) ?: "image/*"
            val clip = ClipData("emoji", arrayOf(type), ClipData.Item("", null as String?, null as Intent?, uri))
            // 上一份剪贴板已经不在系统里了，它换来的读权限先收回，再给这一份授权（emc-1-029）。
            releaseClipboardGrants(context)
            // 粘贴方是微信 / QQ，把读权限明确授出去，别指望系统自动给。
            for (pkg in CLIP_TARGETS) {
                runCatching {
                    context.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            grantedUri = uri
            val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            manager.setPrimaryClip(clip)
            val desc = clip.description
            val mime = if (desc.mimeTypeCount > 0) desc.getMimeType(0) else "未知"
            SendLog.d("剪贴板", "已放入 " + file.name + "（type=" + mime + "）")
            // 取证：写进去之后马上把三处现场记下来（文件字节 / 系统剪贴板 / 接收方能取到什么），
            // 粘贴失败时一眼能看出断在哪一环。要读整个文件，所以丢到后台线程。
            ClipForensics.reportAsync(context, file, uri)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            SendLog.d("剪贴板", "写剪贴板失败：" + describe(e))
            false
        }
    }

    /** 剪贴板要给谁授权：装了的微信 / QQ。 */
    private val CLIP_TARGETS = listOf(WECHAT_PACKAGE, QQ_PACKAGE)

    /** 上一次放进剪贴板时授出去的那条 uri；粘贴结束或再次复制时要收回来（emc-1-029）。 */
    @Volatile
    private var grantedUri: Uri? = null

    /**
     * 撤回 [copyToClipboard] 授出去的读权限。
     *
     * 以前只授不收：这条 uri 的读权限会一直挂到应用进程结束，等于把一张表情图长期对外开放。
     * 粘贴流程走完（成功或失败）后由调用方调一次；下一次复制也会顺带收回上一份。
     */
    fun releaseClipboardGrants(context: Context) {
        val uri = grantedUri ?: return
        grantedUri = null
        for (pkg in CLIP_TARGETS) {
            runCatching {
                context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        SendLog.d("剪贴板", "已收回上一次的临时读权限")
    }

    /**
     * 尝试启动微信的三条路，越具体的越先试：
     * 1. 写死的分享界面组件名 —— 显式 Intent 不受 Android 11 包可见性的影响；
     * 2. 问系统要一个真实的分享接收组件（要在清单里声明 queries 节点才查得到）；
     * 3. 只指定包名，交给系统自己解析。
     */
    private fun shareTargets(context: Context, appPackage: String): List<ComponentName?> {
        val targets = mutableListOf<ComponentName?>()
        knownShareActivity(appPackage)?.let { targets += ComponentName(appPackage, it) }
        if (appPackage == WECHAT_PACKAGE) targets += ComponentName(appPackage, WECHAT_SHARE_ACTIVITY_OLD)
        resolveShare(context, appPackage)?.let { if (it !in targets) targets += it }
        targets += null
        return targets
    }

    /** 已知的分享入口组件名；认不出来的应用就只靠系统解析。 */
    private fun knownShareActivity(appPackage: String): String? = when (appPackage) {
        WECHAT_PACKAGE -> WECHAT_SHARE_ACTIVITY
        QQ_PACKAGE -> QQ_SHARE_ACTIVITY
        else -> null
    }

    @Suppress("DEPRECATION")
    private fun resolveShare(context: Context, appPackage: String): ComponentName? {
        val probe = Intent(Intent.ACTION_SEND).apply {
            type = "image/*"
            setPackage(appPackage)
        }
        val info = context.packageManager.queryIntentActivities(probe, 0)
            .firstOrNull()?.activityInfo ?: return null
        return ComponentName(info.packageName, info.name)
    }

    private fun buildSendIntent(uri: Uri, type: String): Intent =
        Intent(Intent.ACTION_SEND).apply {
            this.type = type
            putExtra(Intent.EXTRA_STREAM, uri)
            // 只授予这一个 content:// 的临时读权限，接收方拿不到别的文件。
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }

    /** 异常压成一行：类名 + 首行消息，够用来判断是被哪条限制拦的。 */
    private fun describe(e: Exception): String {
        val message = e.message?.substringBefore('\n')?.trim()?.take(100)
        return if (message.isNullOrEmpty()) e.javaClass.simpleName
        else e.javaClass.simpleName + ": " + message
    }

    /**
     * 失败时该给用户看的那句话；成功路径返回 null（界面已经跳走了，不必打扰）。
     *
     * 这里只负责给文案、不负责显示：发送时本应用已经退到后台，系统 Toast 很容易
     * 被 ROM 一并拦掉（用户实测：弹了系统分享面板，却什么提示都没有），
     * 所以真正的显示交给持有悬浮窗的 [FloatingBallService]。
     */
    fun failureMessage(context: Context, result: Result): String? = when (result) {
        Result.FileMissing -> context.getString(R.string.overlay_file_missing)
        Result.NoTarget -> context.getString(R.string.overlay_send_failed)
        is Result.ShowedChooser -> context.getString(
            R.string.overlay_direct_failed,
            result.directError ?: context.getString(R.string.msg_unknown_error)
        )
        Result.SentToApp -> null
    }
}

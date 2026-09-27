package com.aris.emojichan.sender

import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import com.aris.emojichan.R

/**
 * 会记账的 FileProvider。
 *
 * 图片一旦以 content:// 交出去（剪贴板或分享），「谁、什么时候、按什么类型、取走了多少字节」
 * 就只有提供者自己看得见。剪贴板粘贴出问题时，这份流水能把下面几种情况分开：
 *   - 完全没人来取 → 微信不认图片剪贴板这条路；
 *   - 有人来但只问了类型 / 只查了文件名 → 它在探测，没有真读；
 *   - 按 image/gif 取走了完整文件 → 字节给对了，静图是微信自己只画第一帧；
 *   - 按 image/png 之类的类型来取、被 FileProvider 拒掉 → 提供者这一环可以修。
 *
 * 记的东西只落在本机的发送日志里，不出设备。
 *
 * 注意：只覆盖 [openAssetFile] / [openTypedAssetFile]，不覆盖 [openFile] ——
 * FileProvider 的 openFile 内部就是调 openAssetFile，两边都记会重复两遍。
 */
class LoggingFileProvider : FileProvider(R.xml.file_paths) {

    override fun onCreate(): Boolean {
        context?.let { SendLog.init(it) }
        return super.onCreate()
    }

    /** 系统/接收方问「这个 uri 是什么类型」。类型正是从文件扩展名推出来的。 */
    override fun getType(uri: Uri): String? {
        val type = super.getType(uri)
        SendLog.d(TAG, caller() + " 问类型 " + short(uri) + " → " + type)
        return type
    }

    /** 接收方查文件名 / 大小（OpenableColumns），多半是在准备展示或估算。 */
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val cursor: Cursor = super.query(uri, projection, selection, selectionArgs, sortOrder)
        val info = runCatching {
            val nameIndex = cursor.getColumnIndex("_display_name")
            val sizeIndex = cursor.getColumnIndex("_size")
            val text = if (cursor.moveToFirst()) {
                "name=" + (if (nameIndex >= 0) cursor.getString(nameIndex) else "-") +
                        " size=" + (if (sizeIndex >= 0) cursor.getLong(sizeIndex) else -1L)
            } else {
                "空结果"
            }
            cursor.moveToPosition(-1)
            text
        }.getOrElse { "读失败：" + describe(it) }
        SendLog.d(
            TAG, caller() + " 查文件信息 " + short(uri) +
                    " projection=" + (projection?.joinToString(",") ?: "-") + " → " + info
        )
        return cursor
    }

    /** 不带类型的普通取法（接收方直接要一个文件描述符）。 */
    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? = try {
        val afd = super.openAssetFile(uri, mode)
        SendLog.d(
            TAG, caller() + " 直接取图 " + short(uri) + " mode=" + mode + " → 成功，" +
                    "fd 大小 " + runCatching { afd?.parcelFileDescriptor?.statSize }.getOrNull()
        )
        afd
    } catch (e: Exception) {
        SendLog.d(TAG, caller() + " 直接取图 " + short(uri) + " mode=" + mode + " → 失败：" + describe(e))
        throw e
    }

    /** 带类型过滤的取法：这一行最能说明接收方到底想要什么格式。 */
    override fun openTypedAssetFile(
        uri: Uri,
        mimeTypeFilter: String,
        opts: Bundle?
    ): AssetFileDescriptor? = try {
        // 这里原来写的是 super.openTypedAssetFile(...)!! —— 父类返回 null 时
        // 会被外层 catch 记成「被拒」，再抛出一个 KotlinNullPointerException
        // （日志与真实原因都对不上）。null 是正常返回，分开处理（emc-1-032）。
        val afd = super.openTypedAssetFile(uri, mimeTypeFilter, opts)
        if (afd == null) {
            SendLog.d(
                TAG, caller() + " 按类型取图 " + short(uri) + " filter=" + mimeTypeFilter +
                        " → 没有内容（父类返回 null）"
            )
            null
        } else {
            SendLog.d(
                TAG, caller() + " 按类型取图 " + short(uri) + " filter=" + mimeTypeFilter +
                        " → 成功，长度 " + afd.length + " 字节（声明 " + afd.declaredLength + "），" +
                        "fd 真实大小 " + runCatching { afd.parcelFileDescriptor.statSize }.getOrDefault(-1L)
            )
            afd
        }
    } catch (e: Exception) {
        SendLog.d(
            TAG, caller() + " 按类型取图 " + short(uri) + " filter=" + mimeTypeFilter +
                    " → 被拒：" + describe(e)
        )
        throw e
    }

    /** 谁在取：优先用系统记下的调用包名，拿不到就退回 uid。 */
    private fun caller(): String {
        val pkg = runCatching { callingPackage }.getOrNull()
        if (!pkg.isNullOrEmpty()) return "「" + pkg + "」"
        val uid = runCatching { Binder.getCallingUid() }.getOrDefault(-1)
        val names = runCatching { context?.packageManager?.getPackagesForUid(uid) }.getOrNull()
        return "「" + (names?.joinToString("/") ?: "uid " + uid) + "」"
    }

    private fun short(uri: Uri): String = uri.lastPathSegment ?: uri.toString()

    private fun describe(t: Throwable): String {
        val message = t.message?.substringBefore('\n')?.trim()?.take(90)
        return if (message.isNullOrEmpty()) t.javaClass.simpleName
        else t.javaClass.simpleName + ": " + message
    }

    private companion object {
        const val TAG = "取图"
    }
}

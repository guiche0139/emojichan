package com.aris.emojichan.sender

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File

/**
 * 相册路线（微信「＋ → 相册 → 第一格 → 发送」）的供货部分：把当前这张表情放进系统相册。
 *
 * v0.1.317 起改成「独占目录 + 固定槽位覆盖 + 写完自检」：
 *  · 图只放在 [ALBUM_DIR] 这一个目录里，系统相册里会多出一个同名分组；
 *  · 复用同一条相册记录（文件名固定 Emojichan.<后缀>），每次发送只覆盖内容：不 insert 新行、
 *    也不 delete。相册里不会越攒越多，更不会弹系统的删除确认框；代价是这条记录会长期留在相册里，
 *    用户想清掉得自己在相册里删。
 *  · 写完立刻自检（[verify]）：按「加入时间」和「拍摄时间」两种倒序排出来的第一张都得是我们这张。
 *    自检不过由调用方退回分享路线 —— 宁可让用户多点一下，也不能让他把别人的图发出去。
 *
 * v0.2.001 起补上「槽位归属」这一层（用户 m09674 实测：v0.2.000 上相册路线整条走不通）。
 *  系统对相册行的写权限是按「这一行是谁建的」发的。应用重装之后（v0.1.400 换正式签名那次要求
 *  卸载重装），以前建的那条 Emojichan.jpg 不再算我们的，update 会抛：
 *     标成写入中失败：RecoverableSecurityException：com.aris.emojichan has no access to
 *     content://media/external/images/media/1000531136
 *  这条异常本身是「要用户点一下同意」的信号，但我们不该在发送中途弹系统框，而且这条旧行同样
 *  删不掉。所以现在的做法是：把候选槽位从前往后试一遍（先试记住的那条），哪条写得进去就用哪条；
 *  全都写不进去就自己 insert 一条新的（insert 出来的行一定归我们），并把 uri 记进
 *  SharedPreferences，下次优先用它。旧的那条留着不动 —— 写不进也删不掉，用户想清自己在相册里删。
 */
object AlbumPublish {

    /** 独占目录：系统相册里会出现同名分组。 */
    const val ALBUM_DIR = "Pictures/Emojichan"

    /** 固定槽位的文件名（不带扩展名），后缀跟着实际图片类型走。 */
    private const val SLOT_NAME = "Emojichan"

    /** v0.1.316 及以前用的目录与文件名前缀（每发一次插一条、发完删）。现在只数一数，一模一样不动。 */
    private const val LEGACY_DIR = "Pictures/EmojiChan"
    private const val LEGACY_PREFIX = "emojichan_"

    /** 记住自己写得进去的那条槽位（v0.2.001）。目录里可能躺着一条改不动的旧槽位，不能每次都去猜。 */
    private const val PREFS = "album_publish"
    private const val KEY_SLOT = "slot_uri"

    /** 一次最多试几条候选，防止目录里积了一堆旧行时反复空转。 */
    private const val MAX_CANDIDATES = 4

    /** 自检结论：[PASS] 能确认第一张是我们；[FAIL] 能确认不是；[UNKNOWN] 说不准（多半是没权限）。 */
    enum class Verdict { PASS, FAIL, UNKNOWN }

    /** [detail] 是给人看的一句话，进日志；别塞进提示条，太长。 */
    data class Check(val verdict: Verdict, val detail: String)

    /** 相册里的一行，只用于自检。 */
    private data class Row(val id: Long, val name: String, val stamp: Long) {
        fun describe(slotId: Long): String = if (id == slotId) "我们的槽位" else "别人的图 " + name
    }

    /** 一条候选槽位。[owner] 只用来写日志（API 29+ 才有这一列，读不到就是 null）。 */
    private data class Slot(val uri: Uri, val name: String, val owner: String?)

    @Volatile
    private var slotUri: Uri? = null

    /** 最近一次写入的槽位，供日志/诊断看。 */
    fun lastSlot(): Uri? = slotUri

    /**
     * 把 [sourcePath] 写进槽位，返回槽位 uri；出错返回 null（调用方退回别的路线）。
     * 「记住的那条 → 目录里的其它旧槽位 → 新插一条」依次试，整个过程不删任何东西。
     */
    fun publish(context: Context, sourcePath: String, mime: String): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            SendLog.w("相册", "系统低于 Android 10，没有相册路线可用")
            return null
        }
        val source = File(sourcePath)
        if (!source.exists()) {
            SendLog.d("相册", "图片文件不在了：" + sourcePath)
            return null
        }
        val actualMime = resolveMime(mime, source.name)
        val displayName = SLOT_NAME + extensionOf(actualMime)
        return runCatching {
            val candidates = candidates(context)
            candidates.forEachIndexed { index, slot ->
                val written = tryWrite(context, slot.uri, source, actualMime, displayName)
                if (written != null && written > 0L) {
                    slotUri = slot.uri
                    remember(context, slot.uri)
                    SendLog.d(
                        "相册",
                        (if (index == 0) "复用槽位 " else "用第 " + (index + 1) + " 条候选槽位 ") +
                            displayName + " → " + slot.uri
                    )
                    SendLog.d("相册", "槽位已覆盖：" + written + " 字节，" + actualMime)
                    return@runCatching slot.uri
                }
                SendLog.d(
                    "相册",
                    "第 " + (index + 1) + " 条候选槽位写不进去（归属=" + (slot.owner ?: "无主") + "）：" + slot.uri
                )
            }
            // 一条都改不动：自己插一条新的。insert 出来的行一定归我们，之后就一直用它。
            val fresh = createSlot(context, displayName, actualMime)
            if (fresh == null) {
                SendLog.e("相册", "建槽位失败：insert 返回 null")
                return@runCatching null
            }
            SendLog.d(
                "相册",
                "候选槽位都写不进去（" + candidates.size + " 条），新建槽位 " + displayName + " → " + fresh
            )
            val written = tryWrite(context, fresh, source, actualMime, displayName)
            if (written == null || written <= 0L) {
                // 写坏了就先藏起来（IS_PENDING=1），不删：删除会弹系统确认，而且下次发送还能接着用。
                hide(context, fresh)
                SendLog.d("相册", "新槽位也没写进去，先藏起来等下次覆盖")
                return@runCatching null
            }
            slotUri = fresh
            remember(context, fresh)
            SendLog.d("相册", "新槽位已写入：" + written + " 字节，" + actualMime)
            fresh
        }.getOrElse { e ->
            SendLog.e("相册", "放进相册出错：" + e.javaClass.simpleName + "：" + (e.message?.take(80) ?: ""))
            null
        }
    }

    /**
     * 自检：相册里「第一张」是不是我们刚写进去的这张。
     *
     * 两种排序都要过 —— 微信可能按拍摄时间排，系统图片选择器可能按加入时间排，我们没法确定用哪个。
     * 没有相册读权限时系统只让我们看见自己贡献的行，「第一张」是假的，所以那种情况一律 [Verdict.UNKNOWN]。
     * 「目录里只有一张」不再必须：重装过的机器上可能躺着一条改不掉的旧槽位（v0.2.001），
     * 只要两种排序的第一张都是我们，微信那一格就点得到我们这张。
     */
    fun verify(context: Context, uri: Uri): Check {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return Check(Verdict.UNKNOWN, "系统低于 Android 10")
        }
        val slotId = uri.lastPathSegment?.toLongOrNull()
            ?: return Check(Verdict.UNKNOWN, "槽位 id 读不出来")
        val inDir = countInDir(context)
        val dirTop = newestInDir(context)
        val byAdded = newest(context, MediaStore.Images.Media.DATE_ADDED)
        val byTaken = newest(context, MediaStore.Images.Media.DATE_TAKEN)
        val read = readPermission(context)
        val shape = "目录内 " + inDir + " 张（组内第一张=" + (dirTop?.describe(slotId) ?: "查不到") + "）" +
            "；按加入时间第一张=" + (byAdded?.describe(slotId) ?: "查不到") +
            "；按拍摄时间第一张=" + (byTaken?.describe(slotId) ?: "查不到")
        if (!read) {
            return Check(
                Verdict.UNKNOWN,
                "没给「读取相册」权限，只能看到自己贡献的行，第一张是谁看不出来（" + shape + "）"
            )
        }
        val addedOk = byAdded?.id == slotId
        val takenOk = byTaken?.id == slotId
        val dirOk = dirTop == null || dirTop.id == slotId
        val verdict = if (addedOk && takenOk && dirOk) Verdict.PASS else Verdict.FAIL
        return Check(verdict, shape)
    }

    /**
     * 启动时跑一遍（后台线程，不碰 UI）：把上次写到一半、还标着 IS_PENDING 的槽位放出来，
     * 顺便数一数旧版本留下的临时图。**都不删东西** —— 旧图留在这里，用户想清自己在相册里清。
     */
    fun onStart(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        runCatching {
            for (slot in candidates(context)) {
                val stuck = pendingSize(context, slot.uri)
                if (stuck > 0L) {
                    val rows = update(
                        context, slot.uri,
                        ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                        "恢复上次没写完的槽位"
                    )
                    SendLog.d("相册", "上次写到一半的槽位已放出来（" + stuck + " 字节，改 " + rows + " 行）")
                }
            }
            val legacy = countLegacy(context)
            if (legacy > 0) {
                SendLog.d(
                    "相册",
                    "旧版本在 " + LEGACY_DIR + " 里留下 " + legacy + " 张临时图：现在不删了，要清请在相册里自己删"
                )
            }
        }.onFailure { e ->
            SendLog.w("相册", "启动自检出错：" + e.javaClass.simpleName + "：" + (e.message?.take(60) ?: ""))
        }
    }

    /** 把 [mime] 落到具体类型上（调用方常传「图片」大类通配类型，而相册记录需要真类型才有对的后缀）。 */
    fun resolveMime(mime: String, fileName: String): String {
        val lower = fileName.lowercase()
        return when {
            mime == "image/gif" || lower.endsWith(".gif") -> "image/gif"
            mime == "image/jpeg" || mime == "image/jpg" || lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            mime == "image/webp" || lower.endsWith(".webp") -> "image/webp"
            mime == "image/png" || lower.endsWith(".png") -> "image/png"
            mime.startsWith("image/") && mime != "image/*" -> mime
            else -> "image/png"
        }
    }

    private fun extensionOf(mime: String): String = when (mime) {
        "image/gif" -> ".gif"
        "image/jpeg" -> ".jpg"
        "image/webp" -> ".webp"
        else -> ".png"
    }

    // ---------- 下面都是和系统相册数据库打交道的小工具，出错一律只记日志 ----------

    /**
     * 候选槽位，按优先级排：先是我们记住的那条，再是这个目录里所有叫 Emojichan.<后缀> 的行。
     * 目录里可能躺着一条改不动的旧槽位，所以不能只取第一条。
     */
    private fun candidates(context: Context): List<Slot> {
        val out = ArrayList<Slot>()
        val seen = HashSet<String>()
        remembered(context)?.let {
            out += Slot(it, "记住的槽位", null)
            seen += it.toString()
        }
        for (slot in slotsInDir(context)) {
            if (out.size >= MAX_CANDIDATES) break
            if (seen.add(slot.uri.toString())) out += slot
        }
        if (out.isEmpty()) SendLog.d("相册", "目录 " + ALBUM_DIR + " 里还没有槽位，这就要建一条")
        return out
    }

    private fun remembered(context: Context): Uri? {
        val raw = runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SLOT, null)
        }.getOrNull() ?: return null
        return runCatching { Uri.parse(raw) }.getOrNull()
    }

    private fun remember(context: Context, uri: Uri) {
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_SLOT, uri.toString()).apply()
        }
    }

    /**
     * 把内容写进 [uri]。写得进去返回字节数；**写不进去返回 null**（不属于我们 / 文件没了 / 流打不开 / 元数据改不动），
     * 由调用方决定换下一条候选还是新插一条。失败原因都进日志。
     */
    private fun tryWrite(
        context: Context,
        uri: Uri,
        source: File,
        mime: String,
        displayName: String
    ): Long? {
        // 先标成「写入中」：写一半的时候微信那边读不到半张图；这一步同时也是在问系统「这行归不归我们」。
        if (updateStrict(
                context, uri,
                ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 1) },
                "标成写入中"
            ) == null
        ) {
            return null
        }
        val written = try {
            val out = context.contentResolver.openOutputStream(uri, "wt")
            if (out == null) {
                SendLog.e("相册", "拿不到输出流（这条记录可能已经不是我们的了）")
                return null
            }
            out.use { target ->
                source.inputStream().use { input -> input.copyTo(target) }
            }
        } catch (e: Exception) {
            SendLog.e("相册", "写内容失败：" + e.javaClass.simpleName + "：" + (e.message?.take(60) ?: ""))
            return null
        }
        // 时间列：DATE_TAKEN 是毫秒、DATE_ADDED 是秒。改得动才可能在「时间倒序」里回到最前。
        val now = System.currentTimeMillis()
        val rows = updateStrict(
            context, uri,
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                put(MediaStore.Images.Media.DATE_TAKEN, now)
                put(MediaStore.Images.Media.DATE_ADDED, now / 1000L)
                put(MediaStore.Images.Media.IS_PENDING, 0)
            },
            "刷新名字/时间列"
        )
        SendLog.d("相册", "写完刷新元数据：改到 " + (rows ?: -1) + " 行（DATE_TAKEN=" + now + "）")
        if (rows == null) return null
        return written
    }

    private fun hide(context: Context, uri: Uri) {
        update(
            context, uri,
            ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 1) },
            "藏起没写成的槽位"
        )
    }

    /** 目录里所有叫 Emojichan.<后缀> 的行（新的在前）；出错返回空表。 */
    private fun slotsInDir(context: Context): List<Slot> = runCatching {
        // owner_package_name 是 API 29 才有的列，这里写字面量：只用来写日志，读不到也没关系。
        val projection = arrayListOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            "owner_package_name"
        )
        val list = ArrayList<Slot>()
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection.toTypedArray(),
            MediaStore.Images.Media.RELATIVE_PATH + " = ? AND " +
                MediaStore.Images.Media.DISPLAY_NAME + " LIKE ?",
            arrayOf(ALBUM_DIR + "/", SLOT_NAME + ".%"),
            MediaStore.Images.Media._ID + " DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndex(MediaStore.Images.Media._ID)
            val nameCol = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
            val ownerCol = cursor.getColumnIndex("owner_package_name")
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val name = if (nameCol >= 0) cursor.getString(nameCol) ?: "?" else "?"
                val owner = if (ownerCol >= 0 && !cursor.isNull(ownerCol)) cursor.getString(ownerCol) else null
                list += Slot(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id), name, owner)
            }
        }
        list
    }.onFailure { e ->
        SendLog.e("相册", "查槽位失败：" + e.javaClass.simpleName + "：" + (e.message?.take(60) ?: ""))
    }.getOrDefault(emptyList())

    private fun createSlot(context: Context, displayName: String, mime: String): Uri? = runCatching {
        context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                put(MediaStore.Images.Media.RELATIVE_PATH, ALBUM_DIR)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        )
    }.onFailure { e ->
        SendLog.e("相册", "建槽位出错：" + e.javaClass.simpleName + "：" + (e.message?.take(60) ?: ""))
    }.getOrNull()

    /** 独占目录里有几张图（没读权限时只数得到我们自己贡献的）。 */
    private fun countInDir(context: Context): Int = count(
        context,
        MediaStore.Images.Media.RELATIVE_PATH + " = ?",
        arrayOf(ALBUM_DIR + "/")
    )

    private fun countLegacy(context: Context): Int = count(
        context,
        MediaStore.Images.Media.RELATIVE_PATH + " = ? AND " +
            MediaStore.Images.Media.DISPLAY_NAME + " LIKE ?",
        arrayOf(LEGACY_DIR + "/", LEGACY_PREFIX + "%")
    )

    private fun count(context: Context, selection: String, args: Array<String>): Int = runCatching {
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID),
            selection,
            args,
            null
        )?.use { it.count } ?: 0
    }.getOrDefault(0)

    /** 按 [column] 倒序排在第一的那一行（整个库）。 */
    private fun newest(context: Context, column: String): Row? = newestWhere(context, null, null, column)

    /** 独占目录里按加入时间排第一的那一行。 */
    private fun newestInDir(context: Context): Row? = newestWhere(
        context,
        MediaStore.Images.Media.RELATIVE_PATH + " = ?",
        arrayOf(ALBUM_DIR + "/"),
        MediaStore.Images.Media.DATE_ADDED
    )

    private fun newestWhere(context: Context, selection: String?, args: Array<String>?, column: String): Row? = runCatching {
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                column
            ),
            selection,
            args,
            column + " DESC"
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                Row(
                    cursor.getLong(0),
                    cursor.getString(1) ?: "?",
                    if (cursor.isNull(2)) 0L else cursor.getLong(2)
                )
            } else {
                null
            }
        }
    }.onFailure { e ->
        SendLog.w("相册", "自检查询失败（" + column + "）：" + e.javaClass.simpleName)
    }.getOrNull()

    /** 这条槽位记录还标着 IS_PENDING 的话，返回它的大小；没标或查不到返回 -1。 */
    private fun pendingSize(context: Context, uri: Uri): Long = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.Images.Media.IS_PENDING, MediaStore.Images.Media.SIZE),
            null,
            null,
            null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) {
                -1L
            } else {
                val pending = if (cursor.isNull(0)) 0L else cursor.getLong(0)
                val size = if (cursor.isNull(1)) 0L else cursor.getLong(1)
                if (pending == 1L) size else -1L
            }
        } ?: -1L
    }.getOrDefault(-1L)

    /** 有没有「能看见整个相册」的读权限。只给了「仅选择的照片」也算没有 —— 那种授权看不全第一张是谁。 */
    private fun readPermission(context: Context): Boolean {
        val full = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return context.checkSelfPermission(full) == PackageManager.PERMISSION_GRANTED
    }

    /** 改得动返回改了几行；**抛异常（比如归属不在我们这儿）返回 null**。 */
    private fun updateStrict(context: Context, uri: Uri, values: ContentValues, what: String): Int? = runCatching {
        context.contentResolver.update(uri, values, null, null)
    }.onFailure { e ->
        SendLog.e("相册", what + "失败：" + e.javaClass.simpleName + "：" + (e.message?.take(80) ?: ""))
    }.getOrNull()

    private fun update(context: Context, uri: Uri, values: ContentValues, what: String): Int =
        updateStrict(context, uri, values, what) ?: 0
}

package com.aris.emojichan.util

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowContentResolver

/**
 * 导入命名的判据：什么时候该信上游给的显示名，什么时候该认出它是选择器漏出来的 _id。
 *
 * 回归的是 emc-2-008：老判据「纯数字 ≥5 位就当 id 丢掉」会把**真名本身就是数字**的图
 * （微信/QQ 存下来的 1727512345678.jpg）一起丢掉，用户看到的是「图片 09-28 14:05」。
 */
@RunWith(RobolectricTestRunner::class)
class ImageNamingTest {

    private lateinit var context: Context
    private lateinit var provider: FakeNameProvider

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        provider = FakeNameProvider()
        ShadowContentResolver.registerProviderInternal(AUTHORITY, provider)
    }

    /** 选择器答不上来、把 _id 当显示名返回：这个名不能当名字用。 */
    @Test
    fun pickerIdIsNotUsedAsAName() {
        provider.names["1000000033"] = "1000000033"
        assertNull(ImageUtil.queryDisplayName(context, uri(pickerUri("1000000033"))))
    }

    /** 选择器带着扩展名把 _id 当显示名返回（1000000033.jpg）：同样是泄漏，不能当名字。 */
    @Test
    fun pickerIdWithExtensionIsNotUsedAsAName() {
        provider.names["1000000033"] = "1000000033.jpg"
        assertNull(ImageUtil.queryDisplayName(context, uri(pickerUri("1000000033"))))
    }

    /**
     * 新版选择器给的是 `picker_get_content` 前缀（ACTION_GET_CONTENT 被重定向到选择器）：
     * 这种形状同样要把 id 挡掉 —— v0.1.311 只认 `picker` 前缀，换算整段被跳过。
     */
    @Test
    fun pickerGetContentIdIsNotUsedAsAName() {
        provider.names["1000530993"] = "1000530993.gif"
        assertNull(ImageUtil.queryDisplayName(context, uri(getContentUri("1000530993"))))
    }

    /** 同一个选择器 Uri，但只要显示名不是那个 id（哪怕也是一串数字），就得原样用。 */
    @Test
    fun numericRealNameFromPickerIsKept() {
        provider.names["1000000033"] = "1727512345678.jpg"
        assertEquals(
            "1727512345678.jpg",
            ImageUtil.queryDisplayName(context, uri(pickerUri("1000000033")))
        )
    }

    /** 普通文件名当然照用。 */
    @Test
    fun plainNameIsKept() {
        provider.names["42"] = "猫猫.png"
        assertEquals("猫猫.png", ImageUtil.queryDisplayName(context, uri(pickerUri("42"))))
    }

    /** 上游完全不给名字时返回 null，让调用方给可读的兜底名。 */
    @Test
    fun missingNameReturnsNull() {
        assertNull(ImageUtil.queryDisplayName(context, uri(pickerUri("999"))))
    }

    private fun uri(suffix: String): Uri = Uri.parse("content://" + AUTHORITY + "/" + suffix)

    private fun pickerUri(id: String): String =
        "picker/0/com.android.providers.media.photopicker/media/" + id

    private fun getContentUri(id: String): String =
        "picker_get_content/0/com.android.providers.media.photopicker/media/" + id

    /** 只认 DISPLAY_NAME 一列，其余列（含 DATA）当作查不到。 */
    private class FakeNameProvider : ContentProvider() {
        val names = HashMap<String, String>()

        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?
        ): Cursor? {
            val column = projection?.firstOrNull() ?: OpenableColumns.DISPLAY_NAME
            val cursor = MatrixCursor(arrayOf(column))
            val value = if (column == OpenableColumns.DISPLAY_NAME) {
                names[uri.lastPathSegment]
            } else {
                null
            }
            if (value != null) cursor.addRow(arrayOf(value))
            return cursor
        }

        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?
        ): Int = 0
    }

    private companion object {
        const val AUTHORITY = "test.names"
    }
}

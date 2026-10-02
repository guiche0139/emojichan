package com.aris.emojichan.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aris.emojichan.R
import com.aris.emojichan.data.EmojiEntity
import com.aris.emojichan.data.EmojiFilter
import com.aris.emojichan.data.EmojiRepository
import com.aris.emojichan.data.ImageFeatureEntity
import com.aris.emojichan.data.TagEntity
import com.aris.emojichan.util.EmojiArchive
import com.aris.emojichan.util.FolderImporter
import com.aris.emojichan.util.ImageUtil
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
class EmojiViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = EmojiRepository(application)

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    /**
     * 只看收藏。
     *
     * 收藏不是数据库里的一行标签，而是表情自身的 `isFavorite` 列 —— 星标一摘，收藏
     * 里就该没有它，两者永远是同一件事。界面上它和标签并排显示，是筛选条件之一。
     */
    private val _favoritesOnly = MutableStateFlow(false)
    val favoritesOnly: StateFlow<Boolean> = _favoritesOnly.asStateFlow()

    /** 过滤区里勾中的标签。 */
    private val _selectedTagIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedTagIds: StateFlow<Set<Long>> = _selectedTagIds.asStateFlow()

    /** true = 同时满足所有标签（AND），false = 任一满足（OR）。 */
    private val _tagMatchAll = MutableStateFlow(true)
    val tagMatchAll: StateFlow<Boolean> = _tagMatchAll.asStateFlow()

    private val _isSelectionMode = MutableStateFlow(false)
    val isSelectionMode: StateFlow<Boolean> = _isSelectionMode.asStateFlow()

    private val _selectedIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedIds: StateFlow<Set<Long>> = _selectedIds.asStateFlow()

    /**
     * 列表数据：收藏 / 搜索条件 / 标签 的组合过滤。
     *
     * 搜索框那句话由 [EmojiFilter.parse] 解析成条件表达式（`&` 同时满足、`/` 任一满足），
     * 这里只管攒条件，翻译成 SQL 是 [EmojiQuery] 的事 —— 只有一条查询路径
     * （[EmojiRepository.observeFiltered]），不按条件分支挑不同的 DAO 方法。
     */
    val emojis: StateFlow<List<EmojiEntity>> = combine(
        _searchQuery,
        _selectedTagIds,
        _tagMatchAll,
        _favoritesOnly
    ) { query, tagIds, matchAll, favoritesOnly ->
        EmojiFilter(
            favoritesOnly = favoritesOnly,
            expr = EmojiFilter.parse(query),
            tagIds = tagIds.toList(),
            tagMatchAll = matchAll
        )
    }.flatMapLatest { filter -> repository.observeFiltered(filter) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 全部标签，过滤区与标签管理都用它。 */
    val tags: StateFlow<List<TagEntity>> = repository.observeTags()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val emojiCount: StateFlow<Int> = repository.getCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val _message = MutableStateFlow<String?>(null)

    /** 一次性提示消息（写操作失败时使用），UI 消费后需调用 [consumeMessage] 清除。 */
    val message: StateFlow<String?> = _message.asStateFlow()

    fun consumeMessage() {
        _message.value = null
    }

    /** 取字符串资源；ViewModel 里所有用户可见文案统一走这里，便于集中维护与多语言。 */
    private fun str(resId: Int, vararg args: Any): String =
        getApplication<Application>().getString(resId, *args)

    /** 兜底异常处理器：避免协程内未捕获的异常导致进程崩溃，同时把原因暴露出来。 */
    private val handler = CoroutineExceptionHandler { _, e ->
        e.printStackTrace()
        _message.value = str(
            R.string.msg_operation_failed,
            e.message ?: str(R.string.msg_unknown_error)
        )
    }

    init {
        // 启动时清理孤儿文件（导入中断、记录被外部删除留下的残留），静默执行不打扰用户
        viewModelScope.launch(handler) {
            val validPaths = repository.getAllFilePaths().toSet()
            withContext(Dispatchers.IO) {
                ImageUtil.cleanOrphanFiles(getApplication(), validPaths)
            }
        }
    }

    /**
     * 统一收口数据库写操作：捕获异常、检查影响行数。
     * @param failureMessage 影响行数为 0（记录已不存在）时展示的提示文案。
     */
    private fun launchWrite(failureMessage: String, block: suspend () -> Int) {
        viewModelScope.launch(handler) {
            try {
                val rows = block()
                if (rows == 0) _message.value = failureMessage
            } catch (e: Exception) {
                e.printStackTrace()
                _message.value = failureMessage
            }
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    // ---------- 筛选 ----------

    fun setFavoritesOnly(only: Boolean) {
        _favoritesOnly.value = only
    }

    fun toggleFavoritesOnly() {
        _favoritesOnly.value = !_favoritesOnly.value
    }

    fun toggleTagFilter(tagId: Long) {
        val current = _selectedTagIds.value.toMutableSet()
        if (!current.add(tagId)) current.remove(tagId)
        _selectedTagIds.value = current
    }

    fun clearTagFilter() {
        _selectedTagIds.value = emptySet()
    }

    fun setTagMatchAll(matchAll: Boolean) {
        _tagMatchAll.value = matchAll
    }

    fun toggleSelectionMode() {
        _isSelectionMode.value = !_isSelectionMode.value
        if (!_isSelectionMode.value) {
            _selectedIds.value = emptySet()
        }
    }

    fun toggleSelection(id: Long) {
        val current = _selectedIds.value.toMutableSet()
        if (current.contains(id)) {
            current.remove(id)
        } else {
            current.add(id)
        }
        _selectedIds.value = current
    }

    fun selectAll() {
        val allIds = emojis.value.map { it.id }.toSet()
        _selectedIds.value = allIds
    }

    fun deselectAll() {
        _selectedIds.value = emptySet()
    }

    // ---------- 导入 ----------

    /**
     * 导入一张表情，并等它真正落库（名字去重与自动标签都在数据层的事务里完成）。
     * @return 新记录的 id，失败返回 null。
     */
    suspend fun importEmoji(emoji: EmojiEntity): Long? = withContext(Dispatchers.IO) {
        try {
            repository.insert(emoji)
        } catch (e: Exception) {
            e.printStackTrace()
            _message.value =
                str(R.string.msg_operation_failed, e.message ?: str(R.string.msg_unknown_error))
            null
        }
    }

    /**
     * 批量导入（一次选多张图）。
     * @param tagId 非空时给这一批表情全部挂上该标签（文件夹导入用）。
     * @return 成功入库的条数。
     */
    suspend fun insertAll(emojis: List<EmojiEntity>, tagId: Long? = null): Int =
        withContext(Dispatchers.IO) {
            try {
                repository.insertAll(emojis, listOfNotNull(tagId)).count { it > 0 }
            } catch (e: Exception) {
                e.printStackTrace()
                _message.value = str(
                    R.string.msg_operation_failed,
                    e.message ?: str(R.string.msg_unknown_error)
                )
                0
            }
        }

    /**
     * 文件夹导入：递归读 [treeUri] 下的所有图片，**每层文件夹各建一个以自己名字命名的标签**
     * （重名按加 (1)(2)… 处理），图片同时挂上从最外层到它所在这一层的全部标签。
     *
     * 整个过程在 IO 线程上跑，读完再一次性入库；标签只在第一次遇到时创建，同一个文件夹
     * 里的图片共用同一个标签 id。
     *
     * @return 成功入库的条数；无法读取该文件夹返回 -1。
     */
    suspend fun importFolder(treeUri: Uri): Int = withContext(Dispatchers.IO) {
        try {
            val batches = FolderImporter.collect(getApplication(), treeUri) ?: return@withContext -1
            val tagIds = mutableMapOf<String, Long>()
            var imported = 0
            batches.forEach { batch ->
                val ids = batch.tagNames.mapNotNull { name ->
                    tagIds.getOrPut(name) { repository.createUniqueTag(name) ?: -1L }
                        .takeIf { it > 0 }
                }
                if (batch.emojis.isNotEmpty()) {
                    imported += repository.insertAll(batch.emojis, ids).count { it > 0 }
                }
            }
            imported
        } catch (e: Exception) {
            e.printStackTrace()
            _message.value =
                str(R.string.msg_operation_failed, e.message ?: str(R.string.msg_unknown_error))
            0
        }
    }

    /**
     * 删除一组表情：**先删文件、确认文件已消失，再删数据库记录**。
     *
     * 顺序不能反。若先删记录再删文件，文件删除失败（被占用、权限等）时记录已经没了，
     * 文件会永久残留在私有目录且无从追溯；反过来最坏情况只是记录保留、下次还能重试。
     *
     * @return 文件删除失败、因而保留了记录的条目数。
     */
    private suspend fun deleteWithFiles(ids: List<Long>): Int {
        if (ids.isEmpty()) return 0
        val emojis = repository.getByIds(ids)
        val deletableIds = mutableListOf<Long>()
        var failedCount = 0
        withContext(Dispatchers.IO) {
            emojis.forEach { emoji ->
                if (ImageUtil.deleteFile(emoji.filePath)) {
                    deletableIds.add(emoji.id)
                } else {
                    failedCount++
                }
            }
        }
        if (deletableIds.isNotEmpty()) repository.deleteByIds(deletableIds)
        return failedCount
    }

    fun deleteSelected() {
        viewModelScope.launch(handler) {
            try {
                val failed = deleteWithFiles(_selectedIds.value.toList())
                if (failed > 0) {
                    _message.value = str(R.string.msg_delete_partial_failed, failed)
                }
                _selectedIds.value = emptySet()
                _isSelectionMode.value = false
            } catch (e: Exception) {
                e.printStackTrace()
                _message.value = str(
                    R.string.msg_delete_failed,
                    e.message ?: str(R.string.msg_unknown_error)
                )
            }
        }
    }

    /** 详情页数据源：以数据库为唯一真值，避免在页面间搬运残缺实体。 */
    fun observeEmoji(id: Long): Flow<EmojiEntity?> = repository.observeById(id)

    /** 精准更新收藏状态：只写一列，不会覆盖 usageCount 等其它字段。 */
    fun updateFavorite(id: Long, isFavorite: Boolean) {
        viewModelScope.launch(handler) {
            try {
                if (repository.updateFavorite(id, isFavorite) == 0) {
                    _message.value = str(R.string.msg_record_missing)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _message.value = str(
                    R.string.msg_operation_failed,
                    e.message ?: str(R.string.msg_unknown_error)
                )
            }
        }
    }

    /**
     * 删除单个表情（详情页使用），与批量删除走同一套「先文件后记录」的流程。
     * 删除成功后记录消失，详情页订阅的 Flow 会发出 null 并自动关闭页面。
     */
    fun deleteEmoji(id: Long) {
        viewModelScope.launch(handler) {
            try {
                if (deleteWithFiles(listOf(id)) > 0) {
                    _message.value = str(R.string.msg_file_delete_failed)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _message.value = str(
                    R.string.msg_delete_failed,
                    e.message ?: str(R.string.msg_unknown_error)
                )
            }
        }
    }

    /** 重命名；失败或影响行数为 0 时通过 [message] 反馈，UI 会回灌数据库真值。 */
    fun renameEmoji(id: Long, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) {
            _message.value = str(R.string.msg_name_empty)
            return
        }
        launchWrite(str(R.string.msg_rename_failed_missing)) {
            repository.rename(id, trimmed)
        }
    }

    /** 统一收口「返回一句提示」的异步操作；返回 null 表示无需提示。 */
    private fun launchAction(block: suspend () -> String?) {
        viewModelScope.launch(handler) {
            try {
                block()?.let { _message.value = it }
            } catch (e: Exception) {
                e.printStackTrace()
                _message.value = str(
                    R.string.msg_operation_failed,
                    e.message ?: str(R.string.msg_unknown_error)
                )
            }
        }
    }

    // ---------- 批量整理（选择模式） ----------

    /** 给选中的表情批量打上已有标签。 */
    fun addTagsToSelected(tagIds: List<Long>) = launchAction {
        val ids = _selectedIds.value.toList()
        if (ids.isEmpty()) return@launchAction str(R.string.msg_nothing_selected)
        if (tagIds.isEmpty()) return@launchAction str(R.string.msg_tag_none_selected)
        repository.addTags(ids, tagIds)
        str(R.string.msg_tags_added, ids.size)
    }

    /** 给选中的表情批量打上一个新标签（不存在就建）。 */
    fun addNewTagToSelected(name: String) = launchAction {
        val ids = _selectedIds.value.toList()
        if (ids.isEmpty()) return@launchAction str(R.string.msg_nothing_selected)
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return@launchAction str(R.string.tag_name_empty)
        val tagId = repository.ensureTag(trimmed)
            ?: return@launchAction str(R.string.msg_tag_create_failed, trimmed)
        repository.addTags(ids, listOf(tagId))
        str(R.string.msg_tags_added, ids.size)
    }

    /** 从选中的表情上批量摘掉标签。 */
    fun removeTagsFromSelected(tagIds: List<Long>) = launchAction {
        val ids = _selectedIds.value.toList()
        if (ids.isEmpty()) return@launchAction str(R.string.msg_nothing_selected)
        if (tagIds.isEmpty()) return@launchAction str(R.string.msg_tag_none_selected)
        repository.removeTags(ids, tagIds)
        str(R.string.msg_tags_removed, ids.size)
    }

    // ---------- 标签管理 ----------

    fun addTag(name: String) = launchAction {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return@launchAction str(R.string.tag_name_empty)
        if (repository.insertTag(trimmed)) {
            str(R.string.msg_tag_created, trimmed)
        } else {
            str(R.string.msg_tag_exists, trimmed)
        }
    }

    /** 重命名标签；新名字与别的标签撞车时拒绝，避免两个同名标签让人无法区分。 */
    fun renameTag(tagId: Long, oldName: String, newName: String) = launchAction {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return@launchAction str(R.string.tag_name_empty)
        if (trimmed == oldName) return@launchAction null
        if (repository.renameTag(tagId, trimmed)) {
            str(R.string.msg_tag_renamed, oldName, trimmed)
        } else {
            str(R.string.msg_tag_exists, trimmed)
        }
    }

    /** 删除标签：它下面所有表情的挂载一起摘掉，表情本身不受影响。 */
    fun deleteTag(tagId: Long, tagName: String) = launchAction {
        if (repository.deleteTag(tagId)) {
            // 正被当筛选条件用的标签没了，条件也要跟着撤掉，否则列表永远是空
            _selectedTagIds.value = _selectedTagIds.value - tagId
            str(R.string.msg_tag_deleted, tagName)
        } else {
            str(R.string.msg_tag_delete_missing, tagName)
        }
    }

    // ---------- 详情页标签编辑 ----------

    /**
     * 一次性取回全部标签（按名字排序）。
     *
     * [tags] 是 `WhileSubscribed` 的 StateFlow：没有人收集它时 `.value` 会一直是初始的空列表。
     * 详情页从不订阅它，所以那里要列标签必须走这个「取一次」的入口，不能读 `.value`。
     */
    suspend fun allTagsOnce(): List<TagEntity> = withContext(Dispatchers.IO) { repository.getTags() }

    fun observeTagsOf(emojiId: Long): Flow<List<TagEntity>> = repository.observeTagsOf(emojiId)

    /** 添加一个标签到某张表情（不存在就建）。 */
    fun addTagToEmoji(emojiId: Long, name: String) = launchAction {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return@launchAction str(R.string.tag_name_empty)
        val tagId = repository.ensureTag(trimmed)
            ?: return@launchAction str(R.string.msg_tag_create_failed, trimmed)
        repository.addTags(listOf(emojiId), listOf(tagId))
        str(R.string.msg_tag_added_to_emoji, trimmed)
    }

    fun removeTagFromEmoji(emojiId: Long, tagId: Long) = launchAction {
        repository.removeTags(listOf(emojiId), listOf(tagId))
        null
    }

    // ---------- 表情库打包导出 / 导入 ----------

    /**
     * 取一次表情总数（导出前问问有几张，给界面做提示用）。
     *
     * [EmojiRepository.getCount] 返回的是给界面订阅的 Flow，这里只要一个数，
     * 所以老实 `.first()` 一下，而不是去读 StateFlow 的 `.value`（没人订阅时它是初始值）。
     */
    suspend fun countEmojisOnce(): Int = withContext(Dispatchers.IO) { repository.getCount().first() }

    /**
     * 把整个表情库打包写到 [output]。
     *
     * 打包是一锤子买卖：取快照、取标签、写 zip 全在 IO 线程里一气做完，
     * 中途任何异常都折成 [EmojiArchive.Report.error] 交回去，不往外抛 ——
     * 界面只看报告就够，不用再包一层 try/catch，也免得异常逃到 viewModelScope 上。
     *
     * 不关 [output]：那是调用方（通常是 ContentResolver 给的流）自己的事。
     */
    suspend fun exportLibrary(
        output: OutputStream,
        onProgress: (done: Int, total: Int) -> Unit
    ): EmojiArchive.Report = withContext(Dispatchers.IO) {
        try {
            EmojiArchive.export(
                getApplication<Application>(),
                output,
                repository.getAllOnce(),
                repository.getTags(),
                repository.getAllTagLinksOnce(),
                appVersion(),
                onProgress
            )
        } catch (e: Exception) {
            e.printStackTrace()
            EmojiArchive.Report(error = EmojiArchive.Error.IO)
        }
    }

    /**
     * 导入一个备份包：[openInput] 每次调用都给出一条新流（zip 不能倒带，读包要开两趟）。
     *
     * 两种模式共同的底线顺序是「先把图落盘 → 再动数据库 → 最后清旧文件」：
     * 这样任何一步失败，都不会出现「库里有记录、磁盘上没图」，
     * 也不会把用户原有的表情弄丢。失败一律折成 [EmojiArchive.Report.error]。
     */
    suspend fun importLibrary(
        openInput: () -> InputStream?,
        mode: EmojiArchive.Mode,
        onProgress: (done: Int, total: Int) -> Unit
    ): EmojiArchive.Report = withContext(Dispatchers.IO) {
        try {
            when (mode) {
                EmojiArchive.Mode.MERGE -> mergeImport(openInput, onProgress)
                EmojiArchive.Mode.REPLACE -> replaceImport(openInput, onProgress)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            EmojiArchive.Report(error = EmojiArchive.Error.IO)
        }
    }

    /**
     * MERGE：把包里库里没有的补进来（同名同体积的已经在解包阶段被跳过）。
     *
     * 逐条走 [EmojiRepository.insert]：它在同一个事务里去重名、并挂上「图片」/「动图」自动标签，
     * 之后我们再补上清单里记着的用户标签。
     */
    private suspend fun mergeImport(
        openInput: () -> InputStream?,
        onProgress: (Int, Int) -> Unit
    ): EmojiArchive.Report {
        val imported = EmojiArchive.import(
            context = getApplication<Application>(),
            openInput = openInput,
            mode = EmojiArchive.Mode.MERGE,
            existing = repository.getAllOnce(),
            onProgress = onProgress
        )
        val report = imported.report
        // 包本身就读坏了（不是备份包 / 打不开）：一条都别往库里塞
        if (report.error != null) return report

        val tagIds = LinkedHashSet<Long>()
        val (stored, failed) = storeAll(imported, tagIds)
        return report.copy(emojis = stored, failed = report.failed + failed, tags = tagIds.size)
    }

    /**
     * REPLACE：整个库换成包里的内容。
     *
     * 顺序是刻意的，换一步都可能丢数据：
     * 1) 先解包落盘 —— 解不开的话旧库一个字节都不用动，把这次写出来的文件删掉就干净了；
     * 2) 再清库、逐条入库 —— 此时磁盘上已经躺着新图，最坏也只是库里少几条记录；
     * 3) 最后才删旧图（旧快照里、且不在这次新文件集合里的那些）——
     *    要是反过来先清旧图再入库，中途一失败，用户原来的表情就真没了。
     */
    private suspend fun replaceImport(
        openInput: () -> InputStream?,
        onProgress: (Int, Int) -> Unit
    ): EmojiArchive.Report {
        // existing 传空表：REPLACE 不去重，包里有什么就收什么
        val imported = EmojiArchive.import(
            context = getApplication<Application>(),
            openInput = openInput,
            mode = EmojiArchive.Mode.REPLACE,
            existing = emptyList(),
            onProgress = onProgress
        )
        val report = imported.report
        val newPaths = imported.emojis.mapTo(HashSet(imported.emojis.size)) { it.filePath }
        if (report.error != null) {
            // 解包没成，就别碰数据库：把这次写出来的文件全部收回去，旧库原样不动
            newPaths.forEach { ImageUtil.deleteFile(it) }
            return report
        }

        // 清库之前先把磁盘现状记下来：清完就再也分不清哪些是老图了
        val oldPaths = ImageUtil.listEmojiFiles(getApplication<Application>())

        val tagIds = LinkedHashSet<Long>()
        repository.clearLibrary()
        val (stored, failed) = storeAll(imported, tagIds)
        // 新库已经落定，这会儿才可以动旧文件；快照里的新文件被 newPaths 挡着，绝不会误删
        for (path in oldPaths) {
            if (path !in newPaths) ImageUtil.deleteFile(path)
        }
        return EmojiArchive.Report(
            emojis = stored,
            failed = report.failed + failed,
            tags = tagIds.size,
            bytes = report.bytes
        )
    }

    /**
     * 把解出来的记录逐条入库，再补上清单里的标签。
     *
     * 入库失败的那一张，连刚解出来的文件一起删掉：库里有记录、磁盘上没图（反过来也一样）
     * 都会让列表里多出一张打不开的空图。
     *
     * @param tagIds 收集这次导入真正用到过的标签 id（跨所有表情去重，用来填 Report.tags）。
     * @return first = 成功入库的张数，second = 入库失败的张数。
     */
    private suspend fun storeAll(
        imported: EmojiArchive.Imported,
        tagIds: MutableSet<Long>
    ): Pair<Int, Int> {
        var stored = 0
        var failed = 0
        imported.emojis.forEachIndexed { index, emoji ->
            val id = runCatching { repository.insert(emoji) }.getOrNull()
            if (id == null || id <= 0L) {
                ImageUtil.deleteFile(emoji.filePath)
                failed++
                return@forEachIndexed
            }
            stored++
            attachTags(id, imported.tagsOf.getOrElse(index) { emptyList() }, tagIds)
        }
        return stored to failed
    }

    /**
     * 把清单里记着的标签名挂到刚入库的表情上。
     *
     * 同一张表情上的重名标签先并掉：DAO 那边虽然是 IGNORE（重复挂不会炸），
     * 但白跑一趟 SQL 不说，「这次导入用到几个标签」的计数也会跟着虚高。
     */
    private suspend fun attachTags(emojiId: Long, names: List<String>, tagIds: MutableSet<Long>) {
        val wanted = names.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (wanted.isEmpty()) return
        val ids = ArrayList<Long>(wanted.size)
        for (name in wanted) {
            // 单个标签建不出来（名字为空 / 写库失败）就跳过它，不该连累这张表情
            val tagId = repository.ensureTag(name) ?: continue
            ids.add(tagId)
        }
        if (ids.isEmpty()) return
        repository.addTags(listOf(emojiId), ids)
        tagIds.addAll(ids)
    }

    // ---------- 存储管理 ----------

    /**
     * 取一次全部表情。
     *
     * 存储页要的是「每张图的文件路径 + 体积」，而且要按磁盘实际占用重算一遍 ——
     * 订阅 Flow 只会拿到数据库里记着的旧数，不合适。
     */
    suspend fun getAllEmojisOnce(): List<EmojiEntity> = withContext(Dispatchers.IO) { repository.getAllOnce() }

    /**
     * 压缩成功后回写：这张表情的文件换成了新的 WebP。
     *
     * 返回 false 表示没写成功，调用方据此决定「删掉新文件、保留旧图」——
     * 宁可这次白压，也不能让数据库指向一个不存在的新文件。
     */
    suspend fun replaceEmojiFile(
        id: Long,
        filePath: String,
        fileSize: Long,
        width: Int,
        height: Int
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            repository.updateCompressed(id, filePath, "image", fileSize, width, height)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // ---------- 重复与相似检索 ----------

    /** 全库内容指纹。判定「完全相同」时先拿它当缓存：体积与修改时间都对得上就不必再读文件。 */
    suspend fun allImageFeatures(): List<ImageFeatureEntity> =
        withContext(Dispatchers.IO) { repository.getAllFeatures() }

    /** 把这一次新算出来的指纹存回去，下次进重复页直接复用。 */
    suspend fun saveImageFeatures(features: List<ImageFeatureEntity>) =
        withContext(Dispatchers.IO) { repository.saveFeatures(features) }

    /**
     * 重复页要删的那几张。刻意和主页、详情页共用同一套「先删文件、确认删掉才删记录」，
     * 不另起一套删除逻辑。
     *
     * @return 文件删除失败、因而保留下来的张数。
     */
    suspend fun deleteEmojisForTool(ids: List<Long>): Int =
        withContext(Dispatchers.IO) { deleteWithFiles(ids) }

    /** 清单里记的应用版本号；取不到就写问号 —— 取版本号失败不该把整个备份带崩。 */
    private fun appVersion(): String = runCatching {
        val app = getApplication<Application>()
        app.packageManager.getPackageInfo(app.packageName, 0).versionName
    }.getOrNull() ?: "?"
}

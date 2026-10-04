package com.aris.emojichan.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aris.emojichan.R
import com.aris.emojichan.SimilarPrefs
import com.aris.emojichan.UiPrefs
import com.aris.emojichan.data.EmojiEntity
import com.aris.emojichan.data.EmojiFilter
import com.aris.emojichan.data.EmojiOrder
import com.aris.emojichan.data.EmojiRepository
import com.aris.emojichan.data.ImageFeatureEntity
import com.aris.emojichan.data.TagEntity
import com.aris.emojichan.data.TagMode
import com.aris.emojichan.data.TagOrder
import com.aris.emojichan.util.EmojiArchive
import com.aris.emojichan.util.FolderImporter
import com.aris.emojichan.util.ImageUtil
import com.aris.emojichan.storage.SimilarIgnore
import java.io.InputStream
import java.io.OutputStream
import java.text.Collator
import java.util.Locale
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
import kotlinx.coroutines.flow.update
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

    /**
     * 只看用过的（v0.2.007，用户 m10764）。
     *
     * 跟「收藏」一样是筛选条上的一档，不是另一套列表：它和标签、搜索条件照常叠加，决定哪些图
     * 留下来的是 [EmojiQuery]。区别在顺序 —— 这一档按最近使用时间倒序排（见 [sorted]），
     * 「最近」的意义就是顺序（跟悬浮球面板里那颗「最近」是同一个读法）。
     */
    private val _recentOnly = MutableStateFlow(false)
    val recentOnly: StateFlow<Boolean> = _recentOnly.asStateFlow()

    /** 过滤区里勾中的标签。 */
    private val _selectedTagIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedTagIds: StateFlow<Set<Long>> = _selectedTagIds.asStateFlow()

    /**
     * 每个已选标签各自怎么合并（交 / 并 / 非，用户 m10282）。
     *
     * 只记「改过」的那些：表里没有的标签按 [TagMode.ALL]（交）算 —— 新选一个标签
     * 默认就是「交」，跟筛选条上那颗 chip 的 & 前缀对得上。
     */
    private val _tagModes = MutableStateFlow<Map<Long, TagMode>>(emptyMap())
    val tagModes: StateFlow<Map<Long, TagMode>> = _tagModes.asStateFlow()

    private val _isSelectionMode = MutableStateFlow(false)
    val isSelectionMode: StateFlow<Boolean> = _isSelectionMode.asStateFlow()

    private val _selectedIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedIds: StateFlow<Set<Long>> = _selectedIds.asStateFlow()

    /** 一种排序：按什么排（[UiPrefs.SORT_TIME] / [UiPrefs.SORT_NAME] / [UiPrefs.SORT_SIZE]）+ 是不是倒序。 */
    data class SortSpec(val field: String, val desc: Boolean)

    /**
     * 当前排序方式（v0.2.003，用户 m09998）：按添加时间 / 名称 / 文件大小，正序或倒序。
     *
     * 偏好存在 [UiPrefs] 里 —— 跟主题色一样属于「这台机器上想怎么看」，不写进数据库。
     * 排序本身在这一层做、不塞进 SQL 的 ORDER BY：改排序时列表已经在手上，换个顺序就够，
     * 不用再查一遍库（几十张图重查一次的代价远大于排一遍）。
     */
    private val _sort: MutableStateFlow<SortSpec> = MutableStateFlow(
        SortSpec(UiPrefs.sortField(getApplication()), UiPrefs.sortDesc(getApplication()))
    )
    val sort: StateFlow<SortSpec> = _sort.asStateFlow()

    fun setSort(field: String, desc: Boolean) {
        UiPrefs.setSort(getApplication(), field, desc)
        _sort.value = SortSpec(field, desc)
    }

    /**
     * 真正排一遍。主字段比完拿 id 兜底：同一批导入的表情 createTime 常常一模一样，
     * 不兜底的话两次排出来的先后可能不同，看着像列表在乱跳。
     *
     * [recent] 为真（筛选条上的「最近」）时改成按最近使用时间倒序：那一档要看的就是顺序本身，
     * 排序设置在那儿让位 —— 否则「最近」看上去跟别的分类没区别。
     */
    private fun sorted(list: List<EmojiEntity>, spec: SortSpec, recent: Boolean): List<EmojiEntity> {
        if (list.size < 2) return list
        if (recent) return EmojiOrder.recentFirst(list)
        val collator = Collator.getInstance(Locale.getDefault())
        val primary = when (spec.field) {
            UiPrefs.SORT_NAME -> Comparator<EmojiEntity> { a, b -> collator.compare(a.name, b.name) }
            UiPrefs.SORT_SIZE -> Comparator<EmojiEntity> { a, b -> a.fileSize.compareTo(b.fileSize) }
            else -> Comparator<EmojiEntity> { a, b -> a.createTime.compareTo(b.createTime) }
        }
        val cmp = primary.thenBy { it.id }
        return if (spec.desc) list.sortedWith(cmp.reversed()) else list.sortedWith(cmp)
    }

    /**
     * 列表数据：收藏 / 最近 / 搜索条件 / 标签 的组合过滤。
     *
     * 搜索框那句话由 [EmojiFilter.parse] 解析成条件表达式（`&` 同时满足、`/` 任一满足），
     * 这里只管攒条件，翻译成 SQL 是 [EmojiQuery] 的事 —— 只有一条查询路径
     * （[EmojiRepository.observeFiltered]），不按条件分支挑不同的 DAO 方法。
     */
    val emojis: StateFlow<List<EmojiEntity>> = combine(
        _searchQuery,
        _selectedTagIds,
        _tagModes,
        _favoritesOnly,
        _recentOnly
    ) { query, tagIds, tagModes, favoritesOnly, recentOnly ->
        EmojiFilter(
            favoritesOnly = favoritesOnly,
            expr = EmojiFilter.parse(query),
            tagIds = tagIds.toList(),
            tagModes = tagModes,
            recentOnly = recentOnly
        )
    }.flatMapLatest { filter -> repository.observeFiltered(filter) }
        .combine(_sort) { list, spec -> list to spec }
        .combine(_recentOnly) { (list, spec), recent -> Triple(list, spec, recent) }
        // 排序交给后台线程：几千张时按名称排要跑一遍 Collator，不能卡在换顺序那一帧
        .map { (list, spec, recent) ->
            withContext(Dispatchers.Default) { sorted(list, spec, recent) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 全部标签，过滤区与标签管理都用它（按名字序，与数据库里一致）。 */
    val tags: StateFlow<List<TagEntity>> = repository.observeTags()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 每个标签挂着多少张表情：只服务于标签下拉的排序（用户 m11668 第 7 条）。 */
    private val tagCounts: StateFlow<Map<Long, Int>> = repository.observeTagCounts()
        .map { rows -> rows.associate { it.tagId to it.count } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /**
     * 标签下拉里的显示顺序（用户 m11668 第 7 条）：「图片」「动图」固定最前，
     * 其余按挂着的张数从多到少，规则本体在 `TagOrder.forPanel` 里。
     *
     * 另开一条流、而不是直接给 [tags] 重排：标签管理弹窗与搜索补全都按名字序读 [tags]，
     * 想按用量排的只有下拉这一处。
     */
    val tagPanelTags: StateFlow<List<TagEntity>> = combine(tags, tagCounts) { list, counts ->
        TagOrder.forPanel(list, counts)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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

    fun setRecentOnly(only: Boolean) {
        _recentOnly.value = only
    }

    fun toggleRecentOnly() {
        _recentOnly.value = !_recentOnly.value
    }

    fun toggleTagFilter(tagId: Long) {
        val current = _selectedTagIds.value.toMutableSet()
        if (current.add(tagId)) {
            // 新选中的标签默认「交」：表里不写它，读的时候按 ALL 兜底
            _selectedTagIds.value = current
            return
        }
        current.remove(tagId)
        _selectedTagIds.value = current
        // 标签撤掉了，它那档合并方式也一并清掉，下次选回来还是「交」
        _tagModes.update { it - tagId }
    }

    fun clearTagFilter() {
        _selectedTagIds.value = emptySet()
        _tagModes.value = emptyMap()
    }

    /** 点筛选条上那颗标签：交 → 并 → 非 → 交（用户 m10282）。 */
    fun cycleTagMode(tagId: Long) {
        _tagModes.update { modes ->
            val next = when (modes[tagId] ?: TagMode.ALL) {
                TagMode.ALL -> TagMode.ANY
                TagMode.ANY -> TagMode.EXCLUDE
                TagMode.EXCLUDE -> TagMode.ALL
            }
            modes + (tagId to next)
        }
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
     * 分两段报进度（v0.2.003 起，用户 m09930）：先只扫描，[onScanning] 报「已经找到多少张」
     * （这时分母还没有，进度条转圈）；扫完拿到总数，[onProgress] 再按「已处理 / 总数」报。
     *
     * @return 成功入库的条数；无法读取该文件夹返回 -1。
     */
    suspend fun importFolder(
        treeUri: Uri,
        onScanning: (Int) -> Unit = {},
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): FolderImport = withContext(Dispatchers.IO) {
        try {
            val app = getApplication<android.app.Application>()
            val plan = FolderImporter.plan(app, treeUri, onScanning)
                ?: return@withContext FolderImport(-1, truncated = false)
            val entries = plan.entries
            val batches = FolderImporter.decode(app, entries, onProgress)
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
            FolderImport(imported, plan.truncated)
        } catch (e: Exception) {
            e.printStackTrace()
            _message.value =
                str(R.string.msg_operation_failed, e.message ?: str(R.string.msg_unknown_error))
            FolderImport(0, truncated = false)
        }
    }

    /** 文件夹导入的结果：入库条数，以及清单有没有被单次上限截断过（用户 m09998）。 */
    data class FolderImport(val count: Int, val truncated: Boolean)

    /**
     * 删除一组表情：**先删文件、确认文件已消失，再删数据库记录**。
     *
     * 顺序不能反。若先删记录再删文件，文件删除失败（被占用、权限等）时记录已经没了，
     * 文件会永久残留在私有目录且无从追溯；反过来最坏情况只是记录保留、下次还能重试。
     *
     * [onProgress] 每处理一个报一次（已处理, 总数），给进度条用（v0.2.003 起，用户 m09940）。
     *
     * @return 文件删除失败、因而保留了记录的条目数。
     */
    private suspend fun deleteWithFiles(ids: List<Long>, onProgress: (Int, Int) -> Unit = { _, _ -> }): Int {
        if (ids.isEmpty()) return 0
        val emojis = repository.getByIds(ids)
        val deletableIds = mutableListOf<Long>()
        var failedCount = 0
        withContext(Dispatchers.IO) {
            emojis.forEachIndexed { index, emoji ->
                onProgress(index + 1, emojis.size)
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

    /**
     * 主页删除选中的那几个：删**当前选中的那些**，清理选择态、报错也照旧，
     * 只是把「删到第几个」报给调用方，进度框由页面自己管（v0.2.003 起，用户 m09940）。
     *
     * 之所以改成挂起函数：进度框得跟着这一趟活儿的头尾开关，而 [viewModelScope] 里的
     * 协程页面等不到。
     *
     * @return 文件删不掉、因而保留记录的条数；出错返回 -1。
     */
    suspend fun deleteSelectedWithProgress(
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): Int = withContext(Dispatchers.IO) {
        try {
            val failed = deleteWithFiles(_selectedIds.value.toList(), onProgress)
            if (failed > 0) {
                _message.value = str(R.string.msg_delete_partial_failed, failed)
            }
            exitSelection()
            failed
        } catch (e: Exception) {
            e.printStackTrace()
            _message.value = str(
                R.string.msg_delete_failed,
                e.message ?: str(R.string.msg_unknown_error)
            )
            -1
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

    /**
     * 批量整理成功后的收尾：清空勾选 + 退出选择模式。
     *
     * 用户 m11465：整理（加/摘标签）做完后勾选还留在网格上，看着像没生效。
     * 收尾与删除用同一套，保证「一次批量操作 = 一次选择结束」。
     */
    private fun exitSelection() {
        _selectedIds.value = emptySet()
        _isSelectionMode.value = false
    }

    /** 给选中的表情批量打上已有标签。 */
    fun addTagsToSelected(tagIds: List<Long>) = launchAction {
        val ids = _selectedIds.value.toList()
        if (ids.isEmpty()) return@launchAction str(R.string.msg_nothing_selected)
        if (tagIds.isEmpty()) return@launchAction str(R.string.msg_tag_none_selected)
        repository.addTags(ids, tagIds)
        exitSelection()
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
        exitSelection()
        str(R.string.msg_tags_added, ids.size)
    }

    /** 从选中的表情上批量摘掉标签。 */
    fun removeTagsFromSelected(tagIds: List<Long>) = launchAction {
        val ids = _selectedIds.value.toList()
        if (ids.isEmpty()) return@launchAction str(R.string.msg_nothing_selected)
        if (tagIds.isEmpty()) return@launchAction str(R.string.msg_tag_none_selected)
        repository.removeTags(ids, tagIds)
        exitSelection()
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
                SimilarPrefs.ignoredRecords(getApplication()),
                appVersion(),
                onProgress
            )
        } catch (e: Exception) {
            e.printStackTrace()
            EmojiArchive.Report(error = EmojiArchive.Error.IO)
        }
    }

    /**
     * 只导出一部分表情（存储页的「长时间不使用的表情」用，v0.2.013 / 用户 m11668 第 5 条）。
     *
     * 跟 [exportLibrary] 是同一条路，只有表情清单换成点中的那些：标签、标签关联、忽略名单
     * 照旧整份带上 —— 包本身仍是一个普通备份包，导入端不用为它写任何分支（清单里多出来的
     * id 只有打包时写、导入时按「名字 + 体积」重新映射，见 [EmojiArchive]）。
     */
    suspend fun exportEmojis(
        ids: List<Long>,
        output: OutputStream,
        onProgress: (done: Int, total: Int) -> Unit
    ): EmojiArchive.Report = withContext(Dispatchers.IO) {
        try {
            EmojiArchive.export(
                getApplication<Application>(),
                output,
                repository.getByIds(ids),
                repository.getTags(),
                repository.getAllTagLinksOnce(),
                SimilarPrefs.ignoredRecords(getApplication()),
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
        // 导入前的库快照有两处要用：MERGE 的去重键，和「旧忽略名单该按哪些 id 剪」。
        val existing = repository.getAllOnce()
        val imported = EmojiArchive.import(
            context = getApplication<Application>(),
            openInput = openInput,
            mode = EmojiArchive.Mode.MERGE,
            existing = existing,
            onProgress = onProgress
        )
        val report = imported.report
        // 包本身就读坏了（不是备份包 / 打不开）：一条都别往库里塞
        if (report.error != null) return report

        val tagIds = LinkedHashSet<Long>()
        val newIds = storeAll(imported, tagIds)
        val restored = restoreIgnored(imported, newIds, existing, keepOld = true)
        return report.copy(
            emojis = newIds.count { it != null },
            failed = report.failed + newIds.count { it == null },
            tags = tagIds.size,
            ignored = restored.records.size,
            ignoredDropped = restored.dropped
        )
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
        val newIds = storeAll(imported, tagIds)
        // REPLACE：库是全新的，旧忽略名单必然指向别的图 —— 整份换成包里带来的（keepOld = false）
        val restored = restoreIgnored(imported, newIds, emptyList(), keepOld = false)
        // 新库已经落定，这会儿才可以动旧文件；快照里的新文件被 newPaths 挡着，绝不会误删
        for (path in oldPaths) {
            if (path !in newPaths) ImageUtil.deleteFile(path)
        }
        return EmojiArchive.Report(
            emojis = newIds.count { it != null },
            failed = report.failed + newIds.count { it == null },
            tags = tagIds.size,
            bytes = report.bytes,
            ignored = restored.records.size,
            ignoredDropped = restored.dropped
        )
    }

    /**
     * 把解出来的记录逐条入库，再补上清单里的标签。
     *
     * 入库失败的那一张，连刚解出来的文件一起删掉：库里有记录、磁盘上没图（反过来也一样）
     * 都会让列表里多出一张打不开的空图。
     *
     * @param tagIds 收集这次导入真正用到过的标签 id（跨所有表情去重，用来填 Report.tags）。
     * @return 与 [EmojiArchive.Imported.emojis] 同序同长：入库成功的那个新 id，失败的位置是 null
     *   （调用方靠下标关系拼「包里的 id → 本机的 id」这张表，忽略名单要照着它搬家）。
     */
    private suspend fun storeAll(
        imported: EmojiArchive.Imported,
        tagIds: MutableSet<Long>
    ): List<Long?> {
        val newIds = ArrayList<Long?>(imported.emojis.size)
        imported.emojis.forEachIndexed { index, emoji ->
            val id = runCatching { repository.insert(emoji) }.getOrNull()
            if (id == null || id <= 0L) {
                ImageUtil.deleteFile(emoji.filePath)
                newIds.add(null)
                return@forEachIndexed
            }
            newIds.add(id)
            attachTags(id, imported.tagsOf.getOrElse(index) { emptyList() }, tagIds)
        }
        return newIds
    }

    /**
     * 把包里带来的忽略名单写回偏好（v0.2.012，用户 m11327 的第 3 条）。
     *
     * old → new 的映射有两个来源：这次真入库的那些（[EmojiArchive.Imported.oldIds] 与
     * [EmojiArchive.Imported.emojis] 同序，失败的位置是 null）和 MERGE 里被「库里已经有
     * 一模一样的」顶掉的那些（[EmojiArchive.Imported.takenOldIds] 直接给了库里那张的 id）。
     *
     * @param aliveBefore 导入前库里那批记录：追加模式下先按它把**旧**名单剪一遍。删掉表情腾出来的
     *   旧 id 可能正好被这次新导入的图占用，不剪的话旧记录就会误伤刚进来的新图。
     * @param keepOld true = 在原有名单上追加（MERGE）；false = 整份换掉（REPLACE：清库后 id 全部
     *   重新分配，旧记录必然指向别的图，留着就是错的）。
     */
    private fun restoreIgnored(
        imported: EmojiArchive.Imported,
        newIds: List<Long?>,
        aliveBefore: List<EmojiEntity>,
        keepOld: Boolean
    ): SimilarIgnore.Restored {
        val context = getApplication<Application>()
        val mapping = HashMap<Long, Long>(imported.takenOldIds)
        imported.oldIds.forEachIndexed { index, oldId ->
            val fresh = newIds.getOrNull(index)
            if (oldId > 0L && fresh != null && fresh > 0L) mapping[oldId] = fresh
        }
        val restored = SimilarIgnore.restore(imported.ignored, mapping)
        val merged = if (keepOld) {
            val alive = aliveBefore.mapTo(HashSet(aliveBefore.size)) { it.id }
            SimilarIgnore.keepAlive(SimilarPrefs.ignoredRecords(context), alive) + restored.records
        } else {
            restored.records
        }
        SimilarPrefs.setIgnoredRecords(context, merged)
        return restored
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
     * [onProgress] 每处理一张报一次（已处理, 总数），给进度条用（v0.2.003 起，用户 m09940）。
     *
     * @return 文件删除失败、因而保留下来的张数。
     */
    suspend fun deleteEmojisForTool(ids: List<Long>, onProgress: (Int, Int) -> Unit = { _, _ -> }): Int =
        withContext(Dispatchers.IO) { deleteWithFiles(ids, onProgress) }

    /** 清单里记的应用版本号；取不到就写问号 —— 取版本号失败不该把整个备份带崩。 */
    private fun appVersion(): String = runCatching {
        val app = getApplication<Application>()
        app.packageManager.getPackageInfo(app.packageName, 0).versionName
    }.getOrNull() ?: "?"
}

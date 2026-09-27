package com.aris.emojichan.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aris.emojichan.R
import com.aris.emojichan.data.EmojiEntity
import com.aris.emojichan.data.EmojiRepository
import com.aris.emojichan.util.ImageUtil
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
class EmojiViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = EmojiRepository(application)

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedCategory = MutableStateFlow("全部")
    val selectedCategory: StateFlow<String> = _selectedCategory.asStateFlow()

    private val _isSelectionMode = MutableStateFlow(false)
    val isSelectionMode: StateFlow<Boolean> = _isSelectionMode.asStateFlow()

    private val _selectedIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedIds: StateFlow<Set<Long>> = _selectedIds.asStateFlow()

    val emojis: StateFlow<List<EmojiEntity>> = combine(_searchQuery, _selectedCategory) { query, category ->
        query to category
    }.flatMapLatest { (query, category) ->
        when {
            query.isBlank() && category == "全部" -> repository.getAllEmojis()
            query.isBlank() && category == "收藏" -> repository.getFavorites()
            query.isBlank() -> repository.getByCategory(category)
            category == "收藏" -> repository.searchFavorites(query)
            category == "全部" -> repository.search(query)
            else -> repository.searchInCategory(query, category)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val categories: StateFlow<List<String>> = repository.getAllCategories()
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

    fun setSelectedCategory(category: String) {
        _selectedCategory.value = category
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

    fun insertEmoji(emoji: EmojiEntity) {
        // 写库失败（磁盘满、约束冲突）必须自己接住：裸 launch 里抛出的异常
        // 会直接走到默认异常处理器上，把整个应用崩掉（emc-1-022）。
        viewModelScope.launch(handler) {
            try {
                repository.insert(emoji)
            } catch (e: Exception) {
                e.printStackTrace()
                _message.value =
                    str(R.string.msg_operation_failed, e.message ?: str(R.string.msg_unknown_error))
            }
        }
    }

    fun insertAll(emojis: List<EmojiEntity>) {
        viewModelScope.launch(handler) {
            try {
                repository.insertAll(emojis)
            } catch (e: Exception) {
                e.printStackTrace()
                _message.value =
                    str(R.string.msg_operation_failed, e.message ?: str(R.string.msg_unknown_error))
            }
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

    /** 精准更新收藏状态：只写一列，不会覆盖 tags / usageCount 等其它字段。 */
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
        launchWrite(str(R.string.msg_rename_failed_missing)) {
            repository.rename(id, newName)
        }
    }

    // ---------- 分类管理 ----------

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

    fun addCategory(name: String) = launchAction {
        if (repository.insertCategory(name)) {
            str(R.string.msg_category_created, name)
        } else {
            str(R.string.msg_category_exists, name)
        }
    }

    fun renameCategory(oldName: String, newName: String) = launchAction {
        when {
            repository.renameCategory(oldName, newName) -> {
                // 当前正停在这个分类上就跟着改名，否则列表会突然变空
                if (_selectedCategory.value == oldName) _selectedCategory.value = newName
                str(R.string.msg_category_renamed, newName)
            }
            categories.value.contains(oldName) -> str(R.string.msg_category_exists, newName)
            else -> str(R.string.msg_category_rename_missing, oldName)
        }
    }

    /** 删除分类；分类下的表情改挂到「默认」，不会被一起删掉。 */
    fun deleteCategory(name: String) = launchAction {
        if (repository.deleteCategory(name, DEFAULT_CATEGORY)) {
            if (_selectedCategory.value == name) _selectedCategory.value = CATEGORY_ALL
            str(R.string.msg_category_deleted, name)
        } else {
            str(R.string.msg_category_delete_missing, name)
        }
    }

    private companion object {
        /** 删除分类时，该分类下的表情改挂到这里，保证表情不跟着消失。 */
        const val DEFAULT_CATEGORY = "默认"
        const val CATEGORY_ALL = "全部"
    }
}

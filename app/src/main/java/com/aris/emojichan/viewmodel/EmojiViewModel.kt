package com.aris.emojichan.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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

    /** 兜底异常处理器：避免协程内未捕获的异常导致进程崩溃，同时把原因暴露出来。 */
    private val handler = CoroutineExceptionHandler { _, e ->
        e.printStackTrace()
        _message.value = "操作失败：" + (e.message ?: "未知错误")
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
        viewModelScope.launch {
            repository.insert(emoji)
        }
    }

    fun insertAll(emojis: List<EmojiEntity>) {
        viewModelScope.launch {
            repository.insertAll(emojis)
        }
    }

    fun deleteSelected() {
        viewModelScope.launch {
            val ids = _selectedIds.value.toList()
            val filePaths = repository.getFilePathsByIds(ids)
            repository.deleteByIds(ids)
            withContext(Dispatchers.IO) {
                filePaths.forEach { ImageUtil.deleteFile(it) }
            }
            _selectedIds.value = emptySet()
            _isSelectionMode.value = false
        }
    }

    /** 详情页数据源：以数据库为唯一真值，避免在页面间搬运残缺实体。 */
    fun observeEmoji(id: Long): Flow<EmojiEntity?> = repository.observeById(id)

    /** 精准更新收藏状态：只写一列，不会覆盖 tags / usageCount 等其它字段。 */
    fun updateFavorite(id: Long, isFavorite: Boolean) {
        viewModelScope.launch(handler) {
            try {
                if (repository.updateFavorite(id, isFavorite) == 0) {
                    _message.value = "操作未生效，该表情可能已被删除"
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _message.value = "操作失败：" + (e.message ?: "未知错误")
            }
        }
    }

    fun deleteEmoji(emoji: EmojiEntity) {
        viewModelScope.launch(handler) {
            try {
                repository.delete(emoji)
            } catch (e: Exception) {
                e.printStackTrace()
                _message.value = "删除失败：" + (e.message ?: "未知错误")
            }
        }
    }

    /** 重命名；失败或影响行数为 0 时通过 [message] 反馈，UI 会回灌数据库真值。 */
    fun renameEmoji(id: Long, newName: String) {
        launchWrite("重命名失败，该表情可能已被删除") {
            repository.rename(id, newName)
        }
    }
}

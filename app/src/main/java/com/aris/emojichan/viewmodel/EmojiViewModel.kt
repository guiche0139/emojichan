package com.aris.emojichan.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aris.emojichan.data.EmojiEntity
import com.aris.emojichan.data.EmojiRepository
import com.aris.emojichan.util.ImageUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

    fun toggleFavorite(emoji: EmojiEntity) {
        viewModelScope.launch {
            repository.updateFavorite(emoji.id, !emoji.isFavorite)
        }
    }

    fun deleteEmoji(emoji: EmojiEntity) {
        viewModelScope.launch {
            repository.delete(emoji)
        }
    }

    fun renameEmoji(id: Long, newName: String) {
        viewModelScope.launch {
            repository.rename(id, newName)
        }
    }
}

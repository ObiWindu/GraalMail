package com.graalmail.ui

import androidx.lifecycle.*
import com.graalmail.data.MailRepository
import com.graalmail.model.MailMessage
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class MailUiState(val messages: List<MailMessage> = emptyList(), val loading: Boolean = false, val error: String? = null)

class MailViewModel(private val repo: MailRepository) : ViewModel() {
    private val _state = MutableStateFlow(MailUiState())
    val state = _state.asStateFlow()

    fun load(accountId: Long) = viewModelScope.launch {
        _state.value = _state.value.copy(messages = repo.inbox(accountId), loading = false)
    }
    fun markRead(id: String) = viewModelScope.launch { repo.markRead(id) }
    fun archive(id: String) = viewModelScope.launch { repo.archive(id) }

    companion object {
        fun factory(repo: MailRepository) = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(c: Class<T>): T {
                @Suppress("UNCHECKED_CAST") return MailViewModel(repo) as T
            }
        }
    }
}

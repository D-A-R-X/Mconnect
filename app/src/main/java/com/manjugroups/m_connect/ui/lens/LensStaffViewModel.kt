package com.manjugroups.m_connect.ui.lens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.manjugroups.m_connect.network.ApiErrors
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.LensStaffItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LensStaffUiState(
    val query: String = "",
    val items: List<LensStaffItem> = emptyList(),
    /** First page of the current query is loading (skeleton). */
    val loading: Boolean = true,
    /** Pull-to-refresh in progress (keep the list visible). */
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val nextCursor: String? = null,
    /** First-page failure with nothing to show. */
    val error: String? = null,
    /** Bumped on every completed first-page load so the list scrolls to top. */
    val generation: Int = 0,
)

/** Lens Enrollment staff directory: search, cursor paging, refresh. */
class LensStaffViewModel : ViewModel() {

    private val api = ApiService.create()
    private val _state = MutableStateFlow(LensStaffUiState())
    val state: StateFlow<LensStaffUiState> = _state.asStateFlow()

    private var token: String = ""
    private var firstPageJob: Job? = null
    private var moreJob: Job? = null
    private var started = false

    fun start(bearerToken: String) {
        token = bearerToken
        if (started) return
        started = true
        loadFirstPage(debounceMs = 0, asRefresh = false)
    }

    fun onQueryChanged(query: String) {
        val q = query.trim()
        if (q == _state.value.query) return
        _state.update { it.copy(query = q) }
        loadFirstPage(debounceMs = 350, asRefresh = false)
    }

    fun refresh() = loadFirstPage(debounceMs = 0, asRefresh = true)

    /** Re-reads the list after an enrollment so the badge updates. */
    fun reloadQuietly() = loadFirstPage(debounceMs = 0, asRefresh = true, silent = true)

    fun loadMore() {
        val s = _state.value
        val cursor = s.nextCursor ?: return
        if (s.loading || s.loadingMore || moreJob?.isActive == true) return
        _state.update { it.copy(loadingMore = true) }
        val query = s.query
        moreJob = viewModelScope.launch {
            try {
                val page = api.getLensStaff(token, query.ifBlank { null }, cursor)
                if (_state.value.query != query) return@launch
                val seen = _state.value.items.mapNotNull { it.staffId }.toHashSet()
                val fresh = page.items.orEmpty().filter { it.staffId != null && seen.add(it.staffId) }
                _state.update {
                    it.copy(items = it.items + fresh, nextCursor = page.nextCursor, loadingMore = false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Keep what's shown; scrolling again retries this page.
                _state.update { it.copy(loadingMore = false) }
            }
        }
    }

    private fun loadFirstPage(debounceMs: Long, asRefresh: Boolean, silent: Boolean = false) {
        firstPageJob?.cancel()
        moreJob?.cancel()
        val query = _state.value.query
        _state.update {
            it.copy(
                loading = !asRefresh,
                refreshing = asRefresh && !silent,
                loadingMore = false,
                error = null,
            )
        }
        firstPageJob = viewModelScope.launch {
            if (debounceMs > 0) delay(debounceMs)
            try {
                val page = api.getLensStaff(token, query.ifBlank { null }, null)
                if (_state.value.query != query) return@launch
                val items = page.items.orEmpty().filter { !it.staffId.isNullOrBlank() }.distinctBy { it.staffId }
                _state.update {
                    it.copy(
                        items = items,
                        nextCursor = page.nextCursor,
                        loading = false,
                        refreshing = false,
                        error = null,
                        generation = it.generation + 1,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val text = ApiErrors.noticeFor(e, "Couldn't load staff.").text
                _state.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        // Keep a loaded list on a failed refresh; only an empty
                        // screen turns into the error state.
                        error = if (it.items.isEmpty()) text else null,
                    )
                }
                if (_state.value.items.isNotEmpty() && !silent) {
                    com.manjugroups.m_connect.ui.common.Floater.error(text)
                }
            }
        }
    }
}

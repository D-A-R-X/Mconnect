package com.manjugroups.m_connect.ui.hr

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.manjugroups.m_connect.network.ApiService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class StaffItem(
    val id: String,
    val name: String,
    val phone: String,
    val role: String,
    val designation: String,
    val status: String
)

sealed interface HrStaffUiState {
    data object Loading : HrStaffUiState
    data class Loaded(
        val staff: List<StaffItem>,
        val hasMore: Boolean = false,
        val isLoadingMore: Boolean = false,
        val searchQuery: String = ""
    ) : HrStaffUiState
    data class Error(val message: String) : HrStaffUiState
}

class HrStaffViewModel : ViewModel() {

    private val api = ApiService.create()
    private val _uiState = MutableStateFlow<HrStaffUiState>(HrStaffUiState.Loading)
    val uiState: StateFlow<HrStaffUiState> = _uiState.asStateFlow()

    private var allStaff: MutableList<StaffItem> = mutableListOf()
    private var cursor: String? = null
    private var isDone = false
    private var isSearching = false

    fun loadStaff(bearerToken: String) {
        allStaff.clear()
        cursor = null
        isDone = false
        isSearching = false
        _uiState.value = HrStaffUiState.Loading

        viewModelScope.launch {
            try {
                val response = api.getStaffPaginated(bearerToken, numItems = 25)
                if (response.success) {
                    allStaff = response.page.map { s ->
                        StaffItem(
                            id = s.id ?: "",
                            name = s.name ?: "",
                            phone = s.phone ?: "",
                            role = s.role ?: "",
                            designation = s.designation ?: "",
                            status = s.status ?: "active"
                        )
                    }.toMutableList()
                    cursor = response.continueCursor
                    isDone = response.isDone
                    _uiState.value = HrStaffUiState.Loaded(
                        staff = allStaff.toList(),
                        hasMore = !isDone
                    )
                } else {
                    _uiState.value = HrStaffUiState.Error("Failed to load staff")
                }
            } catch (e: Exception) {
                _uiState.value = HrStaffUiState.Error(e.message ?: "Network error")
            }
        }
    }

    fun loadMore(bearerToken: String) {
        if (isDone || cursor == null || isSearching) return
        val current = (_uiState.value as? HrStaffUiState.Loaded) ?: return
        _uiState.value = current.copy(isLoadingMore = true)

        viewModelScope.launch {
            try {
                val response = api.getStaffPaginated(bearerToken, numItems = 25, cursor = cursor)
                if (response.success) {
                    val newItems = response.page.map { s ->
                        StaffItem(
                            id = s.id ?: "",
                            name = s.name ?: "",
                            phone = s.phone ?: "",
                            role = s.role ?: "",
                            designation = s.designation ?: "",
                            status = s.status ?: "active"
                        )
                    }
                    allStaff.addAll(newItems)
                    cursor = response.continueCursor
                    isDone = response.isDone
                    _uiState.value = HrStaffUiState.Loaded(
                        staff = allStaff.toList(),
                        hasMore = !isDone
                    )
                }
            } catch (_: Exception) {
                _uiState.value = current.copy(isLoadingMore = false)
            }
        }
    }

    fun searchStaff(bearerToken: String, query: String) {
        if (query.isBlank()) {
            isSearching = false
            _uiState.value = HrStaffUiState.Loaded(
                staff = allStaff.toList(),
                hasMore = !isDone,
                searchQuery = ""
            )
            return
        }
        isSearching = true
        viewModelScope.launch {
            try {
                val response = api.searchStaff(bearerToken, query)
                if (response.success) {
                    val results = response.staff.map { s ->
                        StaffItem(
                            id = s.id ?: "",
                            name = s.name ?: "",
                            phone = s.phone ?: "",
                            role = s.role ?: "",
                            designation = s.designation ?: "",
                            status = s.status ?: "active"
                        )
                    }
                    _uiState.value = HrStaffUiState.Loaded(
                        staff = results,
                        hasMore = false,
                        searchQuery = query
                    )
                }
            } catch (_: Exception) { }
        }
    }
}

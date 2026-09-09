package com.manjugroups.m_connect.ui.hr

internal data class SecurityListPresentation(
    val showSkeleton: Boolean,
    val showRows: Boolean,
    val showLoadingMore: Boolean,
    val showLoadMoreRetry: Boolean,
    val showError: Boolean,
    val showEmpty: Boolean,
)

internal fun securityListPresentation(
    isLoading: Boolean,
    hasRows: Boolean,
    loadFailed: Boolean,
    loadMoreFailed: Boolean,
    dataComplete: Boolean,
): SecurityListPresentation = SecurityListPresentation(
    showSkeleton = isLoading && !hasRows,
    showRows = hasRows,
    showLoadingMore = isLoading && hasRows,
    showLoadMoreRetry = loadMoreFailed && !isLoading && hasRows,
    showError = loadFailed && !isLoading && !hasRows,
    showEmpty = dataComplete && !isLoading && !loadFailed && !hasRows,
)

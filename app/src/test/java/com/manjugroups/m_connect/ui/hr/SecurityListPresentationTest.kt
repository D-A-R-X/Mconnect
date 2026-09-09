package com.manjugroups.m_connect.ui.hr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityListPresentationTest {

    @Test
    fun `first load shows only skeleton`() {
        val state = securityListPresentation(
            isLoading = true,
            hasRows = false,
            loadFailed = false,
            loadMoreFailed = false,
            dataComplete = false,
        )

        assertTrue(state.showSkeleton)
        assertFalse(state.showEmpty)
        assertFalse(state.showError)
        assertFalse(state.showRows)
    }

    @Test
    fun `refresh clears previously visible empty state`() {
        val empty = securityListPresentation(false, false, false, false, true)
        val refreshing = securityListPresentation(true, false, false, false, true)

        assertTrue(empty.showEmpty)
        assertTrue(refreshing.showSkeleton)
        assertFalse(refreshing.showEmpty)
    }

    @Test
    fun `empty state waits until the directory is complete`() {
        val betweenPages = securityListPresentation(false, false, false, false, false)
        val complete = securityListPresentation(false, false, false, false, true)

        assertFalse(betweenPages.showEmpty)
        assertTrue(complete.showEmpty)
    }

    @Test
    fun `later page loading keeps rows and uses spinner`() {
        val state = securityListPresentation(true, true, false, false, false)

        assertTrue(state.showRows)
        assertTrue(state.showLoadingMore)
        assertFalse(state.showSkeleton)
        assertFalse(state.showEmpty)
    }

    @Test
    fun `failed first load shows only retry error`() {
        val state = securityListPresentation(false, false, true, false, false)

        assertTrue(state.showError)
        assertFalse(state.showSkeleton)
        assertFalse(state.showEmpty)
    }
}

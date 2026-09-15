package com.manjugroups.m_connect.ui.marketing

import com.manjugroups.m_connect.network.TodayVisit

/**
 * Decides when a CP list row's view can be reused.
 *
 * The list is a LinearLayout of inflated cards, not a RecyclerView, so every
 * rebuilt row is a real `inflate()` on the main thread. The cache used to be an
 * IdentityHashMap invalidated whenever `allVisits` was replaced — and loading
 * another page replaces it, because the append does
 * `(allVisits + incoming).distinctBy{}.sortedWith{}`. So every scroll that
 * fetched a page threw away EVERY built row and re-inflated the whole grown
 * window on the main thread: 20 cards, then 40, then 60. On a mid-range phone
 * with a few hundred CPs that is a multi-second main-thread stall, which
 * presents as the screen freezing and then dying while scrolling.
 *
 * Keying by the visit's stable id instead fixes that: a new page keeps every
 * row it already built. Reuse is still value-checked, so a row whose data
 * actually changed (status flips after an action, a name arrives) is rebuilt —
 * `TodayVisit` is a data class, so `==` compares content.
 */
internal object CpRowCachePolicy {

    /**
     * Stable identity for a row. Prefers the CP visit id; falls back to the
     * field-visit id, which is what a non-CP row carries.
     */
    fun keyOf(visit: TodayVisit): String =
        visit.clientPlaceVisitId?.trim()?.takeIf { it.isNotEmpty() } ?: visit.id

    /**
     * True when a cached row can be shown as-is.
     *
     * A null [cached] means nothing was built for this key yet. Anything else
     * is compared by value, so a stale card can never survive a real change.
     */
    fun canReuse(cached: TodayVisit?, current: TodayVisit): Boolean =
        cached != null && cached == current

    /**
     * Keys worth keeping after a render — everything currently matched by the
     * active filter and search.
     *
     * Without this the cache would only ever grow: switch filters a few times
     * on a large account and it holds a view for every row ever displayed.
     */
    fun liveKeys(matched: List<TodayVisit>): Set<String> =
        matched.mapTo(HashSet(matched.size)) { keyOf(it) }
}

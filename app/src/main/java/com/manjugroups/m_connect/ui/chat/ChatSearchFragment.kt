package com.manjugroups.m_connect.ui.chat

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.manjugroups.m_connect.MainActivity
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.ChatSearchMessage
import com.manjugroups.m_connect.ui.common.SkeletonUtils
import com.manjugroups.m_connect.ui.common.navigateUp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Search messages within a channel or DM. Calls
 * GET /api/chat/messages/search?channelId|conversationId&q=
 *
 * Debounced (250ms) on every keystroke; 2-character minimum.
 */
class ChatSearchFragment : Fragment() {

    private val api = ApiService.create()
    private lateinit var session: SessionManager
    private var searchJob: Job? = null

    private var channelId: String? = null
    private var conversationId: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_chat_search, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())
        channelId = arguments?.getString(ARG_CHANNEL_ID)
        conversationId = arguments?.getString(ARG_CONVERSATION_ID)

        view.findViewById<View>(R.id.btnSearchBack).setOnClickListener {
            navigateUp()
        }

        val etSearch = view.findViewById<EditText>(R.id.etSearch)
        val hint = view.findViewById<TextView>(R.id.tvSearchHint)
        val list = view.findViewById<LinearLayout>(R.id.searchResultsContainer)

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: Editable?) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString().orEmpty().trim()
                searchJob?.cancel()
                if (query.length < 2) {
                    hint.text = "Type at least 2 characters"
                    hint.visibility = View.VISIBLE
                    list.removeAllViews()
                    return
                }
                searchJob = viewLifecycleOwner.lifecycleScope.launch {
                    delay(250L)
                    runSearch(query, hint, list)
                }
            }
        })
        etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val q = etSearch.text.toString().trim()
                if (q.length >= 2) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        runSearch(q, hint, list)
                    }
                }
                true
            } else false
        }
        etSearch.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        (activity as? MainActivity)?.setTabBarVisible(false)
    }

    override fun onPause() {
        (activity as? MainActivity)?.setTabBarVisible(true)
        super.onPause()
    }

    override fun onDestroyView() {
        SkeletonUtils.stopAll()
        super.onDestroyView()
    }

    private suspend fun runSearch(
        query: String,
        hint: TextView,
        list: LinearLayout
    ) {
        // Detached (Back pressed / view torn down mid-search) → bail.
        val skeletonContainer = (view ?: return).findViewById<View>(R.id.skeletonContainer)
        hint.visibility = View.GONE
        SkeletonUtils.startSkeletonPulse(skeletonContainer)
        list.removeAllViews()
        try {
            val resp = api.searchMessages(
                session.bearerToken,
                query = query,
                channelId = channelId,
                conversationId = conversationId
            )
            SkeletonUtils.stopSkeletonPulse(skeletonContainer)
            if (!resp.success) {
                context?.let {
                    Toast.makeText(it, resp.error ?: "Search failed", Toast.LENGTH_SHORT).show()
                }
                return
            }
            if (resp.messages.isEmpty()) {
                hint.text = "No results for \"$query\""
                hint.visibility = View.VISIBLE
                return
            }
            val timeFmt = SimpleDateFormat("d MMM, h:mm a", Locale.getDefault())
            for (msg in resp.messages) {
                val row = layoutInflater.inflate(R.layout.item_chat_search_result, list, false)
                row.findViewById<TextView>(R.id.tvResultSender).text =
                    msg.senderName?.takeIf { it.isNotBlank() } ?: "Unknown"
                row.findViewById<TextView>(R.id.tvResultTime).text =
                    msg.sentAt?.let { timeFmt.format(Date(it.toLong())) } ?: ""
                row.findViewById<TextView>(R.id.tvResultBody).text =
                    msg.body?.takeIf { it.isNotBlank() } ?: ""
                list.addView(row)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            SkeletonUtils.stopSkeletonPulse(skeletonContainer)
            context?.let {
                Toast.makeText(it, "Network error: ${e.message ?: "unknown"}", Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        private const val ARG_CHANNEL_ID = "channelId"
        private const val ARG_CONVERSATION_ID = "conversationId"
        private const val ARG_TITLE = "title"

        fun newInstance(
            channelId: String?,
            conversationId: String?,
            title: String,
        ): ChatSearchFragment = ChatSearchFragment().apply {
            arguments = Bundle().apply {
                if (channelId != null) putString(ARG_CHANNEL_ID, channelId)
                if (conversationId != null) putString(ARG_CONVERSATION_ID, conversationId)
                putString(ARG_TITLE, title)
            }
        }
    }
}

package com.manjugroups.m_connect.ui.chat

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.manjugroups.m_connect.MainActivity
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.ChatAttachmentItem
import com.manjugroups.m_connect.ui.common.SkeletonUtils
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lists all attachments shared in a channel or DM. Hits
 * GET /api/chat/messages/attachments. Tapping a row opens the file URL
 * in the default browser/handler (works for images, PDFs, links).
 */
class ChatMediaFragment : Fragment() {

    private val api = ApiService.create()
    private lateinit var session: SessionManager

    private var channelId: String? = null
    private var conversationId: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_chat_media, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())
        channelId = arguments?.getString(ARG_CHANNEL_ID)
        conversationId = arguments?.getString(ARG_CONVERSATION_ID)

        view.findViewById<View>(R.id.btnMediaBack).setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        loadAttachments(view)
    }

    override fun onResume() {
        super.onResume()
        (activity as? MainActivity)?.setTabBarVisible(false)
    }

    override fun onPause() {
        (activity as? MainActivity)?.setTabBarVisible(true)
        super.onPause()
    }

    private fun loadAttachments(root: View) {
        val skeletonContainer = root.findViewById<View>(R.id.skeletonContainer)
        SkeletonUtils.startSkeletonPulse(skeletonContainer)
        val loading = root.findViewById<View>(R.id.mediaLoading)
        val empty = root.findViewById<TextView>(R.id.tvMediaEmpty)
        val list = root.findViewById<LinearLayout>(R.id.mediaList)
        loading.visibility = View.VISIBLE
        empty.visibility = View.GONE
        list.removeAllViews()

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = api.listChatAttachments(
                    session.bearerToken,
                    channelId = channelId,
                    conversationId = conversationId
                )
                loading.visibility = View.GONE
                SkeletonUtils.stopSkeletonPulse(skeletonContainer)
                if (!resp.success) {
                    Toast.makeText(
                        requireContext(),
                        resp.error ?: "Failed to load media",
                        Toast.LENGTH_SHORT
                    ).show()
                    empty.visibility = View.VISIBLE
                    return@launch
                }
                val flat = resp.messages.flatMap { msg ->
                    msg.attachments.map { att ->
                        Triple(att, msg.senderName, msg.sentAt)
                    }
                }
                if (flat.isEmpty()) {
                    empty.visibility = View.VISIBLE
                    return@launch
                }
                val timeFmt = SimpleDateFormat("d MMM, h:mm a", Locale.getDefault())
                for ((att, senderName, sentAt) in flat) {
                    val row = layoutInflater.inflate(R.layout.item_chat_media, list, false)
                    row.findViewById<TextView>(R.id.tvFileTypeBadge).text =
                        badgeFor(att.fileType, att.fileName)
                    row.findViewById<TextView>(R.id.tvFileName).text =
                        att.fileName?.takeIf { it.isNotBlank() } ?: "File"
                    val sizeText = formatBytes(att.fileSize)
                    val whenText = sentAt?.let { timeFmt.format(Date(it.toLong())) } ?: ""
                    val byText = senderName?.takeIf { it.isNotBlank() } ?: "Unknown"
                    row.findViewById<TextView>(R.id.tvFileMeta).text =
                        "$byText  •  $sizeText  •  $whenText"
                    row.setOnClickListener { openAttachment(att) }
                    list.addView(row)
                }
            } catch (e: Exception) {
                loading.visibility = View.GONE
                SkeletonUtils.stopSkeletonPulse(skeletonContainer)
                Toast.makeText(
                    requireContext(),
                    "Network error: ${e.message ?: "unknown"}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun openAttachment(att: ChatAttachmentItem) {
        val uri = att.url?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
        if (uri == null) {
            Toast.makeText(requireContext(), "Attachment URL unavailable", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(
                requireContext(),
                "No app available to open this file",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun badgeFor(fileType: String?, fileName: String?): String {
        val mime = fileType?.lowercase(Locale.US).orEmpty()
        if (mime.startsWith("image/")) return "IMG"
        if (mime.startsWith("video/")) return "VID"
        if (mime.startsWith("audio/")) return "AUD"
        val ext = fileName
            ?.substringAfterLast('.', "")
            ?.takeIf { it.isNotBlank() && it.length <= 5 }
            ?.uppercase(Locale.US)
        return ext ?: "FILE"
    }

    private fun formatBytes(bytes: Long?): String {
        if (bytes == null || bytes <= 0) return "—"
        val units = listOf("B", "KB", "MB", "GB")
        var v = bytes.toDouble()
        var i = 0
        while (v >= 1024 && i < units.lastIndex) {
            v /= 1024
            i++
        }
        return "%.${if (i == 0) 0 else 1}f %s".format(v, units[i])
    }

    companion object {
        private const val ARG_CHANNEL_ID = "channelId"
        private const val ARG_CONVERSATION_ID = "conversationId"
        private const val ARG_TITLE = "title"

        fun newInstance(
            channelId: String?,
            conversationId: String?,
            title: String,
        ): ChatMediaFragment = ChatMediaFragment().apply {
            arguments = Bundle().apply {
                if (channelId != null) putString(ARG_CHANNEL_ID, channelId)
                if (conversationId != null) putString(ARG_CONVERSATION_ID, conversationId)
                putString(ARG_TITLE, title)
            }
        }
    }
}

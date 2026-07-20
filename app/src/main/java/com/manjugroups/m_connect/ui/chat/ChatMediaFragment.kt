package com.manjugroups.m_connect.ui.chat

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.manjugroups.m_connect.MainActivity
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.ui.common.SkeletonUtils
import com.manjugroups.m_connect.ui.common.navigateUp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.manjugroups.m_connect.ui.common.commitOnce

/**
 * Lists all attachments and links shared in a channel or DM. Hits the message list endpoint
 * to extract images, videos, files, and URLs directly from conversation message history.
 */
class ChatMediaFragment : Fragment() {

    private val api = ApiService.create()
    private lateinit var session: SessionManager

    private var channelId: String? = null
    private var conversationId: String? = null

    // UI elements
    private lateinit var tvMediaHeaderTitle: TextView
    private lateinit var tvMediaHeaderSubtitle: TextView
    private lateinit var btnMediaBack: View
    private lateinit var btnMediaSearch: View

    private lateinit var btnTabMedia: TextView
    private lateinit var btnTabLinks: TextView
    private lateinit var btnTabDocs: TextView

    private lateinit var mediaScrollView: View
    private lateinit var sectionMedia: View
    private lateinit var rvMediaHorizontal: RecyclerView
    private lateinit var btnSeeAllMedia: TextView
    
    private lateinit var sectionLinks: View
    private lateinit var listLinks: LinearLayout
    private lateinit var btnSeeAllLinks: TextView
    
    private lateinit var sectionDocs: View
    private lateinit var listDocs: LinearLayout
    private lateinit var btnSeeAllDocs: TextView

    private lateinit var fullViewContainer: View
    private lateinit var rvFullList: RecyclerView
    private lateinit var tvMediaEmpty: TextView

    // Lists holding data
    private val mediaItems = mutableListOf<MediaItem>()
    private val linkItems = mutableListOf<LinkItem>()
    private val docItems = mutableListOf<DocItem>()

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

        // Find Header Views
        tvMediaHeaderTitle = view.findViewById(R.id.tvMediaHeaderTitle)
        tvMediaHeaderSubtitle = view.findViewById(R.id.tvMediaHeaderSubtitle)
        btnMediaBack = view.findViewById(R.id.btnMediaBack)
        btnMediaSearch = view.findViewById(R.id.btnMediaSearch)

        // Set dynamic title & subtitle passed from Contact Info page
        val title = arguments?.getString(ARG_TITLE) ?: "Media, Links & Docs"
        val subtitle = arguments?.getString(ARG_SUBTITLE) ?: ""
        tvMediaHeaderTitle.text = title
        if (subtitle.isNotBlank()) {
            tvMediaHeaderSubtitle.text = subtitle
            tvMediaHeaderSubtitle.visibility = View.VISIBLE
        } else {
            tvMediaHeaderSubtitle.visibility = View.GONE
        }

        // Back button action
        btnMediaBack.setOnClickListener {
            navigateUp()
        }

        // Search button action (navigates to ChatSearchFragment)
        btnMediaSearch.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(
                    R.id.fragmentContainer,
                    ChatSearchFragment.newInstance(channelId, conversationId, title)
                )
                .addToBackStack(null)
                .commitOnce()
        }

        // Tab Views
        btnTabMedia = view.findViewById(R.id.btnTabMedia)
        btnTabLinks = view.findViewById(R.id.btnTabLinks)
        btnTabDocs = view.findViewById(R.id.btnTabDocs)

        // Content Scroll and lists
        mediaScrollView = view.findViewById(R.id.mediaScrollView)
        
        sectionMedia = view.findViewById(R.id.sectionMedia)
        rvMediaHorizontal = view.findViewById(R.id.rvMediaHorizontal)
        btnSeeAllMedia = view.findViewById(R.id.btnSeeAllMedia)
        
        sectionLinks = view.findViewById(R.id.sectionLinks)
        listLinks = view.findViewById(R.id.listLinks)
        btnSeeAllLinks = view.findViewById(R.id.btnSeeAllLinks)
        
        sectionDocs = view.findViewById(R.id.sectionDocs)
        listDocs = view.findViewById(R.id.listDocs)
        btnSeeAllDocs = view.findViewById(R.id.btnSeeAllDocs)

        fullViewContainer = view.findViewById(R.id.fullViewContainer)
        rvFullList = view.findViewById(R.id.rvFullList)
        tvMediaEmpty = view.findViewById(R.id.tvMediaEmpty)

        // Set up tab click listeners
        btnTabMedia.setOnClickListener { selectTab("Media") }
        btnTabLinks.setOnClickListener { selectTab("Links") }
        btnTabDocs.setOnClickListener { selectTab("Docs") }

        // Setup See All listeners
        btnSeeAllMedia.setOnClickListener { showFullMedia() }
        btnSeeAllLinks.setOnClickListener { selectTab("Links") }
        btnSeeAllDocs.setOnClickListener { selectTab("Docs") }

        loadAttachments(view)
    }

    override fun onResume() {
        super.onResume()
        (activity as? MainActivity)?.setTabBarVisible(false)
    }
    // No onPause restore — MainActivity's backstack listener owns the tab
    // bar on pop; restoring it here raced the incoming screen's hide.

    override fun onDestroyView() {
        SkeletonUtils.stopAll()
        super.onDestroyView()
    }

    private fun loadAttachments(root: View) {
        val skeletonContainer = root.findViewById<View>(R.id.skeletonContainer)
        SkeletonUtils.startSkeletonPulse(skeletonContainer)

        viewLifecycleOwner.lifecycleScope.launch {
            val parsedMedia = mutableListOf<MediaItem>()
            val parsedLinks = mutableListOf<LinkItem>()
            val parsedDocs = mutableListOf<DocItem>()

            try {
                // 1. Fetch message history to extract both attachments and text links
                val messagesResp = if (channelId != null) {
                    api.getChannelMessages(session.bearerToken, channelId!!, numItems = 150)
                } else if (conversationId != null) {
                    api.getConversationMessages(session.bearerToken, conversationId!!, numItems = 150)
                } else {
                    null
                }

                if (messagesResp?.success == true) {
                    val page = messagesResp.page ?: messagesResp.messages ?: emptyList()
                    val timeFmt = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())

                    for (msg in page) {
                        // Skip deleted messages
                        if (msg.isDeleted == true) continue

                        // Extract file attachments
                        msg.attachments?.forEach { att ->
                            val mime = att.fileType?.lowercase(Locale.US).orEmpty()
                            val isMedia = mime.startsWith("image/") || mime.startsWith("video/")
                            
                            // Resolve the storage URL dynamically if the direct url is blank
                            val resolvedUrl = att.url?.takeIf { it.isNotBlank() } ?: runCatching {
                                val storageId = att.storageId
                                if (storageId != null) {
                                    api.getStorageUrl(session.bearerToken, storageId).url
                                } else null
                            }.getOrNull()

                            if (!resolvedUrl.isNullOrBlank()) {
                                if (isMedia) {
                                    val msgTimeMs = msg.creationTime?.let {
                                        if (it < 10000000000.0) (it * 1000).toLong() else it.toLong()
                                    }
                                    parsedMedia.add(
                                        MediaItem(
                                            id = att.id ?: att.storageId ?: "",
                                            url = resolvedUrl,
                                            isVideo = mime.startsWith("video/"),
                                            duration = if (mime.startsWith("video/")) "0:05" else null,
                                            senderName = msg.senderName,
                                            sentAt = msgTimeMs
                                        )
                                    )
                                } else {
                                    val msgTimeMs = msg.creationTime?.let {
                                        if (it < 10000000000.0) (it * 1000).toLong() else it.toLong()
                                    }
                                    val dateStr = msgTimeMs?.let { timeFmt.format(Date(it)) } ?: ""
                                    val ext = att.fileName?.substringAfterLast('.', "")?.uppercase(Locale.US) ?: "FILE"
                                    parsedDocs.add(
                                        DocItem(
                                            title = att.fileName ?: "File",
                                            fileType = ext,
                                            fileSize = formatBytes(att.fileSize),
                                            date = dateStr,
                                            url = resolvedUrl
                                        )
                                    )
                                }
                            }
                        }

                        // Extract text links from message body
                        val urls = extractUrls(msg.body)
                        val msgTimeMs = msg.creationTime?.let {
                            if (it < 10000000000.0) (it * 1000).toLong() else it.toLong()
                        }
                        val dateStr = msgTimeMs?.let { timeFmt.format(Date(it)) } ?: ""
                        urls.forEach { url ->
                            val uri = Uri.parse(url)
                            val host = uri.host?.removePrefix("www.") ?: "Link"
                            val title = when {
                                host.contains("figma", ignoreCase = true) -> "Figma Link"
                                host.contains("google", ignoreCase = true) -> "Google Link"
                                else -> host.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
                            }
                            parsedLinks.add(LinkItem(title, url, dateStr))
                        }
                    }
                }
            } catch (e: Exception) {
                // Gracefully ignore loading failures and show empty state
            } finally {
                SkeletonUtils.stopSkeletonPulse(skeletonContainer)

                // Populate final lists
                mediaItems.clear()
                mediaItems.addAll(parsedMedia)

                linkItems.clear()
                linkItems.addAll(parsedLinks)

                docItems.clear()
                docItems.addAll(parsedDocs)

                // Setup views
                setupOverviewViews()
            }
        }
    }

    private fun setupOverviewViews() {
        // Horizontal list
        rvMediaHorizontal.adapter = MediaHorizontalAdapter(mediaItems) { item ->
            openUrl(item.url)
        }

        // Render Links & Docs
        renderOverviewPreviews()

        // Handle sections visibility based on presence of items
        sectionMedia.visibility = if (mediaItems.isEmpty()) View.GONE else View.VISIBLE
        sectionLinks.visibility = if (linkItems.isEmpty()) View.GONE else View.VISIBLE
        sectionDocs.visibility = if (docItems.isEmpty()) View.GONE else View.VISIBLE

        // Check empty state (if all sections are empty)
        val isEmpty = mediaItems.isEmpty() && linkItems.isEmpty() && docItems.isEmpty()
        tvMediaEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE

        // Default tab styling
        selectTab("Media")
    }

    private fun renderOverviewPreviews() {
        listLinks.removeAllViews()
        val topLinks = linkItems.take(3)
        for (item in topLinks) {
            val row = layoutInflater.inflate(R.layout.item_chat_link, listLinks, false)
            row.findViewById<TextView>(R.id.tvLinkTitle).text = item.title
            row.findViewById<TextView>(R.id.tvLinkUrl).text = item.url
            row.findViewById<TextView>(R.id.tvLinkDate).text = item.date
            row.setOnClickListener { openUrl(item.url) }
            listLinks.addView(row)
        }

        listDocs.removeAllViews()
        val topDocs = docItems.take(3)
        for (item in topDocs) {
            val row = layoutInflater.inflate(R.layout.item_chat_doc, listDocs, false)
            row.findViewById<TextView>(R.id.tvDocTitle).text = item.title
            row.findViewById<TextView>(R.id.tvDocMeta).text = "${item.fileType} • ${item.fileSize}"
            row.findViewById<TextView>(R.id.tvDocDate).text = item.date

            val iconBg = row.findViewById<FrameLayout>(R.id.iconBackground)
            val docIcon = row.findViewById<ImageView>(R.id.ivDocIcon)
            when (item.fileType.uppercase(Locale.US)) {
                "PDF" -> {
                    iconBg.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FFECEB"))
                    docIcon.imageTintList = ColorStateList.valueOf(Color.parseColor("#F04438"))
                }
                "DOC", "DOCX" -> {
                    iconBg.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#EFF8FF"))
                    docIcon.imageTintList = ColorStateList.valueOf(Color.parseColor("#175CD3"))
                }
                "XLS", "XLSX" -> {
                    iconBg.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#ECFDF3"))
                    docIcon.imageTintList = ColorStateList.valueOf(Color.parseColor("#027A48"))
                }
                else -> {
                    iconBg.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#F2F4F7"))
                    docIcon.imageTintList = ColorStateList.valueOf(Color.parseColor("#667085"))
                }
            }

            row.setOnClickListener { item.url?.let { openUrl(it) } }
            listDocs.addView(row)
        }
    }

    private fun selectTab(tab: String) {
        val activeBg = ContextCompat.getDrawable(requireContext(), R.drawable.bg_welcome_button_chip)
        val activeTint = ColorStateList.valueOf(Color.WHITE)
        val inactiveBg = null

        btnTabMedia.background = if (tab == "Media") activeBg else inactiveBg
        btnTabMedia.backgroundTintList = if (tab == "Media") activeTint else null
        btnTabMedia.elevation = if (tab == "Media") dpToPx(1f) else 0f
        btnTabMedia.setTextColor(if (tab == "Media") Color.parseColor("#101828") else Color.parseColor("#667085"))
        btnTabMedia.typeface = ResourcesCompat.getFont(requireContext(), if (tab == "Media") R.font.inter_semibold else R.font.inter_medium)

        btnTabLinks.background = if (tab == "Links") activeBg else inactiveBg
        btnTabLinks.backgroundTintList = if (tab == "Links") activeTint else null
        btnTabLinks.elevation = if (tab == "Links") dpToPx(1f) else 0f
        btnTabLinks.setTextColor(if (tab == "Links") Color.parseColor("#101828") else Color.parseColor("#667085"))
        btnTabLinks.typeface = ResourcesCompat.getFont(requireContext(), if (tab == "Links") R.font.inter_semibold else R.font.inter_medium)

        btnTabDocs.background = if (tab == "Docs") activeBg else inactiveBg
        btnTabDocs.backgroundTintList = if (tab == "Docs") activeTint else null
        btnTabDocs.elevation = if (tab == "Docs") dpToPx(1f) else 0f
        btnTabDocs.setTextColor(if (tab == "Docs") Color.parseColor("#101828") else Color.parseColor("#667085"))
        btnTabDocs.typeface = ResourcesCompat.getFont(requireContext(), if (tab == "Docs") R.font.inter_semibold else R.font.inter_medium)

        if (tab == "Media") {
            mediaScrollView.visibility = View.VISIBLE
            fullViewContainer.visibility = View.GONE
            val isEmpty = mediaItems.isEmpty() && linkItems.isEmpty() && docItems.isEmpty()
            tvMediaEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
        } else if (tab == "Links") {
            showFullLinks()
        } else if (tab == "Docs") {
            showFullDocs()
        }
    }

    private fun showFullMedia() {
        mediaScrollView.visibility = View.GONE
        fullViewContainer.visibility = View.VISIBLE
        
        val isEmpty = mediaItems.isEmpty()
        tvMediaEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
        rvFullList.visibility = if (isEmpty) View.GONE else View.VISIBLE
        
        if (!isEmpty) {
            rvFullList.layoutManager = GridLayoutManager(requireContext(), 3)
            rvFullList.adapter = MediaGridAdapter(mediaItems) { item ->
                openUrl(item.url)
            }
        }
    }

    private fun showFullLinks() {
        mediaScrollView.visibility = View.GONE
        fullViewContainer.visibility = View.VISIBLE
        
        val isEmpty = linkItems.isEmpty()
        tvMediaEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
        rvFullList.visibility = if (isEmpty) View.GONE else View.VISIBLE
        
        if (!isEmpty) {
            rvFullList.layoutManager = LinearLayoutManager(requireContext())
            rvFullList.adapter = LinksAdapter(linkItems) { item ->
                openUrl(item.url)
            }
        }
    }

    private fun showFullDocs() {
        mediaScrollView.visibility = View.GONE
        fullViewContainer.visibility = View.VISIBLE
        
        val isEmpty = docItems.isEmpty()
        tvMediaEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
        rvFullList.visibility = if (isEmpty) View.GONE else View.VISIBLE
        
        if (!isEmpty) {
            rvFullList.layoutManager = LinearLayoutManager(requireContext())
            rvFullList.adapter = DocsAdapter(docItems) { item ->
                item.url?.let { openUrl(it) }
            }
        }
    }

    private fun openUrl(url: String) {
        val uri = url.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
        if (uri == null) {
            Toast.makeText(requireContext(), "URL unavailable", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(
                requireContext(),
                "No app available to open this link",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun extractUrls(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        val urlPattern = Regex("https?://[\\w\\-_]+(?:\\.[\\w\\-_]+)+(?:[\\w\\-\\.,@?^=%&:/~\\+#]*[\\w\\-\\@?^=%&/~\\+#])?")
        return urlPattern.findAll(text).map { it.value }.toList()
    }

    private fun dpToPx(dp: Float): Float {
        return dp * requireContext().resources.displayMetrics.density
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

    // Adapters definitions
    private inner class MediaHorizontalAdapter(
        private val items: List<MediaItem>,
        private val onItemClick: (MediaItem) -> Unit
    ) : RecyclerView.Adapter<MediaHorizontalAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val ivMediaThumbnail: ImageView = view.findViewById(R.id.ivMediaThumbnail)
            val videoBadge: View = view.findViewById(R.id.videoBadge)
            val tvVideoDuration: TextView = view.findViewById(R.id.tvVideoDuration)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_chat_media_horizontal, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.ivMediaThumbnail.load(item.url)
            if (item.isVideo) {
                holder.videoBadge.visibility = View.VISIBLE
                holder.tvVideoDuration.text = item.duration ?: "0:10"
            } else {
                holder.videoBadge.visibility = View.GONE
            }
            holder.itemView.setOnClickListener { onItemClick(item) }
        }

        override fun getItemCount(): Int = items.size
    }

    private inner class MediaGridAdapter(
        private val items: List<MediaItem>,
        private val onItemClick: (MediaItem) -> Unit
    ) : RecyclerView.Adapter<MediaGridAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val ivMediaThumbnail: ImageView = view.findViewById(R.id.ivMediaThumbnail)
            val videoBadge: View = view.findViewById(R.id.videoBadge)
            val tvVideoDuration: TextView = view.findViewById(R.id.tvVideoDuration)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_chat_media_horizontal, parent, false)
            val lp = view.layoutParams as ViewGroup.MarginLayoutParams
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT
            val density = parent.context.resources.displayMetrics.density
            lp.height = (110 * density).toInt()
            lp.rightMargin = (4 * density).toInt()
            lp.leftMargin = (4 * density).toInt()
            lp.topMargin = (4 * density).toInt()
            lp.bottomMargin = (4 * density).toInt()
            view.layoutParams = lp
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.ivMediaThumbnail.load(item.url)
            if (item.isVideo) {
                holder.videoBadge.visibility = View.VISIBLE
                holder.tvVideoDuration.text = item.duration ?: "0:10"
            } else {
                holder.videoBadge.visibility = View.GONE
            }
            holder.itemView.setOnClickListener { onItemClick(item) }
        }

        override fun getItemCount(): Int = items.size
    }

    private inner class LinksAdapter(
        private val items: List<LinkItem>,
        private val onItemClick: (LinkItem) -> Unit
    ) : RecyclerView.Adapter<LinksAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvTitle: TextView = view.findViewById(R.id.tvLinkTitle)
            val tvUrl: TextView = view.findViewById(R.id.tvLinkUrl)
            val tvDate: TextView = view.findViewById(R.id.tvLinkDate)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_chat_link, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.tvTitle.text = item.title
            holder.tvUrl.text = item.url
            holder.tvDate.text = item.date
            holder.itemView.setOnClickListener { onItemClick(item) }
        }

        override fun getItemCount(): Int = items.size
    }

    private inner class DocsAdapter(
        private val items: List<DocItem>,
        private val onItemClick: (DocItem) -> Unit
    ) : RecyclerView.Adapter<DocsAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvTitle: TextView = view.findViewById(R.id.tvDocTitle)
            val tvMeta: TextView = view.findViewById(R.id.tvDocMeta)
            val tvDate: TextView = view.findViewById(R.id.tvDocDate)
            val iconBg: FrameLayout = view.findViewById(R.id.iconBackground)
            val docIcon: ImageView = view.findViewById(R.id.ivDocIcon)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_chat_doc, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.tvTitle.text = item.title
            holder.tvMeta.text = "${item.fileType} • ${item.fileSize}"
            holder.tvDate.text = item.date

            when (item.fileType.uppercase(Locale.US)) {
                "PDF" -> {
                    holder.iconBg.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FFECEB"))
                    holder.docIcon.imageTintList = ColorStateList.valueOf(Color.parseColor("#F04438"))
                }
                "DOC", "DOCX" -> {
                    holder.iconBg.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#EFF8FF"))
                    holder.docIcon.imageTintList = ColorStateList.valueOf(Color.parseColor("#175CD3"))
                }
                "XLS", "XLSX" -> {
                    holder.iconBg.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#ECFDF3"))
                    holder.docIcon.imageTintList = ColorStateList.valueOf(Color.parseColor("#027A48"))
                }
                else -> {
                    holder.iconBg.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#F2F4F7"))
                    holder.docIcon.imageTintList = ColorStateList.valueOf(Color.parseColor("#667085"))
                }
            }

            holder.itemView.setOnClickListener { onItemClick(item) }
        }

        override fun getItemCount(): Int = items.size
    }

    // Data models
    data class MediaItem(
        val id: String,
        val url: String,
        val isVideo: Boolean,
        val duration: String? = null,
        val senderName: String? = null,
        val sentAt: Long? = null
    )

    data class LinkItem(
        val title: String,
        val url: String,
        val date: String
    )

    data class DocItem(
        val title: String,
        val fileType: String,
        val fileSize: String,
        val date: String,
        val url: String? = null
    )

    companion object {
        private const val ARG_CHANNEL_ID = "channelId"
        private const val ARG_CONVERSATION_ID = "conversationId"
        private const val ARG_TITLE = "title"
        private const val ARG_SUBTITLE = "subtitle"

        fun newInstance(
            channelId: String?,
            conversationId: String?,
            title: String,
            subtitle: String
        ): ChatMediaFragment = ChatMediaFragment().apply {
            arguments = Bundle().apply {
                if (channelId != null) putString(ARG_CHANNEL_ID, channelId)
                if (conversationId != null) putString(ARG_CONVERSATION_ID, conversationId)
                putString(ARG_TITLE, title)
                putString(ARG_SUBTITLE, subtitle)
            }
        }
    }
}

package com.manjugroups.m_connect.ui.layoutmap

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.fragment.app.Fragment
import com.manjugroups.m_connect.BuildConfig
import com.manjugroups.m_connect.MainActivity
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.databinding.FragmentLayoutMapBinding
import com.manjugroups.m_connect.ui.common.navigateUp
import androidx.lifecycle.lifecycleScope
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.ui.common.showOnce
import com.manjugroups.m_connect.ui.common.toastSafe
import com.manjugroups.m_connect.ui.home.CompleteCpVisitBottomSheet
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * One project's 3D layout map. The map itself is the web app's page
 * (/m/layout-map/<projectId>, no web shell) shown in a WebView, so plot status,
 * Hold and the look always match the web. Two things are native:
 *  - sign-in: the app's session token is handed to the page before it loads;
 *  - "Book plot": the page posts it to [Bridge] and the app's booking form opens.
 */
class LayoutMapFragment : Fragment() {

    private var _binding: FragmentLayoutMapBinding? = null
    private val binding get() = _binding!!

    private val projectId get() = requireArguments().getString(ARG_PROJECT_ID).orEmpty()
    private val projectName get() = requireArguments().getString(ARG_PROJECT_NAME).orEmpty()
    private val webBase = BuildConfig.APP_URL.trimEnd('/')

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLayoutMapBinding.inflate(inflater, container, false)
        return binding.root
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.tvLayoutMapTitle.text = projectName.ifBlank { "3D Layout Map" }
        binding.btnLayoutMapBack.setOnClickListener { navigateUp() }
        binding.btnLayoutMapRetry.setOnClickListener { loadMap() }

        binding.layoutMapWeb.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true // the web app keeps its session in localStorage
            setBackgroundColor(Color.parseColor("#F1F3F8"))
            addJavascriptInterface(Bridge(), "MconnectLayout")
            webViewClient = MapClient()
        }
        loadMap()
    }

    private fun loadMap() {
        val b = _binding ?: return
        val token = SessionManager(requireContext()).token
        if (token.isNullOrBlank()) return showOverlay("Sign in again to open the map.", retry = false)
        showOverlay("Loading 3D map…", loading = true)
        val target = "$webBase/m/layout-map/${Uri.encode(projectId)}?name=${Uri.encode(projectName)}"
        // A tiny page on the web app's own origin stores the session where the
        // web app reads it, then moves on to the map. The token never goes in a URL.
        val bootstrap = "<script>try{localStorage.setItem('manju_session_token'," +
            JSONObject.quote(token) + ")}catch(e){}location.replace(" + JSONObject.quote(target) + ")</script>"
        b.layoutMapWeb.loadDataWithBaseURL("$webBase/", bootstrap, "text/html", "utf-8", null)
    }

    private fun showOverlay(message: String, loading: Boolean = false, retry: Boolean = !loading) {
        val b = _binding ?: return
        b.layoutMapOverlay.visibility = View.VISIBLE
        b.layoutMapProgress.visibility = if (loading) View.VISIBLE else View.GONE
        b.tvLayoutMapOverlay.text = message
        b.btnLayoutMapRetry.visibility = if (retry) View.VISIBLE else View.GONE
    }

    private inner class MapClient : WebViewClient() {
        private var failed = false

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            failed = false
        }

        override fun onPageFinished(view: WebView, url: String?) {
            // The bootstrap page finishes first; only the map page lifts the cover.
            if (!failed && url?.contains("/m/layout-map/") == true) _binding?.layoutMapOverlay?.visibility = View.GONE
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame) return
            failed = true
            showOverlay("Couldn't load the map. Check your connection.")
        }

        /** Only the map page stays in here; directions, WhatsApp, calls and other links leave the app. */
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url
            if (url.toString().startsWith("$webBase/m/layout-map/")) return false
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, url)) }
            return true
        }
    }

    /** Messages from the map page (see staff-layout-map.tsx in the web repo). */
    private inner class Bridge {
        @JavascriptInterface
        fun postMessage(json: String) {
            val message = runCatching { JSONObject(json) }.getOrNull() ?: return
            if (message.optString("type") != "book") return
            val plotNo = message.optString("plotNo")
            if (plotNo.isBlank()) return
            // Called on a WebView thread; fragments are touched on the main thread.
            _binding?.root?.post { if (isAdded) bookPlot(plotNo) }
        }
    }

    /** The map knows a plot by its number; the booking form needs that plot's unit row. */
    private fun bookPlot(plotNo: String) {
        // Same normalisation the map uses, so "A-12" and "a 12" are one plot.
        fun key(s: String?) = s.orEmpty().uppercase().replace(Regex("[\\s\\-_]+"), "")
        val session = SessionManager(requireContext())
        viewLifecycleOwner.lifecycleScope.launch {
            val unit = runCatching { ApiService.create().listInventoryUnits(session.bearerToken, projectId) }.getOrNull()
                ?.units?.firstOrNull { key(it.unitNumber) == key(plotNo) }
            if (!isAdded) return@launch
            if (unit == null) return@launch toastSafe("Plot $plotNo is not set up for booking yet")
            CompleteCpVisitBottomSheet
                .forStandaloneBooking(projectId, projectName, unit.id, unit.unitNumber ?: plotNo)
                .showOnce(parentFragmentManager, "layout_map_booking")
        }
    }

    override fun onResume() {
        super.onResume()
        (activity as? MainActivity)?.let { main ->
            main.setTabBarVisible(false)
            main.setTopBarAppearance(Color.WHITE, darkStatusIcons = true)
        }
        _binding?.layoutMapWeb?.onResume()
    }

    override fun onPause() {
        _binding?.layoutMapWeb?.onPause()
        (activity as? MainActivity)?.setTabBarVisible(true)
        super.onPause()
    }

    override fun onDestroyView() {
        binding.layoutMapWeb.apply {
            removeJavascriptInterface("MconnectLayout")
            stopLoading()
            destroy()
        }
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val ARG_PROJECT_ID = "projectId"
        private const val ARG_PROJECT_NAME = "projectName"

        fun newInstance(projectId: String, projectName: String) = LayoutMapFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_PROJECT_ID, projectId)
                putString(ARG_PROJECT_NAME, projectName)
            }
        }
    }
}

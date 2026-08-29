package com.manjugroups.m_connect.ui.marketing

import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.Dash
import com.google.android.gms.maps.model.Gap
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PolylineOptions
import com.google.gson.Gson
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.network.CpApprovalItem
import com.manjugroups.m_connect.network.CpApprovalRouteData
import com.manjugroups.m_connect.network.GeoTrackApi
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Native trip detail for a pending CP completion, including its recorded GPS path. */
class CpApprovalTripDetailDialog : DialogFragment() {
    private val api = GeoTrackApi.create()
    private lateinit var session: SessionManager
    private lateinit var item: CpApprovalItem
    private lateinit var mapView: MapView
    private lateinit var mapStatus: TextView
    private lateinit var progress: ProgressBar

    private fun dp(value: Int) =
        (value * resources.displayMetrics.density).roundToInt()

    private fun font(res: Int): Typeface? =
        runCatching { ResourcesCompat.getFont(requireContext(), res) }.getOrNull()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        item = Gson().fromJson(requireArguments().getString(ARG_ITEM), CpApprovalItem::class.java)
        session = SessionManager(requireContext())
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        mapView = MapView(requireContext()).also { it.onCreate(savedInstanceState) }
        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(20))
            background = roundedBackground("#FFFFFF", 16)
        }
        content.addView(LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(requireContext()).apply {
                text = item.clientName ?: "CP trip details"
                textSize = 20f
                setTextColor(Color.parseColor("#101828"))
                typeface = font(R.font.inter_semibold) ?: typeface
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(requireContext()).apply {
                text = "×"
                gravity = Gravity.CENTER
                textSize = 24f
                setTextColor(Color.parseColor("#475467"))
                contentDescription = "Close"
                background = roundedBackground("#F2F4F7", 20)
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
                setOnClickListener { dismiss() }
            })
        })
        content.addView(TextView(requireContext()).apply {
            text = item.placeName ?: item.placeAddress ?: "Client place"
            textSize = 13f
            setTextColor(Color.parseColor("#667085"))
            typeface = font(R.font.inter_regular) ?: typeface
            setPadding(0, dp(4), 0, dp(14))
        })

        val facts = listOf(
            "Visit date" to (item.scheduledDate ?: "Not recorded"),
            "Scheduled time" to formatClock(item.scheduledTime),
            "Start time" to formatEpoch(item.startedAt),
            "End time" to formatEpoch(item.completedAt ?: item.requestedAt),
            "CP type" to friendlyCpType(item.cpType),
        )
        content.addView(LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = roundedBackground("#F8FAFC", 10)
            facts.forEach { (label, value) ->
                addView(LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(6), 0, dp(6))
                    addView(TextView(requireContext()).apply {
                        text = label
                        textSize = 12f
                        setTextColor(Color.parseColor("#667085"))
                        typeface = font(R.font.inter_regular) ?: typeface
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.42f)
                    })
                    addView(TextView(requireContext()).apply {
                        text = value
                        textSize = 13f
                        setTextColor(Color.parseColor("#101828"))
                        typeface = font(R.font.inter_semibold) ?: typeface
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.58f)
                    })
                })
            }
        })

        content.addView(TextView(requireContext()).apply {
            text = "Travelled path"
            textSize = 15f
            setTextColor(Color.parseColor("#101828"))
            typeface = font(R.font.inter_semibold) ?: typeface
            setPadding(0, dp(16), 0, dp(8))
        })
        content.addView(FrameLayout(requireContext()).apply {
            addView(mapView, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(230),
            ))
            progress = ProgressBar(requireContext()).apply {
                visibility = View.VISIBLE
            }
            addView(progress, FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER))
        })
        mapStatus = TextView(requireContext()).apply {
            text = "Loading recorded GPS trail…"
            textSize = 12f
            setTextColor(Color.parseColor("#667085"))
            typeface = font(R.font.inter_regular) ?: typeface
            setPadding(0, dp(8), 0, dp(8))
        }
        content.addView(mapStatus)

        val dialog = Dialog(requireContext())
        dialog.setContentView(ScrollView(requireContext()).apply { addView(content) })
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            dialog.window?.setLayout(
                (resources.displayMetrics.widthPixels * 0.92).roundToInt(),
                (resources.displayMetrics.heightPixels * 0.84).roundToInt(),
            )
        }
        loadRoute()
        return dialog
    }

    private fun loadRoute() {
        lifecycleScope.launch {
            val result = runCatching { api.getCpApprovalRoute(session.bearerToken, item.id) }
            if (!isAdded) return@launch
            progress.visibility = View.GONE
            result.onSuccess { response ->
                val data = response.data
                if (!response.success || data == null) {
                    showRouteFallback(response.error)
                } else {
                    renderRoute(data)
                }
            }.onFailure { error ->
                Log.w(TAG, "Unable to load detailed CP approval route for ${item.id}", error)
                showRouteFallback(error.message)
            }
        }
    }

    private fun fallbackRoute() = CpApprovalRouteData(
        id = item.id,
        startedAt = item.startedAt,
        endedAt = item.completedAt ?: item.requestedAt,
        startLat = item.startLat,
        startLng = item.startLng,
        endLat = item.endLat ?: item.completionLat ?: item.arrivalLat ?: item.placeLat,
        endLng = item.endLng ?: item.completionLng ?: item.arrivalLng ?: item.placeLng,
    )

    private fun showRouteFallback(error: String?) {
        val fallback = fallbackRoute()
        val hasFallback = coordinates(fallback.startLat, fallback.startLng) != null ||
            coordinates(fallback.endLat, fallback.endLng) != null
        if (hasFallback) {
            renderRoute(fallback)
        } else {
            mapView.visibility = View.GONE
            mapStatus.text = "No GPS coordinates were recorded for this trip."
        }
        if (!error.isNullOrBlank()) {
            Log.w(TAG, "Detailed CP route unavailable: $error")
        }
    }

    private fun renderRoute(data: CpApprovalRouteData) {
        mapView.visibility = View.VISIBLE
        mapView.getMapAsync { map ->
            map.uiSettings.isMapToolbarEnabled = false
            map.clear()
            val recorded = data.routePoints.orEmpty().mapNotNull { coordinates(it.lat, it.lng) }
            val hasRecordedTrail = recorded.size >= 2
            val endpointStart = coordinates(data.startLat, data.startLng)
            val endpointEnd = coordinates(data.endLat, data.endLng)
            val start = if (hasRecordedTrail) recorded.first() else endpointStart ?: recorded.firstOrNull()
            val end = if (hasRecordedTrail) recorded.last() else endpointEnd ?: recorded.lastOrNull()
            val displayPoints = if (hasRecordedTrail) recorded else listOfNotNull(start, end).distinct()

            start?.let {
                map.addMarker(MarkerOptions().position(it).title("Trip start")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN)))
            }
            end?.let {
                map.addMarker(MarkerOptions().position(it).title("Trip end")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)))
            }
            if (hasRecordedTrail) {
                map.addPolyline(PolylineOptions().addAll(recorded).color(Color.parseColor("#0B61CA")).width(10f))
            } else if (displayPoints.size >= 2) {
                map.addPolyline(
                    PolylineOptions()
                        .addAll(displayPoints)
                        .color(Color.parseColor("#5B7FA3"))
                        .width(8f)
                        .pattern(listOf(Dash(24f), Gap(14f)))
                )
            }
            if (displayPoints.isNotEmpty()) {
                mapView.post {
                    if (displayPoints.size == 1) {
                        map.moveCamera(CameraUpdateFactory.newLatLngZoom(displayPoints.first(), 16f))
                    } else {
                        val bounds = LatLngBounds.builder().also { builder ->
                            displayPoints.forEach(builder::include)
                        }.build()
                        map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, dp(40)))
                    }
                }
            }
            mapStatus.text = when {
                hasRecordedTrail -> "Actual travelled path · ${recorded.size} GPS points"
                displayPoints.size >= 2 ->
                    "Recorded GPS trail unavailable. Dashed line connects the trip start and end."
                displayPoints.size == 1 -> "Only one trip coordinate was recorded."
                else -> "No GPS coordinates were recorded for this trip."
            }
        }
    }

    private fun roundedBackground(fill: String, radiusDp: Int) =
        android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(radiusDp).toFloat()
            setColor(Color.parseColor(fill))
        }

    private fun coordinates(lat: Double?, lng: Double?): LatLng? =
        if (lat != null && lng != null && lat.isFinite() && lng.isFinite() &&
            lat in -90.0..90.0 && lng in -180.0..180.0 && !(lat == 0.0 && lng == 0.0)
        ) {
            LatLng(lat, lng)
        } else null

    private fun friendlyCpType(value: String?): String = value
        ?.replace('_', ' ')
        ?.split(' ')
        ?.joinToString(" ") { it.lowercase(Locale.US).replaceFirstChar { c -> c.titlecase(Locale.US) } }
        ?.takeIf { it.isNotBlank() }
        ?: "Not recorded"

    private fun formatClock(value: String?): String {
        if (value.isNullOrBlank()) return "Not recorded"
        val parsed = listOf("HH:mm", "HH:mm:ss").firstNotNullOfOrNull { pattern ->
            runCatching { SimpleDateFormat(pattern, Locale.US).parse(value) }.getOrNull()
        } ?: return value
        return SimpleDateFormat("h:mm a", Locale.US).format(parsed)
    }

    private fun formatEpoch(value: Double?): String =
        if (value == null || value <= 0.0) "Not recorded"
        else SimpleDateFormat("dd MMM yyyy, h:mm a", Locale.US).format(Date(value.toLong()))

    override fun onStart() { super.onStart(); mapView.onStart() }
    override fun onResume() { super.onResume(); mapView.onResume() }
    override fun onPause() { mapView.onPause(); super.onPause() }
    override fun onStop() { mapView.onStop(); super.onStop() }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
    override fun onDestroyView() { mapView.onDestroy(); super.onDestroyView() }

    companion object {
        const val TAG = "CpApprovalTripDetail"
        private const val ARG_ITEM = "item"

        fun newInstance(item: CpApprovalItem) = CpApprovalTripDetailDialog().apply {
            arguments = Bundle().apply { putString(ARG_ITEM, Gson().toJson(item)) }
        }
    }
}

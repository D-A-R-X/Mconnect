package com.manjugroups.m_connect.ui.hr

import android.graphics.Color
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.manjugroups.m_connect.MainActivity
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.databinding.FragmentSelfieClockInDetailBinding
import com.manjugroups.m_connect.ui.common.navigateUp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SelfieClockInDetailFragment : Fragment() {

    private var _binding: FragmentSelfieClockInDetailBinding? = null
    private val binding get() = _binding!!
    private val flowViewModel: AttendanceFlowViewModel by activityViewModels()
    private lateinit var session: SessionManager

    private val mode: PunchMode
        get() {
            val raw = arguments?.getString(ARG_MODE) ?: PunchMode.PUNCH_IN.name
            return runCatching { PunchMode.valueOf(raw) }.getOrDefault(PunchMode.PUNCH_IN)
        }
    private val photoPath: String?
        get() = arguments?.getString(ARG_PHOTO_PATH)
    private val latitude: Double?
        get() = if (arguments?.containsKey(ARG_LATITUDE) == true) {
            arguments?.getDouble(ARG_LATITUDE)
        } else {
            null
        }
    private val longitude: Double?
        get() = if (arguments?.containsKey(ARG_LONGITUDE) == true) {
            arguments?.getDouble(ARG_LONGITUDE)
        } else {
            null
        }
    private val address: String?
        get() = arguments?.getString(ARG_ADDRESS)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSelfieClockInDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())

        val extraBottomSpacingPx = (8 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { root, insets ->
            val bottomInset = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            root.updatePadding(bottom = bottomInset + extraBottomSpacingPx)
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
        bindCapturedData()
        collectEvents()

        binding.btnBack.setOnClickListener {
            navigateUp()
        }

        binding.btnHeaderRefresh.setOnClickListener {
            navigateUp()
        }

        binding.btnRetakePhoto.setOnClickListener {
            navigateUp()
        }

        binding.btnClockInAction.setOnClickListener {
            val file = photoPath?.let(::File)
            val remarks = binding.etClockInNotes.text?.toString()?.trim()
            if (mode == PunchMode.PUNCH_IN) {
                flowViewModel.punchIn(
                    token = session.bearerToken,
                    latitude = latitude,
                    longitude = longitude,
                    address = address,
                    selfieFile = file,
                    deviceId = session.trackingDeviceId,
                    context = requireContext().applicationContext,
                    remarks = remarks,
                )
            } else {
                flowViewModel.punchOut(
                    token = session.bearerToken,
                    latitude = latitude,
                    longitude = longitude,
                    address = address,
                    selfieFile = file,
                    deviceId = session.trackingDeviceId,
                    context = requireContext().applicationContext,
                    remarks = remarks,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        (activity as? MainActivity)?.setTabBarVisible(false)
        (activity as? MainActivity)?.setTopBarAppearance(Color.parseColor("#FEFEFE"), true)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun bindCapturedData() {
        val title = if (mode == PunchMode.PUNCH_IN) "Selfie To Clock In" else "Selfie To Clock Out"
        binding.tvDetailTitle.text = title
        binding.btnClockInAction.text = if (mode == PunchMode.PUNCH_IN) "Clock In" else "Clock Out"

        val file = photoPath?.let(::File)
        if (file != null && file.exists()) {
            loadPreviewImage(file)
        }

        val latValue = latitude
        val lngValue = longitude
        if (latValue != null && lngValue != null) {
            binding.tvCapturedLat.text = String.format(Locale.US, "Lat : %.6f", latValue)
            binding.tvCapturedLng.text = String.format(Locale.US, "Long : %.6f", lngValue)
        } else {
            binding.tvCapturedLat.text = "Lat : --"
            binding.tvCapturedLng.text = "Long : --"
        }
        binding.tvCapturedTime.text = buildCaptureTimeLabel()
        binding.btnClockInAction.isEnabled = file?.exists() == true && latValue != null && lngValue != null
    }

    private fun buildCaptureTimeLabel(): String {
        val now = Date()
        val candidatePatterns = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            listOf("dd/MM/yy hh:mma z", "dd/MM/yy hh:mma")
        } else {
            listOf("dd/MM/yy hh:mma z", "dd/MM/yy hh:mma")
        }
        candidatePatterns.forEach { pattern ->
            try {
                return SimpleDateFormat(pattern, Locale.getDefault()).format(now)
            } catch (_: Exception) {
                // try fallback pattern
            }
        }
        return now.toString()
    }

    private fun loadPreviewImage(file: File) {
        // Decode OFF the main thread — doing it inline froze the UI while a
        // multi-megapixel selfie was downsampled, which read as a crash. A
        // loader covers the (now short) gap.
        binding.selfiePreviewLoader.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                try {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.absolutePath, bounds)
                    var sampleSize = 1
                    val reqSize = 1080
                    var outWidth = bounds.outWidth
                    var outHeight = bounds.outHeight
                    while (outWidth > reqSize || outHeight > reqSize) {
                        sampleSize *= 2
                        outWidth /= 2
                        outHeight /= 2
                    }
                    val options = BitmapFactory.Options().apply {
                        inSampleSize = sampleSize.coerceAtLeast(1)
                    }
                    BitmapFactory.decodeFile(file.absolutePath, options)
                } catch (_: Throwable) {
                    // Throwable, not Exception — a giant image throws
                    // OutOfMemoryError (an Error), which Exception wouldn't catch.
                    null
                }
            }
            if (_binding == null) return@launch
            if (bitmap != null) {
                binding.ivSelfiePreview.setImageBitmap(bitmap)
            }
            // No full-res setImageURI fallback: decoding the original on the
            // main thread was itself an OOM risk. If the downsample failed the
            // preview just stays empty; the captured file is still used to punch.
            binding.selfiePreviewLoader.visibility = View.GONE
        }
    }

    private fun collectEvents() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                flowViewModel.events.collect { event ->
                    when (event) {
                        is AttendanceFlowEvent.Loading -> {
                            if (event.mode == mode) {
                                binding.btnClockInAction.isEnabled = false
                                binding.btnClockInAction.text = "Submitting..."
                            }
                        }

                        is AttendanceFlowEvent.Error -> {
                            if (event.mode == mode) {
                                binding.btnClockInAction.isEnabled = true
                                binding.btnClockInAction.text =
                                    if (mode == PunchMode.PUNCH_IN) "Clock In" else "Clock Out"
                                Toast.makeText(requireContext(), event.message, Toast.LENGTH_SHORT).show()
                            }
                        }

                        // Submission failed after upload — treat the same as a
                        // pre-flight Error: re-enable the button so the user
                        // can retry and surface the server's error message.
                        is AttendanceFlowEvent.SubmissionFailed -> {
                            if (event.mode == mode) {
                                binding.btnClockInAction.isEnabled = true
                                binding.btnClockInAction.text =
                                    if (mode == PunchMode.PUNCH_IN) "Clock In" else "Clock Out"
                                Toast.makeText(requireContext(), event.message, Toast.LENGTH_LONG).show()
                                // Server says there's no open session — the UI
                                // got into this screen because of a stale
                                // clocked-in flag. Send the user back to the
                                // dashboard, which will refresh and show the
                                // right button.
                                if (event.message.contains("No active punch-in", ignoreCase = true)) {
                                    navigateUp()
                                }
                            }
                        }

                        is AttendanceFlowEvent.Success -> {
                            if (event.mode == mode) {
                                // No success toast — the success sheet already
                                // confirms the punch.
                                parentFragmentManager.setFragmentResult(
                                    RESULT_KEY_PUNCH_COMPLETED,
                                    bundleOf(KEY_MODE to mode.name),
                                )
                                // When this clock-in was launched from a trip
                                // card, signal Home (on a DISTINCT key so the HR
                                // dashboard's PUNCH_COMPLETED listener is not
                                // clobbered) to auto-start that trip.
                                val targetVisitId = arguments?.getString(ARG_TARGET_VISIT_ID)
                                if (mode == PunchMode.PUNCH_IN && !targetVisitId.isNullOrBlank()) {
                                    parentFragmentManager.setFragmentResult(
                                        RESULT_KEY_CLOCK_IN_FOR_TRIP,
                                        bundleOf(KEY_TARGET_VISIT_ID to targetVisitId),
                                    )
                                }
                                parentFragmentManager.popBackStack(
                                    null,
                                    FragmentManager.POP_BACK_STACK_INCLUSIVE,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val RESULT_KEY_PUNCH_COMPLETED = "attendance_punch_completed_result"
        const val KEY_MODE = "mode"
        // Emitted (in addition to PUNCH_COMPLETED) only when the clock-in was
        // started from a trip card, so Home can auto-start the intended trip.
        const val RESULT_KEY_CLOCK_IN_FOR_TRIP = "attendance_clock_in_for_trip_result"
        const val KEY_TARGET_VISIT_ID = "target_visit_id"

        private const val ARG_MODE = "arg_mode"
        private const val ARG_PHOTO_PATH = "arg_photo_path"
        private const val ARG_LATITUDE = "arg_latitude"
        private const val ARG_LONGITUDE = "arg_longitude"
        private const val ARG_ADDRESS = "arg_address"
        private const val ARG_TARGET_VISIT_ID = "arg_target_visit_id"

        fun newInstance(
            mode: String,
            photoPath: String,
            latitude: Double,
            longitude: Double,
            address: String?,
            targetVisitId: String? = null,
        ): SelfieClockInDetailFragment {
            return SelfieClockInDetailFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_MODE, mode)
                    putString(ARG_PHOTO_PATH, photoPath)
                    putDouble(ARG_LATITUDE, latitude)
                    putDouble(ARG_LONGITUDE, longitude)
                    putString(ARG_ADDRESS, address)
                    putString(ARG_TARGET_VISIT_ID, targetVisitId)
                }
            }
        }
    }
}

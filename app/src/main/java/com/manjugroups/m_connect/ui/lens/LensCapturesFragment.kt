package com.manjugroups.m_connect.ui.lens

import android.animation.ObjectAnimator
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResult
import androidx.fragment.app.setFragmentResultListener
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import coil.dispose
import coil.load
import com.manjugroups.m_connect.ui.common.AppBottomSheets
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.databinding.FragmentLensCapturesBinding
import com.manjugroups.m_connect.databinding.ItemLensCaptureBinding
import com.manjugroups.m_connect.ui.common.Floater
import com.manjugroups.m_connect.ui.common.SkeletonLoader
import com.manjugroups.m_connect.ui.common.pushDetail
import kotlinx.coroutines.launch

/**
 * HR > Lens Enrollment > a staff member's saved photos. Per pose: retake
 * (camera asks for just that pose) or delete; plus Send to Lens for photos
 * waiting for Lens, Retake all (Replace earlier photos) and Delete all.
 * Delete removes our stored photo only; it never calls Lens.
 */
class LensCapturesFragment : Fragment() {

    private var _binding: FragmentLensCapturesBinding? = null
    private val binding get() = _binding!!
    private val viewModel: LensCapturesViewModel by viewModels()
    private lateinit var session: SessionManager
    private var skeletonAnim: ObjectAnimator? = null

    private val staffId by lazy { requireArguments().getString(ARG_STAFF_ID).orEmpty() }
    private val staffName by lazy { requireArguments().getString(ARG_NAME).orEmpty() }
    private val employeeCode by lazy { requireArguments().getString(ARG_CODE) }
    private val existingSamples by lazy { requireArguments().getInt(ARG_EXISTING, 0) }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLensCapturesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        (activity as? com.manjugroups.m_connect.MainActivity)
            ?.setTopBarAppearance(android.graphics.Color.WHITE, true, fullBleed = true)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())
        if (!LensStaffFragment.isAvailable(session) || staffId.isBlank()) {
            parentFragmentManager.popBackStack()
            return
        }
        com.manjugroups.m_connect.ui.common.StatusBarInsets.padHeader(binding.lensCapturesHeader, fixedHeight = true)
        binding.tvLensCapName.text = staffName.ifBlank { getString(R.string.lens_captures_title) }

        binding.btnLensCapSend.setOnClickListener {
            Floater.info("Sending the photos to Lens…")
            viewModel.sendPending(session.bearerToken, staffId, ::onActionDone)
        }
        binding.btnLensCapRetakeAll.setOnClickListener {
            parentFragmentManager.pushDetail(
                LensEnrollFragment.newInstance(
                    staffId = staffId,
                    name = staffName,
                    employeeCode = employeeCode,
                    existingSamples = existingSamples,
                    replace = true,
                    resultKey = ENROLL_KEY,
                ),
            )
        }
        binding.btnLensCapDeleteAll.setOnClickListener { confirmDelete(null, null) }

        // A retake / re-enrollment from this screen finished: reload and tell the list.
        setFragmentResultListener(ENROLL_KEY) { _, _ ->
            viewModel.load(session.bearerToken, staffId, silent = true)
            notifyChanged()
        }

        if (!viewModel.state.value.loaded) viewModel.load(session.bearerToken, staffId)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::render)
            }
        }
    }

    private fun onActionDone(ok: Boolean, message: String) {
        if (ok) {
            Floater.success(message)
            notifyChanged()
        } else {
            Floater.warning(message)
        }
    }

    private fun notifyChanged() {
        setFragmentResult(RESULT_KEY, Bundle().apply { putString(ARG_STAFF_ID, staffId) })
    }

    private fun render(state: LensCapturesUiState) {
        val b = _binding ?: return
        if (state.loading) {
            b.lensCapSkeletonScroll.isVisible = true
            if (skeletonAnim == null) skeletonAnim = SkeletonLoader.show(b.lensCapSkeleton, count = 4)
        } else {
            skeletonAnim?.cancel()
            skeletonAnim = null
            b.lensCapSkeleton.removeAllViews()
            b.lensCapSkeletonScroll.isVisible = false
        }

        val busy = state.busy != null
        val count = state.captures.size
        b.tvLensCapMeta.text = listOfNotNull(
            employeeCode?.takeIf { it.isNotBlank() },
            resources.getQuantityString(R.plurals.lens_captures_count, count, count),
        ).joinToString("  ·  ")
        val samples = state.enrollment?.faceSamples ?: 0
        b.tvLensCapStatus.text = when {
            state.error != null && !state.loaded -> state.error
            state.waiting > 0 -> getString(R.string.lens_captures_status_waiting, state.waiting)
            samples > 0 -> getString(R.string.lens_captures_status_enrolled, samples)
            count == 0 -> getString(R.string.lens_captures_status_none)
            else -> getString(R.string.lens_captures_status_enrolled, count)
        }
        b.tvLensCapStatus.setTextColor(attrColor(if (state.waiting > 0) R.attr.colorWarning else R.attr.colorForegroundSecondary))

        renderGrid(state, busy)

        b.btnLensCapSend.isVisible = state.waiting > 0
        b.btnLensCapSend.text = if (state.busy == "send") getString(R.string.lens_sending)
        else getString(R.string.lens_captures_send, state.waiting)
        b.btnLensCapSend.isEnabled = !busy
        b.btnLensCapRetakeAll.isEnabled = !busy && state.loaded
        b.btnLensCapDeleteAll.isVisible = count > 0
        b.btnLensCapDeleteAll.isEnabled = !busy
    }

    private fun renderGrid(state: LensCapturesUiState, busy: Boolean) {
        val b = _binding ?: return
        val grid = b.lensCapGrid
        val poses = LensPose.guidedOrder
        if (grid.childCount != poses.size) {
            grid.removeAllViews()
            val gap = (8 * resources.displayMetrics.density).toInt()
            poses.forEachIndexed { index, _ ->
                val item = ItemLensCaptureBinding.inflate(layoutInflater, grid, false)
                item.root.layoutParams = GridLayout.LayoutParams(
                    GridLayout.spec(index / 2), GridLayout.spec(index % 2, 1f),
                ).apply {
                    width = 0
                    setMargins(if (index % 2 == 0) 0 else gap / 2, gap, if (index % 2 == 0) gap / 2 else 0, 0)
                }
                grid.addView(item.root)
            }
        }
        poses.forEachIndexed { index, pose ->
            val item = ItemLensCaptureBinding.bind(grid.getChildAt(index))
            val photo = state.photoFor(pose)
            item.tvLensCapPose.text = if (pose.required) pose.label else "${pose.label} · ${getString(R.string.lens_optional)}"
            val inLens = photo?.lensStatus == "enrolled"
            item.tvLensCapState.text = when {
                photo == null -> getString(R.string.lens_captures_none)
                inLens -> getString(R.string.lens_captures_in_lens)
                else -> getString(R.string.lens_captures_waiting)
            }
            item.tvLensCapState.setTextColor(
                attrColor(
                    when {
                        photo == null -> R.attr.colorForegroundMuted
                        inLens -> R.attr.colorSuccess
                        else -> R.attr.colorWarning
                    },
                ),
            )
            val url = photo?.url
            if (item.ivLensCap.tag != url) {
                item.ivLensCap.tag = url
                item.ivLensCap.dispose()
                if (url.isNullOrBlank()) item.ivLensCap.setImageDrawable(null)
                else item.ivLensCap.load(url) { crossfade(true) }
            }
            item.tvLensCapEmpty.isVisible = photo == null
            item.btnLensCapRetake.text = getString(if (photo == null) R.string.lens_capture_pose else R.string.lens_retake)
            item.btnLensCapRetake.isEnabled = !busy && state.loaded
            item.btnLensCapRetake.setOnClickListener {
                parentFragmentManager.pushDetail(
                    LensEnrollFragment.newSinglePose(staffId, staffName, employeeCode, pose, ENROLL_KEY),
                )
            }
            item.btnLensCapDelete.isVisible = photo != null
            item.btnLensCapDelete.isEnabled = !busy
            item.btnLensCapDelete.text = if (state.busy == photo?.captureId) "…" else getString(R.string.lens_captures_delete)
            item.btnLensCapDelete.setOnClickListener { confirmDelete(photo?.captureId, pose) }
        }
    }

    private fun confirmDelete(captureId: String?, pose: LensPose?) {
        val name = staffName.ifBlank { "this staff member" }
        val count = viewModel.state.value.captures.size
        val one = captureId != null && pose != null
        // Shared sheet: Mconnect buttons, red Delete (never the stock dialog).
        AppBottomSheets.showConfirm(
            ctx = requireContext(),
            title = getString(if (one) R.string.lens_captures_delete_title else R.string.lens_captures_delete_all_title),
            message = if (one) {
                getString(R.string.lens_captures_delete_body, pose!!.label.lowercase(), name)
            } else {
                getString(R.string.lens_captures_delete_all_body, count, name)
            },
            confirmLabel = getString(if (one) R.string.lens_captures_delete else R.string.lens_captures_delete_all),
            destructive = true,
        ) {
            viewModel.delete(session.bearerToken, staffId, captureId, ::onActionDone)
        }
    }

    private fun attrColor(attr: Int): Int {
        val tv = TypedValue()
        requireContext().theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    override fun onDestroyView() {
        skeletonAnim?.cancel()
        skeletonAnim = null
        _binding = null
        super.onDestroyView()
    }

    companion object {
        /** Sent to the staff list when photos changed here. */
        const val RESULT_KEY = "lens_captures_changed"
        /** Result key for enrollments started from this screen. */
        private const val ENROLL_KEY = "lens_captures_enrolled"
        private const val ARG_STAFF_ID = "staffId"
        private const val ARG_NAME = "name"
        private const val ARG_CODE = "employeeCode"
        private const val ARG_EXISTING = "existingSamples"

        fun newInstance(staffId: String, name: String, employeeCode: String?, existingSamples: Int) =
            LensCapturesFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_STAFF_ID, staffId)
                    putString(ARG_NAME, name)
                    putString(ARG_CODE, employeeCode)
                    putInt(ARG_EXISTING, existingSamples)
                }
            }
    }
}

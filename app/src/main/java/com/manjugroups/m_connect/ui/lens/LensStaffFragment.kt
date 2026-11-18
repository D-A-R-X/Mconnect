package com.manjugroups.m_connect.ui.lens

import android.animation.ObjectAnimator
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResultListener
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load
import coil.transform.CircleCropTransformation
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.databinding.FragmentLensStaffBinding
import com.manjugroups.m_connect.databinding.ItemLensStaffBinding
import com.manjugroups.m_connect.network.LensStaffItem
import com.manjugroups.m_connect.ui.common.ProfilePhotos
import com.manjugroups.m_connect.ui.common.SkeletonLoader
import com.manjugroups.m_connect.ui.common.pushDetail
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * HR > Lens Enrollment: the staff directory. Tapping a staff member opens the
 * guided face capture ([LensEnrollFragment]).
 */
class LensStaffFragment : Fragment() {

    private var _binding: FragmentLensStaffBinding? = null
    private val binding get() = _binding!!
    private val viewModel: LensStaffViewModel by viewModels()
    private lateinit var session: SessionManager
    private val adapter = StaffAdapter(::openEnroll)
    private var skeletonAnim: ObjectAnimator? = null
    private var lastGeneration = -1

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLensStaffBinding.inflate(inflater, container, false)
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
        if (!isAvailable(session)) {
            // Lost the permission since the tile was shown: leave quietly.
            parentFragmentManager.popBackStack()
            return
        }
        // Full-bleed like the other detail screens: the header pads itself by
        // the status-bar inset instead of sliding under the status bar.
        com.manjugroups.m_connect.ui.common.StatusBarInsets.padHeader(binding.lensStaffHeader, fixedHeight = true)

        val layoutManager = LinearLayoutManager(requireContext())
        binding.rvLensStaff.layoutManager = layoutManager
        binding.rvLensStaff.adapter = adapter
        binding.rvLensStaff.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                if (layoutManager.findLastVisibleItemPosition() >= adapter.itemCount - 6) viewModel.loadMore()
            }
        })
        binding.lensSwipe.setOnRefreshListener { viewModel.refresh() }
        binding.btnLensRetry.setOnClickListener { viewModel.refresh() }
        binding.btnLensClearSearch.setOnClickListener { binding.etLensSearch.setText("") }
        binding.etLensSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString().orEmpty()
                binding.btnLensClearSearch.isVisible = text.isNotEmpty()
                viewModel.onQueryChanged(text)
            }
        })

        // An enrollment finished: refresh the badges without a skeleton.
        setFragmentResultListener(LensEnrollFragment.RESULT_KEY) { _, _ -> viewModel.reloadQuietly() }

        viewModel.start(session.bearerToken)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::render)
            }
        }
    }

    private fun render(state: LensStaffUiState) {
        val b = _binding ?: return
        // Skeleton only while the first page of a query has nothing to show.
        if (state.loading) {
            b.lensSkeletonScroll.isVisible = true
            if (skeletonAnim == null) skeletonAnim = SkeletonLoader.show(b.lensSkeleton, count = 7)
        } else {
            skeletonAnim?.cancel()
            skeletonAnim = null
            b.lensSkeleton.alpha = 1f
            b.lensSkeleton.removeAllViews()
            b.lensSkeletonScroll.isVisible = false
        }
        b.lensSwipe.isRefreshing = state.refreshing

        val scrollTop = state.generation != lastGeneration
        lastGeneration = state.generation
        adapter.submitList(state.items) {
            if (scrollTop && _binding != null) b.rvLensStaff.scrollToPosition(0)
        }

        val showEmpty = !state.loading && state.items.isEmpty()
        b.lensEmpty.isVisible = showEmpty
        if (showEmpty) {
            b.tvLensEmpty.text = when {
                state.error != null -> state.error
                state.query.isNotBlank() -> getString(R.string.lens_empty_search, state.query)
                else -> getString(R.string.lens_empty_all)
            }
            b.btnLensRetry.isVisible = state.error != null
        }
    }

    private fun openEnroll(item: LensStaffItem) {
        val staffId = item.staffId ?: return
        if (item.employeeCode.isNullOrBlank()) {
            com.manjugroups.m_connect.ui.common.Floater.warning(getString(R.string.lens_no_code))
            return
        }
        val pending = item.pendingPhotos ?: 0
        if (pending > 0) {
            // Photos already saved and waiting: offer to send them instead of recording again.
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.lens_send_pending_title)
                .setMessage(getString(R.string.lens_send_pending_body, pending, item.name.orEmpty()))
                .setPositiveButton(R.string.lens_send_pending) { _, _ ->
                    com.manjugroups.m_connect.ui.common.Floater.show("Sending the photos to Lens…")
                    viewModel.sendPending(staffId) { ok, message ->
                        if (ok) com.manjugroups.m_connect.ui.common.Floater.success(message)
                        else com.manjugroups.m_connect.ui.common.Floater.warning(message)
                    }
                }
                .setNeutralButton(R.string.lens_record_again_short) { _, _ -> pushEnroll(item, staffId) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        pushEnroll(item, staffId)
    }

    private fun pushEnroll(item: LensStaffItem, staffId: String) {
        parentFragmentManager.pushDetail(
            LensEnrollFragment.newInstance(
                staffId = staffId,
                name = item.name.orEmpty(),
                employeeCode = item.employeeCode,
                existingSamples = item.enrollment?.faceSamples ?: 0,
            ),
        )
    }

    override fun onDestroyView() {
        skeletonAnim?.cancel()
        skeletonAnim = null
        _binding?.rvLensStaff?.adapter = null
        _binding = null
        super.onDestroyView()
    }

    private class StaffAdapter(
        private val onClick: (LensStaffItem) -> Unit,
    ) : ListAdapter<LensStaffItem, StaffAdapter.Holder>(Diff) {

        class Holder(val b: ItemLensStaffBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemLensStaffBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = getItem(position)
            val b = holder.b
            val ctx = b.root.context
            val name = item.name?.trim().takeUnless { it.isNullOrEmpty() } ?: "Unnamed staff"
            b.tvLensName.text = name
            b.tvLensMeta.text = listOfNotNull(
                item.employeeCode?.takeIf { it.isNotBlank() },
                item.department?.takeIf { it.isNotBlank() },
                item.designation?.takeIf { it.isNotBlank() },
            ).joinToString("  ·  ")

            // Avatar: photo or initials. Always dispose the recycled request
            // first so a fast scroll never paints another person's photo here.
            b.ivLensPhoto.dispose()
            val photo = ProfilePhotos.resolve(item.photoUrl)
            if (!photo.isNullOrBlank()) {
                b.ivLensPhoto.isVisible = true
                b.ivLensPhoto.load(photo) {
                    crossfade(true)
                    transformations(CircleCropTransformation())
                }
            } else {
                b.ivLensPhoto.setImageDrawable(null)
                b.ivLensPhoto.isVisible = false
            }
            b.tvLensInitials.text = initials(name)

            val hasCode = !item.employeeCode.isNullOrBlank()
            val enrollment = item.enrollment
            val samples = enrollment?.faceSamples ?: 0
            when {
                !hasCode -> {
                    b.tvLensStatus.text = ctx.getString(R.string.lens_no_code)
                    b.tvLensStatus.setBackgroundResource(R.drawable.bg_lens_badge_warning)
                    b.tvLensStatus.setTextColor(attrColor(b.root, R.attr.colorWarning))
                }
                samples > 0 -> {
                    val date = enrollment?.enrolledAt?.takeIf { it > 0 }?.let {
                        SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(it.toLong()))
                    }
                    b.tvLensStatus.text = if (date != null) {
                        ctx.getString(R.string.lens_enrolled_samples, samples, date)
                    } else {
                        ctx.getString(R.string.lens_enrolled_samples_no_date, samples)
                    }
                    b.tvLensStatus.setBackgroundResource(R.drawable.bg_lens_badge_enrolled)
                    b.tvLensStatus.setTextColor(attrColor(b.root, R.attr.colorSuccess))
                }
                (item.pendingPhotos ?: 0) > 0 -> {
                    // Saved while Lens was down / not configured: not lost, waiting.
                    b.tvLensStatus.text = ctx.getString(R.string.lens_saved_waiting)
                    b.tvLensStatus.setBackgroundResource(R.drawable.bg_lens_badge_warning)
                    b.tvLensStatus.setTextColor(attrColor(b.root, R.attr.colorWarning))
                }
                else -> {
                    b.tvLensStatus.text = ctx.getString(R.string.lens_not_enrolled)
                    b.tvLensStatus.setBackgroundResource(R.drawable.bg_lens_badge_pending)
                    b.tvLensStatus.setTextColor(attrColor(b.root, R.attr.colorForegroundSecondary))
                }
            }
            b.root.alpha = if (hasCode) 1f else 0.6f
            b.ivLensChevron.isVisible = hasCode
            b.root.setOnClickListener { onClick(item) }
        }

        override fun onViewRecycled(holder: Holder) {
            holder.b.ivLensPhoto.dispose()
            super.onViewRecycled(holder)
        }

        private fun initials(name: String): String =
            name.split(" ").filter { it.isNotBlank() }.take(2)
                .joinToString("") { it.first().uppercase() }
                .ifBlank { "?" }

        private fun attrColor(view: View, attr: Int): Int {
            val tv = android.util.TypedValue()
            view.context.theme.resolveAttribute(attr, tv, true)
            return tv.data
        }

        object Diff : DiffUtil.ItemCallback<LensStaffItem>() {
            override fun areItemsTheSame(oldItem: LensStaffItem, newItem: LensStaffItem) = oldItem.staffId == newItem.staffId
            override fun areContentsTheSame(oldItem: LensStaffItem, newItem: LensStaffItem) = oldItem == newItem
        }
    }

    companion object {
        const val PERMISSION = "lens.enroll"

        /**
         * Super admins and anyone explicitly granted `lens.enroll`. Deliberately
         * not hasPermission(): that also passes the blanket isAdmin flag, which
         * many non-admin roles carry (see SessionManager.canViewFleetMyTrips).
         */
        fun isAvailable(session: SessionManager): Boolean =
            (session.role ?: "").trim().equals("super-admin", ignoreCase = true) ||
                session.iamPermissions.contains(PERMISSION)
    }
}

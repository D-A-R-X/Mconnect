package com.manjugroups.m_connect.ui.common

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.network.DailyTaskData

class PendingTasksBottomSheet(
    private val tasks: List<DailyTaskData>,
    private val totalPending: Int
) : BottomSheetDialogFragment() {

    private lateinit var viewPager: ViewPager2

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, com.google.android.material.R.style.Theme_Design_Light_BottomSheetDialog)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.setOnShowListener {
            val d = it as BottomSheetDialog
            val bottomSheet = d.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) as FrameLayout?
            bottomSheet?.let {
                val behavior = BottomSheetBehavior.from(it)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
                it.setBackgroundColor(Color.TRANSPARENT)
                it.elevation = 0f
            }
            d.window?.let { window ->
                window.navigationBarColor = Color.WHITE
                androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars = true
            }
        }
        return dialog
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.bottom_sheet_pending_tasks, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        val tvSubtitle = view.findViewById<TextView>(R.id.tvSubtitle)
        tvSubtitle.text = "You have $totalPending pending tasks"
        
        view.findViewById<View>(R.id.btnClose).setOnClickListener {
            dismiss()
        }
        
        view.findViewById<View>(R.id.btnComplete).setOnClickListener {
            dismiss()
            (activity as? com.manjugroups.m_connect.MainActivity)?.openTaskManager()
        }

        viewPager = view.findViewById(R.id.viewPagerTasks)
        val dotsContainer = view.findViewById<LinearLayout>(R.id.dotsContainer)
        
        // Make neighbors peek in
        viewPager.offscreenPageLimit = 3
        (viewPager.getChildAt(0) as? RecyclerView)?.clipToPadding = false
        
        val adapter = PendingTaskAdapter(tasks)
        viewPager.adapter = adapter
        
        // Setup dots
        val dots = arrayOfNulls<ImageView>(tasks.size)
        val density = resources.displayMetrics.density
        for (i in tasks.indices) {
            dots[i] = ImageView(requireContext()).apply {
                setImageResource(R.drawable.bg_task_index_circle)
                alpha = if (i == 0) 1f else 0.3f
                setColorFilter(Color.parseColor("#2D68FE")) 
                val params = LinearLayout.LayoutParams(
                    (6 * density).toInt(), (6 * density).toInt()
                )
                params.setMargins((4 * density).toInt(), 0, (4 * density).toInt(), 0)
                layoutParams = params
            }
            dotsContainer.addView(dots[i])
        }
        
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                for (i in tasks.indices) {
                    dots[i]?.alpha = if (i == position) 1f else 0.3f
                }
                adapter.notifyDataSetChanged()
            }
        })
    }
    
    inner class PendingTaskAdapter(private val items: List<DailyTaskData>) : RecyclerView.Adapter<PendingTaskAdapter.Holder>() {
        
        inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val root = v as ViewGroup
            val cardInner = v.findViewById<View>(R.id.cardInner)
            val tvIndex = v.findViewById<TextView>(R.id.tvIndex)
            val tvStatus = v.findViewById<TextView>(R.id.tvStatus)
            val ivIllustration = v.findViewById<ImageView>(R.id.ivIllustration)
            val tvTitle = v.findViewById<TextView>(R.id.tvTitle)
            val tvProject = v.findViewById<TextView>(R.id.tvProject)
            val tvCreatorName = v.findViewById<TextView>(R.id.tvCreatorName)
            val tvCreatorRole = v.findViewById<TextView>(R.id.tvCreatorRole)
            val tvAvatar = v.findViewById<TextView>(R.id.tvAvatar)
            val tvCreatedOn = v.findViewById<TextView>(R.id.tvCreatedOn)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            return Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_pending_task_card, parent, false))
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val task = items[position]
            val isActive = (position == viewPager.currentItem)
            
            // Themes: 0 -> Blue, 1 -> Orange, 2 -> Purple
            val theme = position % 3
            val (mainColor, bgPillColor, iconRes) = when(theme) {
                0 -> Triple("#2D68FE", "#E0E7FF", R.drawable.img_task_3d_blue)
                1 -> Triple("#F97316", "#FFEDD5", R.drawable.img_task_3d_orange)
                else -> Triple("#8B5CF6", "#EDE9FE", R.drawable.img_task_3d_purple)
            }
            
            val activeColor = Color.parseColor(mainColor)
            val inactiveColor = Color.parseColor("#E5E7EB")
            val strokeColor = if (isActive) activeColor else inactiveColor
            val strokeWidth = if (isActive) 2 else 1
            
            val den = holder.cardInner.context.resources.displayMetrics.density
            val bg = holder.cardInner.background.mutate() as GradientDrawable
            bg.setStroke((strokeWidth * den).toInt(), strokeColor)
            
            holder.tvIndex.text = String.format("%02d", position + 1)
            holder.tvIndex.setTextColor(activeColor)
            val idxBg = holder.tvIndex.background.mutate() as GradientDrawable
            idxBg.setStroke((1 * den).toInt(), activeColor)
            
            holder.tvStatus.text = "In Progress"
            holder.tvStatus.setTextColor(activeColor)
            val statusBg = holder.tvStatus.background.mutate() as GradientDrawable
            statusBg.setColor(Color.parseColor(bgPillColor))
            
            val projBg = holder.tvProject.background.mutate() as GradientDrawable
            projBg.setColor(Color.parseColor(bgPillColor))
            
            holder.ivIllustration.setImageResource(iconRes)
            
            holder.tvTitle.text = task.title ?: task.taskName ?: task.label ?: "Pending task"
            holder.tvProject.text = task.module ?: "General"
            
            // The backend mistakenly puts the assigned-to person's name in 'assignedByName' for system auto-assignments.
            // We can detect this by checking if they match. Also, assignedBy contains a raw Convex ID which we shouldn't show.
            val assignedByName = task.assignedByName?.takeIf { it.isNotBlank() }
            val assignedToName = task.assignedToName?.takeIf { it.isNotBlank() }
            
            val creator = if (assignedByName != null && assignedByName == assignedToName) {
                "Auto assigned"
            } else {
                assignedByName ?: "Auto assigned"
            }
            holder.tvCreatorName.text = creator
            
            val designation = task.assignedByRole?.takeIf { it.isNotBlank() }
                ?: task.assignedByDesignation?.takeIf { it.isNotBlank() }
                ?: task.creatorRole?.takeIf { it.isNotBlank() }
                ?: task.creatorDesignation?.takeIf { it.isNotBlank() }
                ?: ""
                
            if (designation.isNotEmpty()) {
                holder.tvCreatorRole.visibility = View.VISIBLE
                holder.tvCreatorRole.text = designation
            } else {
                holder.tvCreatorRole.visibility = View.GONE
            }
            
            // Set initials
            val initials = creator.split(" ").filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1).uppercase() }
            holder.tvAvatar.text = if (initials.isNotEmpty()) initials else "U"
            
            // Re-theme the avatar pill background to match the active color
            val avatarBg = holder.tvAvatar.background.mutate() as GradientDrawable
            avatarBg.setColor(Color.parseColor(bgPillColor))
            holder.tvAvatar.setTextColor(activeColor)
            
            if (task.creationTime != null) {
                try {
                    val sdf = java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.getDefault())
                    holder.tvCreatedOn.text = sdf.format(java.util.Date(task.creationTime.toLong()))
                } catch (e: Exception) {
                    holder.tvCreatedOn.text = "Unknown"
                }
            } else {
                holder.tvCreatedOn.text = "Unknown"
            }
        }
    }
}

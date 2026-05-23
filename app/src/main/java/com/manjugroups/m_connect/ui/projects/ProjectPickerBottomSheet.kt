package com.manjugroups.m_connect.ui.projects

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.setFragmentResult
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.manjugroups.m_connect.R

/**
 * Bottom-sheet replacement for the bland system AlertDialog the Expenses
 * screen used to pop when the project picker pill is tapped. Same
 * visual language as the other expense sheets — rounded top corners,
 * drag handle, bold title + muted subtitle, list of rows with a check
 * glyph on the currently-selected project.
 *
 * Hands the picked project back through fragment-result:
 *   key   = [RESULT_KEY]
 *   bundle:
 *     [RESULT_PROJECT_ID] -> String
 *     [RESULT_PROJECT_NAME] -> String
 */
class ProjectPickerBottomSheet : BottomSheetDialogFragment() {

    private val projectIds: List<String> by lazy {
        requireArguments().getStringArrayList(ARG_IDS).orEmpty()
    }
    private val projectNames: List<String> by lazy {
        requireArguments().getStringArrayList(ARG_NAMES).orEmpty()
    }
    private val selectedId: String? by lazy {
        requireArguments().getString(ARG_SELECTED_ID)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.sheet_project_picker, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val rv = view.findViewById<RecyclerView>(R.id.rvProjectPicker)
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = Adapter()
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_project_picker, parent, false)
            return VH(v)
        }

        override fun getItemCount(): Int = projectIds.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(position)
        }

        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val name: TextView = itemView.findViewById(R.id.tvProjectPickerName)
            private val check: ImageView = itemView.findViewById(R.id.imgProjectPickerCheck)

            fun bind(position: Int) {
                val id = projectIds.getOrNull(position) ?: return
                val label = projectNames.getOrNull(position) ?: "Untitled project"
                name.text = label
                check.visibility = if (id == selectedId) View.VISIBLE else View.GONE
                itemView.setOnClickListener {
                    setFragmentResult(
                        RESULT_KEY,
                        bundleOf(
                            RESULT_PROJECT_ID to id,
                            RESULT_PROJECT_NAME to label,
                        ),
                    )
                    dismissAllowingStateLoss()
                }
            }
        }
    }

    companion object {
        const val RESULT_KEY = "ProjectPickerResult"
        const val RESULT_PROJECT_ID = "projectId"
        const val RESULT_PROJECT_NAME = "projectName"

        private const val ARG_IDS = "arg_ids"
        private const val ARG_NAMES = "arg_names"
        private const val ARG_SELECTED_ID = "arg_selected_id"

        fun newInstance(
            ids: List<String>,
            names: List<String>,
            selectedId: String?,
        ): ProjectPickerBottomSheet = ProjectPickerBottomSheet().apply {
            arguments = Bundle().apply {
                putStringArrayList(ARG_IDS, ArrayList(ids))
                putStringArrayList(ARG_NAMES, ArrayList(names))
                if (selectedId != null) putString(ARG_SELECTED_ID, selectedId)
            }
        }
    }
}

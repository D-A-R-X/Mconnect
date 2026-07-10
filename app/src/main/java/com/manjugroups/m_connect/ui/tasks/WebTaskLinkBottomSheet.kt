package com.manjugroups.m_connect.ui.tasks

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.manjugroups.m_connect.R

/**
 * App-styled sheet for a web-only task: shows the deep link and offers
 * "Open Link" (launches the browser straight to the task's approval sheet /
 * detail) and "Copy Link". Mirrors the web behaviour where the link opens the
 * exact page the task points to.
 */
class WebTaskLinkBottomSheet : BottomSheetDialogFragment() {

    private var titleText: String = "Open on the web app"
    private var url: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let { a ->
            a.getString(ARG_TITLE)?.let { titleText = it }
            url = a.getString(ARG_URL).orEmpty()
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = BottomSheetDialog(requireContext(), theme)
        dialog.setOnShowListener { di ->
            val sheet = (di as BottomSheetDialog)
                .findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.let {
                it.setBackgroundResource(R.drawable.bg_bottom_sheet)
                androidx.core.view.ViewCompat.setElevation(it, 0f)
                BottomSheetBehavior.from(it).state = BottomSheetBehavior.STATE_EXPANDED
            }
        }
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.bottom_sheet_web_task_link, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<TextView>(R.id.tvWebTaskTitle).text = titleText
        view.findViewById<TextView>(R.id.tvWebTaskUrl).text = url

        view.findViewById<MaterialButton>(R.id.btnWebTaskOpen).setOnClickListener {
            val opened = runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }.isSuccess
            if (!opened) {
                context?.let { Toast.makeText(it, "Couldn't open the link", Toast.LENGTH_SHORT).show() }
            }
            dismissAllowingStateLoss()
        }

        view.findViewById<MaterialButton>(R.id.btnWebTaskCopy).setOnClickListener {
            val ctx = context ?: return@setOnClickListener
            val clip = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clip?.setPrimaryClip(ClipData.newPlainText("Task link", url))
            Toast.makeText(ctx, "Link copied", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val ARG_TITLE = "arg_title"
        private const val ARG_URL = "arg_url"

        fun newInstance(title: String, url: String) = WebTaskLinkBottomSheet().apply {
            arguments = Bundle().apply {
                putString(ARG_TITLE, title)
                putString(ARG_URL, url)
            }
        }
    }
}

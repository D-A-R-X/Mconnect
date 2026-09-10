package com.manjugroups.m_connect.update

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import com.manjugroups.m_connect.R
import kotlin.math.roundToInt

class ReleaseNoticeDialogFragment : DialogFragment() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isCancelable = false
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        super.onCreateDialog(savedInstanceState).also { it.setCanceledOnTouchOutside(false) }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.dialog_release_notice, container, false)

    override fun onStart() {
        super.onStart()
        dialog?.window?.let { window ->
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.attributes = window.attributes.apply { dimAmount = 0.58f }
            val margin = (20 * resources.displayMetrics.density).roundToInt()
            val maxWidth = (420 * resources.displayMetrics.density).roundToInt()
            val width = (resources.displayMetrics.widthPixels - margin * 2).coerceAtMost(maxWidth)
            window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val campaignId = requireArguments().getString(ARG_ID).orEmpty()
        view.findViewById<TextView>(R.id.tvReleaseNoticeLabel).text =
            requireArguments().getString(ARG_LABEL).orEmpty()
        view.findViewById<TextView>(R.id.tvReleaseNoticeTitle).text =
            requireArguments().getString(ARG_TITLE).orEmpty()
        view.findViewById<TextView>(R.id.tvReleaseNoticeTamilTitle).text =
            requireArguments().getString(ARG_TAMIL_TITLE).orEmpty()
        view.findViewById<TextView>(R.id.tvReleaseNoticeMessage).text =
            requireArguments().getString(ARG_MESSAGE).orEmpty()
        view.findViewById<TextView>(R.id.tvReleaseNoticeAttribution).text =
            requireArguments().getString(ARG_ATTRIBUTION).orEmpty()

        val highlights = requireArguments().getStringArrayList(ARG_HIGHLIGHTS).orEmpty()
        val highlightContainer = view.findViewById<LinearLayout>(R.id.releaseNoticeHighlights)
        highlights.forEach { highlight ->
            val row = layoutInflater.inflate(
                R.layout.item_release_notice_highlight,
                highlightContainer,
                false,
            )
            row.findViewById<TextView>(R.id.tvReleaseNoticeHighlight).text = highlight
            highlightContainer.addView(row)
        }

        view.findViewById<View>(R.id.btnReleaseNoticeContinue).setOnClickListener {
            setFragmentResult(
                RESULT_KEY,
                Bundle().apply { putString(KEY_CAMPAIGN_ID, campaignId) },
            )
            dismissAllowingStateLoss()
        }
    }

    companion object {
        const val TAG = "ReleaseNoticeDialog"
        const val RESULT_KEY = "release_notice_result"
        const val KEY_CAMPAIGN_ID = "campaign_id"

        private const val ARG_ID = "id"
        private const val ARG_LABEL = "label"
        private const val ARG_TITLE = "title"
        private const val ARG_TAMIL_TITLE = "tamil_title"
        private const val ARG_MESSAGE = "message"
        private const val ARG_HIGHLIGHTS = "highlights"
        private const val ARG_ATTRIBUTION = "attribution"

        fun newInstance(campaign: ReleaseNoticeCampaign) = ReleaseNoticeDialogFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_ID, campaign.id)
                putString(ARG_LABEL, campaign.label)
                putString(ARG_TITLE, campaign.title)
                putString(ARG_TAMIL_TITLE, campaign.tamilTitle)
                putString(ARG_MESSAGE, campaign.message)
                putStringArrayList(ARG_HIGHLIGHTS, ArrayList(campaign.highlights))
                putString(ARG_ATTRIBUTION, campaign.attribution)
            }
        }
    }
}

package com.manjugroups.m_connect.ui.common

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.databinding.ViewSummaryHeaderBinding

class SummaryHeaderView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val binding: ViewSummaryHeaderBinding

    init {
        binding = ViewSummaryHeaderBinding.inflate(LayoutInflater.from(context), this)
        
        attrs?.let {
            val typedArray = context.obtainStyledAttributes(it, R.styleable.SummaryHeaderView, 0, 0)
            val title = typedArray.getString(R.styleable.SummaryHeaderView_sh_title)
            val subtitle = typedArray.getString(R.styleable.SummaryHeaderView_sh_subtitle)
            val bannerImage = typedArray.getResourceId(R.styleable.SummaryHeaderView_sh_banner_image, -1)
            
            if (title != null) binding.tvHeaderTitle.text = title
            if (subtitle != null) binding.tvHeaderSubtitle.text = subtitle
            if (bannerImage != -1) binding.ivBannerImage.setImageResource(bannerImage)
            
            typedArray.recycle()
        }
    }

    fun setOnBackClickListener(listener: OnClickListener) {
        binding.btnBack.setOnClickListener(listener)
    }

    fun setBackButtonVisible(visible: Boolean) {
        binding.btnBack.visibility = if (visible) VISIBLE else GONE
    }

    fun setTitle(title: String) {
        binding.tvHeaderTitle.text = title
    }

    fun setSubtitle(subtitle: String) {
        binding.tvHeaderSubtitle.text = subtitle
    }
}

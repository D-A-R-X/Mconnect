package com.manjugroups.m_connect.ui.common

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
        ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
            // Prefer the root insets: a scrolling parent can hand us consumed ones.
            applyTopInset(topInsetOf(ViewCompat.getRootWindowInsets(v) ?: insets))
            insets
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ViewCompat.getRootWindowInsets(this)?.let { applyTopInset(topInsetOf(it)) }
        ViewCompat.requestApplyInsets(this)
    }

    private fun topInsetOf(insets: WindowInsetsCompat): Int = maxOf(
        insets.getInsets(WindowInsetsCompat.Type.statusBars()).top,
        insets.getInsets(WindowInsetsCompat.Type.displayCutout()).top,
    )

    /**
     * The header draws behind the status bar. It used to push its content
     * down by a fixed 52 dp, which sat too low on phones with a short status
     * bar and too tight under a tall notch. Now the content starts a fixed
     * distance below the real status-bar / cutout height, and the header grows
     * by the same amount so the overlapping card below keeps its position.
     */
    private fun applyTopInset(top: Int) {
        val density = resources.displayMetrics.density
        val root = binding.summaryHeaderRoot
        val padTop = top + (8 * density).toInt()
        val height = top + (163 * density).toInt()
        if (root.paddingTop == padTop && root.layoutParams.height == height) return
        root.setPadding(root.paddingLeft, padTop, root.paddingRight, root.paddingBottom)
        root.layoutParams = root.layoutParams.apply { this.height = height }
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

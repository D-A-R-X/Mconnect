package com.manjugroups.m_connect.ui.common

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.manjugroups.m_connect.R

class EmptyStateView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val ivEmptyImage: ImageView
    private val tvEmptyTitle: TextView
    private val tvEmptyDesc: TextView

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL

        LayoutInflater.from(context).inflate(R.layout.view_empty_state, this, true)

        ivEmptyImage = findViewById(R.id.ivEmptyImage)
        tvEmptyTitle = findViewById(R.id.tvEmptyTitle)
        tvEmptyDesc = findViewById(R.id.tvEmptyDesc)

        val a = context.obtainStyledAttributes(attrs, R.styleable.EmptyStateView, defStyleAttr, 0)
        val imageRes = a.getResourceId(R.styleable.EmptyStateView_es_image, 0)
        val titleText = a.getString(R.styleable.EmptyStateView_es_title)
        val descText = a.getString(R.styleable.EmptyStateView_es_description)
        a.recycle()

        if (imageRes != 0) {
            ivEmptyImage.setImageResource(imageRes)
            ivEmptyImage.visibility = View.VISIBLE
        } else {
            ivEmptyImage.visibility = View.GONE
        }
        tvEmptyTitle.text = titleText.orEmpty()
        tvEmptyDesc.text = descText.orEmpty()
    }

    fun setEmptyState(imageResId: Int, title: String, description: String) {
        if (imageResId != 0) {
            ivEmptyImage.setImageResource(imageResId)
            ivEmptyImage.visibility = View.VISIBLE
        } else {
            ivEmptyImage.visibility = View.GONE
        }
        tvEmptyTitle.text = title
        tvEmptyDesc.text = description
    }

    fun setTitle(title: CharSequence) {
        tvEmptyTitle.text = title
    }

    fun setDescription(description: CharSequence) {
        tvEmptyDesc.text = description
    }

    fun setImage(imageResId: Int) {
        if (imageResId != 0) {
            ivEmptyImage.setImageResource(imageResId)
            ivEmptyImage.visibility = View.VISIBLE
        } else {
            ivEmptyImage.visibility = View.GONE
        }
    }
}

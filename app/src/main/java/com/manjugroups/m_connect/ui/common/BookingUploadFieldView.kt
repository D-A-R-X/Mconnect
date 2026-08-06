package com.manjugroups.m_connect.ui.common

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.manjugroups.m_connect.R

/** Shared booking-document picker used by standalone, CP, and SV booking forms. */
class BookingUploadFieldView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    private val labelView: TextView
    private val valueView: TextView
    private val actionView: View
    private val progressView: ProgressBar
    private var hintText = context.getString(R.string.booking_upload_choose_file)
    private var selectedFileName: String? = null

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.view_booking_upload_field, this, true)
        labelView = findViewById(R.id.tvBookingUploadLabel)
        valueView = findViewById(R.id.tvBookingUploadValue)
        actionView = findViewById(R.id.bookingUploadAction)
        progressView = findViewById(R.id.progressBookingUpload)

        val values = context.obtainStyledAttributes(
            attrs,
            R.styleable.BookingUploadFieldView,
            defStyleAttr,
            0,
        )
        val label = values.getString(R.styleable.BookingUploadFieldView_buf_label).orEmpty()
        hintText = values.getString(R.styleable.BookingUploadFieldView_buf_hint)
            ?: context.getString(R.string.booking_upload_choose_file)
        val required = values.getBoolean(R.styleable.BookingUploadFieldView_buf_required, false)
        values.recycle()

        labelView.text = if (required && label.isNotBlank()) "$label *" else label
        valueView.text = hintText
    }

    fun setOnUploadClickListener(listener: OnClickListener?) {
        actionView.setOnClickListener(listener)
    }

    fun setUploading(uploading: Boolean) {
        progressView.visibility = if (uploading) View.VISIBLE else View.GONE
        actionView.isEnabled = !uploading
        valueView.text = if (uploading) {
            context.getString(R.string.booking_upload_uploading)
        } else {
            selectedFileName?.let { "\u2713 $it" } ?: hintText
        }
    }

    fun setUploadedFileName(fileName: String?) {
        selectedFileName = fileName?.takeIf(String::isNotBlank)
        progressView.visibility = View.GONE
        actionView.isEnabled = true
        valueView.text = selectedFileName?.let { "\u2713 $it" } ?: hintText
    }
}

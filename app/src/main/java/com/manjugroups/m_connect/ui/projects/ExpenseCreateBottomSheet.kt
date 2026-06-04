package com.manjugroups.m_connect.ui.projects

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.CreateProjectExpenseRequest
import com.manjugroups.m_connect.network.ExpenseReceipt
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Bottom sheet for logging a new project expense — the "Expense Creation"
 * sheet from the mobile Figma:
 *   - Expense Category (dropdown)
 *   - Date
 *   - Money (numeric amount)
 *   - Notes (optional)
 *   - Payment Method (dropdown with per-method icons)
 *   - Photos (receipts) — camera or gallery, uploaded to storage
 *
 * Posts to `/api/projects/expenses/create` (receipts attached as storageIds).
 */
class ExpenseCreateBottomSheet : BottomSheetDialogFragment() {

    private val api = ApiService.create()
    private lateinit var session: SessionManager

    private val projectId: String by lazy {
        requireArguments().getString(ARG_PROJECT_ID).orEmpty()
    }
    private val initialCategory: String? by lazy {
        arguments?.getString(ARG_INITIAL_CATEGORY)
    }

    /** Server slug -> display label. */
    private val categories = listOf(
        "labour" to "Labour",
        "materials" to "Materials",
        "equipment" to "Equipments",
        "other" to "Other",
    )

    private data class PaymentOption(val slug: String, val label: String, val iconRes: Int) {
        // AutoCompleteTextView shows toString() when an item is picked.
        override fun toString(): String = label
    }

    private val paymentOptions = listOf(
        PaymentOption("cash", "Cash", R.drawable.ic_pm_cash),
        PaymentOption("card", "Credit Card", R.drawable.ic_pm_card),
        PaymentOption("paypal", "PayPal", R.drawable.ic_pm_paypal),
        PaymentOption("upi", "UPI", R.drawable.ic_pm_upi),
        PaymentOption("bank_transfer", "Bank Transfer", R.drawable.ic_pm_bank),
    )

    // ── Photos (receipts) ───────────────────────────────────────────────────
    private val receipts = mutableListOf<ExpenseReceipt>()
    private lateinit var llPhotos: LinearLayout
    private lateinit var btnAddPhoto: View
    private lateinit var tvPhotoCount: TextView
    private lateinit var tilPaymentMethod: TextInputLayout

    private var cameraFile: File? = null
    private var cameraUri: Uri? = null

    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents(),
    ) { uris ->
        if (!isAdded || uris.isNullOrEmpty()) return@registerForActivityResult
        uris.forEach { uri -> copyUriToTempFile(uri)?.let(::handleImageFile) }
    }

    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        cameraUri?.let { uri ->
            runCatching {
                requireContext().revokeUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
        if (!isAdded) return@registerForActivityResult
        val f = cameraFile
        if (result.resultCode == Activity.RESULT_OK && f != null && f.exists() && f.length() > 0) {
            handleImageFile(f)
        }
    }

    private val cameraPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) launchCamera()
        else Toast.makeText(requireContext(), "Camera permission is required", Toast.LENGTH_SHORT).show()
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = BottomSheetDialog(requireContext(), theme)
        dialog.setOnShowListener { di ->
            val sheet = (di as BottomSheetDialog)
                .findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.let {
                val behavior = BottomSheetBehavior.from(it)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
            }
        }
        return dialog
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.sheet_expense_create, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())

        val etCategory = view.findViewById<AutoCompleteTextView>(R.id.etCategory)
        val etDate = view.findViewById<TextInputEditText>(R.id.etDate)
        val etAmount = view.findViewById<TextInputEditText>(R.id.etAmount)
        val etPaymentMethod = view.findViewById<AutoCompleteTextView>(R.id.etPaymentMethod)
        val etNotes = view.findViewById<TextInputEditText>(R.id.etNotes)
        val btnSave = view.findViewById<MaterialButton>(R.id.btnSave)
        tilPaymentMethod = view.findViewById(R.id.tilPaymentMethod)
        llPhotos = view.findViewById(R.id.llPhotos)
        btnAddPhoto = view.findViewById(R.id.btnAddPhoto)
        tvPhotoCount = view.findViewById(R.id.tvPhotoCount)

        // ── Category dropdown ───────────────────────────────────────────
        etCategory.setAdapter(
            ArrayAdapter(
                requireContext(),
                android.R.layout.simple_list_item_1,
                categories.map { it.second },
            ),
        )
        var pickedCategory: String =
            initialCategory?.takeIf { initial -> categories.any { it.first == initial } }
                ?: "labour"
        etCategory.setText(
            categories.firstOrNull { it.first == pickedCategory }?.second.orEmpty(),
            /* filter = */ false,
        )
        etCategory.setOnClickListener { etCategory.showDropDown() }
        etCategory.setOnItemClickListener { _, _, position, _ ->
            pickedCategory = categories.getOrNull(position)?.first ?: pickedCategory
        }

        // ── Date — defaults to today, taps open the DateFilter sheet ────
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        var pickedDateIso = fmt.format(Calendar.getInstance().time)
        etDate.setText(pickedDateIso)
        etDate.setOnClickListener {
            DateFilterBottomSheet.newInstance()
                .show(parentFragmentManager, "date_filter")
            parentFragmentManager.setFragmentResultListener(
                DateFilterBottomSheet.REQUEST_KEY,
                viewLifecycleOwner,
            ) { _, bundle ->
                pickedDateIso = bundle.getString(DateFilterBottomSheet.RESULT_FROM)
                    ?: pickedDateIso
                etDate.setText(pickedDateIso)
            }
        }

        // ── Payment method dropdown (icons per method) ──────────────────
        etPaymentMethod.setAdapter(PaymentMethodAdapter(requireContext(), paymentOptions))
        var pickedPaymentMethod: String? = null
        etPaymentMethod.setOnClickListener { etPaymentMethod.showDropDown() }
        etPaymentMethod.setOnItemClickListener { _, _, position, _ ->
            val opt = paymentOptions.getOrNull(position) ?: return@setOnItemClickListener
            pickedPaymentMethod = opt.slug
            etPaymentMethod.setText(opt.label, false)
            tilPaymentMethod.startIconDrawable =
                ContextCompat.getDrawable(requireContext(), opt.iconRes)
        }

        // ── Photos ──────────────────────────────────────────────────────
        btnAddPhoto.setOnClickListener { showPhotoSourceChooser() }
        updatePhotoCount()

        // ── Submit ──────────────────────────────────────────────────────
        btnSave.setOnClickListener {
            val amount = etAmount.text?.toString()?.toDoubleOrNull()
            if (amount == null || amount <= 0) {
                Toast.makeText(requireContext(), "Enter a valid amount", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (projectId.isBlank()) {
                Toast.makeText(requireContext(), "Missing project context", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            btnSave.isEnabled = false
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val resp = api.createProjectExpense(
                        session.bearerToken,
                        CreateProjectExpenseRequest(
                            projectId = projectId,
                            category = pickedCategory,
                            amount = amount,
                            date = pickedDateIso,
                            paymentMethod = pickedPaymentMethod,
                            notes = etNotes.text?.toString()?.takeIf { it.isNotBlank() },
                            receipts = receipts.toList().takeIf { it.isNotEmpty() },
                            paid = false,
                        ),
                    )
                    if (resp.success) {
                        setFragmentResult(RESULT_KEY, Bundle.EMPTY)
                        Toast.makeText(requireContext(), "Expense saved", Toast.LENGTH_SHORT).show()
                        dismissAllowingStateLoss()
                    } else {
                        Toast.makeText(
                            requireContext(),
                            resp.error ?: "Failed to save expense",
                            Toast.LENGTH_LONG,
                        ).show()
                        btnSave.isEnabled = true
                    }
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), e.message ?: "Network error", Toast.LENGTH_LONG).show()
                    btnSave.isEnabled = true
                }
            }
        }

        playEntryAnimations(view)
    }

    // ── Photo picking + upload ──────────────────────────────────────────────
    private fun showPhotoSourceChooser() {
        AlertDialog.Builder(requireContext())
            .setTitle("Add receipt photo")
            .setItems(arrayOf("Take photo", "Choose from gallery")) { _, which ->
                if (which == 0) {
                    if (ContextCompat.checkSelfPermission(
                            requireContext(),
                            Manifest.permission.CAMERA,
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        launchCamera()
                    } else {
                        cameraPermLauncher.launch(Manifest.permission.CAMERA)
                    }
                } else {
                    galleryLauncher.launch("image/*")
                }
            }
            .show()
    }

    private fun launchCamera() {
        val f = createReceiptFile("receipt_cam_") ?: run {
            Toast.makeText(requireContext(), "Unable to create photo file", Toast.LENGTH_SHORT).show()
            return
        }
        cameraFile = f
        val uri = runCatching {
            FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                f,
            )
        }.getOrElse {
            Toast.makeText(requireContext(), "Unable to open camera", Toast.LENGTH_SHORT).show()
            return
        }
        cameraUri = uri
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            clipData = ClipData.newUri(requireContext().contentResolver, "Receipt", uri)
        }
        try {
            cameraLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(requireContext(), "No camera app available", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleImageFile(file: File) {
        if (!isAdded) return
        viewLifecycleOwner.lifecycleScope.launch {
            val receipt = uploadReceipt(file)
            if (!isAdded) return@launch
            if (receipt != null) {
                receipts.add(receipt)
                addThumbnail(file, receipt)
            } else {
                Toast.makeText(requireContext(), "Couldn't upload photo, try again", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private suspend fun uploadReceipt(file: File): ExpenseReceipt? = try {
        val body = file.asRequestBody("image/jpeg".toMediaType())
        val resp = api.uploadStorageFile(session.bearerToken, body)
        resp.storageId?.let { ExpenseReceipt(storageId = it, name = file.name) }
    } catch (_: Exception) {
        null
    }

    private fun addThumbnail(file: File, receipt: ExpenseReceipt) {
        if (!isAdded) return
        val ctx = requireContext()
        val d = resources.displayMetrics.density
        val sizePx = (72 * d).toInt()

        val card = MaterialCardView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).apply {
                marginEnd = (8 * d).toInt()
            }
            radius = 8 * d
            cardElevation = 0f
            strokeWidth = (1 * d).toInt()
            setStrokeColor(Color.parseColor("#E4E7EC"))
        }

        val img = ImageView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            decodeScaled(file)?.let { setImageBitmap(it) }
        }

        val removeSize = (20 * d).toInt()
        val remove = TextView(ctx).apply {
            text = "×"
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_remove_badge)
            layoutParams = FrameLayout.LayoutParams(removeSize, removeSize).apply {
                gravity = Gravity.TOP or Gravity.END
                val m = (3 * d).toInt()
                setMargins(0, m, m, 0)
            }
            setOnClickListener {
                receipts.remove(receipt)
                llPhotos.removeView(card)
                updatePhotoCount()
            }
        }

        card.addView(img)
        card.addView(remove)
        // Insert before the add-photo tile so "+" stays last.
        val addIndex = llPhotos.indexOfChild(btnAddPhoto).coerceAtLeast(0)
        llPhotos.addView(card, addIndex)
        updatePhotoCount()
    }

    private fun updatePhotoCount() {
        tvPhotoCount.text = "${receipts.size} Photos"
    }

    private fun createReceiptFile(prefix: String): File? = try {
        val dir = File(requireContext().cacheDir, "expense_receipts").apply { if (!exists()) mkdirs() }
        File.createTempFile(prefix, ".jpg", dir)
    } catch (_: Exception) {
        null
    }

    private fun copyUriToTempFile(uri: Uri): File? = try {
        val f = createReceiptFile("receipt_pick_")
        if (f != null) {
            requireContext().contentResolver.openInputStream(uri)?.use { input ->
                f.outputStream().use { output -> input.copyTo(output) }
            }
        }
        f?.takeIf { it.length() > 0 }
    } catch (_: Exception) {
        null
    }

    /** Decode a downsampled bitmap (~400px) to keep thumbnails light on memory. */
    private fun decodeScaled(file: File): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        val target = 400
        while (bounds.outWidth / sample > target || bounds.outHeight / sample > target) {
            sample *= 2
        }
        BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    } catch (_: Exception) {
        null
    }

    private fun playEntryAnimations(view: View) {
        val formItems = listOfNotNull(
            view.findViewById<View>(R.id.etCategory)?.parent?.parent as? View,
            view.findViewById<View>(R.id.etDate)?.parent?.parent as? View,
            view.findViewById<View>(R.id.etAmount)?.parent?.parent as? View,
            view.findViewById<View>(R.id.etNotes)?.parent?.parent as? View,
            view.findViewById<View>(R.id.etPaymentMethod)?.parent?.parent as? View,
            view.findViewById<View>(R.id.btnSave),
        )
        formItems.forEachIndexed { i, item ->
            item.alpha = 0f
            item.translationY = 20f
            item.animate().alpha(1f).translationY(0f)
                .setDuration(350L)
                .setStartDelay(100L + i * 50L)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }
    }

    /** Dropdown adapter that renders an icon + label per payment method. */
    private class PaymentMethodAdapter(
        context: Context,
        private val options: List<PaymentOption>,
    ) : ArrayAdapter<PaymentOption>(context, 0, options) {

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            bind(position, convertView, parent)

        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
            bind(position, convertView, parent)

        private fun bind(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView ?: LayoutInflater.from(context)
                .inflate(R.layout.item_payment_method, parent, false)
            val opt = options[position]
            row.findViewById<ImageView>(R.id.ivPmIcon).setImageResource(opt.iconRes)
            row.findViewById<TextView>(R.id.tvPmLabel).text = opt.label
            return row
        }
    }

    companion object {
        const val RESULT_KEY = "ExpenseCreated"
        private const val ARG_PROJECT_ID = "projectId"
        private const val ARG_INITIAL_CATEGORY = "initialCategory"

        fun newInstance(projectId: String, initialCategory: String? = null) =
            ExpenseCreateBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_PROJECT_ID, projectId)
                    initialCategory?.let { putString(ARG_INITIAL_CATEGORY, it) }
                }
            }
    }
}

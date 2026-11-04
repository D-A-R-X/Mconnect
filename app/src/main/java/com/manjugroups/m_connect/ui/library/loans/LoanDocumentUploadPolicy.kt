package com.manjugroups.m_connect.ui.library.loans

import java.util.Locale

/**
 * Where a Loan Desk document upload goes.
 *
 * The server attaches a file to the loan case only while the case is at
 * Document Collection or Document Verification. A new case starts at Loan
 * Initiation: there the file is uploaded on its own and attached by
 * /api/postsales/loans/submit (which accepts Loan Initiation and Document
 * Collection). Past verification the documents are locked.
 */
internal object LoanDocumentUploadPolicy {

    enum class Mode {
        /** Attach each upload to the case as it finishes. */
        ATTACH,
        /** Upload the file only; Submit attaches it. */
        ON_SUBMIT,
        /** Documents can't change at this stage: view only. */
        LOCKED,
    }

    fun mode(stage: String?): Mode = when (stage?.trim()?.lowercase(Locale.US)) {
        // Older servers don't send the stage: attach, and fall back to
        // ON_SUBMIT if the server refuses (see isStageRefusal).
        null, "" -> Mode.ATTACH
        "loan_initiation" -> Mode.ON_SUBMIT
        "document_collection", "document_verification" -> Mode.ATTACH
        else -> Mode.LOCKED
    }

    /** The server refused to attach because of the case's stage. */
    fun isStageRefusal(message: String?): Boolean =
        message?.contains("can only be changed during collection or verification", ignoreCase = true) == true

    enum class Kind { PDF, IMAGE, OTHER }

    /**
     * What a file really is, from its first bytes. Picked files often carry
     * no extension or a wrong one (content:// names, HEIC from the gallery).
     */
    fun sniff(header: ByteArray): Kind {
        fun at(offset: Int, vararg bytes: Int) =
            header.size >= offset + bytes.size &&
                bytes.indices.all { header[offset + it].toInt() and 0xFF == bytes[it] }
        fun ascii(offset: Int, text: String) = at(offset, *text.map { it.code }.toIntArray())
        return when {
            ascii(0, "%PDF") -> Kind.PDF
            at(0, 0xFF, 0xD8, 0xFF) -> Kind.IMAGE
            at(0, 0x89, 0x50, 0x4E, 0x47) -> Kind.IMAGE
            ascii(0, "GIF8") -> Kind.IMAGE
            ascii(0, "RIFF") && ascii(8, "WEBP") -> Kind.IMAGE
            ascii(4, "ftyp") && HEIF_BRANDS.any { ascii(8, it) } -> Kind.IMAGE
            else -> Kind.OTHER
        }
    }

    private val HEIF_BRANDS = listOf("heic", "heix", "hevc", "hevx", "mif1", "msf1", "avif")
}

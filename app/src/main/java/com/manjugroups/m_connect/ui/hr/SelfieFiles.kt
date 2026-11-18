package com.manjugroups.m_connect.ui.hr

import android.content.Context
import android.net.Uri
import com.manjugroups.m_connect.network.IssueReporter
import java.io.File

/**
 * Copies the in-app camera's selfie into the pending punch file.
 *
 * Staff got "Failed to read captured selfie." (2 phones, 17-18 Nov) with no
 * cause in the report: the copy's exception was swallowed. The camera returns
 * a file:// Uri in our own cache, so when the content resolver fails the file
 * is read directly; whatever still fails is left as a breadcrumb (scheme, does
 * the source exist, its size, the error) so the next report shows why.
 */
internal object SelfieFiles {

    fun copyCaptured(context: Context, uri: Uri, target: File): Boolean {
        var lastError: Throwable? = null
        val viaResolver = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } != null
        }.onFailure { lastError = it }.getOrDefault(false)
        if (viaResolver && target.length() > 0L) return true

        val source = uri.takeIf { it.scheme == "file" }?.path?.let(::File)
        if (source != null && source.exists() && source.length() > 0L && source != target) {
            val viaFile = runCatching { source.copyTo(target, overwrite = true); true }
                .onFailure { lastError = it }
                .getOrDefault(false)
            if (viaFile && target.length() > 0L) return true
        }

        IssueReporter.crumb(
            "Selfie copy failed: scheme=${uri.scheme} sourceExists=${source?.exists()} " +
                "sourceBytes=${source?.length()} targetBytes=${target.length()} " +
                "error=${lastError?.javaClass?.simpleName}: ${lastError?.message?.take(120)}",
        )
        return false
    }
}

package com.manjugroups.m_connect.lenstest

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.manjugroups.m_connect.ui.lens.LensEnrollFragment

/**
 * Lens Test build only: hosts the real Lens capture + review screen in
 * dry-run mode (LensEnrollFragment.newDryRun). Nothing is uploaded and no login
 * is needed; "Save test photos" writes the JPEGs + stats to
 * /sdcard/Android/data/com.manjugroups.mconnect.lenstest/files/lens-test/.
 */
class LensCaptureTestActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, LensEnrollFragment.newDryRun())
                .commit()
        }
    }
}

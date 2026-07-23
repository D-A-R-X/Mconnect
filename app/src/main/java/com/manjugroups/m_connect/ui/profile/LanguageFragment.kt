package com.manjugroups.m_connect.ui.profile

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.manjugroups.m_connect.R

class LanguageFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_language, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        val header = view.findViewById<View>(R.id.languageHeader)
        if (header != null) {
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(header) { v, insets ->
                val sys = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                v.setPadding(v.paddingLeft, sys.top, v.paddingRight, v.paddingBottom)
                val lp = v.layoutParams
                lp.height = (56 * resources.displayMetrics.density).toInt() + sys.top
                v.layoutParams = lp
                insets
            }
            androidx.core.view.ViewCompat.requestApplyInsets(header)
        }

        view.findViewById<View>(R.id.btnLanguageBack).setOnClickListener {
            // Async pop — popBackStackImmediate() rebuilds the destination on the
            // tap and makes the back arrow feel frozen.
            parentFragmentManager.popBackStack()
        }
    }
}

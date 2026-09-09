package com.manjugroups.m_connect.ui.marketing

/** Prevents delayed picker requests from presenting duplicate sheets. */
internal class PickerLoadGate {
    private var active = false

    fun tryStart(): Boolean {
        if (active) return false
        active = true
        return true
    }

    fun finish() {
        active = false
    }
}

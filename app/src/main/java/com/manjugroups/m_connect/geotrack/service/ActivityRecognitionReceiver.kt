package com.manjugroups.m_connect.geotrack.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.DetectedActivity

class ActivityRecognitionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ActivityRecog"

        @Volatile var currentActivity: String = "STILL"
        @Volatile var currentConfidence: Int = 0

        // Set on every ENTER transition; the service consumes it to bypass
        // the stationary/moving dedup for ONE point, so a transition that
        // happens between stored points (e.g. vehicle→still while parked)
        // still lands on the timeline instead of being collapsed away.
        @Volatile private var transitionPending: Boolean = false

        fun consumeTransitionPending(): Boolean {
            val was = transitionPending
            transitionPending = false
            return was
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (ActivityTransitionResult.hasResult(intent)) {
            val result = ActivityTransitionResult.extractResult(intent) ?: return
            for (event in result.transitionEvents) {
                // Only care about ENTER transitions (started doing something)
                if (event.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER) {
                    val name = activityName(event.activityType)
                    currentActivity = name
                    currentConfidence = 80 // Transition events don't provide confidence, use high default
                    transitionPending = true
                    Log.i(TAG, "Activity transition: $name")
                }
            }
        }
    }

    private fun activityName(type: Int): String = when (type) {
        DetectedActivity.STILL -> "STILL"
        DetectedActivity.WALKING -> "WALKING"
        DetectedActivity.RUNNING -> "RUNNING"
        DetectedActivity.ON_FOOT -> "ON_FOOT"
        DetectedActivity.ON_BICYCLE -> "ON_BICYCLE"
        DetectedActivity.IN_VEHICLE -> "IN_VEHICLE"
        DetectedActivity.TILTING -> "TILTING"
        else -> "UNKNOWN"
    }
}

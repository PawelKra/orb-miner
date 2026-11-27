package com.example.spheresweeper.ui

import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat

/** Haptic feedback for game events; does nothing while disabled in the settings. */
class Haptics(private val view: View, private val vibrator: Vibrator?) {

    var enabled: Boolean = true

    fun perform(feedback: Feedback) {
        if (!enabled) return
        when (feedback) {
            Feedback.NONE -> Unit
            Feedback.FLAG -> view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            Feedback.CHORD -> view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            Feedback.EXPLOSION -> explode()
        }
    }

    private fun explode() {
        val v = vibrator
        if (v == null || !v.hasVibrator()) {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            return
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(EXPLOSION_PATTERN, -1))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(EXPLOSION_PATTERN, -1)
            }
        } catch (e: SecurityException) {
            // VIBRATE permission missing: fall back to view feedback.
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    private companion object {
        val EXPLOSION_PATTERN = longArrayOf(0L, 60L, 40L, 140L)
    }
}

@Composable
fun rememberHaptics(enabled: Boolean): Haptics {
    val view = LocalView.current
    val context = LocalContext.current
    val haptics = remember(view) {
        Haptics(view, ContextCompat.getSystemService(context, Vibrator::class.java))
    }
    haptics.enabled = enabled
    return haptics
}

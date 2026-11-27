/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.findx.keyhandler

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import com.android.internal.os.DeviceKeyHandler
import java.io.File

/**
 * Runs inside system_server. The S6SY761 touchpanel driver reports every
 * screen off gesture as KEY_F4 and stores the gesture type in
 * /proc/touchpanel/coordinate. Wake on double tap when tap to wake is on and
 * hand the other gestures to the LineageParts key handler as scan code
 * GESTURE_START_KEY + type, which is what the touch HAL advertises.
 */
class KeyHandler(private val context: Context) : DeviceKeyHandler {
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private var gestureType = UNKNOWN_GESTURE

    override fun handleKeyEvent(event: KeyEvent): KeyEvent? {
        if (event.scanCode != KEY_F4 || event.device?.name != TOUCHPANEL_DEVICE) return event

        if (event.action == KeyEvent.ACTION_DOWN) {
            gestureType = readGestureType()
            return null
        }

        val type = gestureType
        gestureType = UNKNOWN_GESTURE
        return when (type) {
            UNKNOWN_GESTURE -> null
            DOUBLE_TAP -> {
                if (isTapToWakeEnabled()) {
                    powerManager.wakeUp(
                        SystemClock.uptimeMillis(),
                        PowerManager.WAKE_REASON_TAP,
                        "org.lineageos.findx.keyhandler:DOUBLE_TAP",
                    )
                }
                null
            }
            else -> KeyEvent(
                event.downTime,
                event.eventTime,
                KeyEvent.ACTION_UP,
                KeyEvent.KEYCODE_UNKNOWN,
                0,
                event.metaState,
                event.deviceId,
                GESTURE_START_KEY + type,
                event.flags,
                event.source,
            )
        }
    }

    private fun readGestureType(): Int = try {
        // "<type>,<x>,<y>:<x>,<y>:..."
        File(COORDINATE_PATH).readText().substringBefore(',').trim().toInt()
    } catch (e: Exception) {
        Log.e(TAG, "Unable to read $COORDINATE_PATH", e)
        UNKNOWN_GESTURE
    }

    private fun isTapToWakeEnabled() =
        Settings.Secure.getInt(context.contentResolver, Settings.Secure.DOUBLE_TAP_TO_WAKE, 0) != 0

    private companion object {
        const val TAG = "FindXKeyHandler"
        const val TOUCHPANEL_DEVICE = "touchpanel"
        const val COORDINATE_PATH = "/proc/touchpanel/coordinate"

        // linux/input-event-codes.h
        const val KEY_F4 = 62

        // drivers/input/touchscreen/oplus_touchscreen/touchpanel_common.h
        const val UNKNOWN_GESTURE = 0
        const val DOUBLE_TAP = 1

        // Keep in sync with touch/service.cpp.
        const val GESTURE_START_KEY = 246
    }
}

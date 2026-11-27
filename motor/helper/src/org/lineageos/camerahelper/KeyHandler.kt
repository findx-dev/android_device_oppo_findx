/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.camerahelper

import android.content.Context
import android.content.Intent
import android.os.UserHandle
import android.view.KeyEvent
import com.android.internal.os.DeviceKeyHandler

/**
 * Runs inside system_server. The "motor" input device of oppo_motor reports
 * motor state changes as KEY_F13..KEY_F20, which have no key layout mapping,
 * so match them by scan code, consume them and hand them to the helper.
 */
class KeyHandler(private val context: Context) : DeviceKeyHandler {
    override fun handleKeyEvent(event: KeyEvent): KeyEvent? {
        if (event.scanCode !in CameraMotorService.MOTOR_SCAN_CODES) return event

        if (event.action == KeyEvent.ACTION_DOWN) {
            val intent = Intent(CameraMotorService.ACTION_MOTOR_EVENT)
                .setPackage(CameraMotorService.PACKAGE)
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                .putExtra(CameraMotorService.EXTRA_SCAN_CODE, event.scanCode)
            context.sendBroadcastAsUser(intent, UserHandle.SYSTEM)
        }
        return null
    }
}

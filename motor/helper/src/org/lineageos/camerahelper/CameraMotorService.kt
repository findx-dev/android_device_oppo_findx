/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.camerahelper

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.ServiceManager
import android.util.Log
import android.widget.Toast
import vendor.oplus.hardware.motor.IMotor

/**
 * Raises the Find X slider while any camera or the flashlight is in use and
 * lowers it once everything is released. Every camera and the flash sit on
 * the slider, so no per-camera mode tracking is needed.
 */
class CameraMotorService : Service() {
    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var cameraManager: CameraManager
    private lateinit var sensorManager: SensorManager
    private var fallSensors = emptyList<Sensor>()
    private var fallListening = false

    private var motor: IMotor? = null
    private val openCameras = mutableSetOf<String>()
    private val torchCameras = mutableSetOf<String>()

    /** Direction last requested from the HAL, or DIRECTION_UNKNOWN. */
    private var commanded = DIRECTION_UNKNOWN
    private var retries = 0

    /**
     * Set after a fall, a manual press or a failed raise. The slider stays
     * down until every camera and the torch have been released.
     */
    private var safetyLatched = false

    private val lowerRunnable = Runnable { move(IMotor.DIRECTION_DOWN) }
    private val verifyRunnable = Runnable { verify() }
    private val connectRunnable = Runnable { connect() }

    private val availabilityCallback = object : CameraManager.AvailabilityCallback() {
        override fun onCameraAvailable(cameraId: String) {
            if (openCameras.remove(cameraId)) reconcile()
        }

        override fun onCameraUnavailable(cameraId: String) {
            if (openCameras.add(cameraId)) reconcile()
        }
    }

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            val changed = if (enabled) torchCameras.add(cameraId) else torchCameras.remove(cameraId)
            if (!changed) return
            if (enabled && safetyLatched) {
                disableTorch()
                return
            }
            reconcile()
        }

        override fun onTorchModeUnavailable(cameraId: String) {
            // The camera owning the flash was opened; availability tracks it.
            if (torchCameras.remove(cameraId)) reconcile()
        }
    }

    private val fallListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.values.isEmpty() || event.values[0] == 0.0f) return
            Log.w(TAG, "Free fall reported by ${event.sensor.name}")
            if (commanded == IMotor.DIRECTION_UP) {
                engageSafetyLatch(R.string.free_fall_detected_message)
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }

    private val motorEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            onMotorEvent(intent.getIntExtra(EXTRA_SCAN_CODE, -1))
        }
    }

    private val deathRecipient = IBinder.DeathRecipient {
        handler.post {
            Log.w(TAG, "Motor HAL died")
            motor = null
            commanded = DIRECTION_UNKNOWN
            handler.removeCallbacks(verifyRunnable)
            handler.postDelayed(connectRunnable, RECONNECT_DELAY_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread(TAG).apply { start() }
        handler = Handler(thread.looper)
        cameraManager = getSystemService(CameraManager::class.java)
        sensorManager = getSystemService(SensorManager::class.java)
        // OPPO sensor hub free fall sensors; the stock MotorManagerService
        // lowers the slider on them, the kernel free fall GPIO never fires.
        fallSensors = sensorManager.getSensorList(Sensor.TYPE_ALL)
            .filter { it.type in FALL_SENSOR_TYPES }
        Log.i(TAG, "Free fall sensors: ${fallSensors.map { it.name }}")

        handler.post {
            connect()
            cameraManager.registerAvailabilityCallback(availabilityCallback, handler)
            cameraManager.registerTorchCallback(torchCallback, handler)
        }
        registerReceiver(
            motorEventReceiver,
            IntentFilter(ACTION_MOTOR_EVENT),
            null,
            handler,
            RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        unregisterReceiver(motorEventReceiver)
        setFallListening(false)
        cameraManager.unregisterAvailabilityCallback(availabilityCallback)
        cameraManager.unregisterTorchCallback(torchCallback)
        thread.quitSafely()
        super.onDestroy()
    }

    private fun connect() {
        val binder = try {
            ServiceManager.waitForDeclaredService(MOTOR_SERVICE)
        } catch (e: Exception) {
            Log.e(TAG, "Unable to get $MOTOR_SERVICE", e)
            null
        }
        if (binder == null) {
            handler.postDelayed(connectRunnable, RECONNECT_DELAY_MS)
            return
        }
        try {
            binder.linkToDeath(deathRecipient, 0)
        } catch (e: Exception) {
            handler.postDelayed(connectRunnable, RECONNECT_DELAY_MS)
            return
        }
        motor = IMotor.Stub.asInterface(binder)
        Log.i(TAG, "Connected to $MOTOR_SERVICE")
        reconcile()
    }

    private fun hasDemand() = openCameras.isNotEmpty() || torchCameras.isNotEmpty()

    private fun reconcile() {
        if (safetyLatched && !hasDemand()) {
            Log.i(TAG, "Everything released, clearing safety latch")
            safetyLatched = false
        }
        if (motor == null) return

        if (hasDemand() && !safetyLatched) {
            handler.removeCallbacks(lowerRunnable)
            if (commanded != IMotor.DIRECTION_UP) move(IMotor.DIRECTION_UP)
        } else if (commanded != IMotor.DIRECTION_DOWN && !handler.hasCallbacks(lowerRunnable)) {
            // Debounce camera switches, which close one camera before opening the next.
            handler.postDelayed(lowerRunnable, if (safetyLatched) 0 else LOWER_DELAY_MS)
        }
    }

    private fun move(direction: Int, force: Boolean = false) {
        val service = motor ?: return
        if (direction != commanded) retries = 0
        commanded = direction
        handler.removeCallbacks(verifyRunnable)
        setFallListening(direction == IMotor.DIRECTION_UP)
        try {
            service.move(direction, if (force) IMotor.START_FORCE else IMotor.START_NORMAL)
        } catch (e: Exception) {
            Log.e(TAG, "Motor move direction=$direction failed", e)
        }
        handler.postDelayed(verifyRunnable, VERIFY_DELAY_MS)
    }

    /** Check the slider reached the commanded end and retry a stalled move. */
    private fun verify() {
        handler.removeCallbacks(verifyRunnable)
        val service = motor ?: return
        val direction = commanded
        if (direction == DIRECTION_UNKNOWN) return

        val (position, moveState) = try {
            service.position to service.moveState
        } catch (e: Exception) {
            Log.e(TAG, "Unable to read motor state", e)
            return
        }
        if (moveState == MOVE_STATE_UPWARD || moveState == MOVE_STATE_DOWNWARD) {
            handler.postDelayed(verifyRunnable, VERIFY_POLL_MS)
            return
        }
        val expected =
            if (direction == IMotor.DIRECTION_UP) IMotor.POSITION_UP else IMotor.POSITION_DOWN
        if (position == expected) {
            retries = 0
            return
        }

        if (retries < MAX_RETRIES) {
            retries++
            Log.w(TAG, "Motor stopped at position=$position, retry $retries direction=$direction")
            move(direction, force = true)
            return
        }

        Log.e(TAG, "Motor failed to reach direction=$direction, position=$position")
        retries = 0
        if (direction == IMotor.DIRECTION_UP) {
            engageSafetyLatch(R.string.motor_cannot_go_up_message)
        } else {
            // Let the next demand change try again.
            commanded = DIRECTION_UNKNOWN
            showToast(R.string.motor_cannot_go_down_message)
        }
    }

    private fun onMotorEvent(scanCode: Int) {
        when (scanCode) {
            MOTOR_EVENT_MANUAL_TO_DOWN -> {
                // The driver lowers the slider itself after a manual press.
                commanded = IMotor.DIRECTION_DOWN
                setFallListening(false)
                engageSafetyLatch(R.string.motor_press_message)
            }
            MOTOR_EVENT_DOWN -> if (commanded == IMotor.DIRECTION_UP) {
                // The driver started lowering on its own (free fall interrupt).
                commanded = IMotor.DIRECTION_DOWN
                setFallListening(false)
                engageSafetyLatch(R.string.free_fall_detected_message)
            }
            MOTOR_EVENT_UP_NORMAL,
            MOTOR_EVENT_UP_ABNORMAL,
            MOTOR_EVENT_DOWN_NORMAL,
            MOTOR_EVENT_DOWN_ABNORMAL -> verify()
        }
    }

    private fun engageSafetyLatch(message: Int) {
        Log.w(TAG, "Engaging safety latch: ${resources.getResourceEntryName(message)}")
        handler.removeCallbacks(lowerRunnable)
        safetyLatched = true
        disableTorch()
        showToast(message)
        if (openCameras.isNotEmpty()) goHome()
        reconcile()
    }

    private fun setFallListening(enable: Boolean) {
        if (enable == fallListening) return
        fallListening = enable
        for (sensor in fallSensors) {
            if (enable) {
                sensorManager.registerListener(
                    fallListener, sensor, SensorManager.SENSOR_DELAY_FASTEST, handler)
            } else {
                sensorManager.unregisterListener(fallListener, sensor)
            }
        }
    }

    private fun disableTorch() {
        for (cameraId in torchCameras.toList()) {
            try {
                cameraManager.setTorchMode(cameraId, false)
            } catch (e: CameraAccessException) {
                Log.w(TAG, "Unable to turn off torch on camera $cameraId", e)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Unable to turn off torch on camera $cameraId", e)
            }
        }
    }

    private fun goHome() {
        mainHandler.post {
            val home = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                startActivity(home)
            } catch (e: Exception) {
                Log.e(TAG, "Unable to return home", e)
            }
        }
    }

    private fun showToast(message: Int) {
        mainHandler.post { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    }

    companion object {
        private const val TAG = "FindXCameraHelper"

        const val PACKAGE = "org.lineageos.camerahelper"
        const val ACTION_MOTOR_EVENT = "org.lineageos.camerahelper.MOTOR_EVENT"
        const val EXTRA_SCAN_CODE = "scan_code"

        private val MOTOR_SERVICE = "${IMotor.DESCRIPTOR}/default"
        private const val DIRECTION_UNKNOWN = -1

        // enum motor_move_state in oppo_motor.h.
        private const val MOVE_STATE_UPWARD = 1
        private const val MOVE_STATE_DOWNWARD = 2

        private const val LOWER_DELAY_MS = 500L
        private const val VERIFY_DELAY_MS = 1_500L
        private const val VERIFY_POLL_MS = 200L
        private const val RECONNECT_DELAY_MS = 1_000L
        private const val MAX_RETRIES = 2

        // KEY_F13..KEY_F20 from oppo_motor.h.
        private const val MOTOR_EVENT_MANUAL_TO_DOWN = 184
        private const val MOTOR_EVENT_DOWN = 188
        private const val MOTOR_EVENT_UP_ABNORMAL = 186
        private const val MOTOR_EVENT_UP_NORMAL = 187
        private const val MOTOR_EVENT_DOWN_ABNORMAL = 189
        private const val MOTOR_EVENT_DOWN_NORMAL = 190
        val MOTOR_SCAN_CODES = 183..190

        // free_fall_detect and free_fall from the OPPO sensor hub.
        private val FALL_SENSOR_TYPES = setOf(33171006, 33171009)
    }
}

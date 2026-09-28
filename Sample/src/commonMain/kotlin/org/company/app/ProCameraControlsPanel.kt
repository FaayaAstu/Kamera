package org.company.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kashif.cameraK.controller.CameraController
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.motion.MotionTrackingStatus
import com.kashif.cameraK.pro.ManualExposure
import com.kashif.cameraK.pro.ProCameraSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
internal fun ProCameraControlsPanel(controller: CameraController) {
    val state by controller.proCameraState.collectAsState()
    val scope = rememberCoroutineScope()
    val lensId = state.capabilities.cameraId
    var iso by remember(controller, lensId) { mutableStateOf("") }
    var shutterMs by remember(controller, lensId) { mutableStateOf("") }
    var focus by remember(controller, lensId) { mutableStateOf("") }
    var ev by remember(controller, lensId) { mutableStateOf("0") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Pro controls", color = Color.White)
        Text(
            "ISO ${state.capabilities.isoRange ?: "unavailable"}; shutter ns ${state.capabilities.exposureTimeRangeNs ?: "unavailable"}",
            color = Color.LightGray,
        )
        OutlinedTextField(
            iso,
            { iso = it },
            label = { Text("ISO — blank for auto") },
            enabled =
            !busy && state.capabilities.supportsManualExposure,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            shutterMs,
            { shutterMs = it },
            label = { Text("Shutter in ms — blank for auto") },
            enabled =
            !busy && state.capabilities.supportsManualExposure,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            focus,
            {
                focus = it
            },
            label = { Text("Focus 0 infinity / 1 near — blank for auto") },
            enabled =
            !busy && state.capabilities.supportsManualFocus,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            ev,
            {
                ev = it
            },
            label = { Text("EV stops (${state.capabilities.exposureCompensationRangeEv ?: "unavailable"})") },
            enabled =
            !busy && state.capabilities.exposureCompensationRangeEv != null,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy && lensId != null, onClick = {
                scope.launch {
                    busy = true
                    try {
                        require(iso.isBlank() == shutterMs.isBlank()) {
                            "Set ISO and shutter together, or leave both blank"
                        }
                        val exposure = if (iso.isBlank()) {
                            null
                        } else {
                            val ms = shutterMs.toDouble()
                            require(ms.isFinite() && ms > 0 && ms < Long.MAX_VALUE / 1_000_000.0) {
                                "Invalid shutter time"
                            }
                            ManualExposure(iso.toInt(), (ms * 1_000_000).toLong())
                        }
                        if (exposure != null) controller.setFlashMode(FlashMode.OFF)
                        controller.applyProCameraSettings(
                            ProCameraSettings(
                                exposure,
                                focus.takeIf {
                                    it.isNotBlank()
                                }?.toFloat(),
                                ev.toFloat(),
                            ),
                        )
                        message = "Applied"
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        message = error.message
                    } finally {
                        busy = false
                    }
                }
            }) { Text("Apply") }
            Button(enabled = !busy && lensId != null, onClick = {
                scope.launch {
                    busy = true
                    try {
                        controller.applyProCameraSettings(ProCameraSettings())
                        iso = ""
                        shutterMs = ""
                        focus = ""
                        ev = "0"
                        message = "Auto restored"
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        message = error.message
                    } finally {
                        busy = false
                    }
                }
            }) { Text("Auto") }
        }
        (message ?: state.errorMessage)?.let { Text(it, color = Color.White) }
        MotionControls(controller)
    }
}

@Composable
private fun MotionControls(controller: CameraController) {
    val motion by controller.motionState.collectAsState()
    Text("Motion: ${motion.status.name}", color = Color.White)
    MotionReadout(controller)
    motion.message?.let { Text(it, color = Color.LightGray) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { controller.startMotionTracking() }) { Text("Start") }
        Button(onClick = { controller.stopMotionTracking() }) { Text("Stop") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = motion.status == MotionTrackingStatus.TRACKING, onClick = {
            controller.calibrateMotion()
        }) { Text("Calibrate") }
        Button(onClick = { controller.clearMotionCalibration() }) { Text("Clear reference") }
    }
}

@Composable
internal fun MotionReadout(controller: CameraController, modifier: Modifier = Modifier) {
    val motion by controller.motionState.collectAsState()
    motion.attitude?.let {
        fun degrees(value: Double?): String = value?.let { angle -> "${(angle * 10).roundToInt() / 10.0}°" } ?: "—"
        Text(
            "Roll ${degrees(
                it.rollDegrees,
            )} · Pitch ${degrees(
                it.pitchDegrees,
            )}\nTilt ${degrees(it.tiltFromHorizontalDegrees)} · Reference ${degrees(it.referenceDeviationDegrees)}",
            modifier = modifier,
            color = Color.White,
        )
    }
}

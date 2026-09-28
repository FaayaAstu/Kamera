# Faaya pro camera extensions

This fork is based on upstream Kamera 1.2 plus upstream's fix for the
`kotlinx-coroutines-test` runtime dependency leak. Pro camera APIs and motion
tracking live in `cameraK`, including Android, iOS and desktop implementations.
The Faayaverse app has not been changed to consume this fork yet.

## Manual controls

After the camera is Ready, collect `controller.proCameraState`. Its capabilities
describe the currently bound lens. Re-query after a lens change. An unavailable
range means that control is unsupported; fixed-focus cameras reject manual focus.
The state reports successfully configured values, not measured sensor results.

```kotlin
val capabilities = controller.proCameraState.value.capabilities
if (capabilities.supportsManualExposure) {
    controller.setFlashMode(FlashMode.OFF)
    controller.applyProCameraSettings(
        ProCameraSettings(
            manualExposure = ManualExposure(iso = 100, exposureTimeNs = 10_000_000),
            focusPosition = 0.5f,
        ),
    )
}

// Restore autofocus / auto exposure and request +1 EV.
controller.applyProCameraSettings(ProCameraSettings(exposureCompensationEv = 1f))

// Fully automatic again.
controller.applyProCameraSettings(ProCameraSettings())
```

The values in the example must fit the selected lens's ranges. Check
`supportsManualFocus` before setting focusPosition. ISO and shutter are applied
together; partially automatic ISO/shutter modes are not provided. Focus position
is normalized: 0 infinity, 1 the nearest supported position, not a distance in metres.
Android maps this to diopters; iOS maps it to AVFoundation's reversed lensPosition.
EV is in stops and works only in auto exposure. Android quantizes EV to the
hardware step and returns the quantized settings. Manual exposure requires flash
OFF. Torch is a separate control and still requires device validation.

`applyProCameraSettings` suspends until the native control completion arrives
(timeout is five seconds plus the requested shutter duration), validates values and
throws for unavailable controls, invalid values or native failures. On a native failure `errorMessage` is set;
reapply valid settings before capturing. Control changes are serialized. Avoid
changing lenses, flash or settings during capture/control updates; lens switches
are also blocked while recording. Pro settings reset on a new lens. Android
restores settings when the same lens rebinds for rotation/analyzers/video.

Android uses Camera2 interop with CameraX 1.5.1. When pro controls are first used
with SPEED quality, capture switches from ZSL to minimize-latency mode so buffered
frames from before the setting change are not selected. iOS uses AVFoundation
custom exposure/focus and forces SPEED photo prioritization for manual exposure,
as required by Apple's photo pipeline to honor ISO and shutter values. Desktop
accepts automatic defaults and rejects unsupported manual controls.

## Motion and angle measurement

```kotlin
controller.startMotionTracking(intervalMs = 50) // main thread; opt-in
controller.motionState.collect { state ->
    val angle = state.attitude
    // angle?.rollDegrees, pitchDegrees, tiltFromHorizontalDegrees
    // angle?.referenceDeviationDegrees, rotationRateDegreesPerSecond
}

controller.calibrateMotion() // current gravity direction is reference; false if stale/unavailable
controller.clearMotionCalibration()
controller.stopMotionTracking()
```

Use a separate coroutine to collect the flow. The StateHolder also exposes
`proCameraState`, `motionState` and forwarding control methods.

- Android uses a fused game rotation vector (rotation vector fallback) and a
  gyroscope. iOS uses Core Motion device motion. No raw gyro integration/drift
  accumulation is performed in the library. Devices lacking the required sensors,
  including the iOS simulator and desktop, report UNAVAILABLE.
- Angles use the device's fixed portrait axes: x right, y top, z out of the screen.
  They do not rotate with the UI or change sign with front-camera mirroring.
- Roll is zero upright and null when flat, where gravity cannot determine roll.
  Pitch is -90 degrees screen-up/flat, zero upright, +90 screen-down/flat.
  Tilt from horizontal is zero flat and 90 upright.
- Calibration compares gravity directions. It measures pitch/roll deviation,
  not yaw, compass heading, or the angle to an arbitrary fabric plane. A known
  reference pose is needed for a non-horizontal subject. It does not deskew pixels.
- Rates are requested at 20–200 ms intervals (default 50 ms), not guaranteed by
  the OS. Sensor accuracy depends on hardware and movement; displayed decimal
  places are not an accuracy guarantee.
- Tracking automatically suspends in the background and resumes while requested.
  stopSession/cleanup stop it. Start/stop on the main thread. Calibration survives
  a temporary pause; clear it explicitly when changing the reference surface.
- Add `NSMotionUsageDescription` to the host iOS Info.plist; the sample includes it.

Successful file capture returns optional `motionAtCaptureRequest`, containing the
sensor sample and its age. Disabled, unavailable, stopped or older-than-250-ms
readings produce null. This is an in-memory result, not an EXIF tag. It is sampled
at the capture request, **not synchronized to the shutter exposure**; exact
exposure-timestamp synchronization requires a further native capture-metadata API.

## Sample and verification

The sample Settings panel contains capability-aware ISO/shutter/focus/EV inputs,
Apply/Auto buttons, motion Start/Stop and calibration. A live motion readout remains
on the preview after closing Settings. Camera Type choices use enumerated hardware.

Automated tests cover EV quantization, lens limits, manual/auto conflicts, missing
hardware, gravity normalization, known angle poses, calibration, stale/out-of-order
samples and stopped-sensor metadata. Native controls still require real-device QA:

1. Capture before/after changing ISO/shutter and compare EXIF values and brightness.
2. Test focus at infinity/near and restore auto; fixed-focus lens rejects manual.
3. Change EV and verify quantization/brightness; manual exposure rejects nonzero EV.
4. Switch front/back/auxiliary lenses and verify capabilities and auto reset.
5. Rotate, add/remove analyzers and start/stop video; check restored Android controls.
6. Verify portrait, landscape and flat motion signs; calibrate and pause/resume.
7. Capture with tracking disabled/enabled and check snapshot presence and age.

### Local validation (2026-09-28)

Checked in the primary `Faaya/Kamera` checkout on `faaya/feature/pro-camera`:

- `:cameraK:desktopTest`: 61 tests passed, including 8 new pro/motion tests.
- `:cameraK:compileDebugKotlinAndroid`: passed.
- `:cameraK:compileKotlinIosSimulatorArm64`: passed.
- `:Sample:compileDebugKotlinAndroid`: passed.
- `:Sample:compileKotlinIosSimulatorArm64`: passed.
- `spotlessCheck` and `git diff --check`: passed.

Used installed Gradle 8.14.3 and Android SDK 36; the repository's Gradle 8.11.1
wrapper download timed out. iOS compilation is for the simulator target; no
physical-device run, photo metadata comparison or sensor accuracy measurement
has been performed yet. Compile/test success does not replace the device checks
listed above.

The fork is unpublished. Choose Faaya-owned artifact coordinates/version before
publishing or integrating it into Faayaverse; upstream publishing coordinates have
not been used to release these changes.

References: [Camera2CameraControl](https://developer.android.com/reference/androidx/camera/camera2/interop/Camera2CameraControl),
[AVFoundation custom exposure](https://developer.apple.com/documentation/avfoundation/avcapturedevice/setexposuremodecustom(duration:iso:completionhandler:)),
[Android motion sensors](https://developer.android.com/develop/sensors-and-location/sensors/sensors_motion),
[Core Motion](https://developer.apple.com/documentation/coremotion/cmmotionmanager).

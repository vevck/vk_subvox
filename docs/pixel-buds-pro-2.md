# Subvox on Pixel Buds Pro 2: feasibility and port plan

## Short answer

The core idea carries over: segment the head-motion stream, DTW-match it against templates, and use a permutation test to check whether the signal is real. It can't run on the Mac, though. It has to be an **Android app on a phone that supports head tracking** (in practice a Pixel). The buds also give a **narrower signal** than AirPods, with no acceleration channel.

## Why not the existing macOS app

`HeadphoneMotionService` is built on `CMHeadphoneMotionManager`. That API only talks to Apple and Beats headphones over Apple's proprietary link, so Pixel Buds never show up in it on any Apple platform. Pixel Buds Pro 2 report head pose through Android's **head-tracker HID protocol**. Android's sensor framework picks that up and exposes it as a dynamic sensor of type `Sensor.TYPE_HEAD_TRACKER` (Android 13 / API 33+). macOS has no consumer for that protocol.

## What the buds expose, compared with AirPods

| Channel (macOS `MotionSample`) | AirPods (`CMHeadphoneMotionManager`) | Pixel Buds Pro 2 (`TYPE_HEAD_TRACKER`) |
|---|---|---|
| User acceleration `ax ay az` | ✅ | ❌ not reported |
| Rotation rate `rx ry rz` | ✅ | ✅ `values[3..5]`, rad/s |
| Attitude `pitch roll yaw` / quaternion | ✅ | ✅ derived from the rotation vector in `values[0..2]` |
| Gravity `gx gy gz` | ✅ | ❌ (the reference frame isn't guaranteed gravity-aligned) |
| Re-centring signal | n/a | ✅ `firstEventAfterDiscontinuity` |
| Sample rate | ~25 Hz | **unknown until measured**. The probe reports `minDelay` and the measured rate. |

What this means for decoding:

- The **acceleration ablation goes away**. Only the `rotation` and `attitude` feature sets apply. If jaw motion mostly shows up as linear acceleration at the ear, the buds will do worse than AirPods. That's the main scientific risk, and the permutation test exists to measure exactly that.
- **Segmentation still works**. `MotionFeatures.energy` is `|ω| + 5·|a|`, so with `a = 0` it reduces to rotation magnitude. The noise floor gets calibrated per device either way.
- **Re-centring has to be handled.** The head tracker can reset its reference frame, which would make attitude jump in the middle of a word. `HeadTrackerConverter` re-bases the orientation so it stays continuous and flags the event. `CaptureController.cancel()` lets the app drop a segment that is in progress when that happens.

## Unknowns the probe app resolves

These can only be answered on real hardware. They decide whether the port is worth the effort:

1. **Is the sensor visible to a third-party app?** The HID protocol is aimed at the audio spatializer. Whether `SensorManager.getDynamicSensorList(TYPE_HEAD_TRACKER)` returns it to an ordinary app on a given Pixel or firmware version has to be checked. If it doesn't, there's no supported route and the project stops here, or turns into reverse-engineering the buds' Bluetooth protocol.
2. **Does it need Spatial Audio / Head tracking turned on** in the buds' Bluetooth settings? Does it stream when no audio is playing?
3. **Sample rate and latency.** Faster than ~25 Hz would help, since articulation is fast.
4. **Smoothing.** Pose that's tuned for spatial audio may be low-pass filtered so heavily that jaw micro-motion is gone.
5. **Is there any signal?** Use **Hold while mouthing** in the probe, then compare the marked segments with rest in `motion.csv`.

## What's in this branch

```
android/
  core/   Pure Kotlin/JVM port of the decoder. No Android dependency, unit-tested.
          MotionSample (same CSV columns as macOS), FeatureSet (+ capability gating),
          MotionFeatures, DTW, TemplateClassifier, Evaluation (LOO + permutation test),
          CaptureController, HeadTrackerConverter (TYPE_HEAD_TRACKER → MotionSample).
  app/    "Subvox Probe": a single-Activity feasibility app. It lists dynamic sensors and
          spatializer state, streams the head tracker, shows the live rate, and records
          motion.csv / events.csv / meta.json.
```

`motion.csv` has the same columns as the macOS recorder, plus a trailing `discontinuity` column. AirPods and Pixel Buds sessions can go through the same analysis.

### Build

```sh
cd android
./gradlew :core:test                 # any machine with a JDK 17+
# With an Android SDK (Android Studio sets sdk.dir in local.properties):
./gradlew :app:installDebug
adb pull /sdcard/Android/data/com.joeblau.subvox.probe/files/Sessions .
```

The `:app` module is only included when an Android SDK is configured, so `:core:test` works without one.

## Plan

| Phase | Goal | Exit criterion |
|---|---|---|
| **0. Probe** (this branch) | Find out whether a third-party app gets usable head-tracker data. | The sensor is visible, the rate is known, and marked mouthing segments look visibly different from rest. |
| **1. Signal test** | Train/Evaluate on the phone, using `core` as is. Same 5-word script, 10 reps, rotation vs. attitude vs. both. | Permutation p < 0.05 on at least one feature set. |
| **2. Parity app** | Compose UI matching Sensors / Train / Decode, dataset persistence, Bluetooth SCO mic + on-device `SpeechRecognizer` for the reference transcript. | Feature parity with macOS for the channels that exist. |
| **3. Cross-device study** | Same protocol on AirPods and Pixel Buds, analysed from the shared CSV format. | A direct comparison of the two devices. |

Phase 2 depends on Phase 1. If the buds' signal doesn't beat chance, a full UI isn't worth building.

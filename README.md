# VCam Modern

A non-root virtual camera for Android 11–15 (API 30–35), built as a native
EGL/GLES 3.0 pipeline with a guest container. No Xposed, Zygisk, Magisk, root,
or platform signature is required.

## Upstream VCamera

`andvipgroup/VCamera` (AGPL-3.0) is a UI shell, not a camera engine:

| Component | State |
|---|---|
| `:opensdk` module referenced by `settings.gradle` | absent from the repository |
| `virtual.camera.camera:camera:1.0.0` | hosted on `andvipgroup/CameraLib`, repository deleted, artifact 404 |
| `hack.jar` (VirtualApp runtime) | referenced, never present |
| Camera1/Camera2/SurfaceTexture/MediaCodec code | none in its 127 files |

It cannot resolve its own dependencies and contains no interception plane to
migrate. This project keeps the product goal and implements the engine that the
missing binaries used to provide.

## Architecture

```
Media selector ──► VideoFeeder (MediaExtractor + MediaCodec)
                        │ Surface
                        ▼
                 SourceRenderer ──► BufferRing ──► SurfaceSink ──► Surface
 NV21 / RGBA ───────────┘   (EGL, GLES 3.0)        (eglSwapBuffers)
```

- `app/src/main/cpp/NativeBridge.cpp` — EGL core, buffer ring, sink,
  `SourceRenderer`, session thread, JNI.
- `com.vcam.core.VirtualCamera` / `NativeBridge` — session handle and feed API.
- `com.vcam.core.VideoFeeder` — decodes a local video straight into the
  session's external texture, optionally looping.
- `com.vcam.sandbox.*` — local classloader container, guest contexts, camera
  broker, MediaCodec proxy.
- `com.vcam.ui.MainActivity` — media selection, recent list, host preview,
  guest management.

## Feed paths

| Mode | Path | CPU pixel copies |
|---|---|---|
| Video | MediaCodec → Surface → `samplerExternalOES` | 0 |
| Image | `ImageDecoder` → RGBA → `glTexSubImage2D` once | 1 upload |
| NV21 | direct buffer → Y/UV textures → shader matrix | 1 upload |
| Test | synthetic pattern in the ring FBO | 0 |

Sources are center-cropped to the session aspect, not stretched.

## Build

```bash
./gradlew :app:assembleRelease
```

CI (`.github/workflows/build.yml`) installs JDK 21, NDK r28, CMake 3.22.1 and
build-tools 35, builds, checks `zipalign -P 16`, verifies the signature, uploads
`vcam-modern-release.apk`, and updates the `vcam-modern-v3.0` release on `main`.

Requirements: NDK r28+, `-Wl,-z,max-page-size=16384`, arm64-v8a.

## Use

1. Install `vcam-modern-release.apk`.
2. Pick **Video** or **Image** (SAF, persisted read grant, no storage
   permission). The preview starts immediately; recent selections stay listed.
3. **Test** returns to the synthetic pattern.
4. **Install guest APK** adds a VCam-aware APK to the container. Guests receive
   frames through `com.vcam.api.SandboxEnv`, not through the platform camera.

## Limits

- An arbitrary unmodified APK still gets the physical camera. Redirecting a
  third-party app without hooks requires repackaging it and routing its
  `openCamera` call sites to `SandboxEnv`; the container deliberately does not
  claim that it can substitute frames for code that never calls it.
- The sandbox is a classloader container: guests share this process and must be
  built against `com.vcam.api` to obtain a virtual camera.
- No runtime test has been performed on a physical device in this environment.
  Build, packaging, alignment, and signature checks are automated in CI.

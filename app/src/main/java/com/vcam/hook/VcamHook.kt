// language: Kotlin, file: VcamHook.kt, target: LSPosed API 93+, Android 11-15
package com.vcam.hook

import android.app.ActivityManager
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.HardwareBuffer
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Size
import android.view.Surface
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import kotlin.math.abs

class VcamHook : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        if (lpparam.packageName == "android") return
        if (lpparam.packageName == MODULE_PACKAGE) return

        val cl = lpparam.classLoader
        safe("addTarget") { hookAddTarget(cl) }
        safe("addTargetList") { hookAddTargetList(cl) }
        safe("sessionList") { hookCreateSessionList(cl) }
        safe("sessionConfig") { hookCreateSessionConfig(cl) }
        safe("legacyOutputs") { hookLegacyOutputConfigurations(cl) }
        safe("repeating") { hookSetRepeatingRequest(cl) }
        safe("sessionClose") { hookSessionClose(cl) }
        safe("deviceClose") { hookDeviceClose(cl) }
        safe("openCamera") { hookOpenCamera(cl) }
    }

    // ---------------------------------------------------------------- utilities

    private inline fun safe(tag: String, block: () -> Unit) {
        runCatching(block).onFailure {
            XposedBridge.log("[vcam] hook $tag unavailable: $it")
        }
    }

    private fun implClass(cl: ClassLoader): Class<*>? = runCatching {
        XposedHelpers.findClass("android.hardware.camera2.impl.CameraDeviceImpl", cl)
    }.getOrNull()

    private fun sessionImplClass(cl: ClassLoader): Class<*>? = runCatching {
        XposedHelpers.findClass(
            "android.hardware.camera2.impl.CameraDeviceImpl\$CameraCaptureSessionImpl", cl)
    }.getOrNull()

    private fun manager(): CameraManager? = SlotRegistry.cameraManager()

    // ------------------------------------------------------- 1. addTarget hooks

    private fun hookAddTarget(cl: ClassLoader) {
        val builder = runCatching {
            XposedHelpers.findClass("android.hardware.camera2.CaptureRequest\$Builder", cl)
        }.getOrNull() ?: return

        XposedHelpers.findAndHookMethod(builder, "addTarget", Surface::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val original = param.args[0] as? Surface ?: return
                        val slot = SlotRegistry.slotFor(original, manager()) ?: return
                        param.args[0] = slot.substitute
                    }
                }
            })
    }

    private fun hookAddTargetList(cl: ClassLoader) {
        val builder = runCatching {
            XposedHelpers.findClass("android.hardware.camera2.CaptureRequest\$Builder", cl)
        }.getOrNull() ?: return

        XposedHelpers.findAndHookMethod(builder, "addTarget", List::class.java,
            object : XC_MethodHook() {
                @Suppress("UNCHECKED_CAST")
                override fun beforeHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val list = param.args[0] as? List<Surface> ?: return
                        val cm = manager()
                        param.args[0] = list.map { surface ->
                            SlotRegistry.slotFor(surface, cm)?.substitute ?: surface
                        }
                    }
                }
            })
    }

    // ------------------------------ 2. createCaptureSession(List<Surface>, ..)

    private fun hookCreateSessionList(cl: ClassLoader) {
        val impl = implClass(cl) ?: return

        XposedHelpers.findAndHookMethod(
            impl, "createCaptureSession",
            List::class.java,
            "android.hardware.camera2.CameraCaptureSession\$StateCallback",
            Handler::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    runCatching {
                        @Suppress("UNCHECKED_CAST")
                        val outputs = param.args[0] as? List<Surface> ?: return
                        val cm = manager()
                        val slots = ArrayList<SlotRegistry.Slot>(outputs.size)
                        val rewritten = ArrayList<Surface>(outputs.size)

                        for (surface in outputs) {
                            val slot = SlotRegistry.slotFor(surface, cm)
                            if (slot == null) {
                                rewritten.add(surface)
                            } else {
                                SlotRegistry.acquire(slot)
                                slots.add(slot)
                                rewritten.add(slot.substitute)
                            }
                        }
                        if (slots.isEmpty()) return

                        val callback = param.args[1] as? CameraCaptureSession.StateCallback
                        param.args[0] = rewritten
                        if (callback != null) {
                            param.args[1] = ForwardingCallback(callback, slots)
                        }
                    }
                }
            })
    }

    // --------------------------- 3. createCaptureSession(SessionConfiguration)

    private fun hookCreateSessionConfig(cl: ClassLoader) {
        val impl = implClass(cl) ?: return
        val configClass = runCatching {
            XposedHelpers.findClass("android.hardware.camera2.params.SessionConfiguration", cl)
        }.getOrNull() ?: return

        XposedHelpers.findAndHookMethod(impl, "createCaptureSession", configClass,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val config = param.args[0] as? SessionConfiguration ?: return
                        val cm = manager()
                        val slots = ArrayList<SlotRegistry.Slot>()

                        val rebuiltOutputs = ArrayList<OutputConfiguration>()
                        for (output in config.outputSurfaces) {
                            rebuiltOutputs.add(rewriteOutput(output, cm, slots) ?: output)
                        }
                        if (slots.isEmpty()) return

                        val rebuilt = SessionConfiguration(
                            config.sessionType,
                            rebuiltOutputs,
                            config.executor,
                            ForwardingCallback(config.stateCallback, slots),
                        )
                        config.inputConfiguration?.let { input ->
                            runCatching { rebuilt.setInputConfiguration(input) }
                        }
                        param.args[0] = rebuilt
                    }
                }
            })
    }

    private fun rewriteOutput(
        config: OutputConfiguration,
        cm: CameraManager?,
        slots: MutableList<SlotRegistry.Slot>,
    ): OutputConfiguration? = runCatching {
        val originals = config.surfaces
        if (originals.isEmpty()) return null

        val substitutes = ArrayList<Surface>(originals.size)
        var changed = false
        for (surface in originals) {
            val slot = SlotRegistry.slotFor(surface, cm)
            if (slot == null) {
                substitutes.add(surface)
            } else {
                SlotRegistry.acquire(slot)
                slots.add(slot)
                substitutes.add(slot.substitute)
                changed = true
            }
        }
        if (!changed) return null

        val copy = if (substitutes.size > 1) {
            OutputConfiguration(substitutes)
        } else {
            OutputConfiguration(substitutes.first())
        }

        runCatching {
            XposedHelpers.getObjectField(config, "mPhysicalCameraId")?.let {
                copy.setPhysicalCameraId(it as String)
            }
        }
        runCatching { copy.setTimestampBase(config.timestampBase) }
        runCatching { copy.setStreamUseCase(config.streamUseCase) }
        runCatching { copy.setDynamicRangeProfile(config.dynamicRangeProfile) }
        runCatching { copy.setMirrorMode(config.mirrorMode) }
        if (substitutes.size > 1) runCatching { copy.enableSurfaceSharing() }
        copy
    }.getOrNull()

    // ------------------------------- 4. legacy OutputConfiguration entry point

    private fun hookLegacyOutputConfigurations(cl: ClassLoader) {
        val impl = implClass(cl) ?: return

        runCatching {
            XposedHelpers.findAndHookMethod(
                impl, "createCaptureSessionByOutputConfigurations",
                List::class.java,
                "android.hardware.camera2.CameraCaptureSession\$StateCallback",
                Handler::class.java,
                Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    @Suppress("UNCHECKED_CAST")
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        runCatching {
                            val configs = param.args[0] as? List<OutputConfiguration> ?: return
                            val slots = ArrayList<SlotRegistry.Slot>()
                            val rewritten = configs.map { rewriteOutput(it, manager(), slots) ?: it }
                            if (slots.isEmpty()) return

                            val callback = param.args[1]
                                as? CameraCaptureSession.StateCallback ?: return
                            param.args[0] = ArrayList(rewritten)
                            param.args[1] = ForwardingCallback(callback, slots)
                        }
                    }
                })
        }
    }

    // --------------------------------- 5. arm the sink on the preview request

    private fun hookSetRepeatingRequest(cl: ClassLoader) {
        val sessionImpl = sessionImplClass(cl) ?: return

        XposedHelpers.findAndHookMethod(
            sessionImpl, "setRepeatingRequest",
            CaptureRequest::class.java,
            "android.hardware.camera2.CameraCaptureSession\$CaptureCallback",
            Handler::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null) return
                    runCatching {
                        val request = param.args[0] as? CaptureRequest ?: return
                        for (surface in request.targets) {
                            val slot = SlotRegistry.bySubstitute(surface) ?: continue
                            SlotRegistry.arm(slot)
                        }
                    }
                }
            })
    }

    // ------------------------------------------------------------- 6. teardown

    private fun hookSessionClose(cl: ClassLoader) {
        val sessionImpl = sessionImplClass(cl) ?: return

        XposedHelpers.findAndHookMethod(sessionImpl, "close", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                runCatching { releaseSlotsOf(param.thisObject) }
            }
        })
    }

    private fun hookDeviceClose(cl: ClassLoader) {
        val impl = implClass(cl) ?: return

        XposedHelpers.findAndHookMethod(impl, "close", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                runCatching {
                    @Suppress("UNCHECKED_CAST")
                    val tagged = XposedHelpers.getAdditionalInstanceField(
                        param.thisObject, KEY_SLOTS) as? MutableSet<SlotRegistry.Slot>

                    tagged?.toList()?.forEach { slot ->
                        slot.owners = 1
                        SlotRegistry.release(slot)
                    }
                    XposedHelpers.removeAdditionalInstanceField(param.thisObject, KEY_SLOTS)
                }
            }
        })
    }

    private fun releaseSlotsOf(session: Any) {
        @Suppress("UNCHECKED_CAST")
        val slots = XposedHelpers.getAdditionalInstanceField(session, KEY_SLOTS)
            as? List<SlotRegistry.Slot> ?: return
        slots.forEach(SlotRegistry::release)
        XposedHelpers.removeAdditionalInstanceField(session, KEY_SLOTS)
    }

    private fun hookOpenCamera(cl: ClassLoader) {
        val managerClass = runCatching {
            XposedHelpers.findClass("android.hardware.camera2.CameraManager", cl)
        }.getOrNull() ?: return

        XposedHelpers.findAndHookMethod(
            managerClass, "openCamera",
            String::class.java,
            "android.hardware.camera2.CameraDevice\$StateCallback",
            Handler::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    param.throwable?.let {
                        XposedBridge.log("[vcam] openCamera(${param.args[0]}) failed: $it")
                    }
                }
            })
    }

    // -------------------------------------------------------- callback proxy

    private class ForwardingCallback(
        private val delegate: CameraCaptureSession.StateCallback,
        private val slots: List<SlotRegistry.Slot>,
    ) : CameraCaptureSession.StateCallback() {

        override fun onConfigured(session: CameraCaptureSession) {
            runCatching {
                XposedHelpers.setAdditionalInstanceField(session, KEY_SLOTS, slots)

                val device = XposedHelpers.getObjectField(session, "mDevice")
                @Suppress("UNCHECKED_CAST")
                val tagged = runCatching {
                    XposedHelpers.getAdditionalInstanceField(device, KEY_SLOTS)
                }.getOrNull() as? MutableSet<SlotRegistry.Slot>
                    ?: mutableSetOf<SlotRegistry.Slot>().also {
                        XposedHelpers.setAdditionalInstanceField(device, KEY_SLOTS, it)
                    }
                tagged.addAll(slots)
            }
            delegate.onConfigured(session)
        }

        override fun onConfigureFailed(session: CameraCaptureSession) {
            runCatching { slots.forEach(SlotRegistry::release) }
            delegate.onConfigureFailed(session)
        }

        override fun onReady(session: CameraCaptureSession) {
            runCatching { delegate.onReady(session) }
        }

        override fun onActive(session: CameraCaptureSession) {
            runCatching { delegate.onActive(session) }
        }

        override fun onClosed(session: CameraCaptureSession) {
            runCatching { releaseSlotsOf(session) }
            runCatching { delegate.onClosed(session) }
        }

        override fun onSurfacePrepared(session: CameraCaptureSession, surface: Surface) {
            runCatching { delegate.onSurfacePrepared(session, surface) }
        }
    }

    companion object {
        const val MODULE_PACKAGE = "com.vcam.hook"
        const val KEY_SLOTS = "vcam.slots"
    }
}

// ---------------------------------------------------------------------------
// Slot registry: original Surface -> PRIVATE ImageReader Surface + native sink
// ---------------------------------------------------------------------------

internal object SlotRegistry {

    private const val MAX_IMAGES = 3

    internal class Slot(
        val original: Surface,
        val substitute: Surface,
        val reader: ImageReader,
        val width: Int,
        val height: Int,
    ) {
        var handle: Long = 0L
        var owners: Int = 0
        var armed: Boolean = false
    }

    private val byOriginal = HashMap<Surface, Slot>()
    private val bySubstitute = HashMap<Surface, Slot>()

    private val drainThread = HandlerThread("vcam-drain").apply { start() }
    private val drainHandler = Handler(drainThread.looper)

    private var appContext: Context? = null

    fun cameraManager(): CameraManager? {
        val ctx = appContext ?: resolveContext() ?: return null
        return runCatching { ctx.getSystemService(CameraManager::class.java) }.getOrNull()
    }

    private fun resolveContext(): Context? {
        val ctx = runCatching {
            Class.forName("android.app.ActivityThread")
                .getDeclaredMethod("currentApplication")
                .invoke(null) as? Context
        }.getOrNull()
        appContext = ctx?.applicationContext
        return appContext
    }

    @Synchronized
    fun slotFor(original: Surface, cameraManager: CameraManager?): Slot? {
        byOriginal[original]?.let { return it }
        if (!original.isValid) return null

        // 1. dimensions of the app's own buffer, straight from its ANativeWindow
        val dims = NativeBridge.surfaceSize(original)
        var width = dims?.getOrNull(0) ?: 0
        var height = dims?.getOrNull(1) ?: 0
        if (width <= 0 || height <= 0) {
            width = 1280
            height = 720
        }

        // 2. snap to a dimension the device advertises for PRIVATE output
        val size = snapToPrivate(width, height, cameraManager)

        // 3. replacement consumer surface
        val reader = createReader(size.width, size.height) ?: return null

        // 4. native render thread writing into the original surface
        val handle = NativeBridge.createSession(
            original, size.width, size.height,
            NativeBridge.MATRIX_BT709, true,
        )
        if (handle == 0L) {
            runCatching { reader.close() }
            XposedBridge.log("[vcam] native session refused ${size.width}x${size.height}")
            return null
        }

        val slot = Slot(original, reader.surface, reader, size.width, size.height)
        slot.handle = handle
        byOriginal[original] = slot
        bySubstitute[slot.substitute] = slot
        XposedBridge.log("[vcam] slot ${size.width}x${size.height} handle=$handle")
        return slot
    }

    @Synchronized
    fun bySubstitute(surface: Surface): Slot? = bySubstitute[surface]

    @Synchronized
    fun acquire(slot: Slot) {
        slot.owners++
    }

    @Synchronized
    fun arm(slot: Slot) {
        if (slot.armed || slot.handle == 0L) return

        if (!ForegroundGate.isForeground(appContext)) {
            XposedBridge.log("[vcam] arm deferred: process not foreground")
            return
        }

        slot.armed = true
        runCatching { NativeBridge.setActive(slot.handle, true) }
        XposedBridge.log("[vcam] armed ${slot.width}x${slot.height}")
    }

    @Synchronized
    fun release(slot: Slot) {
        if (slot.owners > 0) slot.owners--
        if (slot.owners > 0) return

        if (slot.handle != 0L) {
            runCatching { NativeBridge.setActive(slot.handle, false) }
            runCatching { NativeBridge.destroySession(slot.handle) }
            slot.handle = 0L
        }
        byOriginal.remove(slot.original)
        bySubstitute.remove(slot.substitute)
        runCatching {
            slot.reader.setOnImageAvailableListener(null, null)
            slot.reader.close()
        }
        XposedBridge.log("[vcam] released ${slot.width}x${slot.height}")
    }

    @Synchronized
    fun releaseAll() {
        byOriginal.values.toList().forEach { slot ->
            slot.owners = 1
            release(slot)
        }
    }

    private fun snapToPrivate(width: Int, height: Int,
                              cameraManager: CameraManager?): Size {
        val manager = cameraManager ?: return Size(width, height)
        val sizes = runCatching {
            manager.cameraIdList.asSequence()
                .mapNotNull { id ->
                    manager.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                }
                .flatMap { map ->
                    map.getOutputSizes(ImageFormat.PRIVATE)?.asSequence() ?: emptySequence()
                }
                .distinct()
                .toList()
        }.getOrElse { emptyList() }

        if (sizes.isEmpty()) return Size(width, height)
        if (sizes.any { it.width == width && it.height == height }) return Size(width, height)

        return sizes.minByOrNull { abs(it.width - width) + abs(it.height - height) }
            ?: Size(width, height)
    }

    private fun createReader(width: Int, height: Int): ImageReader? {
        val usage = HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or
                    HardwareBuffer.USAGE_GPU_FRAMEBUFFER

        // The 5-arg overload is @SystemApi; it is the only way to request GPU
        // usage on a PRIVATE reader. Fall back to the public 4-arg form.
        val reader = runCatching {
            XposedHelpers.callStaticMethod(
                ImageReader::class.java,
                "newInstance",
                width, height, ImageFormat.PRIVATE, MAX_IMAGES, usage,
            ) as ImageReader
        }.getOrElse {
            runCatching {
                ImageReader.newInstance(width, height, ImageFormat.PRIVATE, MAX_IMAGES)
            }.getOrElse { error ->
                XposedBridge.log("[vcam] PRIVATE reader refused ${width}x$height: $error")
                return null
            }
        }

        // Nothing consumes these frames; the listener only recycles buffers so
        // the producer never stalls on a full queue.
        reader.setOnImageAvailableListener({ r ->
            runCatching { r.acquireLatestImage()?.close() }
        }, drainHandler)
        return reader
    }
}

// ---------------------------------------------------------------------------
// Android 14/15 foreground gating
// ---------------------------------------------------------------------------

internal object ForegroundGate {

    fun isForeground(context: Context?): Boolean {
        val ctx = context ?: return true
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return true
        val processes = runCatching { am.runningAppProcesses }.getOrNull() ?: return true
        val self = processes.firstOrNull { it.pid == Process.myPid() } ?: return true
        return self.importance <=
               ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
    }
}

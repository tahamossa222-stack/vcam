// language: Kotlin, file: GuestHost.kt
package com.vcam.sandbox

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.vcam.api.SandboxEntry
import com.vcam.api.SandboxEnv
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Owns one guest instance: a thread with its own Looper, the entry object, and
 * its lifecycle. A guest that throws during creation is marked failed and its
 * thread exits; the host process is untouched.
 */
class GuestHost(
    val guest: GuestPackage,
    private val loader: ClassLoader,
    private val context: GuestContext,
) {

    @Volatile
    private var thread: Thread? = null

    @Volatile
    private var handler: Handler? = null

    @Volatile
    private var entry: SandboxEntry? = null

    @Volatile
    var isRunning: Boolean = false
        private set

    @Volatile
    var lastError: String? = null
        private set

    fun start() {
        if (isRunning) return

        val ready = CountDownLatch(1)

        val worker = Thread({
            Looper.prepare()
            handler = Handler(Looper.myLooper()!!)

            try {
                val instance = loader.loadClass(guest.entryClass)
                    .getDeclaredConstructor()
                    .newInstance() as SandboxEntry

                instance.onCreate(SandboxEnv(context, guest.packageName))
                entry = instance
                isRunning = true
            } catch (t: Throwable) {
                lastError = t.toString()
                Log.e(TAG, "guest ${guest.packageName} failed to start", t)
            } finally {
                ready.countDown()
            }

            if (isRunning) Looper.loop()
        }, "guest-${guest.packageName}")

        worker.start()
        ready.await(START_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        thread = worker

        if (!isRunning) {
            worker.join(1000)
            thread = null
            handler = null
        }
    }

    fun stop() {
        val h = handler
        val worker = thread
        if (h == null || worker == null) {
            isRunning = false
            return
        }

        h.post {
            runCatching { entry?.onDestroy() }
                .onFailure { Log.w(TAG, "guest ${guest.packageName} teardown failed: $it") }
            entry = null
            isRunning = false
            Looper.myLooper()?.quitSafely()
        }

        runCatching { worker.join(STOP_TIMEOUT_MS) }
        thread = null
        handler = null
    }

    private companion object {
        const val TAG = "vcam-sandbox"
        const val START_TIMEOUT_MS = 5000L
        const val STOP_TIMEOUT_MS = 3000L
    }
}

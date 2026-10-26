package com.mthtamim.phonelink

import android.content.Context
import android.content.Intent
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Blocking QR/barcode scan for flow threads in Automation Lab v1.4.3.
 *
 * Same pattern as FlowDialogs: launch FlowScanActivity and wait on a
 * CountDownLatch for one decoded code. Never called on the main
 * thread (guarded, logs and returns null). The result/latch holders
 * are shared statics, so concurrent scans from different flow
 * threads may interfere — a later call overwrites the pending
 * latch/call-id, and a stale activity finish is ignored via the
 * call-id check in complete(). Flows should avoid overlapping scans.
 */
object FlowScan {

    @Volatile
    var result: String? = null

    @Volatile
    var latch: CountDownLatch? = null

    @Volatile
    var currentCallId: Long = -1L

    private val nextId = AtomicLong(0L)

    /**
     * Called by FlowScanActivity when scanning finishes (code found,
     * cancelled, or timed out). Only the latch belonging to the
     * currently pending call is counted down.
     */
    fun complete(callId: Long, value: String?) {
        if (callId == currentCallId) {
            result = value
            latch?.countDown()
        }
    }

    fun scan(ctx: Context, timeoutSeconds: Int): String? {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            FlowStore.log(ctx, "qr_scan called on main thread, ignored")
            return null
        }
        val id = nextId.incrementAndGet()
        val l = CountDownLatch(1)
        result = null
        currentCallId = id
        latch = l
        try {
            val intent = Intent(ctx, FlowScanActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.putExtra("call_id", id)
            intent.putExtra("timeout", timeoutSeconds)
            ctx.startActivity(intent)
        } catch (e: Exception) {
            FlowStore.log(ctx, "qr_scan failed to start: " + e.message)
            return null
        }
        try {
            l.await((timeoutSeconds + 10).toLong(), TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
        }
        return result
    }
}

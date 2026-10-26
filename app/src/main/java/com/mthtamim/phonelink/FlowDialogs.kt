package com.mthtamim.phonelink

import android.content.Context
import android.content.Intent
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Blocking dialog helpers for flow threads in Automation Lab v1.3.0.
 *
 * Each function launches FlowDialogActivity and waits on a CountDownLatch
 * (up to 60 seconds) for the user to answer. They must NEVER be called on
 * the main thread: a main-thread call logs via FlowStore and returns
 * immediately (null / no-op) instead of blocking the UI thread.
 *
 * NOTE on concurrency: the result/latch holders below are shared statics,
 * so concurrent dialog calls from different flow threads may interfere
 * with each other (a later call overwrites the pending latch/call-id, and
 * a stale activity finish is ignored via the call-id check in complete()).
 * This is accepted behaviour for v1.3.0; flows should avoid overlapping
 * dialog calls.
 */
object FlowDialogs {

    @Volatile
    var result: String? = null

    @Volatile
    var latch: CountDownLatch? = null

    @Volatile
    var currentCallId: Long = -1L

    private val nextId = AtomicLong(0L)

    /**
     * Called by FlowDialogActivity when a dialog finishes. Only the latch
     * belonging to the currently pending call is counted down, so a stale
     * finish from a previous timed-out call can never release (or answer)
     * a newer call.
     */
    fun complete(callId: Long, value: String?) {
        if (callId == currentCallId) {
            result = value
            latch?.countDown()
        }
    }

    private fun onMainThread(): Boolean {
        return Looper.myLooper() == Looper.getMainLooper()
    }

    private fun startAndWait(
        ctx: Context,
        kind: String,
        title: String,
        text: String,
        def: String,
        seconds: Int,
        prompt: String
    ): String? {
        val id = nextId.incrementAndGet()
        val l = CountDownLatch(1)
        result = null
        currentCallId = id
        latch = l
        try {
            val intent = Intent(ctx, FlowDialogActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.putExtra("kind", kind)
            intent.putExtra("call_id", id)
            intent.putExtra("title", title)
            intent.putExtra("text", text)
            intent.putExtra("def", def)
            intent.putExtra("seconds", seconds)
            intent.putExtra("prompt", prompt)
            ctx.startActivity(intent)
        } catch (e: Exception) {
            FlowStore.log(ctx, "dialog " + kind + " failed to start: " + e.message)
            return null
        }
        try {
            l.await(60, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
        }
        return result
    }

    fun input(ctx: Context, title: String, def: String): String? {
        if (onMainThread()) {
            FlowStore.log(ctx, "dialog input called on main thread, ignored")
            return null
        }
        return startAndWait(ctx, "input", title, "", def, 0, "")
    }

    fun message(ctx: Context, title: String, text: String) {
        if (onMainThread()) {
            FlowStore.log(ctx, "dialog message called on main thread, ignored")
            return
        }
        startAndWait(ctx, "message", title, text, "", 0, "")
    }

    fun progress(ctx: Context, title: String, seconds: Int) {
        if (onMainThread()) {
            FlowStore.log(ctx, "dialog progress called on main thread, ignored")
            return
        }
        startAndWait(ctx, "progress", title, "", "", seconds, "")
    }

    fun date(ctx: Context, title: String): String? {
        if (onMainThread()) {
            FlowStore.log(ctx, "dialog date called on main thread, ignored")
            return null
        }
        return startAndWait(ctx, "date", title, "", "", 0, "")
    }

    fun time(ctx: Context, title: String): String? {
        if (onMainThread()) {
            FlowStore.log(ctx, "dialog time called on main thread, ignored")
            return null
        }
        return startAndWait(ctx, "time", title, "", "", 0, "")
    }

    fun listen(ctx: Context, prompt: String): String? {
        if (onMainThread()) {
            FlowStore.log(ctx, "dialog listen called on main thread, ignored")
            return null
        }
        return startAndWait(ctx, "listen", "", "", "", 0, prompt)
    }

    fun pickFile(ctx: Context): String? {
        if (onMainThread()) {
            FlowStore.log(ctx, "dialog pickFile called on main thread, ignored")
            return null
        }
        return startAndWait(ctx, "pickFile", "", "", "", 0, "")
    }

    fun pickDir(ctx: Context): String? {
        if (onMainThread()) {
            FlowStore.log(ctx, "dialog pickDir called on main thread, ignored")
            return null
        }
        return startAndWait(ctx, "pickDir", "", "", "", 0, "")
    }
}

package com.mthtamim.phonelink

/** Process-wide flags for the app lock and one-shot camera snaps. */
object SessionState {
    @Volatile var unlocked: Boolean = false

    /** Next SnapService shot uses the front camera (intruder selfie). */
    @Volatile var snapFront: Boolean = false
    /** v2.16: >0 = SnapService records video for this many seconds. */
    @Volatile var videoSecs: Int = 0
}

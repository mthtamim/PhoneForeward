package com.mthtamim.phonelink

/** Process-wide flags for the app lock and one-shot camera snaps. */
object SessionState {
    @Volatile var unlocked: Boolean = false

    /** Next SnapService shot uses the front camera (intruder selfie). */
    @Volatile var snapFront: Boolean = false
}

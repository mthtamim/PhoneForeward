package com.mthtamim.phonelink

/** Process-wide "app unlocked" flag for the app lock. */
object SessionState {
    @Volatile var unlocked: Boolean = false
}

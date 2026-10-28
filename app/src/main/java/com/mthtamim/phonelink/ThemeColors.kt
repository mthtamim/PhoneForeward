package com.mthtamim.phonelink

import android.content.Context
import android.content.res.ColorStateList
import android.util.TypedValue
import androidx.core.content.ContextCompat
import com.google.android.material.R as MaterialR

/**
 * v2.25.1: the single place that resolves a colour from the ACTIVE
 * theme (not from a fixed colour resource). Fixed resources such as
 * R.color.md_onSurface only follow light/dark (values vs
 * values-night); they never follow the v2.25.0 glass themes, whose
 * colours live in theme attributes (colorPrimary, colorSurface,
 * ...). Every activity helper in LocalizedActivity delegates here,
 * and non-activity code (AppLockUi, FlowCanvasView) calls this
 * object directly with its own context. Each resolver falls back
 * to the old fixed colour, so a failure can never produce a
 * transparent/black surprise — it just looks like Emerald Dark.
 */
object ThemeColors {

    fun resolve(ctx: Context, attr: Int, fallbackRes: Int): Int {
        return try {
            val out = TypedValue()
            if (ctx.theme.resolveAttribute(attr, out, true)) {
                if (out.type >= TypedValue.TYPE_FIRST_COLOR_INT &&
                    out.type <= TypedValue.TYPE_LAST_COLOR_INT
                ) {
                    out.data
                } else if (out.resourceId != 0) {
                    ContextCompat.getColor(ctx, out.resourceId)
                } else {
                    ContextCompat.getColor(ctx, fallbackRes)
                }
            } else {
                ContextCompat.getColor(ctx, fallbackRes)
            }
        } catch (_: Exception) {
            try {
                ContextCompat.getColor(ctx, fallbackRes)
            } catch (_: Exception) {
                0
            }
        }
    }

    fun primary(ctx: Context): Int =
        resolve(ctx, MaterialR.attr.colorPrimary, R.color.pl_mint)

    fun onSurface(ctx: Context): Int =
        resolve(ctx, MaterialR.attr.colorOnSurface, R.color.md_onSurface)

    fun onBackground(ctx: Context): Int =
        resolve(ctx, MaterialR.attr.colorOnBackground, R.color.md_onBackground)

    fun onSurfaceVariant(ctx: Context): Int =
        resolve(ctx, MaterialR.attr.colorOnSurfaceVariant, R.color.md_onSurfaceVariant)

    fun outline(ctx: Context): Int =
        resolve(ctx, MaterialR.attr.colorOutline, R.color.md_outline)

    fun surfaceVariant(ctx: Context): Int =
        resolve(ctx, MaterialR.attr.colorSurfaceVariant, R.color.md_surfaceVariant)

    fun secondaryContainer(ctx: Context): Int =
        resolve(ctx, MaterialR.attr.colorSecondaryContainer, R.color.md_secondaryContainer)

    /** Bottom-nav tint for GLASS themes only: checked = theme accent,
     *  unchecked = theme onSurfaceVariant. Emerald Dark keeps the
     *  old XML selector (callers branch on app_theme), so its
     *  checked #2FD68F / unchecked #7E938A (night) are untouched. */
    fun navTint(ctx: Context): ColorStateList {
        return ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_checked),
                intArrayOf()
            ),
            intArrayOf(primary(ctx), onSurfaceVariant(ctx))
        )
    }
}

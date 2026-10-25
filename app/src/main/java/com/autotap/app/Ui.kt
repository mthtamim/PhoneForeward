package com.autotap.app

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.SeekBar
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Design system - EXACTLY FocusLock's system, as specified by Tamim.
 * Flat bento cards, 16px gaps, no gradients in the visual sense (flat fills
 * only), no emoji in any label.
 */
object Ui {
    val BG = Color.parseColor("#101415")
    val CARD = Color.parseColor("#1C2326")
    val RAISED = Color.parseColor("#27313A")
    val STROKE = Color.parseColor("#2A3336")
    val ACCENT = Color.parseColor("#10B981")
    val BRIGHT = Color.parseColor("#2FD68F")
    val TEXT = Color.parseColor("#EAF4EE")
    val SECONDARY = Color.parseColor("#8FA79B")
    val MUTED = Color.parseColor("#7E938A")
    val DANGER = Color.parseColor("#F87171")

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    fun rounded(ctx: Context, fill: Int, radiusDp: Int, stroke: Boolean = false): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            cornerRadius = dp(ctx, radiusDp).toFloat()
            if (stroke) setStroke(dp(ctx, 1), STROKE)
        }

    fun circle(ctx: Context, fill: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fill)
            setStroke(dp(ctx, 2), BG)
        }

    /** Small circled "i": explanations live behind it, not on the screen. */
    fun infoBtn(ctx: Context, text: String) = TextView(ctx).apply {
        this.text = "i"
        textSize = 12f
        setTextColor(BRIGHT)
        gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setStroke(dp(ctx, 1), BRIGHT)
        }
        val s = dp(ctx, 22)
        layoutParams = LinearLayout.LayoutParams(s, s)
        setOnClickListener {
            android.app.AlertDialog.Builder(ctx)
                .setMessage(text)
                .setPositiveButton("ঠিক আছে", null)
                .show()
        }
    }

    /** Compact on/off toggle pill (knob slides, accent when on). */
    fun toggleSwitch(ctx: Context, on: Boolean, onChange: (Boolean) -> Unit): View {
        var state = on
        val pill = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 52), dp(ctx, 28))
        }
        val knob = View(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFFFFFFF.toInt())
            }
        }
        fun render() {
            pill.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(ctx, 999).toFloat()
                setColor(if (state) ACCENT else RAISED)
                setStroke(dp(ctx, 1), STROKE)
            }
            val k = dp(ctx, 22)
            knob.layoutParams = FrameLayout.LayoutParams(k, k).apply {
                gravity = Gravity.CENTER_VERTICAL or if (state) Gravity.END else Gravity.START
                leftMargin = dp(ctx, 3)
                rightMargin = dp(ctx, 3)
            }
        }
        pill.addView(knob)
        render()
        pill.setOnClickListener {
            state = !state
            render()
            onChange(state)
        }
        return pill
    }

    /** One compact settings row: title on the left, control on the right. */
    fun settingRow(ctx: Context, title: String, control: View): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(ctx, 5), 0, dp(ctx, 5))
            addView(TextView(ctx).apply {
                text = title
                setTextColor(TEXT)
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(control)
        }

    /** Small pill button showing a current value; tapping cycles/changes it. */
    fun valueButton(ctx: Context, text: String, onClick: () -> Unit) = TextView(ctx).apply {
        this.text = text
        textSize = 13f
        setTextColor(BRIGHT)
        gravity = Gravity.CENTER
        background = rounded(ctx, RAISED, 999, stroke = true)
        setPadding(dp(ctx, 12), dp(ctx, 6), dp(ctx, 12), dp(ctx, 6))
        setOnClickListener { onClick() }
    }

    /** Compact slider row with a live value label; reports on release. */
    fun sliderRow(
        ctx: Context, title: String, min: Int, max: Int, value: Int,
        format: (Int) -> String, onChange: (Int) -> Unit
    ): LinearLayout {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(ctx, 4), 0, dp(ctx, 4))
        }
        val valueLabel = TextView(ctx).apply {
            setTextColor(BRIGHT)
            textSize = 13f
        }
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(ctx).apply {
                text = title
                setTextColor(TEXT)
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(valueLabel)
        }
        root.addView(header)
        val seek = SeekBar(ctx).apply {
            this.max = max - min
            progress = (value - min).coerceIn(0, max - min)
        }
        valueLabel.text = format(value)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                valueLabel.text = format(min + progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                onChange(min + (sb?.progress ?: 0))
            }
        })
        root.addView(seek)
        return root
    }

    /** A right-aligned row holding only an info button. */
    fun infoRow(ctx: Context, text: String): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
        addView(infoBtn(ctx, text))
    }

    fun card(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(ctx, CARD, 20, stroke = true)
        setPadding(dp(ctx, 16), dp(ctx, 16), dp(ctx, 16), dp(ctx, 16))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(ctx, 16) }
    }

    fun appTitle(ctx: Context, text: String) = TextView(ctx).apply {
        this.text = text; textSize = 22f; setTextColor(TEXT)
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }

    fun cardTitle(ctx: Context, text: String) = TextView(ctx).apply {
        this.text = text; textSize = 17f; setTextColor(TEXT)
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        setPadding(0, 0, 0, dp(ctx, 10))
    }

    fun body(ctx: Context, text: String) = TextView(ctx).apply {
        this.text = text; textSize = 14f; setTextColor(TEXT)
    }

    fun secondary(ctx: Context, text: String) = TextView(ctx).apply {
        this.text = text; textSize = 13f; setTextColor(SECONDARY)
    }

    fun label(ctx: Context, text: String) = TextView(ctx).apply {
        this.text = text; textSize = 12f; setTextColor(MUTED)
    }

    fun primaryButton(ctx: Context, text: String, onClick: () -> Unit) = Button(ctx).apply {
        this.text = text; textSize = 14f; isAllCaps = false
        setTextColor(BG)
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        background = rounded(ctx, ACCENT, 14)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(ctx, 8) }
    }

    fun secondaryButton(ctx: Context, text: String, onClick: () -> Unit) = Button(ctx).apply {
        this.text = text; textSize = 14f; isAllCaps = false
        setTextColor(TEXT)
        background = rounded(ctx, RAISED, 14, stroke = true)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(ctx, 8) }
    }

    fun dangerButton(ctx: Context, text: String, onClick: () -> Unit) = Button(ctx).apply {
        this.text = text; textSize = 14f; isAllCaps = false
        setTextColor(DANGER)
        background = rounded(ctx, RAISED, 14, stroke = true)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(ctx, 8) }
    }

    /** Small pill chip: fully round. Active = emerald, inactive = raised/muted. */
    fun chip(ctx: Context, text: String, active: Boolean) = TextView(ctx).apply {
        this.text = text; textSize = 12f
        if (active) {
            setTextColor(BG)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            background = rounded(ctx, ACCENT, 999)
        } else {
            setTextColor(MUTED)
            background = rounded(ctx, RAISED, 999, stroke = true)
        }
        setPadding(dp(ctx, 10), dp(ctx, 4), dp(ctx, 10), dp(ctx, 4))
    }

    fun statusRow(ctx: Context, name: String, on: Boolean, onText: String = "চালু", offText: String = "বন্ধ"): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(ctx, 5), 0, dp(ctx, 5))
            addView(body(ctx, name), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(chip(ctx, if (on) onText else offText, on))
        }

    fun row(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
    }
}

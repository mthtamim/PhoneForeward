package com.mthtamim.phonelink

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * v2.8: interactive choice dialog for the automation "dialog_choice"
 * action. Shows the question with up to 4 option buttons; the chosen
 * option text is stored in the named automation variable.
 */
class PromptActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val varName = intent.getStringExtra("var") ?: ""
        val question = intent.getStringExtra("q") ?: ""
        val options = intent.getStringArrayListExtra("opts") ?: arrayListOf()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 40, 48, 32)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        root.addView(TextView(this).apply {
            text = question
            textSize = 17f
        })
        for (opt in options.take(4)) {
            root.addView(Button(this).apply {
                text = opt
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 16 }
                setOnClickListener {
                    if (varName.isNotEmpty()) Automation.setVar(this@PromptActivity, varName, opt)
                    finish()
                }
            })
        }
        setContentView(root)
    }
}

package com.mthtamim.phonelink

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.widget.EditText
import android.widget.ProgressBar
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.util.Calendar
import java.util.Locale

/**
 * Translucent host activity for FlowDialogs (Automation Lab v1.3.0).
 * No layout file: every UI is built programmatically. The activity is
 * declared with @android:style/Theme.Translucent.NoTitleBar (see the
 * manifest snippet in the delivery report) so only the dialog is visible.
 *
 * Every path counts the latch down exactly once (via FlowDialogs.complete
 * with the call-id echoed back) and finishes the activity. Nothing here
 * ever throws out of a lifecycle callback: everything is wrapped in
 * try/catch so a failed dialog degrades to a null result, never a crash.
 */
class FlowDialogActivity : LocalizedActivity() {

    companion object {
        private const val RC_LISTEN = 101
        private const val RC_FILE = 102
        private const val RC_DIR = 103
    }

    private var callId: Long = -1L
    private var dialogKind = ""
    private var finished = false
    private var progressDialog: AlertDialog? = null
    private var progressRunnable: Runnable? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            callId = intent?.getLongExtra("call_id", -1L) ?: -1L
            val kind = intent?.getStringExtra("kind") ?: ""
            dialogKind = kind
            val title = intent?.getStringExtra("title") ?: ""
            val text = intent?.getStringExtra("text") ?: ""
            val def = intent?.getStringExtra("def") ?: ""
            val seconds = intent?.getIntExtra("seconds", 5) ?: 5
            val prompt = intent?.getStringExtra("prompt") ?: ""
            when (kind) {
                "input" -> showInput(title, def)
                "message" -> showMessage(title, text)
                "progress" -> showProgress(title, seconds)
                "date" -> showDate(title)
                "time" -> showTime(title)
                "listen" -> startListen(prompt)
                "pickFile" -> startPickFile()
                "pickDir" -> startPickDir()
                else -> finishResult(null)
            }
        } catch (_: Exception) {
            finishResult(null)
        }
    }

    private fun showInput(title: String, def: String) {
        try {
            val edit = EditText(this)
            edit.setText(def)
            AlertDialog.Builder(this)
                .setTitle(title)
                .setView(edit)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    finishResult(edit.text.toString(), "ok")
                }
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    finishResult(null, "cancel")
                }
                .setOnCancelListener { finishResult(null) }
                .show()
        } catch (_: Exception) {
            finishResult(null)
        }
    }

    private fun showMessage(title: String, text: String) {
        try {
            AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(text)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    finishResult(null, "ok")
                }
                .setOnCancelListener { finishResult(null) }
                .show()
        } catch (_: Exception) {
            finishResult(null)
        }
    }

    private fun showProgress(title: String, seconds: Int) {
        try {
            val bar = ProgressBar(this)
            val dialog = AlertDialog.Builder(this)
                .setTitle(title)
                .setView(bar)
                .setCancelable(true)
                .setOnCancelListener { finishResult(null) }
                .show()
            progressDialog = dialog
            val delay = if (seconds > 0) seconds * 1000L else 5000L
            val runnable = Runnable {
                try {
                    dialog.dismiss()
                } catch (_: Exception) {
                }
                finishResult(null)
            }
            progressRunnable = runnable
            handler.postDelayed(runnable, delay)
        } catch (_: Exception) {
            finishResult(null)
        }
    }

    private fun showDate(title: String) {
        try {
            val now = Calendar.getInstance()
            val dialog = DatePickerDialog(
                this,
                { _, year, month, day ->
                    finishResult(String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, day), "ok")
                },
                now.get(Calendar.YEAR),
                now.get(Calendar.MONTH),
                now.get(Calendar.DAY_OF_MONTH)
            )
            dialog.setTitle(title)
            dialog.setOnCancelListener { finishResult(null) }
            dialog.show()
        } catch (_: Exception) {
            finishResult(null)
        }
    }

    private fun showTime(title: String) {
        try {
            val now = Calendar.getInstance()
            val dialog = TimePickerDialog(
                this,
                { _, hour, minute ->
                    finishResult(String.format(Locale.US, "%02d:%02d", hour, minute), "ok")
                },
                now.get(Calendar.HOUR_OF_DAY),
                now.get(Calendar.MINUTE),
                true
            )
            dialog.setTitle(title)
            dialog.setOnCancelListener { finishResult(null) }
            dialog.show()
        } catch (_: Exception) {
            finishResult(null)
        }
    }

    private fun startListen(prompt: String) {
        try {
            val rec = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            rec.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            rec.putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
            rec.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            startActivityForResult(rec, RC_LISTEN)
        } catch (_: Exception) {
            finishResult(null)
        }
    }

    private fun startPickFile() {
        try {
            val pick = Intent(Intent.ACTION_OPEN_DOCUMENT)
            pick.addCategory(Intent.CATEGORY_OPENABLE)
            pick.type = "*/*"
            startActivityForResult(pick, RC_FILE)
        } catch (_: Exception) {
            finishResult(null)
        }
    }

    private fun startPickDir() {
        try {
            val pick = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            startActivityForResult(pick, RC_DIR)
        } catch (_: Exception) {
            finishResult(null)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        try {
            if (resultCode != RESULT_OK) {
                finishResult(null)
                return
            }
            when (requestCode) {
                RC_LISTEN -> {
                    val spoken = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
                    finishResult(spoken)
                }
                RC_FILE -> {
                    val uri = data?.data
                    if (uri == null) {
                        finishResult(null)
                    } else {
                        try {
                            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        } catch (_: Exception) {
                        }
                        finishResult(uri.toString())
                    }
                }
                RC_DIR -> {
                    val uri = data?.data
                    if (uri == null) {
                        finishResult(null)
                    } else {
                        try {
                            contentResolver.takePersistableUriPermission(
                                uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            )
                        } catch (_: Exception) {
                        }
                        finishResult(uri.toString())
                    }
                }
                else -> finishResult(null)
            }
        } catch (_: Exception) {
            finishResult(null)
        }
    }

    override fun onDestroy() {
        if (!finished) {
            try {
                FlowDialogs.complete(callId, null)
            } catch (_: Exception) {
            }
        }
        super.onDestroy()
    }

    private fun finishResult(value: String?, button: String = "dismiss") {
        if (finished) {
            return
        }
        finished = true
        try {
            progressRunnable?.let { handler.removeCallbacks(it) }
        } catch (_: Exception) {
        }
        try {
            progressDialog?.dismiss()
        } catch (_: Exception) {
        }
        // v1.4.0: a real button press on a Lab dialog is a trigger
        // event (dialog_interacted). Dismissals, timeouts and picker
        // results are not button presses and do not fire.
        if (button != "dismiss") {
            try {
                val data = org.json.JSONObject()
                data.put("button", button)
                data.put("kind", dialogKind)
                FlowEngine.onEvent(this, "dialog_interacted", data)
            } catch (_: Exception) {
            }
        }
        try {
            FlowDialogs.complete(callId, value)
        } catch (_: Exception) {
        }
        try {
            finish()
        } catch (_: Exception) {
        }
    }
}

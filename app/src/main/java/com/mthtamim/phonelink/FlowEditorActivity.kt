package com.mthtamim.phonelink

import android.content.Context
import android.os.BatteryManager
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import org.json.JSONArray
import org.json.JSONObject

/**
 * Flowchart editor, v1.1.5 (icon-only toolbar, name in the top
 * bar, node dialog on double-tap).
 * Top bar (Back + editable flow name) + icon-only toolbar (Run,
 * Undo, Redo, Save, Reset view — icons, no text); canvas fills
 * the rest of the screen with a dotted grid; the palette is a
 * bottom sheet behind the floating + button. There is NO
 * Connect button or mode and NO bottom panel: edges are made by
 * dragging from an output port (GO/YES/NO/OK) to an IN port, and
 * the port decides the branch (YES -> "yes", NO -> "no",
 * GO/OK -> "any"). Tapping a curve opens an "A connection"
 * dialog with DELETE. Single tap on a node only selects it
 * (highlight + resize grip); double-tapping a node opens its
 * properties in a dialog window (Options / Input arguments /
 * Output variables / Current values, with Save and Delete
 * node). Undo/redo are in-memory flow-JSON snapshots (cap 50,
 * identical states skipped); they never auto-save — Save/Run/
 * Back save as before. The "immediate" flag is stored in the
 * node JSON but the engine ignores it for now. Edge JSON stays
 * {from, to, branch(yes/no/any)}, so old flows load and run
 * unchanged. Rules are untouched: this screen only edits
 * flows, which live beside rules.
 */
class FlowEditorActivity : LocalizedActivity() {

    private lateinit var etFlowName: EditText
    private lateinit var etFlowFolder: EditText
    private lateinit var canvas: FlowCanvasView
    private lateinit var tvZoomPct: TextView
    private lateinit var btnUndoFlow: View
    private lateinit var btnRedoFlow: View
    private lateinit var btnSnapGrid: View
    // v1.4.2 fullscreen: the three UI groups fullscreen hides.
    private lateinit var topBar: View
    private lateinit var toolbarScroll: View
    private lateinit var zoomCluster: View
    private var isFullscreen = false
    private var copiedNodeJson: String? = null

    private var flowId: String = ""
    private var isNew: Boolean = true
    private lateinit var flow: JSONObject
    private val dialogFields = ArrayList<Pair<String, EditText>>()
    private var dialogFallback = false
    private var dialogFallbackField: EditText? = null
    private var dialogNoInput = false
    private var dialogOrigParam = ""

    private val undoStack = ArrayDeque<String>()
    private val redoStack = ArrayDeque<String>()

    private val curatedTrig = listOf(
        "time_at", "time_interval", "battery_below", "screen_on",
        "boot", "sms_received", "wifi_connected", "app_launched"
    )
    private val curatedCond = listOf(
        "time_range", "battery_above", "charging", "variable",
        "screen", "wifi_ssid"
    )
    private val curatedAct = listOf(
        "local_notify", "toast_local", "speak_local", "focuslock_control",
        "set_var", "wait", "vibrate", "open_app"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_flow_editor)
        etFlowName = findViewById(R.id.etFlowName)
        etFlowFolder = findViewById(R.id.etFlowFolder)
        canvas = findViewById(R.id.flowCanvas)
        tvZoomPct = findViewById(R.id.tvZoomPct)
        topBar = findViewById(R.id.layoutTopBar)
        toolbarScroll = findViewById(R.id.toolbarScroll)
        zoomCluster = findViewById(R.id.zoomCluster)
        btnUndoFlow = findViewById(R.id.btnUndoFlow)
        btnRedoFlow = findViewById(R.id.btnRedoFlow)
        btnSnapGrid = findViewById(R.id.btnSnapGrid)
        btnSnapGrid.alpha = 0.4f

        flowId = intent.getStringExtra("flow_id") ?: ""
        isNew = flowId.isEmpty()
        flow = if (isNew) {
            val f = JSONObject()
            f.put("id", "f" + System.currentTimeMillis().toString())
            f.put("name", "")
            f.put("nodes", JSONArray())
            f.put("edges", JSONArray())
            f
        } else {
            FlowStore.loadFlow(this, flowId) ?: run {
                val f = JSONObject()
                f.put("id", flowId)
                f.put("name", "")
                f.put("nodes", JSONArray())
                f.put("edges", JSONArray())
                f
            }
        }
        flowId = flow.optString("id")
        etFlowName.setText(flow.optString("name"))
        etFlowFolder.setText(flow.optString("folder"))
        // v1.4.1: the folder is PICKED, not typed — tapping the
        // field opens the folder list. The name auto-saves on
        // focus loss, on any canvas tap and on leaving the editor,
        // so it survives without pressing Save.
        etFlowFolder.isFocusable = false
        etFlowFolder.setOnClickListener { showFolderDialog() }
        etFlowName.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                persistNameFolder()
            }
        }
        canvas.nodes = FlowFlow.nodesOf(flow)
        canvas.edges = FlowFlow.edgesOf(flow)

        canvas.onZoomChanged = { z ->
            tvZoomPct.text = ((z * 100).toInt().toString() + "%")
        }
        canvas.onNodeSelected = {
            // Tapping anywhere else in the editor saves the name,
            // hides the keyboard and drops name-field focus.
            hideKeyboard()
            etFlowName.clearFocus()
            persistNameFolder()
        }
        canvas.onNodeDoubleTap = { node ->
            showNodeDialog(node)
        }
        canvas.onPortConnect = { fromId, port, toId ->
            val branch = when (port) {
                "YES" -> "yes"
                "NO" -> "no"
                else -> "any"
            }
            pushUndo()
            storeEdge(fromId, toId, branch)
        }
        canvas.onEdgeSelected = { edge ->
            AlertDialog.Builder(this)
                .setTitle(R.string.flow_connection_title)
                .setPositiveButton(R.string.btn_delete) { _, _ ->
                    pushUndo()
                    removeEdge(
                        edge.optString("from"),
                        edge.optString("to"),
                        edge.optString("branch")
                    )
                    canvas.selectedEdge = null
                    canvas.edges = FlowFlow.edgesOf(flow)
                    canvas.invalidate()
                }
                .setNegativeButton(R.string.btn_cancel) { _, _ ->
                    canvas.selectedEdge = null
                    canvas.invalidate()
                }
                .setOnCancelListener {
                    canvas.selectedEdge = null
                    canvas.invalidate()
                }
                .show()
        }
        canvas.onNodeDragStart = {
            pushUndo()
        }
        canvas.onNodeResizeStart = {
            pushUndo()
        }

        findViewById<View>(R.id.btnAddNode).setOnClickListener {
            showPaletteSheet()
        }
        findViewById<View>(R.id.btnZoomOut).setOnClickListener {
            canvas.setZoom(canvas.zoom - 0.25f)
        }
        findViewById<View>(R.id.btnZoomIn).setOnClickListener {
            canvas.setZoom(canvas.zoom + 0.25f)
        }
        findViewById<View>(R.id.btnZoomFit).setOnClickListener {
            canvas.fitToScreen()
        }
        findViewById<View>(R.id.btnFullscreen).setOnClickListener {
            setFullscreen(true)
        }
        // v1.4.2: Back while fullscreen only exits fullscreen; a
        // second Back leaves the editor as before.
        onBackPressedDispatcher.addCallback(
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (isFullscreen) {
                        setFullscreen(false)
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        )
        btnUndoFlow.setOnClickListener {
            undo()
        }
        btnRedoFlow.setOnClickListener {
            redo()
        }
        findViewById<View>(R.id.btnCopyNode).setOnClickListener {
            val selId = canvas.selectedId
            val node = if (selId != null) {
                FlowFlow.nodeByIdFromArray(canvas.nodes, selId)
            } else {
                null
            }
            if (node != null) {
                copiedNodeJson = node.toString()
                Toast.makeText(this, R.string.toast_saved, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, R.string.flow_copy_no_selection, Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<View>(R.id.btnPasteNode).setOnClickListener {
            val json = copiedNodeJson
            if (json == null) {
                Toast.makeText(this, R.string.flow_paste_empty, Toast.LENGTH_SHORT).show()
            } else {
                try {
                    val node = JSONObject(json)
                    node.put("id", FlowFlow.newId())
                    node.put("x", node.optDouble("x", 0.0) + 40.0)
                    node.put("y", node.optDouble("y", 0.0) + 40.0)
                    pushUndo()
                    val nodes = FlowFlow.nodesOf(flow)
                    nodes.put(node)
                    canvas.nodes = nodes
                    canvas.selectedId = node.optString("id")
                    canvas.invalidate()
                } catch (_: Exception) {
                    Toast.makeText(this, R.string.flow_paste_empty, Toast.LENGTH_SHORT).show()
                }
            }
        }
        btnSnapGrid.setOnClickListener {
            canvas.snapToGrid = !canvas.snapToGrid
            btnSnapGrid.alpha = if (canvas.snapToGrid) 1.0f else 0.4f
        }
        findViewById<View>(R.id.btnResetView).setOnClickListener {
            canvas.fitToScreen()
        }
        findViewById<View>(R.id.btnRunFlow).setOnClickListener {
            if (saveFlowSilent()) {
                FlowEngine.runFlow(this, flow)
                Toast.makeText(this, R.string.btn_run, Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<View>(R.id.btnSaveFlow).setOnClickListener {
            if (saveFlowSilent()) {
                Toast.makeText(this, R.string.flow_saved, Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<View>(R.id.btnExportFlow).setOnClickListener {
            if (saveFlowSilent()) {
                if (!FlowIO.exportFlow(this, flow)) {
                    Toast.makeText(this, R.string.flow_export_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
        findViewById<View>(R.id.btnBackFlow).setOnClickListener {
            persistNameFolder()
            finish()
        }
        refreshUndoButtons()
    }

    override fun onResume() {
        super.onResume()
        FlowEngine.nodeListener = { id ->
            runOnUiThread {
                canvas.highlightId = id
                canvas.invalidate()
            }
        }
    }

    override fun onPause() {
        // Leaving the editor (Back, Home, app switch) must not drop
        // a typed name: persist it here too, not only on Save.
        persistNameFolder()
        // v1.4.2: fullscreen is not persisted and must not leave
        // the UI hidden on return — restore before pausing.
        if (isFullscreen) {
            setFullscreen(false)
        }
        FlowEngine.nodeListener = null
        canvas.highlightId = null
        canvas.invalidate()
        super.onPause()
    }

    /**
     * v1.4.2 fullscreen: hides the top bar, the whole toolbar row
     * and the top zoom cluster; ONLY the canvas and the + FAB
     * stay visible (the FAB keeps working — the palette sheet
     * still opens). Exiting restores all three groups. The flag
     * lives in memory only: a fresh editor always starts normal.
     */
    private fun setFullscreen(on: Boolean) {
        isFullscreen = on
        val vis = if (on) View.GONE else View.VISIBLE
        topBar.visibility = vis
        toolbarScroll.visibility = vis
        zoomCluster.visibility = vis
    }

    /**
     * v1.4.1: writes name + folder to the flow file WITHOUT the
     * Save button's node-count gate — a typed name must survive
     * even before the first node is added. Unnamed flows are
     * never created by this path. Silent: no toast.
     */
    private fun persistNameFolder() {
        val name = etFlowName.text.toString().trim()
        if (name.isEmpty()) {
            return
        }
        flow.put("name", name)
        flow.put("folder", etFlowFolder.text.toString().trim())
        FlowStore.saveFlow(this, flow)
    }

    private fun hideKeyboard() {
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(canvas.windowToken, 0)
        } catch (_: Exception) {
        }
    }

    /** v1.4.1: folder picker — existing folders from saved flows,
     *  plus a "new folder" row that opens a name input. */
    private fun showFolderDialog() {
        val folders = FlowStore.flows(this)
            .map { it.optString("folder") }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()
        val items = ArrayList<String>()
        items.addAll(folders)
        items.add(getString(R.string.flow_folder_new))
        AlertDialog.Builder(this)
            .setTitle(R.string.flow_folder_title)
            .setItems(items.toTypedArray()) { _, which ->
                if (which == folders.size) {
                    showNewFolderDialog()
                } else {
                    etFlowFolder.setText(folders[which])
                    persistNameFolder()
                }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun showNewFolderDialog() {
        val input = EditText(this)
        input.hint = getString(R.string.flow_folder_name_hint)
        input.setText(etFlowFolder.text.toString())
        AlertDialog.Builder(this)
            .setTitle(R.string.flow_folder_new)
            .setView(input)
            .setPositiveButton(R.string.btn_save) { _, _ ->
                etFlowFolder.setText(input.text.toString().trim())
                persistNameFolder()
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    /**
     * Node properties as a dialog window (v1.1.5): double-tap a
     * node to open it. Same structured content the v1.1.4 bottom
     * panel showed — Options / Input arguments / Output
     * variables / Current values — plus Save and Delete node.
     * Save writes the fields back to the node param + flag and
     * pushes an undo snapshot; Delete removes the node and its
     * edges and pushes an undo snapshot. Dismisses on
     * Save/Delete/Cancel/back.
     */
    private fun showNodeDialog(node: JSONObject) {
        val type = node.optString("type")
        val density = resources.displayMetrics.density
        val pad = (20f * density).toInt()
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(pad, (12f * density).toInt(), pad, pad)
        dialogFields.clear()
        dialogFallback = false
        dialogFallbackField = null

        val section = { labelRes: Int ->
            val label = TextView(this)
            label.text = getString(labelRes)
            label.textSize = 13f
            label.setTypeface(label.typeface, android.graphics.Typeface.BOLD)
            label.setTextColor(getColor(R.color.md_onSurfaceVariant))
            label.setPadding(0, (10f * density).toInt(), 0, (2f * density).toInt())
            root.addView(label)
        }

        section(R.string.panel_options)
        val cbImmediate = CheckBox(this)
        cbImmediate.text = getString(R.string.panel_proceed)
        cbImmediate.textSize = 14f
        cbImmediate.setTextColor(getColor(R.color.md_onBackground))
        cbImmediate.isChecked = node.optBoolean("immediate", true)
        root.addView(cbImmediate)
        val cbEnabled = CheckBox(this)
        cbEnabled.text = getString(R.string.panel_enabled)
        cbEnabled.textSize = 14f
        cbEnabled.setTextColor(getColor(R.color.md_onBackground))
        cbEnabled.isChecked = node.optBoolean("enabled", true)
        root.addView(cbEnabled)
        val cbContinueOnError = CheckBox(this)
        cbContinueOnError.text = getString(R.string.panel_continue_on_error)
        cbContinueOnError.textSize = 14f
        cbContinueOnError.setTextColor(getColor(R.color.md_onBackground))
        cbContinueOnError.isChecked = node.optString("onerror", "stop") == "continue"
        root.addView(cbContinueOnError)
        val etNote = EditText(this)
        etNote.setText(node.optString("note"))
        etNote.hint = getString(R.string.panel_note_hint)
        etNote.textSize = 14f
        etNote.setTextColor(getColor(R.color.md_onBackground))
        root.addView(etNote)

        // v1.4.1: every node explains itself first — a one-line
        // description of what the block does, plus the catalog's
        // own fill hint when one exists (e.g. "Title | message").
        val nodeTitle = FlowFlow.title(this, node)
        val descRes = when (node.optString("kind")) {
            "t" -> R.string.flow_desc_trigger
            "c" -> R.string.flow_desc_condition
            else -> R.string.flow_desc_action
        }
        val descLine = TextView(this)
        descLine.text = getString(descRes, nodeTitle)
        descLine.textSize = 13f
        descLine.setTextColor(getColor(R.color.md_onSurfaceVariant))
        root.addView(descLine)
        val catalogHint = FlowCatalog.hint(this, node.optString("kind"), type)
        if (catalogHint.isNotEmpty()) {
            val fillLine = TextView(this)
            fillLine.text = getString(R.string.flow_fill_hint, catalogHint)
            fillLine.textSize = 13f
            fillLine.setTextColor(getColor(R.color.md_onSurfaceVariant))
            root.addView(fillLine)
        }

        section(R.string.panel_inputs)
        val inputContainer = LinearLayout(this)
        inputContainer.orientation = LinearLayout.VERTICAL
        root.addView(inputContainer)
        val param = node.optString("param")
        dialogOrigParam = param
        dialogNoInput = false
        when (type) {
            "time_at" -> {
                addPanelField(inputContainer, "time", R.string.panel_time, param, InputType.TYPE_CLASS_TEXT, "09:00")
            }
            "time_interval", "wait" -> {
                addPanelField(inputContainer, "seconds", R.string.panel_seconds, param, InputType.TYPE_CLASS_NUMBER, "30")
            }
            "time_at_days" -> {
                val time = param.substringAfter("time=", "").substringBefore("|")
                val days = param.substringAfter("days=", "")
                addPanelField(inputContainer, "time", R.string.panel_time, time, InputType.TYPE_CLASS_TEXT, "09:00")
                addPanelField(inputContainer, "days", R.string.panel_days, days, InputType.TYPE_CLASS_TEXT, "sat,sun,mon")
            }
            "location_at" -> {
                val p = barParts(param)
                addPanelField(inputContainer, "lat", R.string.panel_lat, barPart(p, 0), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or InputType.TYPE_NUMBER_FLAG_DECIMAL, "23.8103")
                addPanelField(inputContainer, "lon", R.string.panel_lon, barPart(p, 1), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or InputType.TYPE_NUMBER_FLAG_DECIMAL, "90.4125")
                addPanelField(inputContainer, "radius", R.string.panel_radius, barPart(p, 2), InputType.TYPE_CLASS_NUMBER, "100")
            }
            "content_changed" -> {
                addPanelField(inputContainer, "uri", R.string.panel_uri, param, InputType.TYPE_CLASS_TEXT, "content://media/external/images/media")
            }
            "settings_changed" -> {
                addPanelField(inputContainer, "key", R.string.panel_key, param, InputType.TYPE_CLASS_TEXT, "screen_brightness")
            }
            "wifi_rssi" -> {
                addPanelField(inputContainer, "level", R.string.panel_level, param, InputType.TYPE_CLASS_NUMBER, "0-4")
            }
            "audio_muted" -> {
                addPanelField(inputContainer, "stream", R.string.panel_stream, param, InputType.TYPE_CLASS_TEXT, "music")
            }
            "app_recent" -> {
                addPanelField(inputContainer, "package", R.string.panel_package, param.substringBefore("|"), InputType.TYPE_CLASS_TEXT, "com.facebook.lite")
            }
            "sound_level", "temp", "humidity", "heart_rate", "accel", "hinge_angle" -> {
                addPanelField(inputContainer, "threshold", R.string.panel_threshold, param, InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL, FlowFlow.defaultParam(type))
            }
            "battery_below", "battery_above" -> {
                addPanelField(inputContainer, "level", R.string.panel_level, param, InputType.TYPE_CLASS_NUMBER, "20")
            }
            "time_range" -> {
                addPanelField(inputContainer, "range", R.string.panel_range, param, InputType.TYPE_CLASS_TEXT, "09:00-17:00")
            }
            "charging", "screen" -> {
                addPanelField(inputContainer, "state", R.string.panel_state, param, InputType.TYPE_CLASS_TEXT, "on")
            }
            "variable" -> {
                addPanelField(inputContainer, "expression", R.string.panel_expression, param, InputType.TYPE_CLASS_TEXT, "x > 5")
            }
            "wifi_ssid" -> {
                addPanelField(inputContainer, "ssid", R.string.panel_ssid, param, InputType.TYPE_CLASS_TEXT, "MyWifi")
            }
            "foreground_app" -> {
                addPanelField(inputContainer, "package", R.string.panel_package, param, InputType.TYPE_CLASS_TEXT, "com.facebook.lite")
            }
            "local_notify", "toast_local", "speak_local" -> {
                addPanelField(inputContainer, "message", R.string.panel_message, param, InputType.TYPE_CLASS_TEXT, "PhoneLink flow")
            }
            "call_number" -> {
                addPanelField(inputContainer, "number", R.string.panel_number, param.substringBefore("|"), InputType.TYPE_CLASS_PHONE, "01XXXXXXXXX")
            }
            "open_app" -> {
                val p = barParts(param, 2)
                addPanelField(inputContainer, "package", R.string.panel_package, barPart(p, 0), InputType.TYPE_CLASS_TEXT, "com.facebook.lite")
                addPanelField(inputContainer, "extras", R.string.panel_extras, barPart(p, 1), InputType.TYPE_CLASS_TEXT, "key=value")
            }
            "dialog_input" -> {
                val p = barParts(param)
                addPanelField(inputContainer, "title", R.string.panel_title, barPart(p, 0), InputType.TYPE_CLASS_TEXT, "Title")
                addPanelField(inputContainer, "default", R.string.panel_default, barPart(p, 1), InputType.TYPE_CLASS_TEXT, "default")
                addPanelField(inputContainer, "dest", R.string.panel_dest, barPart(p, 2, "dialog_input"), InputType.TYPE_CLASS_TEXT, "dialog_input")
            }
            "dialog_message" -> {
                val p = barParts(param, 2)
                addPanelField(inputContainer, "title", R.string.panel_title, barPart(p, 0), InputType.TYPE_CLASS_TEXT, "Title")
                addPanelField(inputContainer, "message", R.string.panel_message, barPart(p, 1), InputType.TYPE_CLASS_TEXT, "Hello")
            }
            "dialog_progress" -> {
                val p = barParts(param)
                addPanelField(inputContainer, "title", R.string.panel_title, barPart(p, 0), InputType.TYPE_CLASS_TEXT, "Working")
                addPanelField(inputContainer, "seconds", R.string.panel_seconds, barPart(p, 1, "5"), InputType.TYPE_CLASS_NUMBER, "5")
            }
            "date_picker" -> {
                val p = barParts(param)
                addPanelField(inputContainer, "title", R.string.panel_title, barPart(p, 0), InputType.TYPE_CLASS_TEXT, "Pick a date")
                addPanelField(inputContainer, "dest", R.string.panel_dest, barPart(p, 1, "picked_date"), InputType.TYPE_CLASS_TEXT, "picked_date")
            }
            "time_picker" -> {
                val p = barParts(param)
                addPanelField(inputContainer, "title", R.string.panel_title, barPart(p, 0), InputType.TYPE_CLASS_TEXT, "Pick a time")
                addPanelField(inputContainer, "dest", R.string.panel_dest, barPart(p, 1, "picked_time"), InputType.TYPE_CLASS_TEXT, "picked_time")
            }
            "listen" -> {
                val p = barParts(param, 2)
                addPanelField(inputContainer, "dest", R.string.panel_dest, barPart(p, 0, "speech_text"), InputType.TYPE_CLASS_TEXT, "speech_text")
                addPanelField(inputContainer, "prompt", R.string.panel_prompt, barPart(p, 1, "Speak now"), InputType.TYPE_CLASS_TEXT, "Speak now")
            }
            "file_pick" -> {
                addPanelField(inputContainer, "dest", R.string.panel_dest, param.ifEmpty { "picked_file" }, InputType.TYPE_CLASS_TEXT, "picked_file")
            }
            "dir_pick" -> {
                addPanelField(inputContainer, "dest", R.string.panel_dest, param.ifEmpty { "picked_dir" }, InputType.TYPE_CLASS_TEXT, "picked_dir")
            }
            "file_list" -> {
                val p = barParts(param, 2)
                addPanelField(inputContainer, "subdir", R.string.panel_subdir, barPart(p, 0), InputType.TYPE_CLASS_TEXT, getString(R.string.panel_subdir_hint))
                addPanelField(inputContainer, "dest", R.string.panel_dest, barPart(p, 1, "file_list"), InputType.TYPE_CLASS_TEXT, "file_list")
            }
            "file_exists" -> {
                val p = barParts(param, 2)
                addPanelField(inputContainer, "file", R.string.panel_file, barPart(p, 0), InputType.TYPE_CLASS_TEXT, "file.txt")
                addPanelField(inputContainer, "dest", R.string.panel_dest, barPart(p, 1, "file_exists"), InputType.TYPE_CLASS_TEXT, "file_exists")
            }
            "unzip" -> {
                val p = barParts(param)
                addPanelField(inputContainer, "archive", R.string.panel_archive, barPart(p, 0), InputType.TYPE_CLASS_TEXT, "archive.zip")
                addPanelField(inputContainer, "outdir", R.string.panel_outdir, barPart(p, 1), InputType.TYPE_CLASS_TEXT, "out")
            }
            "clipboard_get" -> {
                addPanelField(inputContainer, "dest", R.string.panel_dest, param.ifEmpty { "clipboard_text" }, InputType.TYPE_CLASS_TEXT, "clipboard_text")
            }
            "wifi_connect" -> {
                val p = barParts(param, 2)
                addPanelField(inputContainer, "ssid", R.string.panel_ssid, barPart(p, 0), InputType.TYPE_CLASS_TEXT, "MyWifi")
                addPanelField(inputContainer, "password", R.string.panel_password, barPart(p, 1), InputType.TYPE_CLASS_TEXT, "password")
            }
            "focuslock_control" -> {
                addPanelField(inputContainer, "command", R.string.panel_command, param, InputType.TYPE_CLASS_TEXT, "cmd=lock_full")
            }
            "hotspot_set", "battery_saver_set", "data_saver_set", "dark_set" -> {
                addPanelField(inputContainer, "onoff", R.string.panel_onoff, param, InputType.TYPE_CLASS_TEXT, "on")
            }
            "loop_count" -> {
                addPanelField(inputContainer, "count", R.string.panel_count, param, InputType.TYPE_CLASS_NUMBER, "3")
            }
            "for_each" -> {
                val bar = param.indexOf("|")
                if (bar >= 0) {
                    addPanelField(inputContainer, "list", R.string.panel_list, param.substring(0, bar), InputType.TYPE_CLASS_TEXT, "items")
                    addPanelField(inputContainer, "item", R.string.panel_item, param.substring(bar + 1), InputType.TYPE_CLASS_TEXT, "item")
                } else {
                    addPanelField(inputContainer, "list", R.string.panel_list, param, InputType.TYPE_CLASS_TEXT, "items")
                    addPanelField(inputContainer, "item", R.string.panel_item, "item", InputType.TYPE_CLASS_TEXT, "item")
                }
            }
            "call_flow" -> {
                addPanelField(inputContainer, "flow", R.string.panel_flow_name, param, InputType.TYPE_CLASS_TEXT, "")
            }
            "set_var", "set_local" -> {
                val eq = param.indexOf("=")
                if (eq >= 0) {
                    addPanelField(inputContainer, "name", R.string.panel_var_name, param.substring(0, eq), InputType.TYPE_CLASS_TEXT, "x")
                    addPanelField(inputContainer, "value", R.string.panel_var_value, param.substring(eq + 1), InputType.TYPE_CLASS_TEXT, "1")
                } else {
                    addPanelField(inputContainer, "name", R.string.panel_var_name, param, InputType.TYPE_CLASS_TEXT, "x")
                    addPanelField(inputContainer, "value", R.string.panel_var_value, "", InputType.TYPE_CLASS_TEXT, "1")
                }
            }
            "list_append" -> {
                val p = barParts(param, 2)
                addPanelField(inputContainer, "list", R.string.panel_list, barPart(p, 0), InputType.TYPE_CLASS_TEXT, "items")
                addPanelField(inputContainer, "value", R.string.panel_var_value, barPart(p, 1), InputType.TYPE_CLASS_TEXT, "value")
            }
            "list_get" -> {
                val p = barParts(param)
                addPanelField(inputContainer, "list", R.string.panel_list, barPart(p, 0), InputType.TYPE_CLASS_TEXT, "items")
                addPanelField(inputContainer, "index", R.string.panel_index, barPart(p, 1), InputType.TYPE_CLASS_NUMBER, "0")
                addPanelField(inputContainer, "dest", R.string.panel_dest, barPart(p, 2, "list_value"), InputType.TYPE_CLASS_TEXT, "list_value")
            }
            "dict_get" -> {
                val p = barParts(param)
                addPanelField(inputContainer, "dict", R.string.panel_dict, barPart(p, 0), InputType.TYPE_CLASS_TEXT, "data")
                addPanelField(inputContainer, "key", R.string.panel_key, barPart(p, 1), InputType.TYPE_CLASS_TEXT, "key")
                addPanelField(inputContainer, "dest", R.string.panel_dest, barPart(p, 2, "dict_value"), InputType.TYPE_CLASS_TEXT, "dict_value")
            }
            "text_replace" -> {
                val p = barParts(param, 3)
                addPanelField(inputContainer, "var", R.string.panel_var_name, barPart(p, 0), InputType.TYPE_CLASS_TEXT, "text")
                addPanelField(inputContainer, "from", R.string.panel_from, barPart(p, 1), InputType.TYPE_CLASS_TEXT, "from")
                addPanelField(inputContainer, "to", R.string.panel_to, barPart(p, 2), InputType.TYPE_CLASS_TEXT, "to")
            }
            "text_split" -> {
                val p = barParts(param, 3)
                addPanelField(inputContainer, "var", R.string.panel_var_name, barPart(p, 0), InputType.TYPE_CLASS_TEXT, "text")
                addPanelField(inputContainer, "separator", R.string.panel_separator, barPart(p, 1), InputType.TYPE_CLASS_TEXT, ",")
                addPanelField(inputContainer, "dest", R.string.panel_dest, barPart(p, 2), InputType.TYPE_CLASS_TEXT, "items")
            }
            "text_join" -> {
                val p = barParts(param, 3)
                addPanelField(inputContainer, "list", R.string.panel_list, barPart(p, 0), InputType.TYPE_CLASS_TEXT, "items")
                addPanelField(inputContainer, "separator", R.string.panel_separator, barPart(p, 1), InputType.TYPE_CLASS_TEXT, ",")
                addPanelField(inputContainer, "dest", R.string.panel_dest, barPart(p, 2), InputType.TYPE_CLASS_TEXT, "text")
            }
            "random_int" -> {
                val p = barParts(param)
                addPanelField(inputContainer, "min", R.string.panel_min, barPart(p, 0), InputType.TYPE_CLASS_NUMBER, "1")
                addPanelField(inputContainer, "max", R.string.panel_max, barPart(p, 1), InputType.TYPE_CLASS_NUMBER, "100")
                addPanelField(inputContainer, "dest", R.string.panel_dest, barPart(p, 2, "random"), InputType.TYPE_CLASS_TEXT, "random")
            }
            // v1.4.3: QR scan timeout; Wake-on-LAN MAC|IP|port;
            // ping host|count|timeoutMs.
            "qr_scan" -> {
                addPanelField(inputContainer, "timeout", R.string.panel_timeout, param, InputType.TYPE_CLASS_NUMBER, "60")
            }
            "wol" -> {
                val p = barParts(param)
                addPanelField(inputContainer, "mac", R.string.panel_mac, barPart(p, 0), InputType.TYPE_CLASS_TEXT, "AA:BB:CC:DD:EE:FF")
                addPanelField(inputContainer, "ip", R.string.panel_ip, barPart(p, 1, "255.255.255.255"), InputType.TYPE_CLASS_TEXT, "255.255.255.255")
                addPanelField(inputContainer, "port", R.string.panel_port, barPart(p, 2, "9"), InputType.TYPE_CLASS_NUMBER, "9")
            }
            "ping" -> {
                val p = barParts(param)
                addPanelField(inputContainer, "host", R.string.panel_host, barPart(p, 0), InputType.TYPE_CLASS_TEXT, "8.8.8.8")
                addPanelField(inputContainer, "count", R.string.panel_count, barPart(p, 1, "4"), InputType.TYPE_CLASS_NUMBER, "4")
                addPanelField(inputContainer, "timeout", R.string.panel_timeout, barPart(p, 2, "2000"), InputType.TYPE_CLASS_NUMBER, "2000")
            }
            // v1.4.3: answer/end call need no input — they act
            // through CallShield (token set on Home).
            "get_location", "screenshot", "fork", "stop_flow", "flow_stop", "answer_call", "end_call" -> {
                dialogNoInput = true
                val note = TextView(this)
                note.text = getString(R.string.flow_no_input)
                note.textSize = 14f
                note.setTextColor(getColor(R.color.md_onBackground))
                inputContainer.addView(note)
            }
            else -> {
                // v1.4.1: no more bare "Parameter" — the field is
                // labeled with the block's own name, and its hint
                // is the catalog hint, else an example from the
                // default param, else a plain value hint.
                dialogFallback = true
                val label = TextView(this)
                label.text = nodeTitle
                label.textSize = 13f
                label.setTextColor(getColor(R.color.md_onSurfaceVariant))
                inputContainer.addView(label)
                val field = EditText(this)
                field.setText(param)
                val example = FlowFlow.defaultParam(type)
                field.hint = when {
                    catalogHint.isNotEmpty() -> catalogHint
                    example.isNotEmpty() -> getString(R.string.flow_example, example)
                    else -> getString(R.string.panel_value_hint)
                }
                field.textSize = 14f
                field.setTextColor(getColor(R.color.md_onBackground))
                inputContainer.addView(field)
                dialogFallbackField = field
            }
        }

        when (type) {
            "battery_below", "battery_above" -> {
                section(R.string.panel_outputs)
                val out = TextView(this)
                out.text = getString(R.string.panel_level)
                out.textSize = 14f
                out.setTextColor(getColor(R.color.md_onBackground))
                root.addView(out)
            }
            "set_var" -> {
                val name = fieldText("name")
                if (name.isNotEmpty()) {
                    section(R.string.panel_outputs)
                    val out = TextView(this)
                    out.text = name
                    out.textSize = 14f
                    out.setTextColor(getColor(R.color.md_onBackground))
                    root.addView(out)
                }
            }
            else -> {
            }
        }
        if (type == "battery_below" || type == "battery_above") {
            val pct = batteryPercent()
            if (pct >= 0) {
                section(R.string.panel_current)
                val cur = TextView(this)
                cur.text = getString(R.string.panel_level) + ": " + pct + "%"
                cur.textSize = 14f
                cur.setTextColor(getColor(R.color.md_onBackground))
                root.addView(cur)
            }
        }

        AlertDialog.Builder(this)
            .setTitle(FlowFlow.title(this, node))
            .setView(root)
            .setPositiveButton(R.string.btn_save) { _, _ ->
                pushUndo()
                node.put("immediate", cbImmediate.isChecked)
                node.put("enabled", cbEnabled.isChecked)
                node.put("onerror", if (cbContinueOnError.isChecked) "continue" else "stop")
                node.put("note", etNote.text.toString())
                node.put("param", composeParam(type))
                canvas.invalidate()
                Toast.makeText(this, R.string.toast_saved, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.flow_delete_node) { _, _ ->
                pushUndo()
                FlowFlow.removeNode(flow, node.optString("id"))
                canvas.nodes = FlowFlow.nodesOf(flow)
                canvas.edges = FlowFlow.edgesOf(flow)
                canvas.selectedId = null
                canvas.invalidate()
            }
            .setNeutralButton(R.string.btn_cancel, null)
            .show()
    }

    private fun addPanelField(
        container: LinearLayout,
        key: String,
        labelRes: Int,
        initial: String,
        inputType: Int,
        hint: String = ""
    ) {
        val label = TextView(this)
        label.text = getString(labelRes)
        label.textSize = 13f
        label.setTextColor(getColor(R.color.md_onSurfaceVariant))
        container.addView(label)
        val field = EditText(this)
        field.setText(initial)
        field.inputType = inputType
        if (hint.isNotEmpty()) {
            field.hint = hint
        }
        field.textSize = 14f
        field.setTextColor(getColor(R.color.md_onBackground))
        container.addView(field)
        dialogFields.add(key to field)
    }

    /** Split a "|" param; limit behaves like Kotlin's split limit. */
    private fun barParts(param: String, limit: Int = 0): List<String> {
        return if (limit > 0) param.split("|", limit = limit) else param.split("|")
    }

    private fun barPart(parts: List<String>, i: Int, default: String = ""): String {
        return parts.getOrNull(i) ?: default
    }

    private fun fieldText(key: String): String {
        return dialogFields.firstOrNull { it.first == key }?.second?.text.toString()
    }

    private fun composeParam(type: String): String {
        if (dialogFallback) {
            return dialogFallbackField?.text.toString()
        }
        if (dialogNoInput) {
            return dialogOrigParam
        }
        return when (type) {
            "set_var", "set_local" -> fieldText("name") + "=" + fieldText("value")
            "for_each" -> fieldText("list") + "|" + fieldText("item")
            "loop_count" -> fieldText("count")
            "call_flow" -> fieldText("flow")
            "time_at" -> fieldText("time")
            "time_at_days" -> "time=" + fieldText("time") + "|days=" + fieldText("days")
            "location_at" -> fieldText("lat") + "|" + fieldText("lon") + "|" + fieldText("radius")
            "content_changed" -> fieldText("uri")
            "settings_changed" -> fieldText("key")
            "wifi_rssi", "audio_muted" -> fieldText(if (type == "wifi_rssi") "level" else "stream")
            "app_recent", "foreground_app" -> fieldText("package")
            "sound_level", "temp", "humidity", "heart_rate", "accel", "hinge_angle" -> fieldText("threshold")
            "time_interval", "wait" -> fieldText("seconds")
            "battery_below", "battery_above" -> fieldText("level")
            "time_range" -> fieldText("range")
            "charging", "screen" -> fieldText("state")
            "variable" -> fieldText("expression")
            "wifi_ssid" -> fieldText("ssid")
            "local_notify", "toast_local", "speak_local" -> fieldText("message")
            "call_number" -> fieldText("number")
            "open_app" -> {
                val extras = fieldText("extras")
                if (extras.isEmpty()) fieldText("package") else fieldText("package") + "|" + extras
            }
            "dialog_input" -> fieldText("title") + "|" + fieldText("default") + "|" + fieldText("dest")
            "dialog_message" -> fieldText("title") + "|" + fieldText("message")
            "dialog_progress" -> fieldText("title") + "|" + fieldText("seconds")
            "date_picker", "time_picker" -> fieldText("title") + "|" + fieldText("dest")
            "listen" -> fieldText("dest") + "|" + fieldText("prompt")
            "file_pick", "dir_pick", "clipboard_get" -> fieldText("dest")
            "file_list" -> fieldText("subdir") + "|" + fieldText("dest")
            "file_exists" -> fieldText("file") + "|" + fieldText("dest")
            "unzip" -> fieldText("archive") + "|" + fieldText("outdir")
            "wifi_connect" -> fieldText("ssid") + "|" + fieldText("password")
            "focuslock_control" -> fieldText("command")
            "hotspot_set", "battery_saver_set", "data_saver_set", "dark_set" -> fieldText("onoff")
            "list_append" -> fieldText("list") + "|" + fieldText("value")
            "list_get" -> fieldText("list") + "|" + fieldText("index") + "|" + fieldText("dest")
            "dict_get" -> fieldText("dict") + "|" + fieldText("key") + "|" + fieldText("dest")
            "text_replace" -> fieldText("var") + "|" + fieldText("from") + "|" + fieldText("to")
            "text_split" -> fieldText("var") + "|" + fieldText("separator") + "|" + fieldText("dest")
            "text_join" -> fieldText("list") + "|" + fieldText("separator") + "|" + fieldText("dest")
            "random_int" -> fieldText("min") + "|" + fieldText("max") + "|" + fieldText("dest")
            // v1.4.3.
            "qr_scan" -> fieldText("timeout")
            "wol" -> fieldText("mac") + "|" + fieldText("ip") + "|" + fieldText("port")
            "ping" -> fieldText("host") + "|" + fieldText("count") + "|" + fieldText("timeout")
            else -> dialogFallbackField?.text.toString()
        }
    }

    private fun batteryPercent(): Int {
        return try {
            val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (_: Exception) {
            -1
        }
    }

    private fun showPaletteSheet() {
        val sheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottomsheet_palette, null)
        sheet.setContentView(view)
        fillSheet(view.findViewById(R.id.cgTrig), "t", curatedTrig) {
            sheet.dismiss()
        }
        fillSheet(view.findViewById(R.id.cgCond), "c", curatedCond) {
            sheet.dismiss()
        }
        fillSheet(view.findViewById(R.id.cgAct), "a", curatedAct) {
            sheet.dismiss()
        }
        view.findViewById<View>(R.id.btnSheetClose).setOnClickListener {
            sheet.dismiss()
        }
        sheet.show()
    }

    private fun fillSheet(group: ChipGroup, kind: String, curated: List<String>, onDone: () -> Unit) {
        for (type in curated) {
            val chip = Chip(this)
            chip.text = FlowCatalog.label(this, kind, type)
            chip.setOnClickListener {
                onDone()
                addNode(kind, type)
            }
            group.addView(chip)
        }
        val all = Chip(this)
        all.text = "+"
        all.setOnClickListener {
            onDone()
            FlowPicker.show(this, kind) { type ->
                addNode(kind, type)
            }
        }
        group.addView(all)
    }

    private fun addNode(kind: String, type: String) {
        val nodes = FlowFlow.nodesOf(flow)
        val density = resources.displayMetrics.density
        val nodeWpx = 230f * density
        val nodeHpx = 92f * density
        val cx = if (canvas.width > 0) {
            canvas.width / canvas.zoom / 2f
        } else {
            200f * density
        }
        val cy = if (canvas.height > 0) {
            canvas.height / canvas.zoom / 2f
        } else {
            200f * density
        }
        val x = (cx - nodeWpx / 2f).coerceAtLeast(0f)
        val y = (cy - nodeHpx / 2f).coerceAtLeast(0f)
        pushUndo()
        val node = FlowFlow.newNode(kind, type, FlowFlow.defaultParam(type), x, y)
        nodes.put(node)
        canvas.nodes = nodes
        canvas.selectedId = node.optString("id")
        canvas.invalidate()
    }

    /**
     * v1.4.1 fan-out: one output port may feed MANY actions. The old
     * code replaced any edge sharing (from, branch), so a trigger
     * could only ever drive one action. Now an edge is added unless
     * that exact (from, to, branch) edge already exists; edge-tap
     * delete still removes only that one edge.
     */
    private fun storeEdge(fromId: String, toId: String, branch: String) {
        val edges = FlowFlow.edgesOf(flow)
        for (i in 0 until edges.length()) {
            val e = edges.optJSONObject(i) ?: continue
            if (e.optString("from") == fromId && e.optString("to") == toId &&
                e.optString("branch") == branch
            ) {
                return
            }
        }
        edges.put(FlowFlow.newEdge(fromId, toId, branch))
        canvas.edges = edges
        canvas.invalidate()
    }

    private fun removeEdge(fromId: String, toId: String, branch: String) {
        val kept = JSONArray()
        val old = FlowFlow.edgesOf(flow)
        for (i in 0 until old.length()) {
            val e = old.optJSONObject(i) ?: continue
            if (e.optString("from") == fromId && e.optString("to") == toId &&
                e.optString("branch") == branch
            ) {
                continue
            }
            kept.put(e)
        }
        flow.put("edges", kept)
    }

    private fun pushUndo() {
        undoStack.addLast(flow.toString())
        while (undoStack.size > 50) {
            undoStack.removeFirst()
        }
        redoStack.clear()
        refreshUndoButtons()
    }

    private fun undo() {
        if (undoStack.isEmpty()) {
            return
        }
        val current = flow.toString()
        while (undoStack.isNotEmpty()) {
            val candidate = undoStack.removeLast()
            if (candidate != current) {
                redoStack.addLast(current)
                flow = JSONObject(candidate)
                afterUndoRedo()
                return
            }
        }
        refreshUndoButtons()
    }

    private fun redo() {
        if (redoStack.isEmpty()) {
            return
        }
        val current = flow.toString()
        while (redoStack.isNotEmpty()) {
            val candidate = redoStack.removeLast()
            if (candidate != current) {
                undoStack.addLast(current)
                flow = JSONObject(candidate)
                afterUndoRedo()
                return
            }
        }
        refreshUndoButtons()
    }

    private fun afterUndoRedo() {
        canvas.nodes = FlowFlow.nodesOf(flow)
        canvas.edges = FlowFlow.edgesOf(flow)
        canvas.selectedId = null
        canvas.selectedEdge = null
        etFlowName.setText(flow.optString("name"))
        etFlowFolder.setText(flow.optString("folder"))
        canvas.invalidate()
        refreshUndoButtons()
    }

    private fun refreshUndoButtons() {
        btnUndoFlow.isEnabled = undoStack.isNotEmpty()
        btnUndoFlow.alpha = if (undoStack.isNotEmpty()) 1f else 0.4f
        btnRedoFlow.isEnabled = redoStack.isNotEmpty()
        btnRedoFlow.alpha = if (redoStack.isNotEmpty()) 1f else 0.4f
    }

    private fun saveFlowSilent(): Boolean {
        val name = etFlowName.text.toString().trim()
        if (name.isEmpty()) {
            Toast.makeText(this, R.string.toast_need_name, Toast.LENGTH_SHORT).show()
            return false
        }
        if (FlowFlow.nodesOf(flow).length() == 0) {
            Toast.makeText(this, R.string.toast_need_node, Toast.LENGTH_SHORT).show()
            return false
        }
        flow.put("name", name)
        flow.put("folder", etFlowFolder.text.toString().trim())
        FlowStore.saveFlow(this, flow)
        return true
    }
}

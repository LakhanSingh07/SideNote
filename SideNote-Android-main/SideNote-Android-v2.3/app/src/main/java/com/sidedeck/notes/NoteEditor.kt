package com.sidedeck.notes

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.Manifest
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.*
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Modern bottom-sheet note editor with rich editing, interactive to-dos, local undo/redo, and debounced persistence. */
object NoteEditor {
    fun show(context: Context, store: NotesStore, original: Note, done: () -> Unit) {
        Ui.configure(context)
        val d = Dialog(context)
        val scrollRoot = ScrollView(context).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        val root = Ui.column(context).apply {
            background = Ui.bg(Ui.card, Ui.dp(context, 28).toFloat())
            setPadding(Ui.dp(context, 20), Ui.dp(context, 16), Ui.dp(context, 20), Ui.dp(context, 22))
        }
        scrollRoot.addView(root)

        val bar = View(context).apply { background = Ui.bg(Ui.border, Ui.dp(context, 3).toFloat()) }
        val wrap = Ui.row(context).apply { gravity = Gravity.CENTER }
        wrap.addView(bar, Ui.lp(context, 36, 4))
        root.addView(wrap)
        root.addView(Ui.space(context, 14))

        // State variables
        var isChecklistMode = original.isChecklist
        var isInteractiveListMode = original.isChecklist
        var bodyContent = original.body
        var chosen = original.color.coerceIn(0, 4)
        var isPinned = original.pinned
        var isArchived = original.archived
        var reminderTime = original.reminderAt
        val handler = Handler(Looper.getMainLooper())
        var pending: Runnable? = null
        var dirty = false
        var deleted = false

        // Top Header Row
        val head = Ui.row(context).apply { gravity = Gravity.CENTER_VERTICAL }
        val label = Ui.column(context)
        label.addView(Ui.text(context, if (original.title.isBlank()) "New note" else "Edit note", 20f, true))
        label.addView(Ui.space(context, 3))
        val statsText = Ui.text(context, "Saved privately on this device", 12f, false, Ui.muted)
        label.addView(statsText)
        head.addView(label, LinearLayout.LayoutParams(0, -2, 1f))

        fun iconBtn(iconRes: Int, desc: String, isActive: () -> Boolean, action: () -> Unit): FrameLayout =
            FrameLayout(context).apply {
                val size = Ui.dp(context, 36)
                layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = Ui.dp(context, 6) }
                background = Ui.bg(if (isActive()) Ui.accentSoft else Ui.subtle, Ui.dp(context, 18).toFloat(), if (isActive()) Ui.violet else Ui.border)
                isClickable = true; isFocusable = true
                contentDescription = desc
                val iv = Ui.icon(context, iconRes, 18, if (isActive()) Ui.violet else Ui.ink)
                val lp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
                addView(iv, lp)
                setOnClickListener {
                    action()
                    background = Ui.bg(if (isActive()) Ui.accentSoft else Ui.subtle, Ui.dp(context, 18).toFloat(), if (isActive()) Ui.violet else Ui.border)
                    iv.imageTintList = android.content.res.ColorStateList.valueOf(if (isActive()) Ui.violet else Ui.ink)
                }
            }

        // Header quick actions: Pin, Archive, Reminder
        val pinBtn = iconBtn(R.drawable.ic_pin, "Pin note", { isPinned }) {
            isPinned = !isPinned
            dirty = true
            Toast.makeText(context, if (isPinned) "Note pinned to top" else "Note unpinned", Toast.LENGTH_SHORT).show()
        }
        head.addView(pinBtn)

        val archiveBtn = iconBtn(R.drawable.ic_nav_archive, "Archive note", { isArchived }) {
            isArchived = !isArchived
            dirty = true
            Toast.makeText(context, if (isArchived) "Note archived" else "Note unarchived", Toast.LENGTH_SHORT).show()
        }
        head.addView(archiveBtn)

        val reminderBtn = iconBtn(R.drawable.ic_time, "Set reminder", { reminderTime > System.currentTimeMillis() }) {
            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                if (context is Activity) {
                    context.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
                }
            }
            val choices = arrayOf("In 15 minutes", "In 1 hour", "Tomorrow (9:00 AM)", "Pick date & time…", "Clear reminder")
            AlertDialog.Builder(context)
                .setTitle("Note reminder")
                .setItems(choices) { _, which ->
                    when (which) {
                        0 -> {
                            reminderTime = System.currentTimeMillis() + 15 * 60 * 1000L
                            dirty = true
                            ReminderScheduler.schedule(context, original.copy(reminderAt = reminderTime))
                            val msg = "Reminder set for " + SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(reminderTime))
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        }
                        1 -> {
                            reminderTime = System.currentTimeMillis() + 60 * 60 * 1000L
                            dirty = true
                            ReminderScheduler.schedule(context, original.copy(reminderAt = reminderTime))
                            val msg = "Reminder set for " + SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(reminderTime))
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        }
                        2 -> {
                            val cal = Calendar.getInstance().apply {
                                add(Calendar.DAY_OF_YEAR, 1)
                                set(Calendar.HOUR_OF_DAY, 9)
                                set(Calendar.MINUTE, 0)
                                set(Calendar.SECOND, 0)
                            }
                            reminderTime = cal.timeInMillis
                            dirty = true
                            ReminderScheduler.schedule(context, original.copy(reminderAt = reminderTime))
                            val msg = "Reminder set for " + SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(reminderTime))
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        }
                        3 -> {
                            DateTimePickerHelper.pickDateTime(context, if (reminderTime > 0L) reminderTime else null) { chosenMillis ->
                                reminderTime = chosenMillis
                                dirty = true
                                ReminderScheduler.schedule(context, original.copy(reminderAt = reminderTime))
                                val msg = "Reminder set for " + SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(reminderTime))
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            }
                        }
                        else -> {
                            reminderTime = 0L
                            dirty = true
                            ReminderScheduler.schedule(context, original.copy(reminderAt = 0L))
                            Toast.makeText(context, "Reminder cleared", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        head.addView(reminderBtn)

        val doneButton = Ui.button(context, "Done") {}
        head.addView(doneButton)
        root.addView(head)
        root.addView(Ui.space(context, 14))

        var currentFontId = NoteFontManager.getFontForNote(context, original.id)

        val title = EditText(context).apply {
            hint = "Title"; setSingleLine(true); setText(original.title)
            setTextColor(Ui.ink); setHintTextColor(Ui.muted); textSize = 18f
            typeface = NoteFontManager.getTypeface(context, currentFontId, bold = true)
            background = Ui.bg(Ui.field, Ui.dp(context, 14).toFloat())
            setPadding(Ui.dp(context, 14), Ui.dp(context, 12), Ui.dp(context, 14), Ui.dp(context, 12))
            contentDescription = "Note title"
        }
        root.addView(title)
        root.addView(Ui.space(context, 10))

        // Control row: Checklist checkbox + View toggle + save status
        val controlRow = Ui.row(context)
        val checkbox = CheckBox(context).apply {
            text = "To-do list"; isChecked = isChecklistMode; setTextColor(Ui.ink); textSize = 13f
        }
        controlRow.addView(checkbox)

        val viewToggleBtn = Ui.text(context, if (isInteractiveListMode) "Text mode" else "List mode", 12f, true, Ui.ink).apply {
            background = Ui.bg(Ui.accentSoft, Ui.dp(context, 10).toFloat(), Ui.border)
            setPadding(Ui.dp(context, 10), Ui.dp(context, 6), Ui.dp(context, 10), Ui.dp(context, 6))
            isClickable = true; isFocusable = true
            visibility = if (isChecklistMode) View.VISIBLE else View.GONE
        }
        controlRow.addView(viewToggleBtn, LinearLayout.LayoutParams(-2, -2).apply { marginStart = Ui.dp(context, 12) })

        val status = Ui.text(context, "Saved", 12f, false, Ui.muted).apply {
            gravity = Gravity.END
        }
        controlRow.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(controlRow)
        root.addView(Ui.space(context, 8))

        // Text body editor (unconstrained maxLines so typing flows naturally)
        val body = EditText(context).apply {
            hint = if (isChecklistMode) "[ ] First task…" else "Write something lovely…"
            setText(RichTextFormatter.fromStorageString(original.body)); minLines = 5
            textSize = if (currentFontId == NoteFontManager.FONT_CURSIVE) 17f else 15f; gravity = Gravity.TOP
            typeface = NoteFontManager.getTypeface(context, currentFontId, bold = false)
            setTextColor(Ui.ink); setHintTextColor(Ui.muted)
            background = Ui.bg(Ui.field, Ui.dp(context, 16).toFloat())
            setPadding(Ui.dp(context, 14), Ui.dp(context, 14), Ui.dp(context, 14), Ui.dp(context, 14))
            contentDescription = "Note content"
        }

        // Interactive Checklist Container
        val checklistContainer = Ui.column(context).apply {
            background = Ui.bg(Ui.field, Ui.dp(context, 16).toFloat())
            setPadding(Ui.dp(context, 10), Ui.dp(context, 10), Ui.dp(context, 10), Ui.dp(context, 10))
            visibility = if (isChecklistMode && isInteractiveListMode) View.VISIBLE else View.GONE
        }
        val itemsLayout = Ui.column(context)
        checklistContainer.addView(itemsLayout)

        // Add task input row inside checklistContainer
        val addTaskRow = Ui.row(context).apply {
            setPadding(Ui.dp(context, 4), Ui.dp(context, 6), Ui.dp(context, 4), Ui.dp(context, 4))
        }
        val newTaskInput = EditText(context).apply {
            hint = "＋ Add a task… (Enter to add)"; setSingleLine(true); textSize = 14f
            setTextColor(Ui.ink); setHintTextColor(Ui.muted); background = null
            imeOptions = EditorInfo.IME_ACTION_DONE
            setPadding(Ui.dp(context, 8), Ui.dp(context, 8), Ui.dp(context, 8), Ui.dp(context, 8))
        }
        val addTaskBtn = Ui.text(context, "Add", 13f, true, Ui.onAccent).apply {
            background = Ui.bg(Ui.violet, Ui.dp(context, 10).toFloat())
            setPadding(Ui.dp(context, 14), Ui.dp(context, 8), Ui.dp(context, 14), Ui.dp(context, 8))
            isClickable = true; isFocusable = true
        }
        addTaskRow.addView(newTaskInput, LinearLayout.LayoutParams(0, -2, 1f))
        addTaskRow.addView(addTaskBtn)
        checklistContainer.addView(addTaskRow)

        root.addView(body)
        root.addView(checklistContainer)
        body.visibility = if (isChecklistMode && isInteractiveListMode) View.GONE else View.VISIBLE

        // Rich Toolbar (Format options + Undo/Redo)
        val toolbarScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val toolbar = Ui.row(context).apply {
            setPadding(0, Ui.dp(context, 4), 0, Ui.dp(context, 8))
        }

        fun toolBtn(iconRes: Int, label: String, desc: String, action: () -> Unit): LinearLayout =
            Ui.row(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = Ui.bg(Ui.subtle, Ui.dp(context, 10).toFloat(), Ui.border)
                setPadding(Ui.dp(context, 10), Ui.dp(context, 7), Ui.dp(context, 12), Ui.dp(context, 7))
                isClickable = true; isFocusable = true
                contentDescription = desc
                setOnClickListener { action() }
                addView(Ui.icon(context, iconRes, 16, Ui.ink), LinearLayout.LayoutParams(Ui.dp(context, 16), Ui.dp(context, 16)))
                addView(Ui.space(context, 6))
                addView(Ui.text(context, label, 12.5f, true, Ui.ink))
            }

        fun insertAtCursor(before: String, after: String = "") {
            val start = body.selectionStart.coerceAtLeast(0)
            val end = body.selectionEnd.coerceAtLeast(0)
            val text = body.text
            val selected = if (start != end) text.substring(start, end) else ""
            val replacement = "$before$selected$after"
            text.replace(start, end, replacement)
            body.setSelection(start + before.length + selected.length)
        }

        fun updateStats() {
            val txt = bodyContent
            if (isChecklistMode) {
                val lines = txt.lines().filter { it.isNotBlank() }
                val doneCount = lines.count { it.startsWith("[x] ") }
                statsText.text = "$doneCount / ${lines.size} tasks completed"
            } else {
                val words = if (txt.isBlank()) 0 else txt.trim().split("\\s+".toRegex()).size
                val chars = txt.length
                val reminderNote = if (reminderTime > System.currentTimeMillis()) " · ⏰ Set" else ""
                statsText.text = "$words words · $chars characters$reminderNote"
            }
        }

        fun persist(): Boolean {
            if (deleted || !dirty) return true
            val name = title.text.toString().trim()
            val contents = if (isChecklistMode && isInteractiveListMode) bodyContent else RichTextFormatter.toStorageString(body.text)
            if (name.isBlank() && contents.isBlank()) { dirty = false; return true }
            return try {
                store.save((store.all().firstOrNull { it.id==original.id } ?: original).copy(
                    title = name,
                    body = contents,
                    color = chosen,
                    isChecklist = isChecklistMode,
                    pinned = isPinned,
                    archived = isArchived,
                    reminderAt = reminderTime
                ))
                dirty = false; status.text = "Saved"; updateStats(); true
            } catch (e: Exception) { status.text = "Save failed"; false }
        }

        fun scheduleSave() {
            dirty = true; status.text = "Saving…"
            pending?.let(handler::removeCallbacks)
            pending = Runnable { persist() }.also { handler.postDelayed(it, 450L) }
        }

        // Local Undo/Redo Engine
        var undoBtn: LinearLayout? = null
        var redoBtn: LinearLayout? = null
        val historyManager = TextHistoryManager { canUndo, canRedo ->
            undoBtn?.alpha = if (canUndo) 1.0f else 0.4f
            undoBtn?.isClickable = canUndo
            redoBtn?.alpha = if (canRedo) 1.0f else 0.4f
            redoBtn?.isClickable = canRedo
        }
        historyManager.init(bodyContent)

        // Populate interactive checklist items
        fun renderChecklistItems() {
            itemsLayout.removeAllViews()
            val lines = bodyContent.lines().toMutableList()
            val validLines = lines.filterIndexed { index, line -> line.isNotBlank() || index < lines.size - 1 }
            if (validLines.isEmpty()) {
                val emptyHint = Ui.text(context, "No tasks yet. Type below to add one!", 13f, false, Ui.muted).apply {
                    setPadding(Ui.dp(context, 6), Ui.dp(context, 10), Ui.dp(context, 6), Ui.dp(context, 10))
                }
                itemsLayout.addView(emptyHint)
            } else {
                lines.forEachIndexed { idx, line ->
                    if (line.isBlank() && idx == lines.size - 1) return@forEachIndexed
                    val isChecked = line.startsWith("[x] ")
                    val taskCleanText = line.removePrefix("[x] ").removePrefix("[ ] ")
                    val itemRow = Ui.row(context).apply {
                        minimumHeight = Ui.dp(context, 40)
                        setPadding(Ui.dp(context, 2), Ui.dp(context, 4), Ui.dp(context, 2), Ui.dp(context, 4))
                    }
                    val cb = CheckBox(context).apply {
                        this.isChecked = isChecked
                    }
                    val taskTv = EditText(context).apply {
                        setText(taskCleanText)
                        textSize = if (currentFontId == NoteFontManager.FONT_CURSIVE) 16f else 14f
                        typeface = NoteFontManager.getTypeface(context, currentFontId, bold = false)
                        setTextColor(if (isChecked) Ui.muted else Ui.ink)
                        background = null; setSingleLine(false); maxLines = 3
                        setPadding(Ui.dp(context, 4), Ui.dp(context, 4), Ui.dp(context, 4), Ui.dp(context, 4))
                    }
                    Ui.strikeThrough(taskTv, isChecked)

                    cb.setOnCheckedChangeListener { _, checked ->
                        val updated = bodyContent.lines().toMutableList()
                        if (idx < updated.size) {
                            updated[idx] = (if (checked) "[x] " else "[ ] ") + taskCleanText
                            bodyContent = updated.joinToString("\n")
                            historyManager.onTextChanged(bodyContent)
                            body.setText(bodyContent)
                            Ui.strikeThrough(taskTv, checked)
                            taskTv.setTextColor(if (checked) Ui.muted else Ui.ink)
                            updateStats()
                            scheduleSave()
                        }
                    }

                    taskTv.addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                            val updated = bodyContent.lines().toMutableList()
                            if (idx < updated.size) {
                                val prefix = if (cb.isChecked) "[x] " else "[ ] "
                                updated[idx] = prefix + (s?.toString().orEmpty())
                                bodyContent = updated.joinToString("\n")
                                historyManager.onTextChanged(bodyContent)
                                scheduleSave()
                            }
                        }
                        override fun afterTextChanged(s: Editable?) {}
                    })

                    val del = Ui.icon(context, R.drawable.ic_close, 18, Color.rgb(210, 80, 80)).apply {
                        setPadding(Ui.dp(context, 8), Ui.dp(context, 4), Ui.dp(context, 8), Ui.dp(context, 4))
                        isClickable = true; isFocusable = true
                        contentDescription = "Delete item"
                    }
                    del.setOnClickListener {
                        val updated = bodyContent.lines().toMutableList()
                        if (idx < updated.size) {
                            updated.removeAt(idx)
                            bodyContent = updated.joinToString("\n")
                            historyManager.onTextChanged(bodyContent)
                            body.setText(bodyContent)
                            renderChecklistItems()
                            updateStats()
                            scheduleSave()
                        }
                    }

                    itemRow.addView(cb)
                    itemRow.addView(taskTv, LinearLayout.LayoutParams(0, -2, 1f))
                    itemRow.addView(del)
                    itemsLayout.addView(itemRow)
                }
            }
        }

        fun addNewTaskFromInput() {
            val text = newTaskInput.text.toString().trim()
            if (text.isNotBlank()) {
                val prefix = if (bodyContent.isBlank()) "" else if (bodyContent.endsWith("\n")) "" else "\n"
                bodyContent = "$bodyContent$prefix[ ] $text"
                historyManager.onTextChanged(bodyContent)
                body.setText(bodyContent)
                newTaskInput.setText("")
                renderChecklistItems()
                updateStats()
                scheduleSave()
            }
        }

        addTaskBtn.setOnClickListener { addNewTaskFromInput() }
        newTaskInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_NEXT) {
                addNewTaskFromInput(); true
            } else false
        }

        // Toolbar buttons: Undo, Redo, To-do, Bullet, Bold, Italic, Header, Time, Copy, Link, Speak
        undoBtn = toolBtn(R.drawable.ic_undo, "Undo", "Undo changes") {
            val previous = historyManager.undo(bodyContent)
            if (previous != null) {
                bodyContent = previous
                body.setText(previous)
                body.setSelection(previous.length.coerceAtMost(body.text.length))
                if (isInteractiveListMode) renderChecklistItems()
                updateStats()
                scheduleSave()
            }
        }.also {
            it.alpha = 0.4f
            it.isClickable = false
            toolbar.addView(it, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = Ui.dp(context, 6) })
        }

        redoBtn = toolBtn(R.drawable.ic_redo, "Redo", "Redo changes") {
            val next = historyManager.redo(bodyContent)
            if (next != null) {
                bodyContent = next
                body.setText(next)
                body.setSelection(next.length.coerceAtMost(body.text.length))
                if (isInteractiveListMode) renderChecklistItems()
                updateStats()
                scheduleSave()
            }
        }.also {
            it.alpha = 0.4f
            it.isClickable = false
            toolbar.addView(it, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = Ui.dp(context, 6) })
        }

        toolbar.addView(toolBtn(R.drawable.ic_checkbox, "To-do", "Insert to-do item") {
            insertAtCursor("[ ] ")
            scheduleSave()
        }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = Ui.dp(context, 6) })

        toolbar.addView(toolBtn(R.drawable.ic_bullet_list, "Bullet", "Insert bullet") {
            insertAtCursor("• ")
            scheduleSave()
        }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = Ui.dp(context, 6) })

        toolbar.addView(toolBtn(R.drawable.ic_format_bold, "Bold", "Format bold") {
            RichTextFormatter.applyBold(body)
            scheduleSave()
        }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = Ui.dp(context, 6) })

        toolbar.addView(toolBtn(R.drawable.ic_format_italic, "Italic", "Format italic") {
            RichTextFormatter.applyItalic(body)
            scheduleSave()
        }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = Ui.dp(context, 6) })

        toolbar.addView(toolBtn(R.drawable.ic_format_header, "Header", "Format header") {
            RichTextFormatter.applyHeading(body)
            scheduleSave()
        }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = Ui.dp(context, 6) })

        toolbar.addView(toolBtn(R.drawable.ic_format_bold, "Underline", "Format underline") {
            RichTextFormatter.applyUnderline(body)
            scheduleSave()
        }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = Ui.dp(context, 6) })

        toolbar.addView(toolBtn(R.drawable.ic_time, "Time", "Insert date and time") {
            val stamp = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date())
            insertAtCursor(stamp)
            scheduleSave()
        }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = Ui.dp(context, 6) })

        toolbar.addView(toolBtn(R.drawable.ic_copy, "Copy", "Copy note text") {
            val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clip.setPrimaryClip(ClipData.newPlainText("SideNote", bodyContent))
            Toast.makeText(context, "Note copied to clipboard", Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = Ui.dp(context, 6) })

        toolbar.addView(toolBtn(R.drawable.ic_link, "Link", "Insert web link") {
            val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clipText = clip?.primaryClip?.getItemAt(0)?.text?.toString()?.trim().orEmpty()
            val defaultUrl = if (clipText.startsWith("http://") || clipText.startsWith("https://")) clipText else "https://"

            val selStart = body.selectionStart.coerceAtLeast(0)
            val selEnd = body.selectionEnd.coerceAtLeast(selStart)
            val selectedText = if (selStart < selEnd) body.text.substring(selStart, selEnd) else ""

            val dialogLayout = Ui.column(context).apply {
                setPadding(Ui.dp(context, 20), Ui.dp(context, 16), Ui.dp(context, 20), Ui.dp(context, 10))
            }
            dialogLayout.addView(Ui.text(context, "Insert Web Link", 18f, true))
            dialogLayout.addView(Ui.space(context, 12))

            val titleInput = EditText(context).apply {
                hint = "Link Text (optional)"; setText(selectedText)
                textSize = 14f; setTextColor(Ui.ink); setHintTextColor(Ui.muted)
                background = Ui.bg(Ui.field, Ui.dp(context, 10).toFloat())
                setPadding(Ui.dp(context, 12), Ui.dp(context, 10), Ui.dp(context, 12), Ui.dp(context, 10))
            }
            dialogLayout.addView(titleInput)
            dialogLayout.addView(Ui.space(context, 10))

            val urlInput = EditText(context).apply {
                hint = "https://example.com"; setText(defaultUrl)
                textSize = 14f; setTextColor(Ui.ink); setHintTextColor(Ui.muted)
                background = Ui.bg(Ui.field, Ui.dp(context, 10).toFloat())
                setPadding(Ui.dp(context, 12), Ui.dp(context, 10), Ui.dp(context, 12), Ui.dp(context, 10))
                setSelection(defaultUrl.length)
            }
            dialogLayout.addView(urlInput)

            AlertDialog.Builder(context)
                .setView(dialogLayout)
                .setPositiveButton("Insert") { _, _ ->
                    val url = urlInput.text.toString().trim()
                    val linkTitle = titleInput.text.toString().trim()
                    if (url.isNotBlank() && url != "https://") {
                        val formatted = if (linkTitle.isNotBlank()) "[$linkTitle]($url)" else url
                        insertAtCursor(formatted)
                        scheduleSave()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = Ui.dp(context, 6) })

        toolbar.addView(toolBtn(R.drawable.ic_mic, "Speak", "Dictate note by voice") {
            try {
                val intent = Intent(context, VoiceInputActivity::class.java).apply {
                    putExtra(VoiceInputActivity.EXTRA_TARGET_NOTE_ID, original.id)
                    putExtra(VoiceInputActivity.EXTRA_APPEND_MODE, true)
                    putExtra(VoiceInputActivity.EXTRA_EDITOR_ACTIVE, true)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(context, "Voice input not available", Toast.LENGTH_SHORT).show()
            }
        }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = Ui.dp(context, 6) })

        fun applyCurrentFont(fontId: String) {
            currentFontId = fontId
            title.typeface = NoteFontManager.getTypeface(context, fontId, bold = true)
            body.typeface = NoteFontManager.getTypeface(context, fontId, bold = false)
            body.textSize = if (fontId == NoteFontManager.FONT_CURSIVE) 17f else 15f
            newTaskInput.typeface = NoteFontManager.getTypeface(context, fontId, bold = false)
            renderChecklistItems()
        }

        toolbar.addView(toolBtn(R.drawable.ic_font, "Font", "Choose note font") {
            NoteFontManager.showFontPickerDialog(context, currentFontId) { chosenFontId ->
                applyCurrentFont(chosenFontId)
                NoteFontManager.setFontForNote(context, original.id, chosenFontId)
                dirty = true
                scheduleSave()
                val item = NoteFontManager.FONTS.find { it.id == chosenFontId }
                Toast.makeText(context, "Font: ${item?.displayName ?: chosenFontId}", Toast.LENGTH_SHORT).show()
            }
        }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = Ui.dp(context, 6) })

        toolbarScroll.addView(toolbar)
        root.addView(toolbarScroll)
        root.addView(Ui.button(context,"Files (${original.attachments.size})") {
            dirty=true
            if(persist()) {
                if(store.all().none {it.id==original.id})store.save(original)
                context.startActivity(Intent(context,AttachmentActivity::class.java).putExtra("id",original.id).apply {if(context !is Activity)addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)})
                d.dismiss();done()
            }
        })
        root.addView(Ui.space(context, 10))

        // Checkbox mode toggle logic
        checkbox.setOnCheckedChangeListener { _, isChecked ->
            isChecklistMode = isChecked
            viewToggleBtn.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (isChecked) {
                val lines = bodyContent.lines().map { line ->
                    if (line.isNotBlank() && !line.startsWith("[ ] ") && !line.startsWith("[x] ")) "[ ] $line" else line
                }
                bodyContent = lines.joinToString("\n")
                historyManager.onTextChanged(bodyContent)
                body.setText(bodyContent)
                isInteractiveListMode = true
                viewToggleBtn.text = "Text mode"
                body.visibility = View.GONE
                checklistContainer.visibility = View.VISIBLE
                renderChecklistItems()
            } else {
                isInteractiveListMode = false
                body.visibility = View.VISIBLE
                checklistContainer.visibility = View.GONE
            }
            updateStats()
            scheduleSave()
        }

        viewToggleBtn.setOnClickListener {
            isInteractiveListMode = !isInteractiveListMode
            viewToggleBtn.text = if (isInteractiveListMode) "Text mode" else "List mode"
            if (isInteractiveListMode) {
                bodyContent = body.text.toString()
                body.visibility = View.GONE
                checklistContainer.visibility = View.VISIBLE
                renderChecklistItems()
            } else {
                body.visibility = View.VISIBLE
                checklistContainer.visibility = View.GONE
                body.setText(bodyContent)
            }
        }

        if (isChecklistMode && isInteractiveListMode) {
            renderChecklistItems()
        }

        // Color circles row
        val row = Ui.row(context)
        val circles = mutableListOf<TextView>()
        Ui.colors.forEachIndexed { i, color ->
            val circle = Ui.text(context, if (chosen == i) "✓" else "", 17f, true, if (Ui.dark) Color.WHITE else Color.rgb(40, 43, 44)).apply {
                gravity = Gravity.CENTER
                background = Ui.bg(color, Ui.dp(context, 22).toFloat(), if (chosen == i) Ui.ink else color)
                contentDescription = "Note color ${i + 1}" + (if (chosen == i) ", selected" else "")
                isClickable = true; isFocusable = true
            }
            row.addView(circle, LinearLayout.LayoutParams(0, Ui.dp(context, 40), 1f).apply { marginEnd = Ui.dp(context, 8) })
            circles.add(circle)
            circle.setOnClickListener {
                chosen = i
                circles.forEachIndexed { j, v ->
                    v.text = if (j == i) "✓" else ""
                    v.background = Ui.bg(Ui.colors[j], Ui.dp(context, 22).toFloat(), if (j == i) Ui.ink else Ui.colors[j])
                    v.contentDescription = "Note color ${j + 1}" + (if (j == i) ", selected" else "")
                }
                scheduleSave()
            }
        }
        root.addView(row)

        if (original.title.isNotBlank() || original.body.isNotBlank()) {
            root.addView(Ui.space(context, 16))
            val delete = Ui.text(context, "Delete note", 13f, true, if (Ui.dark) Color.rgb(248, 140, 130) else Color.rgb(184, 72, 63)).apply {
                minHeight = Ui.dp(context, 44); gravity = Gravity.CENTER_VERTICAL; isClickable = true; isFocusable = true
            }
            delete.setOnClickListener {
                AlertDialog.Builder(context).setTitle("Delete this note?")
                    .setMessage("You can restore this note from Trash in Settings.")
                    .setNegativeButton("Keep", null).setPositiveButton("Delete") { _, _ ->
                        pending?.let(handler::removeCallbacks)
                        try {
                            store.delete(original.id); ReminderScheduler.schedule(context, original.copy(reminderAt = 0L))
                            deleted = true; d.dismiss(); done()
                        } catch (e: Exception) { Toast.makeText(context, "Delete failed", Toast.LENGTH_LONG).show() }
                    }.show()
            }
            root.addView(delete)
        }

        d.setContentView(scrollRoot)
        d.setCancelable(false)

        title.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { scheduleSave() }
            override fun afterTextChanged(s: Editable?) {}
        })

        body.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val str = s?.toString().orEmpty()
                bodyContent = str
                historyManager.onTextChanged(str)
                updateStats()
                scheduleSave()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Auto-prefix [ ] on Enter in body when in checklist mode
        body.setOnKeyListener { _, keyCode, event ->
            if (isChecklistMode && event.action == android.view.KeyEvent.ACTION_DOWN && keyCode == android.view.KeyEvent.KEYCODE_ENTER) {
                insertAtCursor("\n[ ] ")
                scheduleSave()
                true
            } else false
        }

        updateStats()

        doneButton.setOnClickListener {
            pending?.let(handler::removeCallbacks)
            if (persist()) { d.dismiss(); done() }
            else Toast.makeText(context, "Your draft was not saved. Please retry.", Toast.LENGTH_LONG).show()
        }

        val voiceReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                val text = intent?.getStringExtra(VoiceInputActivity.EXTRA_RESULT_TEXT).orEmpty()
                if (text.isNotBlank()) {
                    val space = if (body.text.isNotEmpty() && !body.text.endsWith(" ") && !body.text.endsWith("\n")) " " else ""
                    insertAtCursor("$space$text")
                    scheduleSave()
                }
            }
        }
        val filter = android.content.IntentFilter(VoiceInputActivity.ACTION_VOICE_RESULT)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(voiceReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(voiceReceiver, filter)
        }

        d.setOnDismissListener {
            pending?.let(handler::removeCallbacks)
            runCatching { context.unregisterReceiver(voiceReceiver) }
            if (context is EditNoteActivity) context.finish()
        }

        d.show()
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(context.resources.displayMetrics.widthPixels - Ui.dp(context, 16), WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = .2f }
        }
    }
}

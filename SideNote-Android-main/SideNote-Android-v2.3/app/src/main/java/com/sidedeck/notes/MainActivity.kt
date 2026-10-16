package com.sidedeck.notes

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.text.DateFormat
import java.util.Date

/** Notes-first, editorial Android UI with automatic overlay service launch and interactive to-dos. */
class MainActivity : Activity() {
    private val store by lazy { NotesStore(this) }
    private var page = "Notes"
    private var query = ""
    private var selectedTab = "All"
    private lateinit var notesList: LinearLayout
    private val backupExport = 601
    private val backupImport = 602
    private val REQ_VOICE_NOTE = 701

    private fun startVoiceDictationNote() {
        try {
            val intent = Intent(this, VoiceInputActivity::class.java)
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "Voice dictation not available", Toast.LENGTH_SHORT).show()
        }
    }



    private val syncReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if(intent?.action==SyncHealth.ACTION){updateConnectionLabels();return}
            if (page == "Notes" || page == "Archive") {
                renderNotes(page == "Archive")
            } else if (page == "Settings") {
                showSettings()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Ui.configure(this)
        setTheme(if (Ui.dark) R.style.Theme_SideNote_Dark else R.style.Theme_SideNote)
        super.onCreate(savedInstanceState)
        Ui.configure(this)
        captureSharedText(intent)

        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(syncReceiver, android.content.IntentFilter(LanSyncManager.ACTION_SYNC_UPDATED).apply {addAction(SyncHealth.ACTION)}, RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(syncReceiver, android.content.IntentFilter(LanSyncManager.ACTION_SYNC_UPDATED).apply {addAction(SyncHealth.ACTION)})
            }
        } catch (_: Exception) {}

        LanSyncManager.startBackgroundListener(this)
        LanSyncManager.triggerSync(this)

        // Automatically start overlay service if permission was already granted
        if (Settings.canDrawOverlays(this)) {
            startSidebar(silent = true)
        }

        if (getSharedPreferences("sidenote_onboarding", MODE_PRIVATE).getBoolean("completed", false)) {
            navigate("Notes")
            handleIncomingNoteIntent(intent)
        } else {
            onboarding(0)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(syncReceiver) } catch (_: Exception) {}
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        captureSharedText(intent)
        navigate("Notes")
        handleIncomingNoteIntent(intent)
    }

    private fun handleIncomingNoteIntent(incoming: Intent?) {
        val noteId = incoming?.getStringExtra("open_note_id")
        val pickReminderId = incoming?.getStringExtra("pick_reminder_id")
        incoming?.removeExtra("open_note_id")
        incoming?.removeExtra("pick_reminder_id")
        try {
            if (pickReminderId != null) {
                val note = store.all().firstOrNull { it.id == pickReminderId }
                if (note != null) reminderDialog(note)
            } else if (noteId != null) {
                val note = store.all().firstOrNull { it.id == noteId }
                if (note != null) {
                    NoteEditor.show(this, store, note) { navigate(page) }
                }
            }
        } catch (_: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        Ui.configure(this)

        if (Settings.canDrawOverlays(this)) {
            // Automatically launch edge handle as soon as user returns from granting permission
            startSidebar(silent = true)
            if (page == "Onboarding") {
                getSharedPreferences("sidenote_onboarding", MODE_PRIVATE).edit().putBoolean("completed", true).apply()
                Toast.makeText(this, "SideNote handle is active! Swipe from edge of screen.", Toast.LENGTH_LONG).show()
                navigate("Notes")
                return
            }
        }

        if (getSharedPreferences("sidenote_onboarding", MODE_PRIVATE).getBoolean("completed", false)) {
            if (page == "Notes" || page == "Archive" || page == "Settings") navigate(page)
        }
    }

    private fun captureSharedText(incoming: Intent?) {
        if(incoming?.action==Intent.ACTION_SEND || incoming?.action==Intent.ACTION_SEND_MULTIPLE) {
            @Suppress("DEPRECATION") val single=incoming?.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
            @Suppress("DEPRECATION") val multiple=incoming?.getParcelableArrayListExtra<android.net.Uri>(Intent.EXTRA_STREAM)
            val files=multiple ?: arrayListOf<android.net.Uri>().apply {if(single!=null)add(single)}
            if(files.isNotEmpty()) {
                val note=store.fresh().copy(title="Shared files",body=incoming?.getStringExtra(Intent.EXTRA_TEXT).orEmpty());store.save(note)
                startActivity(Intent(this,AttachmentActivity::class.java).putExtra("id",note.id).putParcelableArrayListExtra("files",files)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply {clipData=incoming?.clipData ?: android.content.ClipData.newRawUri("File",files[0])})
                incoming?.action=null;return
            }
        }
        if (incoming?.action == Intent.ACTION_SEND && incoming.type?.startsWith("text/") == true) {
            val text = incoming.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            if (text.isNotBlank()) runCatching { store.save(store.fresh().copy(title = "Shared note", body = text)) }
            incoming.action = null
        }
    }

    private fun systemBars() {
        window.statusBarColor = Ui.surface
        window.navigationBarColor = if (Ui.dark) Color.rgb(10, 11, 12) else Color.rgb(18, 19, 20)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (Ui.dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
    }

    private fun margin(bottom: Int = 0, top: Int = 0, start: Int = 0, end: Int = 0) = Ui.lp(this).apply {
        bottomMargin = Ui.dp(this@MainActivity, bottom); topMargin = Ui.dp(this@MainActivity, top)
        marginStart = Ui.dp(this@MainActivity, start); marginEnd = Ui.dp(this@MainActivity, end)
    }

    private fun onboarding(stage: Int) {
        page = "Onboarding"; Ui.configure(this); systemBars()
        val root = Ui.column(this).apply {
            setBackgroundColor(Ui.surface)
            setPadding(Ui.dp(this@MainActivity, 24), Ui.dp(this@MainActivity, 40), Ui.dp(this@MainActivity, 24), Ui.dp(this@MainActivity, 25))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            fitsSystemWindows = true
        }
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            insets
        }
        scroll.addView(root)
        setContentView(scroll)

        val brand = Ui.row(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val brandImg = ImageView(this).apply {
            setImageResource(if (Ui.dark) R.drawable.ic_sidenote_brand_dark else R.drawable.ic_sidenote_brand)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_START
        }
        brand.addView(brandImg, LinearLayout.LayoutParams(Ui.dp(this, 180), Ui.dp(this, 52)))
        root.addView(brand)
        root.addView(Ui.space(this, 36))
        root.addView(Ui.editorial(this, if (stage == 0) "Your thoughts.\nAlways in reach." else "A little note,\none swipe away.", 34f))
        root.addView(Ui.space(this, 12))
        root.addView(Ui.text(this, if (stage == 0) "Keep the little things close, without switching apps." else "A prominent edge notch handle lets you peek at notes anywhere.", 14f, false, Ui.muted))
        root.addView(Ui.space(this, 26))

        val demo = Ui.column(this).apply {
            background = Ui.bg(if (Ui.dark) Ui.field else 0xFFE6F1D2.toInt(), Ui.dp(this@MainActivity, 24).toFloat())
            setPadding(Ui.dp(this@MainActivity, 20), Ui.dp(this@MainActivity, 22), Ui.dp(this@MainActivity, 20), Ui.dp(this@MainActivity, 22))
        }
        demo.addView(Ui.text(this, "✳  Interactive To-dos & Floating Edge Deck", 14f, true, if (Ui.dark) Ui.ink else Color.rgb(40, 58, 42)))
        demo.addView(Ui.space(this, 18))
        val paper = Ui.surfaceCard(this, if (Ui.dark) Ui.card else Color.WHITE)
        paper.addView(Ui.text(this, "Things for today", 16f, true))
        paper.addView(Ui.space(this, 12))
        paper.addView(Ui.text(this, "☐  Pick up a package", 13f, false, Ui.muted))
        paper.addView(Ui.space(this, 8))
        paper.addView(Ui.text(this, "☑  Send the presentation", 13f, false, Ui.muted))
        paper.addView(Ui.space(this, 8))
        paper.addView(Ui.text(this, "☐  Water the plants", 13f, false, Ui.muted))
        demo.addView(paper)
        root.addView(demo)
        root.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        root.addView(Ui.space(this, 20))

        if (stage == 1) {
            val hasOverlay = Settings.canDrawOverlays(this)
            val overlayCard = Ui.surfaceCard(this, Ui.card).apply {
                addView(Ui.text(this@MainActivity, "1. Floating Note Handle", 15f, true))
                addView(Ui.space(this@MainActivity, 4))
                addView(Ui.text(this@MainActivity, "Allow 'Display over other apps' so the edge notch can appear anywhere.", 12f, false, Ui.muted))
                addView(Ui.space(this@MainActivity, 10))
                addView(Ui.button(this@MainActivity, if (hasOverlay) "✓ Overlay Permission Granted" else "Grant Overlay Permission") {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                })
            }
            root.addView(overlayCard)
            root.addView(Ui.space(this, 12))

            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                val notifCard = Ui.surfaceCard(this, Ui.card).apply {
                    addView(Ui.text(this@MainActivity, "2. Notifications", 15f, true))
                    addView(Ui.space(this@MainActivity, 4))
                    addView(Ui.text(this@MainActivity, "Allows persistent background service & note reminders.", 12f, false, Ui.muted))
                    addView(Ui.space(this@MainActivity, 10))
                    addView(Ui.outline(this@MainActivity, "Allow Notifications") {
                        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
                    })
                }
                root.addView(notifCard)
                root.addView(Ui.space(this, 12))
            }
        }

        root.addView(Ui.button(this, if (stage == 0) "See how it works   →" else "Start writing   →") {
            if (stage == 0) {
                onboarding(1)
            } else {
                getSharedPreferences("sidenote_onboarding", MODE_PRIVATE).edit().putBoolean("completed", true).apply()
                if (Settings.canDrawOverlays(this)) startSidebar(silent = false)
                navigate("Notes")
            }
        })
        root.addView(Ui.space(this, 14))
        root.addView(Ui.text(this, if (stage == 0) "01 / 02" else "When permission is granted, SideNote automatically appears at the edge.", 12f, false, Ui.muted).apply { gravity = Gravity.CENTER })
    }

    private fun navigate(target: String) {
        page = target; Ui.configure(this); systemBars()
        when (target) {
            "Settings" -> showSettings()
            "Archive" -> showNotes(true)
            else -> showNotes(false)
        }
    }

    private fun shell(showPlus: Boolean, pageTitle: String, build: (LinearLayout) -> Unit) {
        val container = Ui.column(this).apply {
            setBackgroundColor(Ui.surface)
            fitsSystemWindows = true
        }
        val center = FrameLayout(this)
        container.addView(center, LinearLayout.LayoutParams(-1, 0, 1f))
        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false; overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS }
        val body = Ui.column(this).apply {
            setPadding(Ui.dp(this@MainActivity, 20), Ui.dp(this@MainActivity, 20), Ui.dp(this@MainActivity, 20), Ui.dp(this@MainActivity, 100))
        }
        scroll.addView(body); center.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        build(body)
        if (showPlus) {
            val fabCol = Ui.column(this).apply { gravity = Gravity.CENTER_HORIZONTAL }
            val voiceFab = FrameLayout(this).apply {
                background = Ui.bg(Ui.card, Ui.dp(this@MainActivity, 24).toFloat(), Ui.border)
                elevation = Ui.dp(this@MainActivity, 4).toFloat()
                isClickable = true; isFocusable = true; contentDescription = "Dictate note by voice"
                setOnClickListener { startVoiceDictationNote() }
                addView(Ui.icon(this@MainActivity, R.drawable.ic_mic, 22, Ui.ink), FrameLayout.LayoutParams(Ui.dp(this@MainActivity, 22), Ui.dp(this@MainActivity, 22), Gravity.CENTER))
            }
            fabCol.addView(voiceFab, Ui.lp(this, 48, 48).apply { bottomMargin = Ui.dp(this@MainActivity, 12) })

            val plus = FrameLayout(this).apply {
                background = Ui.bg(Ui.violet, Ui.dp(this@MainActivity, 28).toFloat())
                elevation = Ui.dp(this@MainActivity, 5).toFloat()
                isClickable = true; isFocusable = true; contentDescription = "Create note"
                setOnClickListener { NoteEditor.show(this@MainActivity, store, store.fresh()) { navigate(page) } }
                setOnLongClickListener {NoteTemplates.choose(this@MainActivity,store){navigate(page)};true}
                addView(Ui.icon(this@MainActivity, R.drawable.ic_add, 28, Ui.onAccent), FrameLayout.LayoutParams(Ui.dp(this@MainActivity, 28), Ui.dp(this@MainActivity, 28), Gravity.CENTER))
            }
            fabCol.addView(plus, Ui.lp(this, 56, 56))

            center.addView(fabCol, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
                marginEnd = Ui.dp(this@MainActivity, 24); bottomMargin = Ui.dp(this@MainActivity, 22)
            })
        }
        val navigation = Ui.row(this).apply {
            setBackgroundColor(if (Ui.dark) Color.rgb(9, 10, 11) else Color.rgb(18, 19, 20))
            setPadding(Ui.dp(this@MainActivity, 12), Ui.dp(this@MainActivity, 8), Ui.dp(this@MainActivity, 12), Ui.dp(this@MainActivity, 9))
        }
        container.setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(0, insets.systemWindowInsetTop, 0, 0)
            navigation.setPadding(
                Ui.dp(this@MainActivity, 12),
                Ui.dp(this@MainActivity, 8),
                Ui.dp(this@MainActivity, 12),
                Ui.dp(this@MainActivity, 9) + insets.systemWindowInsetBottom
            )
            insets
        }
        listOf(
            Triple("Notes", R.drawable.ic_nav_notes, "Notes"),
            Triple("Archive", R.drawable.ic_nav_archive, "Archive"),
            Triple("Settings", R.drawable.ic_nav_settings, "Settings")
        ).forEach { (destination, iconRes, label) ->
            val isSel = page == destination
            val tintColor = if (isSel) Ui.violet else Color.rgb(150, 153, 158)
            val tab = Ui.column(this).apply {
                gravity = Gravity.CENTER; minimumHeight = Ui.dp(this@MainActivity, 56); isClickable = true; isFocusable = true
                contentDescription = label + (if (isSel) ", selected" else "")
                setOnClickListener { if (page != destination) navigate(destination) }
            }
            val icView = Ui.icon(this, iconRes, 22, tintColor)
            tab.addView(icView, LinearLayout.LayoutParams(Ui.dp(this, 22), Ui.dp(this, 22)).apply { gravity = Gravity.CENTER })
            tab.addView(Ui.space(this, 3))
            tab.addView(Ui.text(this, label, 10.5f, isSel, tintColor).apply { gravity = Gravity.CENTER })
            navigation.addView(tab, LinearLayout.LayoutParams(0, -2, 1f))
        }
        container.addView(navigation)
        setContentView(container)
    }

    private fun topBrand(body: LinearLayout) {
        val row = Ui.row(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val logoImg = ImageView(this).apply {
            setImageResource(if (Ui.dark) R.drawable.ic_sidenote_brand_dark else R.drawable.ic_sidenote_brand)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_START
        }
        row.addView(logoImg, LinearLayout.LayoutParams(Ui.dp(this, 160), Ui.dp(this, 48)))
        row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))

        val isPaired = LanSyncManager.isPaired(this)
        val pcName = LanSyncManager.getPairedPcName(this) ?: "Windows PC"
        val syncLabel = if (isPaired) "${SyncHealth.label(this)} $pcName" else "📶 Windows Sync"
        val tag = Ui.text(this, syncLabel, 11.5f, true, if (isPaired) Color.rgb(46, 125, 50) else Ui.violet).apply {
            this.tag="sync-state-tag"
            background = Ui.bg(if (isPaired) 0x224CAF50.toInt() else Ui.subtle, Ui.dp(this@MainActivity, 12).toFloat(), if (isPaired) 0x664CAF50.toInt() else Ui.border)
            setPadding(Ui.dp(this@MainActivity, 12), Ui.dp(this@MainActivity, 6), Ui.dp(this@MainActivity, 12), Ui.dp(this@MainActivity, 6))
            isClickable = true
            isFocusable = true
            setOnClickListener { showPairingDialog() }
        }
        row.addView(tag)
        body.addView(row)
    }

    private fun showNotes(archived: Boolean) {
        shell(!archived, if (archived) "Archive" else "Notes") { body ->
            topBrand(body)
            body.addView(Ui.space(this, 24))

            body.addView(Ui.editorial(this, if (archived) "Kept for later." else "Make space for\nyour little thoughts.", 34f))
            body.addView(Ui.space(this, 8))
            body.addView(Ui.text(this, if (archived) "Notes you tucked away." else "A calm place for everything on your mind.", 14f, false, Ui.muted))
            body.addView(Ui.space(this, 24))
            body.addView(Ui.secondary(this,"Start from a template"){NoteTemplates.choose(this,store){navigate(page)}});body.addView(Ui.space(this,16))
            val searchWrap = Ui.row(this).apply {
                background = Ui.bg(Ui.card, Ui.dp(this@MainActivity, 16).toFloat(), Ui.border)
                setPadding(Ui.dp(this@MainActivity, 14), 0, Ui.dp(this@MainActivity, 14), 0)
                minimumHeight = Ui.dp(this@MainActivity, 48)
            }
            searchWrap.addView(Ui.text(this, "⌕", 22f, false, Ui.muted))
            val search = EditText(this).apply {
                hint = "Search your notes"; setSingleLine(true); setText(query); textSize = 14f
                setTextColor(Ui.ink); setHintTextColor(Ui.muted); background = null
                setPadding(Ui.dp(this@MainActivity, 10), 0, Ui.dp(this@MainActivity, 4), 0)
                contentDescription = "Search notes"
            }
            searchWrap.addView(search, LinearLayout.LayoutParams(0, -2, 1f)); body.addView(searchWrap)
            body.addView(Ui.space(this, 18))
            val filters = Ui.row(this)
            (if (archived) listOf("Archived") else listOf("All", "Pinned", "To-dos", "Reminders")).forEach { name ->
                val chosen = archived || selectedTab == name
                val cell = Ui.column(this).apply {
                    gravity = Gravity.CENTER_HORIZONTAL; isClickable = true; isFocusable = true
                    setPadding(Ui.dp(this@MainActivity, 14), 0, Ui.dp(this@MainActivity, 14), 0)
                    contentDescription = "$name filter" + (if (chosen) ", selected" else "")
                    setOnClickListener { selectedTab = name; navigate("Notes") }
                }
                val tabText = Ui.text(this, name, 14f, chosen, if (chosen) Ui.ink else Ui.muted).apply {
                    setSingleLine(true)
                }
                cell.addView(tabText, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL })
                cell.addView(Ui.space(this, 8))
                val underline = View(this).apply { background = Ui.bg(if (chosen) Ui.violet else Color.TRANSPARENT, Ui.dp(this@MainActivity, 2).toFloat()) }
                cell.addView(underline, LinearLayout.LayoutParams(Ui.dp(this@MainActivity, 24), Ui.dp(this@MainActivity, 3)).apply { gravity = Gravity.CENTER_HORIZONTAL })
                filters.addView(cell, LinearLayout.LayoutParams(-2, -2))
            }
            body.addView(filters); body.addView(Ui.space(this, 20))
            notesList = Ui.column(this)
            body.addView(notesList)
            search.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { query = s?.toString().orEmpty(); renderNotes(archived) }
                override fun afterTextChanged(s: Editable?) {}
            })
            renderNotes(archived)
        }
    }

    private fun renderNotes(archived: Boolean) {
        if (!::notesList.isInitialized) return
        notesList.removeAllViews()
        val all = try { store.all() } catch (e: Exception) {
            notesList.addView(Ui.emptyState(this, "Your notes need attention", "Local data could not be read. Original bytes have been preserved.")); return
        }
        val entries = all.filter { note ->
            note.archived == archived && (archived || when (selectedTab) { "Pinned" -> note.pinned; "To-dos" -> note.isChecklist; "Reminders" -> note.reminderAt > 0L; else -> true }) &&
                (query.isBlank() || note.title.contains(query, true) || RichTextFormatter.toPlainText(note.body).contains(query, true))
        }
        if (entries.isEmpty()) {
            val emptyTitle = if (query.isNotBlank()) "Nothing found"
                else if (archived) "Your archive is empty"
                else if (selectedTab == "Reminders") "No reminders set"
                else "A fresh page."
            val emptySubtitle = if (query.isNotBlank()) "Try a different search."
                else if (archived) "Archived notes will appear here."
                else if (selectedTab == "Reminders") "Tap ⋯ or edit a note to set a reminder."
                else "Tap + to save your first thought."
            notesList.addView(Ui.emptyState(this, emptyTitle, emptySubtitle))
            return
        }
        entries.forEach { note ->
            val pastel = Ui.colors[note.color.coerceIn(0, 4)]
            val box = Ui.column(this).apply {
                background = Ui.bg(pastel, Ui.dp(this@MainActivity, 22).toFloat())
                setPadding(Ui.dp(this@MainActivity, 18), Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 16))
                isClickable = true; isFocusable = true
                contentDescription = "Open note ${note.title.ifBlank { "Untitled" }}"
                setOnClickListener { NoteEditor.show(this@MainActivity, store, note) { navigate(page) } }
            }
            val head = Ui.row(this)
            val mark = Ui.text(this, if (note.isChecklist) "☑" else if (note.pinned) "✳" else "◌", 15f, true, if (Ui.dark) Ui.ink else Color.rgb(74, 76, 73))
            head.addView(mark)
            head.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            head.addView(Ui.text(this, DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(note.updatedAt)), 11f, false, Ui.muted))
            box.addView(head); box.addView(Ui.space(this, 12))
            val noteFont = NoteFontManager.getFontForNote(this, note.id)
            val noteTf = NoteFontManager.getTypeface(this, noteFont, bold = false)
            val noteTfBold = NoteFontManager.getTypeface(this, noteFont, bold = true)

            box.addView(Ui.text(this, note.title.ifBlank { "Untitled note" }, 17f, true).apply {
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                typeface = noteTfBold
            })
            box.addView(Ui.space(this, 8))

            // Interactive To-Do preview vs standard note preview
            if (note.isChecklist) {
                val lines = note.body.lines().filter { it.isNotBlank() }
                val taskLayout = Ui.column(this)
                lines.take(5).forEach { line ->
                    val checked = line.startsWith("[x] ")
                    val taskClean = line.removePrefix("[x] ").removePrefix("[ ] ")
                    val tRow = Ui.row(this).apply {
                        setPadding(0, Ui.dp(this@MainActivity, 2), 0, Ui.dp(this@MainActivity, 2))
                    }
                    val cb = CheckBox(this).apply {
                        isChecked = checked
                    }
                    val tv = Ui.text(this, taskClean, if (noteFont == NoteFontManager.FONT_CURSIVE) 14.5f else 13f, false, if (checked) Ui.muted else Ui.ink).apply {
                        maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                        typeface = noteTf
                    }
                    Ui.strikeThrough(tv, checked)

                    cb.setOnClickListener {
                        val currentLines = note.body.lines().toMutableList()
                        val lineIndex = currentLines.indexOf(line)
                        if (lineIndex >= 0) {
                            currentLines[lineIndex] = (if (cb.isChecked) "[x] " else "[ ] ") + taskClean
                            val updated = (store.all().firstOrNull {it.id==note.id} ?: note).copy(body = currentLines.joinToString("\n"))
                            updateNote(updated)
                        }
                    }

                    tRow.addView(cb)
                    tRow.addView(tv, LinearLayout.LayoutParams(0, -2, 1f))
                    taskLayout.addView(tRow)
                }
                if (lines.size > 5) {
                    taskLayout.addView(Ui.text(this, "+ ${lines.size - 5} more tasks", 11f, false, Ui.muted).apply {
                        setPadding(Ui.dp(this@MainActivity, 32), Ui.dp(this@MainActivity, 2), 0, 0)
                    })
                }
                box.addView(taskLayout)
            } else {
                val richSpanned = RichTextFormatter.fromStorageString(note.body)
                val preview = if (richSpanned.isBlank()) "Tap to start writing…" else richSpanned
                val tv = TextView(this).apply {
                    text = preview
                    textSize = if (noteFont == NoteFontManager.FONT_CURSIVE) 15f else 13f
                    typeface = noteTf
                    setTextColor(if (Ui.dark) Ui.muted else Color.rgb(81, 85, 85))
                    maxLines = 4
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                box.addView(tv)
            }

            box.addView(Ui.space(this, 14))
            val bottom = Ui.row(this).apply { gravity = Gravity.CENTER_VERTICAL }
            val typeLabel = NoteTemplates.badge(note)
            bottom.addView(Ui.text(this, typeLabel, 11f, true, Ui.muted))

            if (note.reminderAt > 0L) {
                val remBadge = Ui.text(this, formatReminderBadge(note.reminderAt), 10.5f, true, Ui.violet).apply {
                    background = Ui.bg(
                        if (Ui.dark) Color.argb(45, 140, 100, 255) else Color.argb(35, 120, 80, 230),
                        Ui.dp(this@MainActivity, 8).toFloat()
                    )
                    setPadding(Ui.dp(this@MainActivity, 7), Ui.dp(this@MainActivity, 2),
                        Ui.dp(this@MainActivity, 7), Ui.dp(this@MainActivity, 2))
                }
                bottom.addView(Ui.space(this, 8))
                bottom.addView(remBadge)
            }
            bottom.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))

            val more = Ui.text(this, "⋯", 22f, true, Ui.ink).apply {
                gravity = Gravity.CENTER; minWidth = Ui.dp(this@MainActivity, 44); minHeight = Ui.dp(this@MainActivity, 44)
                isClickable = true; isFocusable = true; contentDescription = "More options for ${note.title}"
            }
            more.setOnClickListener { openNoteActions(more, note) }
            bottom.addView(more)
            box.addView(bottom)
            notesList.addView(box, margin(bottom = 14))
        }
    }

    private fun openNoteActions(anchor: View, note: Note) {
        val items = listOf(
            Ui.ThemedMenuItem(label = if (note.pinned) "Unpin note" else "Pin note", iconRes = R.drawable.ic_pin) {
                updateNote((store.all().firstOrNull {it.id==note.id} ?: note).copy(pinned = !note.pinned))
            },
            Ui.ThemedMenuItem(label = if (note.archived) "Restore note" else "Archive note", iconRes = R.drawable.ic_nav_archive) {
                updateNote((store.all().firstOrNull {it.id==note.id} ?: note).copy(archived = !note.archived))
            },
            Ui.ThemedMenuItem(label = "Set reminder", iconRes = R.drawable.ic_time) {
                reminderDialog(note)
            },
            Ui.ThemedMenuItem(label = "Copy text", iconRes = R.drawable.ic_copy) {
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("SideNote", note.body))
                Toast.makeText(this, "Note copied to clipboard", Toast.LENGTH_SHORT).show()
            },
            Ui.ThemedMenuItem(label = "Delete note", iconRes = R.drawable.ic_delete, isDestructive = true) {
                AlertDialog.Builder(this).setTitle("Delete this note?")
                    .setMessage("This cannot be undone.")
                    .setNegativeButton("Keep", null).setPositiveButton("Delete") { _, _ ->
                        try {
                            store.delete(note.id)
                            ReminderScheduler.schedule(this, (store.all().firstOrNull {it.id==note.id} ?: note).copy(reminderAt = 0L))
                            renderNotes(page == "Archive")
                        } catch (e: Exception) { Toast.makeText(this, "Delete failed", Toast.LENGTH_SHORT).show() }
                    }.show()
            }
        )
        Ui.showThemedMenu(anchor, items)
    }

    private fun updateNote(note: Note) {
        try { store.save(note); renderNotes(page == "Archive") }
        catch (e: Exception) { Toast.makeText(this, "Could not save note", Toast.LENGTH_LONG).show() }
    }

    private fun settingRow(label: String, description: String, action: () -> Unit): LinearLayout = Ui.column(this).apply {
        background = Ui.bg(Ui.card, Ui.dp(this@MainActivity, 18).toFloat())
        setPadding(Ui.dp(this@MainActivity, 17), Ui.dp(this@MainActivity, 15), Ui.dp(this@MainActivity, 17), Ui.dp(this@MainActivity, 15))
        isClickable = true; isFocusable = true; setOnClickListener { action() }
        addView(Ui.text(this@MainActivity, label, 15f, true))
        addView(Ui.space(this@MainActivity, 5))
        addView(Ui.text(this@MainActivity, description, 12f, false, Ui.muted))
    }

    private fun showSettings() {
        shell(false, "Settings") { body ->
            topBrand(body); body.addView(Ui.space(this, 26))
            body.addView(Ui.editorial(this, "Make it yours.", 34f)); body.addView(Ui.space(this, 8))
            body.addView(Ui.text(this, "A quieter, more personal workspace.", 14f, false, Ui.muted))
            body.addView(Ui.space(this, 30))
            body.addView(Ui.text(this, "APPEARANCE", 11f, true, Ui.muted)); body.addView(Ui.space(this, 12))
            val themeRow = Ui.row(this)
            val pref = getSharedPreferences("overlay_ui", MODE_PRIVATE)
            listOf("Light", "Dark", "System").forEach { mode ->
                val active = pref.getString("theme_mode", "System") == mode
                val btn = Ui.text(this, mode, 14f, active, Ui.ink).apply {
                    gravity = Gravity.CENTER; minHeight = Ui.dp(this@MainActivity, 48)
                    background = Ui.bg(if (active) Ui.accentSoft else Ui.card, Ui.dp(this@MainActivity, 15).toFloat(), if (active) Ui.violet else Ui.border)
                    isClickable = true; isFocusable = true; contentDescription = "$mode theme" + (if (active) ", selected" else "")
                    setOnClickListener {
                        pref.edit().putString("theme_mode", mode).apply(); Ui.configure(this@MainActivity)
                        refreshOverlayAppearance(); navigate("Settings")
                    }
                }
                themeRow.addView(btn, LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1f).apply { marginEnd = Ui.dp(this@MainActivity, 7) })
            }
            body.addView(themeRow); body.addView(Ui.space(this, 30))
            body.addView(CheckBox(this).apply {text="Reduce motion";setTextColor(Ui.ink);isChecked=pref.getBoolean("reduce_motion",false);setOnCheckedChangeListener {_,checked->pref.edit().putBoolean("reduce_motion",checked).apply();refreshOverlayAppearance()}})

            body.addView(Ui.text(this, "FLOATING NOTES", 11f, true, Ui.muted)); body.addView(Ui.space(this, 12))
            body.addView(settingRow(if (Settings.canDrawOverlays(this)) "✓  Floating access enabled" else "Enable floating notes", "Show notes on top of supported apps") {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }); body.addView(Ui.space(this, 9))
            body.addView(settingRow("Open the edge deck", "Turn on the prominent edge swipe handle") { startSidebar() }); body.addView(Ui.space(this, 9))
            body.addView(settingRow("Stop floating notes", "Dismiss all overlays without deleting notes") {
                stopService(Intent(this, OverlayService::class.java)); Toast.makeText(this, "Floating notes stopped", Toast.LENGTH_SHORT).show()
            }); body.addView(Ui.space(this, 16))
            val dock = CheckBox(this).apply {
                text = "Dock handle on the right"; isChecked = pref.getBoolean("dock_right", false); setTextColor(Ui.ink)
                setOnCheckedChangeListener { _, checked -> pref.edit().putBoolean("dock_right", checked).apply(); refreshOverlayAppearance() }
            }
            body.addView(dock); body.addView(Ui.space(this, 12))
            body.addView(Ui.text(this, "Peek transparency", 13f, true)); body.addView(Ui.space(this, 6))
            val seek = SeekBar(this).apply { max = 100; progress = pref.getInt("panel_opacity", 100).coerceIn(65, 100) }
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) { if (fromUser) pref.edit().putInt("panel_opacity", progress.coerceIn(65, 100)).apply() }
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) { refreshOverlayAppearance() }
            }); body.addView(seek); body.addView(Ui.space(this, 28))
            body.addView(Ui.text(this, "WI-FI SYNC WITH WINDOWS PC", 11f, true, Ui.muted)); body.addView(Ui.space(this, 12))
            val isPaired = LanSyncManager.isPaired(this)
            val pcName = LanSyncManager.getPairedPcName(this) ?: "Windows PC"
            if (isPaired) {
                val syncCard = Ui.surfaceCard(this, Ui.card)
                syncCard.addView(Ui.text(this, "${SyncHealth.label(this)} $pcName", 15f, true, Ui.ink))
                syncCard.addView(Ui.space(this, 4))
                syncCard.addView(Ui.text(this, SyncHealth.detail(this), 13f, false, Ui.muted))
                syncCard.addView(Ui.space(this, 14))
                val btnRow = Ui.row(this)
                val syncBtn = Ui.button(this, "Sync Now") {
                    Toast.makeText(this, "Syncing with $pcName...", Toast.LENGTH_SHORT).show()
                    LanSyncManager.triggerSync(this) { success, msg ->
                        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                        if (page == "Settings") showSettings() else navigate(page)
                    }
                }
                btnRow.addView(syncBtn, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = Ui.dp(this@MainActivity, 8) })
                val unpairBtn = Ui.outline(this, "Unpair") {
                    AlertDialog.Builder(this)
                        .setTitle("Unpair from $pcName?")
                        .setMessage("Notes will remain safe on both devices, but automatic Wi-Fi sync will stop.")
                        .setPositiveButton("Unpair") { _, _ ->
                            LanSyncManager.unpair(this)
                            Toast.makeText(this, "Unpaired from PC", Toast.LENGTH_SHORT).show()
                            showSettings()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
                btnRow.addView(unpairBtn, LinearLayout.LayoutParams(0, -2, 1f))
                syncCard.addView(btnRow)
                body.addView(syncCard)
            } else {
                body.addView(settingRow("Connect with Windows PC", "Secure QR pairing, saved PCs and device switching") {
                    showPairingDialog()
                })
            }
            body.addView(Ui.space(this, 28))

            body.addView(settingRow(if(BuildConfig.ANDROID_FREE_BETA)"Early access" else "SideNote Pro",ProAccess.status(this).message){startActivity(Intent(this,ProActivity::class.java))});body.addView(Ui.space(this,12))
            body.addView(settingRow("Manage connected PCs", "Pair, switch or forget a computer") {showPairingDialog()});body.addView(Ui.space(this,12))
            body.addView(settingRow("Trash & sync review", "Restore deleted notes and choose between conflicting versions") {startActivity(Intent(this,RecoveryActivity::class.java))});body.addView(Ui.space(this,12))
            body.addView(settingRow("Complete backup & restore", "Notes and attached files in a portable ZIP") {startActivity(Intent(this,BackupActivity::class.java))});body.addView(Ui.space(this,20))
            body.addView(Ui.text(this, "DATA & PRIVACY", 11f, true, Ui.muted)); body.addView(Ui.space(this, 12))
            body.addView(settingRow("Export my notes", "Save a JSON backup to a folder you choose") {
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply { type = "application/json"; addCategory(Intent.CATEGORY_OPENABLE); putExtra(Intent.EXTRA_TITLE, "sidenote-backup.json") }, backupExport)
            }); body.addView(Ui.space(this, 9))
            body.addView(settingRow("Import notes", "Safely merge an existing SideNote backup") {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "application/json"; addCategory(Intent.CATEGORY_OPENABLE) }, backupImport)
            }); body.addView(Ui.space(this, 28))
            body.addView(Ui.text(this, "HELP", 11f, true, Ui.muted)); body.addView(Ui.space(this, 12))
            body.addView(settingRow("How SideNote works", "Edge swipe, permissions and note gestures") { onboarding(0) })
            body.addView(Ui.space(this, 16))
            body.addView(Ui.text(this, "Offline by default. Android may restrict overlays on secure screens or while device optimizes battery.", 12f, false, Ui.muted))
        }
    }

    private fun updateConnectionLabels(){
        val name=LanSyncManager.getPairedPcName(this) ?: "Windows PC"
        val root=window.decorView
        root.findViewWithTag<TextView>("sync-state-tag")?.text=SyncHealth.label(this)+" "+name
        root.findViewWithTag<TextView>("sync-title")?.text=SyncHealth.label(this)+" - "+name
        root.findViewWithTag<TextView>("sync-detail")?.text=SyncHealth.detail(this)
    }

    private fun showPairingDialog() {startActivity(Intent(this,PairingActivity::class.java))}

    private fun reminderDialog(note: Note) {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
        }
        val choices = arrayOf("In 15 minutes", "In 1 hour", "Tomorrow (9:00 AM)", "Pick date & time…", "Clear reminder")
        AlertDialog.Builder(this).setTitle("Remind me about ${note.title.ifBlank { "this note" }}").setItems(choices) { _, index ->
            if (index == 3) {
                DateTimePickerHelper.pickDateTime(this, note.reminderAt) { chosenMillis ->
                    val updated = (store.all().firstOrNull {it.id==note.id} ?: note).copy(reminderAt = chosenMillis)
                    store.save(updated)
                    ReminderScheduler.schedule(this, updated)
                    renderNotes(page == "Archive")
                    val msg = "Reminder set for " + java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.getDefault()).format(java.util.Date(chosenMillis))
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                }
                return@setItems
            }
            val updatedReminderAt = when (index) {
                0 -> System.currentTimeMillis() + 15 * 60 * 1000L
                1 -> System.currentTimeMillis() + 60 * 60 * 1000L
                2 -> {
                    val cal = java.util.Calendar.getInstance().apply {
                        add(java.util.Calendar.DAY_OF_YEAR, 1)
                        set(java.util.Calendar.HOUR_OF_DAY, 9)
                        set(java.util.Calendar.MINUTE, 0)
                        set(java.util.Calendar.SECOND, 0)
                    }
                    cal.timeInMillis
                }
                else -> 0L
            }
            val updated = (store.all().firstOrNull {it.id==note.id} ?: note).copy(reminderAt = updatedReminderAt)
            try {
                store.save(updated)
                ReminderScheduler.schedule(this, updated)
                renderNotes(page == "Archive")
                val msg = if (updatedReminderAt > 0L) "Reminder set for " + java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.getDefault()).format(java.util.Date(updatedReminderAt)) else "Reminder cleared"
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Reminder could not be saved", Toast.LENGTH_LONG).show()
            }
        }.show()
    }

    private fun formatReminderBadge(reminderAt: Long): String {
        val now = System.currentTimeMillis()
        if (reminderAt <= now) return "⏰ Overdue"
        val calNow = java.util.Calendar.getInstance()
        val calRem = java.util.Calendar.getInstance().apply { timeInMillis = reminderAt }
        val timeFmt = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(java.util.Date(reminderAt))
        return if (calNow.get(java.util.Calendar.YEAR) == calRem.get(java.util.Calendar.YEAR) &&
            calNow.get(java.util.Calendar.DAY_OF_YEAR) == calRem.get(java.util.Calendar.DAY_OF_YEAR)) {
            "⏰ Today, $timeFmt"
        } else if (calNow.get(java.util.Calendar.YEAR) == calRem.get(java.util.Calendar.YEAR) &&
            calNow.get(java.util.Calendar.DAY_OF_YEAR) + 1 == calRem.get(java.util.Calendar.DAY_OF_YEAR)) {
            "⏰ Tomorrow, $timeFmt"
        } else {
            val dateFmt = java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.getDefault()).format(java.util.Date(reminderAt))
            "⏰ $dateFmt"
        }
    }

    private fun refreshOverlayAppearance() {
        if (Settings.canDrawOverlays(this)) runCatching {
            startService(Intent(this, OverlayService::class.java).apply { action = OverlayService.ACTION_REFRESH_APPEARANCE })
        }
    }

    private fun startSidebar(silent: Boolean = false) {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))); return
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        try {
            startForegroundService(Intent(this, OverlayService::class.java))
            if (!silent) Toast.makeText(this, "SideNote handle is active", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            if (!silent) Toast.makeText(this, "Could not start floating notes", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Uses Android document picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        if (requestCode == REQ_VOICE_NOTE) {
            val matches = data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
            val spoken = matches?.firstOrNull()?.trim().orEmpty()
            if (spoken.isNotBlank()) {
                val words = spoken.split(" ")
                val titleStr = if (words.size <= 5) spoken else words.take(5).joinToString(" ")
                val note = store.fresh().copy(title = titleStr, body = spoken)
                store.save(note)
                navigate(page)
                NoteEditor.show(this, store, note) { navigate(page) }
            }
            return
        }
        val uri = data?.data ?: return
        try {
            if (requestCode == backupExport) contentResolver.openOutputStream(uri)?.use { it.write(store.exportJson().toByteArray(Charsets.UTF_8)) }
            if (requestCode == backupImport) {
                val json = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: error("Empty backup")
                require(json.length <= 5_000_000) { "Backup too large" }
                store.importJson(json)
            }
            Toast.makeText(this, "Backup operation completed", Toast.LENGTH_SHORT).show(); navigate("Notes")
        } catch (e: Exception) { AlertDialog.Builder(this).setMessage("Backup failed: ${e.message}").setPositiveButton("OK", null).show() }
    }
}

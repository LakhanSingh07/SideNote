package com.sidedeck.notes

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.*
import java.io.FileInputStream
import java.util.concurrent.Executors

/** Note-scoped file shelf backed by the streaming attachment store. */
class AttachmentActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var noteId: String
    private lateinit var rows: LinearLayout
    private lateinit var status: TextView
    private lateinit var noteTitle: TextView
    private lateinit var summary: TextView
    private lateinit var connection: TextView
    private lateinit var addButton: TextView
    private var export: NoteAttachment? = null
    private var busy = false
    private var renderedState = ""
    private var query=""
    private var filter="All"
    private val selected=linkedSetOf<String>()
    private lateinit var tools:LinearLayout
    private lateinit var selectionLabel:TextView
    private var exportBatch:List<NoteAttachment> = emptyList()
    private val thumbnails=object:android.util.LruCache<String,android.graphics.Bitmap>(8*1024*1024){override fun sizeOf(key:String,value:android.graphics.Bitmap)=value.byteCount}

    private val tick = object : Runnable {
        override fun run() { if (!isFinishing) { if (!busy) render(); main.postDelayed(this, 1000) } }
    }
    private fun dp(n: Int) = Ui.dp(this, n)
    private fun size(bytes: Long) = android.text.format.Formatter.formatFileSize(this, bytes)
    private fun gap(width: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(width), 1) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); Ui.configure(this)
        noteId = intent.getStringExtra("id") ?: run { finish(); return }
        val root = Ui.column(this).apply { setBackgroundColor(Ui.surface); setPadding(dp(20), dp(8), dp(20), dp(12)) }
        root.setOnApplyWindowInsetsListener { view, insets ->
            val top: Int; val bottom: Int
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
                top = bars.top; bottom = bars.bottom
            } else {
                @Suppress("DEPRECATION") val legacyTop = insets.systemWindowInsetTop
                @Suppress("DEPRECATION") val legacyBottom = insets.systemWindowInsetBottom
                top = legacyTop; bottom = legacyBottom
            }
            view.setPadding(dp(20), dp(8) + top, dp(20), dp(12) + bottom); insets
        }
        val toolbar = Ui.row(this)
        toolbar.addView(iconButton(R.drawable.ic_arrow_back, "Back") { finish() }); toolbar.addView(gap(8))
        toolbar.addView(Ui.text(this, "Note files", 19f, true), LinearLayout.LayoutParams(0, -2, 1f))
        toolbar.addView(iconButton(R.drawable.ic_file_sync, "Retry sync") {
            if (!LanSyncManager.isPaired(this)) message("Pair a Windows PC in Settings to sync your files.")
            else { message("Checking for transfers…"); LanSyncManager.triggerSync(this) { _, text -> message(text); render() } }
        })
        root.addView(toolbar)
        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false; overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS }
        val content = Ui.column(this).apply { setPadding(0, dp(20), 0, dp(16)) }
        val hero = Ui.column(this).apply {
            setPadding(dp(22), dp(22), dp(22), dp(22))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, if (Ui.dark) intArrayOf(0xFF35294E.toInt(), 0xFF282735.toInt()) else intArrayOf(0xFFEDE2FF.toInt(), 0xFFF8F1FF.toInt())).apply { cornerRadius = dp(28).toFloat() }
        }
        val eyebrow = Ui.row(this)
        eyebrow.addView(Ui.icon(this, R.drawable.ic_file_folder, 22, Ui.violet), Ui.lp(this, 22, 22)); eyebrow.addView(gap(8))
        eyebrow.addView(Ui.text(this, "YOUR NOTE, WITH EVERYTHING", 10f, true, Ui.violet).apply { letterSpacing = .09f })
        hero.addView(eyebrow); hero.addView(Ui.space(this, 16))
        hero.addView(Ui.editorial(this, "Good things.\nKept together.", 30f)); hero.addView(Ui.space(this, 16))
        noteTitle = Ui.text(this, "", 13f, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        hero.addView(noteTitle); hero.addView(Ui.space(this, 7))
        connection = Ui.text(this, "", 12f, false, Ui.muted); hero.addView(connection)
        content.addView(hero); content.addView(Ui.space(this, 26))
        val section = Ui.row(this)
        section.addView(Ui.text(this, "In this note", 18f, true), LinearLayout.LayoutParams(0, -2, 1f))
        summary = Ui.text(this, "", 12f, false, Ui.muted); section.addView(summary)
        content.addView(section); content.addView(Ui.space(this, 14))
        tools=Ui.column(this)
        val search=EditText(this).apply {hint="Find a file in this note";setSingleLine();textSize=14f;setTextColor(Ui.ink);setHintTextColor(Ui.muted);background=Ui.bg(Ui.card,dp(14).toFloat(),Ui.border);setPadding(dp(14),dp(12),dp(14),dp(12));contentDescription="Search files"}
        search.addTextChangedListener(object:android.text.TextWatcher{override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){};override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){};override fun afterTextChanged(value:android.text.Editable?){query=value.toString();render()}})
        tools.addView(search);tools.addView(Ui.space(this,8))
        val filters=Ui.row(this)
        for(kind in listOf("All","Images","Documents","Media","Other"))filters.addView(Ui.chip(this,kind,kind==filter){filter=kind;for(i in 0 until filters.childCount){filters.getChildAt(i).isSelected=(filters.getChildAt(i) as? TextView)?.text==kind};render()})
        tools.addView(HorizontalScrollView(this).apply {isHorizontalScrollBarEnabled=false;addView(filters)})
        selectionLabel=Ui.text(this,"Select files for batch actions",12f,false,Ui.muted);tools.addView(selectionLabel)
        val bulk=Ui.row(this)
        bulk.addView(Ui.secondary(this,"Share selected"){shareSelected()},LinearLayout.LayoutParams(0,-2,1f));bulk.addView(gap(8))
        bulk.addView(Ui.secondary(this,"Save ZIP"){
            val files=selectedFiles();if(files.isNotEmpty()){exportBatch=files;startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {type="application/zip";addCategory(Intent.CATEGORY_OPENABLE);putExtra(Intent.EXTRA_TITLE,"SideNote-files.zip")},913)}
        },LinearLayout.LayoutParams(0,-2,1f));tools.addView(Ui.space(this,8));tools.addView(bulk);tools.addView(Ui.space(this,14));content.addView(tools)
        rows = Ui.column(this); content.addView(rows)
        scroll.addView(content); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        status = Ui.text(this, "", 12f, false, Ui.muted).apply {
            visibility = View.GONE; setPadding(dp(12), dp(10), dp(12), dp(10)); background = Ui.bg(Ui.subtle, dp(12).toFloat())
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        root.addView(status)
        addButton = Ui.button(this, "Add files") { if (!busy) pickFiles() }
        val addContainer = FrameLayout(this).apply {
            addView(addButton, FrameLayout.LayoutParams(-1, -1))
            addView(Ui.icon(this@AttachmentActivity, R.drawable.ic_add, 20, Ui.onAccent).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = dp(22) })
        }
        root.addView(addContainer, LinearLayout.LayoutParams(-1, dp(54)).apply { topMargin = dp(10) })
        root.addView(Ui.text(this, "Any file type · Your files stay with your note", 11f, false, Ui.muted).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(2))
        })
        setContentView(root)
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val lightBars = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (Ui.dark) 0 else lightBars, lightBars)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (Ui.dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        root.requestApplyInsets(); render(); main.post(tick)
        @Suppress("DEPRECATION") val incoming = intent.getParcelableArrayListExtra<Uri>("files")
        if (!incoming.isNullOrEmpty()) importFiles(incoming)
    }

    private fun iconButton(icon: Int, label: String, action: () -> Unit): View = FrameLayout(this).apply {
        layoutParams = Ui.lp(this@AttachmentActivity, 48, 48); background = Ui.bg(Ui.card, dp(16).toFloat(), Ui.border)
        addView(Ui.icon(this@AttachmentActivity, icon, 21, Ui.ink), FrameLayout.LayoutParams(dp(21), dp(21), Gravity.CENTER))
        contentDescription = label; isClickable = true; isFocusable = true; setOnClickListener { action() }
    }
    private fun message(text: String) { status.text = text; status.visibility = if (text.isBlank()) View.GONE else View.VISIBLE }
    private fun setBusy(value: Boolean) { busy = value; addButton.isEnabled = !value; addButton.alpha = if (value) .5f else 1f }
    private fun pickFiles() = startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE); putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }, 910)

    private fun render() {
        if (!::rows.isInitialized) return
        val note = NotesStore(this).all().firstOrNull { it.id == noteId } ?: run { finish(); return }
        val transfer = getSharedPreferences("sidenote_transfers", MODE_PRIVATE)
        val paired = LanSyncManager.isPaired(this); val peer = LanSyncManager.getPairedPcName(this)
        connection.text=if(paired) "${SyncHealth.label(this)} - ${peer ?: "Windows PC"}" else "Pair a PC to share these files"
        connection.isClickable=true;connection.isFocusable=true;connection.setOnClickListener {startActivity(Intent(this,PairingActivity::class.java))}
        selected.retainAll(note.attachments.map {it.id}.toSet());selectionLabel.text=if(selected.isEmpty())"Select files for batch actions" else "${selected.size} selected"
        tools.visibility=if(note.attachments.isEmpty())View.GONE else View.VISIBLE
        val state = query+filter+note.title + paired + peer + NoteAttachment.toArray(note.attachments).toString() + note.attachments.joinToString {
            "${it.sha256}:${AttachmentStore.available(this, it)}:${transfer.getString(it.sha256, "")}" }
        if (state == renderedState) return
        renderedState = state; noteTitle.text = note.title.ifBlank { "Untitled note" }
        summary.text = "${note.attachments.size} ${if (note.attachments.size == 1) "file" else "files"} · ${size(note.attachments.sumOf { it.size })}"
        rows.removeAllViews()
        if (note.attachments.isEmpty()) rows.addView(Ui.column(this).apply {
            gravity = Gravity.CENTER; setPadding(dp(20), dp(30), dp(20), dp(30)); background = Ui.bg(Ui.card, dp(24).toFloat(), Ui.border)
            val art = FrameLayout(this@AttachmentActivity).apply {
                background = Ui.bg(Ui.accentSoft, dp(24).toFloat()); rotation = -7f
                addView(Ui.icon(this@AttachmentActivity, R.drawable.ic_file_folder, 36, Ui.violet), FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER))
            }
            addView(art, Ui.lp(this@AttachmentActivity, 76, 76)); addView(Ui.space(this@AttachmentActivity, 20))
            addView(Ui.text(this@AttachmentActivity, "Make room for more.", 19f, true)); addView(Ui.space(this@AttachmentActivity, 9))
            addView(Ui.text(this@AttachmentActivity, "Attach a photo, PDF, video, ZIP or any other file.", 13f, false, Ui.muted).apply { gravity = Gravity.CENTER; setLineSpacing(dp(4).toFloat(), 1f) })
        }, Ui.lp(this))
        val visible=note.attachments.filter {it.name.contains(query,true)&&when(filter){"Images"->it.mimeType.startsWith("image/");"Documents"->it.mimeType=="application/pdf"||it.mimeType.startsWith("text/")||it.mimeType.contains("document");"Media"->it.mimeType.startsWith("audio/")||it.mimeType.startsWith("video/");"Other"->!it.mimeType.startsWith("image/")&&!it.mimeType.startsWith("audio/")&&!it.mimeType.startsWith("video/")&&it.mimeType!="application/pdf"&&!it.mimeType.startsWith("text/")&&!it.mimeType.contains("document");else->true}}
        if(visible.isEmpty()&&note.attachments.isNotEmpty())rows.addView(Ui.emptyState(this,"No matching files","Try another name or file type."))
        for (file in visible) {
            val ready = AttachmentStore.available(this, file); val detail = transfer.getString(file.sha256, null).orEmpty()
            val paused = detail.startsWith("Transfer paused")
            val percent = Regex("(\\d+)%").find(detail)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(0, 100)
            val both = detail == "Available on both devices"
            val box = Ui.surfaceCard(this).apply { background = Ui.bg(Ui.card, dp(22).toFloat(), Ui.border) }
            val heading = Ui.row(this).apply { gravity = Gravity.TOP }
            val extension = file.name.substringAfterLast('.', "FILE").uppercase().take(5)
            val family = when { file.mimeType.startsWith("image/") -> 4; file.mimeType.startsWith("video/") -> 5; file.mimeType.startsWith("audio/") -> 3; extension == "PDF" -> 2; else -> 1 }
            heading.addView(Ui.text(this, extension, 11f, true).apply {
                gravity = Gravity.CENTER; background = Ui.bg(Ui.colors[family], dp(16).toFloat()); letterSpacing = .02f
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, Ui.lp(this, 52, 58)); heading.addView(gap(12))
            val info = Ui.column(this)
            info.addView(Ui.text(this, file.name, 15f, true).apply { maxLines = 3; ellipsize = TextUtils.TruncateAt.END })
            info.addView(Ui.space(this, 6)); info.addView(Ui.text(this, "${size(file.size)} · ${fileKind(file)}", 12f, false, Ui.muted))
            heading.addView(info, LinearLayout.LayoutParams(0, -2, 1f)); heading.addView(gap(4))
            heading.addView(iconButton(R.drawable.ic_more, "More options for ${file.name}") { fileMenu(file, ready) })
            box.addView(heading)
            if(ready&&file.mimeType.startsWith("image/")&&file.mimeType!="image/svg+xml"){
                val preview=ImageView(this).apply{scaleType=ImageView.ScaleType.CENTER_CROP;adjustViewBounds=true;contentDescription="Preview of ${file.name}";tag=file.sha256;background=Ui.bg(Ui.subtle,dp(12).toFloat());clipToOutline=true}
                box.addView(Ui.space(this,12));box.addView(preview,LinearLayout.LayoutParams(-1,dp(126)));thumbnail(file,preview)
            }
            val choose=CheckBox(this).apply {text="Select ${file.name}";contentDescription="Select ${file.name}";setTextColor(Ui.muted);textSize=12f;maxLines=1;ellipsize=TextUtils.TruncateAt.END;isChecked=file.id in selected;minimumHeight=dp(48)
                setOnCheckedChangeListener {_,checked->if(checked)selected.add(file.id)else selected.remove(file.id);selectionLabel.text=if(selected.isEmpty())"Select files for batch actions" else "${selected.size} selected"}}
            box.addView(choose);box.addView(Ui.space(this, 4))
            val label = when {
                paused -> "Transfer paused · Tap retry above"; both -> "Available on both devices"
                percent != null -> detail.replace(" ? ", " · "); ready && paired -> "On this phone · Waiting to sync"
                ready -> "Available on this phone"; else -> "Waiting for your paired device"
            }
            box.addView(Ui.text(this, label, 12f, false, if (both || ready && !paired) Ui.positive else Ui.muted))
            if (percent != null && !both && !paused) {
                box.addView(Ui.space(this, 10))
                box.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 100; progress = percent; progressTintList = ColorStateList.valueOf(Ui.violet)
                    progressBackgroundTintList = ColorStateList.valueOf(Ui.accentSoft); contentDescription = "Transfer $percent percent"
                }, LinearLayout.LayoutParams(-1, dp(4)))
            }
            if (ready) {
                val actions = Ui.row(this)
                actions.addView(Ui.secondary(this, "Open") { open(file, false) }, LinearLayout.LayoutParams(0, -2, 1f)); actions.addView(gap(10))
                actions.addView(Ui.secondary(this, "Share") { open(file, true) }, LinearLayout.LayoutParams(0, -2, 1f))
                box.addView(Ui.space(this, 14)); box.addView(actions)
            }
            rows.addView(box, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        }
    }
    private fun selectedFiles():List<NoteAttachment>{
        val files=NotesStore(this).all().firstOrNull {it.id==noteId}?.attachments.orEmpty().filter {it.id in selected}
        if(files.isEmpty()){message("Select at least one file first.");return emptyList()}
        if(files.any {!AttachmentStore.available(this,it)}){message("Wait for the selected files to finish transferring.");return emptyList()};return files
    }
    private fun shareSelected(){
        val files=selectedFiles();if(files.isEmpty())return
        val uris=ArrayList(files.map {Uri.parse("content://$packageName.attachments/${it.sha256}")})
        val clip=android.content.ClipData.newRawUri(files.first().name,uris.first());uris.drop(1).forEach {clip.addItem(android.content.ClipData.Item(it))}
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND_MULTIPLE).apply {type="*/*";putParcelableArrayListExtra(Intent.EXTRA_STREAM,uris);clipData=clip;addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)},"Share selected files"))
    }
    private fun thumbnail(file:NoteAttachment,view:ImageView){
        thumbnails.get(file.sha256)?.let {view.setImageBitmap(it);return}
        worker.execute {
            val bitmap=runCatching{
                val path=AttachmentStore.path(this,file.sha256).absolutePath
                val bounds=android.graphics.BitmapFactory.Options().apply {inJustDecodeBounds=true};android.graphics.BitmapFactory.decodeFile(path,bounds)
                require(bounds.outWidth in 1..16000&&bounds.outHeight in 1..16000)
                var sample=1;while(bounds.outWidth/sample>768||bounds.outHeight/sample>768)sample*=2
                android.graphics.BitmapFactory.decodeFile(path,android.graphics.BitmapFactory.Options().apply {inSampleSize=sample})
            }.getOrNull()
            if(bitmap!=null){thumbnails.put(file.sha256,bitmap);main.post {if(!isDestroyed&&view.tag==file.sha256)view.setImageBitmap(bitmap)}}
        }
    }

    private fun fileKind(file: NoteAttachment): String = when {
        file.mimeType.startsWith("image/") -> "Image"; file.mimeType.startsWith("video/") -> "Video"; file.mimeType.startsWith("audio/") -> "Audio"
        file.mimeType == "application/pdf" -> "PDF document"; file.name.endsWith(".zip", true) -> "Archive"
        else -> "${file.name.substringAfterLast('.', "").uppercase().take(12).ifBlank { "Attached" }} file"
    }
    private fun fileMenu(file: NoteAttachment, ready: Boolean) {
        val options = if (ready) arrayOf("Save as", "Remove from note") else arrayOf("Remove from note")
        AlertDialog.Builder(this).setTitle(file.name).setItems(options) { _, index ->
            if (ready && index == 0) { export = file; startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply { type = file.mimeType; addCategory(Intent.CATEGORY_OPENABLE); putExtra(Intent.EXTRA_TITLE, file.name) }, 911) }
            else remove(file)
        }.setNegativeButton("Cancel", null).show()
    }
    private fun remove(file: NoteAttachment) {
        AlertDialog.Builder(this).setTitle("Remove from this note?").setMessage("${file.name}\n\nCopies saved outside SideNote will stay where they are.")
            .setNegativeButton("Keep file", null).setPositiveButton("Remove") { _, _ ->
                val store = NotesStore(this)
                synchronized(NOTE_STORE_LOCK) { store.all().firstOrNull { it.id == noteId }?.let { note -> store.save(note.copy(attachments = note.attachments.filterNot { it.id == file.id })) } }
                render()
            }.show()
    }
    private fun open(file: NoteAttachment, share: Boolean) {
        val uri = Uri.parse("content://$packageName.attachments/${file.sha256}")
        try { startActivity(Intent.createChooser(Intent(if (share) Intent.ACTION_SEND else Intent.ACTION_VIEW).apply {
            type = file.mimeType; addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (share) putExtra(Intent.EXTRA_STREAM, uri) else setDataAndType(uri, file.mimeType)
            clipData = android.content.ClipData.newRawUri(file.name, uri)
        }, if (share) "Share file" else "Open file")) }
        catch (_: Exception) { Toast.makeText(this, "No app can open this file. Use Save as instead.", Toast.LENGTH_LONG).show() }
    }
    private fun importFiles(uris: List<Uri>) {
        setBusy(true); message("Adding files…")
        worker.execute {
            var error: String? = null
            try { for ((index, uri) in uris.withIndex()) {
                var last = 0L
                val file = AttachmentStore.import(this, uri) { bytes -> val now = System.currentTimeMillis(); if (now - last > 250) { last = now; main.post { message("Adding ${index + 1} of ${uris.size} · ${size(bytes)}") } } }
                val store = NotesStore(this)
                synchronized(NOTE_STORE_LOCK) { val note = store.all().firstOrNull { it.id == noteId } ?: error("Note was deleted"); store.save(note.copy(attachments = note.attachments + file)) }
            } } catch (e: Exception) { error = e.message ?: "Could not add file" }
            main.post { setBusy(false); message(error ?: if (LanSyncManager.isPaired(this)) "Files added. Ready to sync with your PC." else "Files added to this note."); render() }
        }
    }
    @Deprecated("Uses platform activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data); if (resultCode != RESULT_OK || data == null) return
        if (requestCode == 910) {
            val uris = mutableListOf<Uri>(); data.clipData?.let { clip -> for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri) } ?: data.data?.let { uris.add(it) }; importFiles(uris)
        } else if(requestCode==913){
            val files=exportBatch.toList();val uri=data.data ?: return;setBusy(true);message("Saving selected files...")
            worker.execute {
                val result=runCatching {java.util.zip.ZipOutputStream(contentResolver.openOutputStream(uri) ?: error("Cannot save here")).use {zip->
                    files.forEachIndexed {index,file->val name=file.name.replace('/','_').replace('\\','_').take(200);zip.putNextEntry(java.util.zip.ZipEntry("${index+1}-$name"));AttachmentStore.path(this,file.sha256).inputStream().use {it.copyTo(zip,AttachmentStore.CHUNK_SIZE)};zip.closeEntry()}
                }}
                main.post {setBusy(false);message(if(result.isSuccess)"Selected files saved as ZIP." else result.exceptionOrNull()?.message ?: "Could not save files")}
            }
        } else if (requestCode == 911) {
            val file = export ?: return; val uri = data.data ?: return; setBusy(true); message("Saving file…")
            worker.execute {
                val result = runCatching { FileInputStream(AttachmentStore.path(this, file.sha256)).use { input -> (contentResolver.openOutputStream(uri) ?: error("Cannot save here")).use { output -> input.copyTo(output, AttachmentStore.CHUNK_SIZE) } } }
                main.post { setBusy(false); message(if (result.isSuccess) "File saved. You're all set." else result.exceptionOrNull()?.message ?: "Could not save") }
            }
        }
    }
    override fun onDestroy() { main.removeCallbacks(tick); thumbnails.evictAll();worker.shutdown(); super.onDestroy() }
}

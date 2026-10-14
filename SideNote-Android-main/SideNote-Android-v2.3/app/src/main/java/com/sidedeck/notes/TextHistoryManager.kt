package com.sidedeck.notes

/** Lightweight local undo/redo text history manager with bounded stack. */
class TextHistoryManager(
    private val onStateChanged: (canUndo: Boolean, canRedo: Boolean) -> Unit
) {
    private val undoStack = java.util.ArrayDeque<String>()
    private val redoStack = java.util.ArrayDeque<String>()
    private var isApplying = false
    private var lastSnapshot = ""

    fun init(initialText: String) {
        undoStack.clear()
        redoStack.clear()
        lastSnapshot = initialText
        onStateChanged(false, false)
    }

    fun onTextChanged(newText: String) {
        if (isApplying) return
        if (newText == lastSnapshot) return
        undoStack.push(lastSnapshot)
        if (undoStack.size > 50) undoStack.removeLast()
        redoStack.clear()
        lastSnapshot = newText
        onStateChanged(undoStack.isNotEmpty(), false)
    }

    fun undo(currentText: String): String? {
        if (undoStack.isEmpty()) return null
        isApplying = true
        return try {
            redoStack.push(currentText)
            val prev = undoStack.pop()
            lastSnapshot = prev
            onStateChanged(undoStack.isNotEmpty(), redoStack.isNotEmpty())
            prev
        } finally {
            isApplying = false
        }
    }

    fun redo(currentText: String): String? {
        if (redoStack.isEmpty()) return null
        isApplying = true
        return try {
            undoStack.push(currentText)
            val next = redoStack.pop()
            lastSnapshot = next
            onStateChanged(undoStack.isNotEmpty(), redoStack.isNotEmpty())
            next
        } finally {
            isApplying = false
        }
    }

    fun canUndo(): Boolean = undoStack.isNotEmpty()
    fun canRedo(): Boolean = redoStack.isNotEmpty()
}

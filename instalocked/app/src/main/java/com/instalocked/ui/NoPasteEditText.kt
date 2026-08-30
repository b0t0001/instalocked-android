package com.instalocked.ui

import android.content.Context
import android.text.InputType
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText

/**
 * Paste blocking needs three independent layers, because each one alone leaks.
 *
 *   1. onTextContextMenuItem  -> kills the long-press context menu
 *   2. action mode callbacks  -> kills the floating selection toolbar
 *   3. bulk-insert rejection  -> kills the Gboard clipboard chip and autofill,
 *                                which neither of the above ever sees
 *
 * Layer 3 lives in GateActivity's TextWatcher, since it needs to undo edits.
 */
class NoPasteEditText(context: Context) : EditText(context) {

    init {
        isLongClickable = false
        setTextIsSelectable(false)
        inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        setSingleLine(false)
        maxLines = 8

        val block = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode?, menu: Menu?) = false
            override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?) = false
            override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?) = false
            override fun onDestroyActionMode(mode: ActionMode?) {}
        }
        customSelectionActionModeCallback = block
        customInsertionActionModeCallback = block
    }

    override fun onTextContextMenuItem(id: Int): Boolean {
        // Swallow the whole clipboard family. Returning true means "handled",
        // so the platform does nothing.
        return when (id) {
            android.R.id.paste,
            android.R.id.pasteAsPlainText,
            android.R.id.copy,
            android.R.id.cut,
            android.R.id.shareText,
            android.R.id.selectAll,
            android.R.id.autofill -> true
            else -> super.onTextContextMenuItem(id)
        }
    }

    override fun isSuggestionsEnabled(): Boolean = false

    override fun performLongClick(): Boolean = true

    override fun performLongClick(x: Float, y: Float): Boolean = true
}

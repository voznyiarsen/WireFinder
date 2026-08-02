package com.wirefinder.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.wirefinder.R

/**
 * Shows a dialog with a selectable (copyable) log/error details view.
 */
object ErrorDialog {

    fun show(context: Context, title: String, details: String) {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_copyable_log, null)
        dialogView.findViewById<TextView>(R.id.log_details).text = details

        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(dialogView)
            .setPositiveButton(R.string.btn_copy) { _, _ ->
                copyToClipboard(context, title, details)
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    fun copyToClipboard(context: Context, label: String, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, R.string.toast_copied, Toast.LENGTH_SHORT).show()
    }
}

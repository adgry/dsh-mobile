package com.dshmobile.app.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

fun copyToClipboard(context: Context, text: String, label: String = "DSH Mobile") {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    manager.setPrimaryClip(ClipData.newPlainText(label, text))
}

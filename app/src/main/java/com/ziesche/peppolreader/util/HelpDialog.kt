package com.ziesche.peppolreader.util

import android.content.Context
import androidx.annotation.StringRes
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ziesche.peppolreader.R

/**
 * Shows a context-specific help dialog. Every screen and bottom sheet passes its own help text,
 * so the user always gets guidance for the area they are actually looking at. `getText` is used
 * so the inline `<b>/<i>` markup in the help strings is rendered.
 */
object HelpDialog {
    fun show(context: Context, @StringRes messageRes: Int) {
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.help_title)
            .setMessage(context.getText(messageRes))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}

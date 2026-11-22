package com.example.callbridge.ui.dialogs

import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import com.example.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * User-controlled dialogs for optional Battery Optimization explanation and Outgoing Dial testing.
 */
object BridgeActionDialogs {

    fun showBatteryOptimizationDialog(
        context: Context,
        onProceedToSettings: () -> Unit
    ) {
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.dialog_battery_title)
            .setMessage(R.string.dialog_battery_body)
            .setPositiveButton(R.string.dialog_open_settings) { dialog, _ ->
                onProceedToSettings()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.dialog_close, null)
            .show()
    }

    fun showDialTestDialog(
        context: Context,
        onPlaceCall: (String) -> Unit
    ) {
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_PHONE
            hint = context.getString(R.string.dialog_dial_hint)
        }
        val container = FrameLayout(context).apply {
            val pad = (20 * context.resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.dialog_dial_title)
            .setView(container)
            .setPositiveButton(R.string.dialog_dial_action) { dialog, _ ->
                val number = input.text?.toString()?.trim().orEmpty()
                if (number.isNotEmpty()) {
                    onPlaceCall(number)
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.dialog_close, null)
            .show()
    }
}

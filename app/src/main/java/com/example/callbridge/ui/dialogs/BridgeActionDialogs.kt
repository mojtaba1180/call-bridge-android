package com.example.callbridge.ui.dialogs

import android.content.Context
import android.text.InputType
import android.widget.LinearLayout
import com.example.R
import com.example.callbridge.device.SimSubscriptionInfo
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * Reusable Material 3 action dialogs for BridgeLink (Dial, SMS Handoff, Add Contact, SIM Picker, Battery).
 */
object BridgeActionDialogs {

    fun showDialDialog(context: Context, onDial: (String) -> Unit) {
        val padding = (20 * context.resources.displayMetrics.density).toInt()
        val inputLayout = TextInputLayout(context).apply {
            hint = context.getString(R.string.dialog_dial_hint)
            setPadding(padding, padding / 2, padding, 0)
        }
        val editText = TextInputEditText(inputLayout.context).apply {
            inputType = InputType.TYPE_CLASS_PHONE
        }
        inputLayout.addView(editText)

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.dialog_dial_title)
            .setView(inputLayout)
            .setPositiveButton(R.string.action_dial) { _, _ ->
                val number = editText.text?.toString()?.trim().orEmpty()
                if (number.isNotEmpty()) onDial(number)
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    fun showSmsHandoffDialog(context: Context, onSubmit: (destination: String, body: String) -> Unit) {
        val density = context.resources.displayMetrics.density
        val padH = (20 * density).toInt()
        val padV = (8 * density).toInt()

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padH, padV, padH, 0)
        }

        val destLayout = TextInputLayout(context).apply {
            hint = context.getString(R.string.hint_sms_destination)
        }
        val destEdit = TextInputEditText(destLayout.context).apply {
            inputType = InputType.TYPE_CLASS_PHONE
        }
        destLayout.addView(destEdit)

        val bodyLayout = TextInputLayout(context).apply {
            hint = context.getString(R.string.hint_sms_body)
            setPadding(0, padV, 0, 0)
        }
        val bodyEdit = TextInputEditText(bodyLayout.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
        }
        bodyLayout.addView(bodyEdit)

        container.addView(destLayout)
        container.addView(bodyLayout)

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.dialog_sms_title)
            .setView(container)
            .setPositiveButton(R.string.action_send_handoff) { _, _ ->
                val dest = destEdit.text?.toString()?.trim().orEmpty()
                val body = bodyEdit.text?.toString()?.trim().orEmpty()
                if (dest.isNotEmpty() && body.isNotEmpty()) {
                    onSubmit(dest, body)
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    fun showAddContactDialog(context: Context, onCreate: (displayName: String, phone: String) -> Unit) {
        val density = context.resources.displayMetrics.density
        val padH = (20 * density).toInt()
        val padV = (8 * density).toInt()

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padH, padV, padH, 0)
        }

        val nameLayout = TextInputLayout(context).apply {
            hint = context.getString(R.string.hint_contact_name)
        }
        val nameEdit = TextInputEditText(nameLayout.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PERSON_NAME
        }
        nameLayout.addView(nameEdit)

        val phoneLayout = TextInputLayout(context).apply {
            hint = context.getString(R.string.hint_contact_phone)
            setPadding(0, padV, 0, 0)
        }
        val phoneEdit = TextInputEditText(phoneLayout.context).apply {
            inputType = InputType.TYPE_CLASS_PHONE
        }
        phoneLayout.addView(phoneEdit)

        container.addView(nameLayout)
        container.addView(phoneLayout)

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.dialog_contact_title)
            .setView(container)
            .setPositiveButton(R.string.action_save_contact) { _, _ ->
                val name = nameEdit.text?.toString()?.trim().orEmpty()
                val phone = phoneEdit.text?.toString()?.trim().orEmpty()
                if (name.isNotEmpty()) onCreate(name, phone)
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    fun showSimPickerDialog(
        context: Context,
        subscriptions: List<SimSubscriptionInfo>,
        currentSelectedId: Int?,
        onSelect: (Int) -> Unit
    ) {
        if (subscriptions.isEmpty()) return
        val labels = subscriptions.map { "${it.displayName} (${it.carrierName}) - SIM ${it.simSlotIndex + 1}" }.toTypedArray()
        val checked = subscriptions.indexOfFirst { it.subscriptionId == currentSelectedId }.coerceAtLeast(0)
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.action_select_sim)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                subscriptions.getOrNull(which)?.let { onSelect(it.subscriptionId) }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    fun showBatteryOptimizationDialog(context: Context, onConfirm: () -> Unit) {
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.dialog_battery_title)
            .setMessage(R.string.dialog_battery_body)
            .setPositiveButton(R.string.dialog_confirm) { _, _ -> onConfirm() }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }
}

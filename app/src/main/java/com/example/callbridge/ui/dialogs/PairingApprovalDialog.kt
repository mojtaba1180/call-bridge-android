package com.example.callbridge.ui.dialogs

import android.content.Context
import androidx.appcompat.app.AlertDialog
import com.example.R
import com.example.callbridge.security.PairedDevice
import com.example.callbridge.security.PendingPairingRequest
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Dialogs supporting explicit Android user approval for iPhone pairing and PIN display.
 */
object PairingApprovalDialog {

    fun showPairingWindowDialog(
        context: Context,
        pinCode: String,
        pairedDevice: PairedDevice?,
        onUnpair: () -> Unit,
        onDismiss: () -> Unit
    ): AlertDialog {
        val currentName = pairedDevice?.deviceName ?: context.getString(R.string.status_none)
        val builder = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.dialog_pairing_window_title)
            .setMessage(context.getString(R.string.dialog_pairing_window_body, pinCode, currentName))
            .setPositiveButton(R.string.dialog_close) { dialog, _ ->
                dialog.dismiss()
                onDismiss()
            }

        if (pairedDevice != null) {
            builder.setNegativeButton(R.string.dialog_unpair) { dialog, _ ->
                onUnpair()
                dialog.dismiss()
            }
        }
        return builder.show()
    }

    fun showExplicitApprovalDialog(
        context: Context,
        request: PendingPairingRequest,
        onApprove: () -> Unit,
        onReject: () -> Unit
    ): AlertDialog {
        return MaterialAlertDialogBuilder(context)
            .setTitle(R.string.dialog_pairing_approval_title)
            .setMessage(
                context.getString(
                    R.string.dialog_pairing_approval_body,
                    request.deviceName,
                    request.pinCode
                )
            )
            .setCancelable(false)
            .setPositiveButton(R.string.dialog_approve) { dialog, _ ->
                onApprove()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.dialog_reject) { dialog, _ ->
                onReject()
                dialog.dismiss()
            }
            .show()
    }
}

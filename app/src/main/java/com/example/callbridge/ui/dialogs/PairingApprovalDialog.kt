package com.example.callbridge.ui.dialogs

import android.content.Context
import com.example.R
import com.example.callbridge.security.PendingPairingRequest
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Explicit user confirmation dialog required before BridgeLink trusts any remote iPhone device.
 */
object PairingApprovalDialog {

    fun show(
        context: Context,
        request: PendingPairingRequest,
        onApprove: () -> Unit,
        onReject: () -> Unit
    ) {
        val message = context.getString(
            R.string.dialog_pairing_approval_body,
            request.deviceName,
            request.pinCode
        )
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.dialog_pairing_approval_title)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton(R.string.dialog_approve) { _, _ -> onApprove() }
            .setNegativeButton(R.string.dialog_reject) { _, _ -> onReject() }
            .show()
    }
}

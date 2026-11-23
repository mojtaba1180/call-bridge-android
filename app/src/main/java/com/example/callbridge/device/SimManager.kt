package com.example.callbridge.device

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import com.example.callbridge.core.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SimSubscriptionInfo(
    val subscriptionId: Int,
    val simSlotIndex: Int,
    val displayName: String,
    val carrierName: String
)

/**
 * Queries active SIM card subscriptions (API 22+) when READ_PHONE_STATE is granted,
 * enabling explicit SIM selection on dual-SIM devices without silently picking an arbitrary SIM.
 */
class SimManager(context: Context) {

    private val appContext = context.applicationContext

    private val _subscriptions = MutableStateFlow<List<SimSubscriptionInfo>>(emptyList())
    val subscriptions: StateFlow<List<SimSubscriptionInfo>> = _subscriptions.asStateFlow()

    private val _selectedSubscriptionId = MutableStateFlow<Int?>(null)
    val selectedSubscriptionId: StateFlow<Int?> = _selectedSubscriptionId.asStateFlow()

    @SuppressLint("MissingPermission")
    fun refresh(): List<SimSubscriptionInfo> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) {
            _subscriptions.value = emptyList()
            return emptyList()
        }

        val hasPhonePerm = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPhonePerm) {
            _subscriptions.value = emptyList()
            return emptyList()
        }

        val list = try {
            val sm = appContext.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
            sm?.activeSubscriptionInfoList.orEmpty().map { info ->
                SimSubscriptionInfo(
                    subscriptionId = info.subscriptionId,
                    simSlotIndex = info.simSlotIndex,
                    displayName = info.displayName?.toString().orEmpty().ifBlank { "SIM ${info.simSlotIndex + 1}" },
                    carrierName = info.carrierName?.toString().orEmpty().ifBlank { "Carrier" }
                )
            }
        } catch (se: SecurityException) {
            Logger.w("SIM", "Unable to read active SIM subscriptions: ${se.message}")
            emptyList()
        } catch (t: Throwable) {
            Logger.w("SIM", "Error querying SubscriptionManager: ${t.message}")
            emptyList()
        }

        _subscriptions.value = list
        if (list.size == 1 && _selectedSubscriptionId.value == null) {
            _selectedSubscriptionId.value = list.first().subscriptionId
        } else if (list.none { it.subscriptionId == _selectedSubscriptionId.value }) {
            _selectedSubscriptionId.value = if (list.size == 1) list.first().subscriptionId else null
        }
        return list
    }

    fun selectSubscription(subscriptionId: Int?) {
        _selectedSubscriptionId.value = subscriptionId
        Logger.i("SIM", "Active SIM selection updated (subId=${subscriptionId ?: "explicit-required"})")
    }

    /**
     * Returns true when multiple SIMs exist and the user has NOT explicitly selected one.
     */
    fun requiresExplicitSimSelection(requestedSubId: Int?): Boolean {
        val active = _subscriptions.value
        if (active.size <= 1) return false
        val effective = requestedSubId ?: _selectedSubscriptionId.value
        return effective == null || active.none { it.subscriptionId == effective }
    }
}

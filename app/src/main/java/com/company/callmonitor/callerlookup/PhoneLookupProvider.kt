package com.company.callmonitor.callerlookup

data class PhoneLookupOutcome(
    val phoneNumber: String,
    val status: String,
    val callerName: String? = null,
    val metadataJson: String? = null,
    val kycJson: String? = null,
    val rawResponseJson: String? = null,
    val durationMs: Long = 0,
    val error: String? = null,
    val providerId: String,
    val providerLabel: String
)

/**
 * Device-side lookup contract. Providers may use Android content providers,
 * licensed Android SDKs, carrier integrations, or future on-device mechanisms.
 */
interface PhoneLookupProvider {
    val id: String
    val label: String
    fun lookup(phoneNumber: String): PhoneLookupOutcome
}


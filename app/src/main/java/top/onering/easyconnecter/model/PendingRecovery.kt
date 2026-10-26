package top.onering.easyconnecter.model

// Retain identity and retry progress across process death without copying passwords.
data class PendingRecovery(
    val campus: Campus,
    val studentId: String,
    val carrier: Carrier,
    val loginAttempts: Int = 1,
    val exhaustedCarriers: Set<Carrier> = emptySet()
)

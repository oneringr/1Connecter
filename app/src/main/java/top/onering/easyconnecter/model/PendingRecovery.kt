package top.onering.easyconnecter.model

// Retain the session identity across process death without copying its password.
data class PendingRecovery(val campus: Campus, val studentId: String, val carrier: Carrier)

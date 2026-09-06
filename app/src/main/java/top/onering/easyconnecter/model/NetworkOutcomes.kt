package top.onering.easyconnecter.model

sealed interface LoginOutcome {
    val message: String

    data class Success(
        val carrier: Carrier,
        override val message: String = "登录成功"
    ) : LoginOutcome

    data class InvalidCredentials(override val message: String) : LoginOutcome
    data class OperatorNotBound(override val message: String) : LoginOutcome

    data class RateLimited(
        val waitSeconds: Int,
        override val message: String
    ) : LoginOutcome

    data class ProtocolError(override val message: String) : LoginOutcome
    data class NetworkError(override val message: String) : LoginOutcome
}

data class SessionStatus(
    val loggedIn: Boolean,
    val account: String?,
    val mac: String?,
    val ipv4: String?
)

data class LogoutOutcome(
    val sessionLoggedOut: Boolean,
    val unbindAttempted: Boolean,
    val unbindSucceeded: Boolean?,
    val logoutRequestSucceeded: Boolean,
    val message: String,
    val warnings: List<String> = emptyList()
)

sealed interface ConnectionOutcome {
    data class Connected(
        val account: Account?,
        val carrier: Carrier?,
        val alreadyConnected: Boolean
    ) : ConnectionOutcome

    data class AccountRemoved(val account: Account) : ConnectionOutcome
    data class Failed(val message: String) : ConnectionOutcome
    data object NoAccounts : ConnectionOutcome
}

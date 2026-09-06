package top.onering.easyconnecter.model

enum class Carrier(
    val r3: Int,
    val displayName: String
) {
    MOBILE(1, "移动"),
    TELECOM(2, "电信");

    fun alternate(): Carrier = if (this == MOBILE) TELECOM else MOBILE
}

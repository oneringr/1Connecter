package top.onering.easyconnecter.model

data class Account(
    val studentId: String,
    val password: String,
    val name: String,
    val className: String,
    val phone: String,
    val idCard: String
)
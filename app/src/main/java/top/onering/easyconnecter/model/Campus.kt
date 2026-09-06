package top.onering.easyconnecter.model

enum class Campus(
    val displayName: String,
    val host: String
) {
    CHENGBEI("城北", "172.17.254.6"),
    ZHONGSHAN("忠山", "192.168.2.27");

    val loginUrl: String
        get() = "http://$host/drcom/login"

    companion object {
        fun fromStored(value: String?): Campus =
            entries.firstOrNull { campus ->
                value == campus.name || value?.contains(campus.host) == true
            } ?: CHENGBEI
    }
}

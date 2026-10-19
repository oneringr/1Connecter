package top.onering.easyconnecter.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import top.onering.easyconnecter.BuildConfig
import top.onering.easyconnecter.data.ReleaseCheckResult
import top.onering.easyconnecter.model.Account
import top.onering.easyconnecter.model.Campus
import top.onering.easyconnecter.ui.theme.LocalConnectorPalette
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text

@Composable
private fun AppDialog(
    title: String,
    onDismiss: () -> Unit,
    onUserInteraction: () -> Unit,
    dismissible: Boolean = true,
    fullWidthContent: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = { if (dismissible) { onUserInteraction(); onDismiss() } },
        properties = DialogProperties(
            dismissOnBackPress = dismissible,
            dismissOnClickOutside = dismissible,
            usePlatformDefaultWidth = false,
        ),
    ) {
        BoxWithConstraints(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp)
                .observeUserTouches(onUserInteraction),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.widthIn(max = 440.dp).fillMaxWidth().heightIn(max = maxHeight)
                    .flatSurface().verticalScroll(rememberScrollState())
                    .then(if (fullWidthContent) Modifier else Modifier.padding(16.dp)),
                verticalArrangement = Arrangement.spacedBy(if (fullWidthContent) 0.dp else 12.dp),
            ) {
                Text(title, color = LocalConnectorPalette.current.ink,
                    fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 30.sp,
                    modifier = Modifier
                        .then(if (fullWidthContent) Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp) else Modifier)
                        .semantics { heading() })
                content()
            }
        }
    }
}

@Composable
private fun DialogButton(
    label: String,
    primary: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        colors = if (primary) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors(),
    ) { Text(label) }
}

@Composable
fun AccountManagerDialog(
    accounts: List<Account>,
    currentIndex: Int,
    onDelete: (Int) -> Unit,
    onDismiss: () -> Unit,
    enabled: Boolean = true,
    onUserInteraction: () -> Unit = {},
) {
    AppDialog("账号管理 (${accounts.size})", onDismiss, onUserInteraction) {
        SupportingText("“下次尝试”仅表示候选顺序，不代表当前在线账号。")
        accounts.forEachIndexed { index, account ->
            SectionDivider()
            Text(account.name.ifBlank { "未命名账号" }, color = LocalConnectorPalette.current.ink)
            SupportingText(account.studentId)
            if (index == currentIndex) SupportingText("下次尝试")
            Button(
                onClick = { onUserInteraction(); onDelete(index) },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().testTag("account_delete_$index"),
            ) { Text("删除账号") }
        }
        DialogButton("关闭", onClick = onDismiss)
    }
}

@Composable
fun CampusChangeDialog(
    campus: Campus,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    enabled: Boolean = true,
    onUserInteraction: () -> Unit = {},
) {
    val target = if (campus == Campus.CHENGBEI) Campus.ZHONGSHAN else Campus.CHENGBEI
    AppDialog("切换校区", onDismiss, onUserInteraction) {
        SupportingText("将切换到${target.displayName}校区，并重启当前连接流程。")
        DialogButton("切换", primary = true, enabled = enabled, onClick = onConfirm)
        DialogButton("取消", onClick = onDismiss)
    }
}

@Composable
fun LogoutDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    enabled: Boolean = true,
    onUserInteraction: () -> Unit = {},
) {
    AppDialog("注销校园网", onDismiss, onUserInteraction) {
        SupportingText("注销期间会暂停当前连接服务，但保留服务模式开关设置。是否继续？")
        DialogButton("解绑并注销", primary = true, enabled = enabled, onClick = onConfirm)
        DialogButton("取消", onClick = onDismiss)
    }
}

@Composable
fun RestartAfterLogoutDialog(
    message: String,
    onRestart: () -> Unit,
    onDismiss: () -> Unit,
    enabled: Boolean = true,
    onUserInteraction: () -> Unit = {},
) {
    AppDialog("校园网已注销", onDismiss, onUserInteraction) {
        SupportingText(message)
        SupportingText("是否立即重启软件？暂不重启时，连接服务将保持暂停，服务模式开关状态不会改变。")
        DialogButton("立即重启", primary = true, enabled = enabled, onClick = onRestart)
        DialogButton("暂不重启", onClick = onDismiss)
    }
}

@Composable
fun AboutDialog(
    onDismiss: () -> Unit,
    onUserInteraction: () -> Unit = {},
    isCheckingUpdate: Boolean,
    updateResult: ReleaseCheckResult?,
    onOpenProject: () -> Unit,
    onCheckForUpdate: () -> Unit,
    onOpenRelease: (String) -> Unit,
) {
    AppDialog("关于", onDismiss, onUserInteraction, fullWidthContent = true) {
        PanelContent {
            Text("1Connecter（医连接）", color = LocalConnectorPalette.current.ink, fontWeight = FontWeight.SemiBold)
            SupportingText("SWMU 校园网自动连接工具")
            SupportingText("V${BuildConfig.VERSION_NAME}")
        }
        SectionDivider(Modifier.padding(horizontal = 16.dp))
        ActionRow("项目主页", "GitHub · oneringr/1Connecter", tag = "project_link", onClick = onOpenProject)
        val available = updateResult as? ReleaseCheckResult.Available
        ActionRow(
            title = if (available != null) "有新版本，点击跳转 Release" else "检查更新",
            summary = when {
                isCheckingUpdate -> "正在检查新版本…"
                available != null -> "最新版本：${available.version}"
                updateResult is ReleaseCheckResult.UpToDate -> "当前已是最新版本，点击重新检查"
                updateResult is ReleaseCheckResult.Failure -> "${updateResult.message.trimEnd('。')} 点击重试"
                else -> "点击检查 GitHub 上的最新正式版本"
            },
            enabled = !isCheckingUpdate,
            tag = "check_update",
            onClick = { if (available != null) onOpenRelease(available.url) else onCheckForUpdate() },
        )
        SectionDivider(Modifier.padding(horizontal = 16.dp))
        PanelContent {
            SupportingText("本工具仅提供自动连接服务，不对您的信息来源负责。")
            SupportingText("Made By: OneRingR\nSpecially Thanks: Larry for work")
            DialogButton("确定", primary = true, onClick = onDismiss)
        }
    }
}

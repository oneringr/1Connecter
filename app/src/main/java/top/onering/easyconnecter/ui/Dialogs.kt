package top.onering.easyconnecter.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import top.onering.easyconnecter.model.Account
import top.onering.easyconnecter.model.Campus

@Composable
fun AccountManagerDialog(
    accounts: List<Account>,
    currentIndex: Int,
    onDelete: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("账号管理 (${accounts.size})") },
        text = {
            Column(Modifier.height(400.dp)) {
                Text("下一个账号位置: $currentIndex")
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(accounts) { index, account ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (index == currentIndex) MaterialTheme.colorScheme.primaryContainer
                                    else Color.Transparent
                                )
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(account.name.ifBlank { "未命名" })
                                Text(account.studentId, style = MaterialTheme.typography.bodySmall)
                            }
                            IconButton(onClick = { onDelete(index) }) {
                                Icon(Icons.Default.Delete, contentDescription = "删除")
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@Composable
fun CampusChangeDialog(campus: Campus, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val target = if (campus == Campus.CHENGBEI) Campus.ZHONGSHAN else Campus.CHENGBEI
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("切换校区") },
        text = { Text("将切换到${target.displayName}校区，并重启当前连接流程。") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("切换") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
fun LogoutDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("注销校园网") },
        text = { Text("注销期间会暂停当前连接服务，但保留服务模式开关设置。是否继续？") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("解绑并注销") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
fun RestartAfterLogoutDialog(message: String, onRestart: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("校园网已注销") },
        text = {
            Text(
                "$message\n\n是否立即重启软件？暂不重启时，连接服务将保持暂停，" +
                    "服务模式开关状态不会改变。"
            )
        },
        confirmButton = { TextButton(onClick = onRestart) { Text("立即重启") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("暂不重启") } }
    )
}

@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("关于") },
        text = {
            Text(
                "1Connecter（医连接）\nSWMU 校园网自动连接工具\n\n" +
                    "注意：本工具仅提供自动连接服务，不对您的信息来源负责！\n\n" +
                    "V1.6\nMade By: OneRingR\nSpecially Thanks: Larry for work"
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("确定") } }
    )
}

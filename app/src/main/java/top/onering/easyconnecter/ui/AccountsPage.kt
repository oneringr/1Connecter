package top.onering.easyconnecter.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.onering.easyconnecter.MainUiState
import top.onering.easyconnecter.ui.theme.LocalConnectorPalette
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextFieldDefaults

@Composable
internal fun AccountsPage(
    state: MainUiState,
    listState: LazyListState,
    modifier: Modifier,
    contentPadding: PaddingValues,
    onImportCsv: () -> Unit,
    onReimportCsv: () -> Unit,
    onExportCsv: () -> Unit,
    onDeleteAccount: (Int) -> Unit,
    accountsExpanded: Boolean,
    onAccountsExpandedChange: (Boolean) -> Unit,
    accountQuery: String,
    onAccountQueryChange: (String) -> Unit,
) {
    val palette = LocalConnectorPalette.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val normalizedQuery = accountQuery.trim()
    // Filtering must keep source indices: currentIndex and deletion both address the repository list.
    val matchingAccounts = remember(state.accounts, normalizedQuery) {
        state.accounts.withIndex().filter { (_, account) ->
            normalizedQuery.isEmpty() ||
                account.name.contains(normalizedQuery, ignoreCase = true) ||
                account.studentId.contains(normalizedQuery, ignoreCase = true)
        }
    }

    // MainScreen supplies the list tag and reserves space for the keyboard and bottom navigation.
    LazyColumn(
        modifier = modifier,
        state = listState,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "accountActions") {
            ActionPanel {
                PanelContent {
                    Text("账号配置", color = palette.ink, fontWeight = FontWeight.SemiBold)
                    SupportingText(
                        if (state.accounts.isEmpty()) "尚未导入账号。导入 CSV 后会自动开始连接。"
                        else "共 ${state.accounts.size} 个账号。“下次尝试”表示候选顺序，不代表当前在线账号。",
                    )
                }
                ActionRow(
                    if (state.accounts.isEmpty()) "导入配置 (CSV)" else "重新导入配置",
                    if (state.accounts.isEmpty()) "选择 CSV 文件" else "用新的 CSV 替换当前账号配置",
                    enabled = !state.isBusy,
                    tag = if (state.accounts.isEmpty()) "import_csv" else "reimport_csv",
                    onClick = if (state.accounts.isEmpty()) onImportCsv else onReimportCsv,
                )
                SectionDivider(Modifier.padding(horizontal = 16.dp))
                ActionRow(
                    "导出配置 (CSV)", "保存现有账号配置", enabled = !state.isBusy,
                    tag = "export_csv", onClick = onExportCsv,
                )
            }
        }
        item(key = "accountsDisclosure") {
            ActionPanel {
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .testTag("accounts_expand")
                        .semantics { stateDescription = if (accountsExpanded) "已展开" else "已收起" }
                        .clickable(role = Role.Button) {
                            if (accountsExpanded) {
                                focusManager.clearFocus()
                                keyboardController?.hide()
                            }
                            onAccountsExpandedChange(!accountsExpanded)
                        }
                        .heightIn(min = 80.dp)
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("全部账号（${state.accounts.size}）", modifier = Modifier.weight(1f),
                        color = palette.ink, fontWeight = FontWeight.Medium)
                    Text(if (accountsExpanded) "收起" else "展开", color = palette.accentText, fontSize = 14.sp)
                }
            }
        }
        if (accountsExpanded) {
            item(key = "accountSearch") {
                Panel {
                    TextField(
                        value = accountQuery,
                        onValueChange = onAccountQueryChange,
                        modifier = Modifier.fillMaxWidth().testTag("account_search"),
                        label = "搜索姓名或学号",
                        useLabelAsPlaceholder = true,
                        singleLine = true,
                        cornerRadius = 12.dp,
                        colors = TextFieldDefaults.textFieldColors(
                            backgroundColor = palette.background,
                            labelColor = palette.muted,
                            borderColor = palette.accentText,
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            focusManager.clearFocus()
                            keyboardController?.hide()
                        }),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        SupportingText("显示 ${matchingAccounts.size} 个账号", Modifier.weight(1f))
                        if (accountQuery.isNotEmpty()) {
                            Button(
                                onClick = { onAccountQueryChange("") },
                                modifier = Modifier.testTag("clear_account_search"),
                            ) { Text("清空搜索") }
                        }
                    }
                }
            }
            if (matchingAccounts.isEmpty()) {
                item(key = "emptyAccountResults") {
                    Panel {
                        Text(if (state.accounts.isEmpty()) "暂无账号" else "没有匹配的账号",
                            color = palette.ink, fontWeight = FontWeight.SemiBold)
                        SupportingText(
                            if (state.accounts.isEmpty()) "导入 CSV 配置后，账号会显示在这里。"
                            else "试试其他姓名或学号，或清空搜索。",
                        )
                    }
                }
            } else {
                items(matchingAccounts, key = { "account_${it.index}" }) { indexedAccount ->
                    val originalIndex = indexedAccount.index
                    val account = indexedAccount.value
                    Panel {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(account.name.ifBlank { "未命名账号" }, color = palette.ink, fontWeight = FontWeight.SemiBold)
                                SupportingText(account.studentId)
                            }
                            if (originalIndex == state.currentIndex) {
                                Text("下次尝试", color = palette.muted, fontSize = 12.sp,
                                    modifier = Modifier.background(palette.background, CircleShape)
                                        .padding(horizontal = 10.dp, vertical = 6.dp))
                            }
                        }
                        if (account.className.isNotBlank()) SupportingText(account.className)
                        Button(
                            onClick = { onDeleteAccount(originalIndex) },
                            enabled = !state.isBusy,
                            modifier = Modifier.align(Alignment.End).testTag("account_delete_$originalIndex"),
                            colors = ButtonDefaults.buttonColors(contentColor = palette.danger),
                        ) { Text("删除账号") }
                    }
                }
            }
        }
    }
}

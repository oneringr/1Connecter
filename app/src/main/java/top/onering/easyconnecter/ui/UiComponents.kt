package top.onering.easyconnecter.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.onering.easyconnecter.ui.theme.LocalConnectorPalette
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text

/** Observe real input without consuming clicks, drags or child control gestures. */
@Composable
internal fun Modifier.observeUserTouches(onInteraction: () -> Unit): Modifier {
    val currentInteraction by rememberUpdatedState(onInteraction)
    return pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.any { it.pressed && !it.previousPressed }) currentInteraction()
            }
        }
    }
}

/** Opaque, unraised surface shared by cards and dialogs. */
@Composable
internal fun Modifier.flatSurface(radius: Dp = 18.dp): Modifier {
    val shape = RoundedCornerShape(radius)
    return clip(shape).background(LocalConnectorPalette.current.panel)
}

@Composable
internal fun Panel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth().flatSurface().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

/** Menu rows own their padding so both input and press feedback reach the card edges. */
@Composable
internal fun ActionPanel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth().flatSurface(), content = content)
}

@Composable
internal fun PanelContent(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(horizontal = 4.dp, vertical = 4.dp).semantics { heading() },
        color = LocalConnectorPalette.current.muted, fontSize = 14.sp, fontWeight = FontWeight.Medium)
}

@Composable
internal fun SupportingText(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, color = LocalConnectorPalette.current.muted, fontSize = 14.sp, lineHeight = 21.sp)
}

@Composable
internal fun SectionDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(LocalConnectorPalette.current.divider))
}

@Composable
internal fun ActionRow(
    title: String,
    summary: String? = null,
    enabled: Boolean = true,
    tag: String? = null,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val palette = LocalConnectorPalette.current
    Row(
        modifier = Modifier.fillMaxWidth()
            .then(if (tag != null) Modifier.testTag(tag) else Modifier)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.45f)
            .heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = if (destructive) palette.danger else palette.ink, fontWeight = FontWeight.Normal)
            if (summary != null) SupportingText(summary)
        }
        Text("›", color = palette.muted, fontSize = 26.sp)
    }
}

@Composable
internal fun ToggleRow(
    title: String,
    summary: String,
    checked: Boolean,
    enabled: Boolean = true,
    tag: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = LocalConnectorPalette.current.ink, fontWeight = FontWeight.Normal)
            SupportingText(summary)
        }
        Switch(
            checked, onCheckedChange,
            modifier = Modifier.testTag(tag).semantics { contentDescription = title },
            enabled = enabled,
        )
    }
}

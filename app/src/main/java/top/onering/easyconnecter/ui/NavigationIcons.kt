package top.onering.easyconnecter.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

internal object NavigationIcons {
    val Home = lineIcon("首页") {
        moveTo(3f, 10f); lineTo(12f, 3f); lineTo(21f, 10f)
        moveTo(5f, 9f); lineTo(5f, 21f); lineTo(10f, 21f); lineTo(10f, 14f)
        lineTo(14f, 14f); lineTo(14f, 21f); lineTo(19f, 21f); lineTo(19f, 9f)
    }
    val Accounts = lineIcon("账号") {
        moveTo(12f, 12f); curveTo(8f, 12f, 8f, 4f, 12f, 4f); curveTo(16f, 4f, 16f, 12f, 12f, 12f)
        moveTo(5f, 21f); lineTo(5f, 19f); curveTo(5f, 12f, 19f, 12f, 19f, 19f); lineTo(19f, 21f)
    }
    val Logs = lineIcon("日志") {
        moveTo(5f, 3f); lineTo(19f, 3f); lineTo(19f, 21f); lineTo(5f, 21f); close()
        moveTo(9f, 8f); lineTo(15f, 8f); moveTo(9f, 12f); lineTo(15f, 12f)
        moveTo(9f, 16f); lineTo(13f, 16f)
    }
    val Settings = lineIcon("设置") {
        moveTo(4f, 6f); lineTo(9f, 6f); moveTo(13f, 6f); lineTo(20f, 6f)
        moveTo(4f, 12f); lineTo(14f, 12f); moveTo(18f, 12f); lineTo(20f, 12f)
        moveTo(4f, 18f); lineTo(6f, 18f); moveTo(10f, 18f); lineTo(20f, 18f)
        moveTo(9f, 4f); lineTo(13f, 4f); lineTo(13f, 8f); lineTo(9f, 8f); close()
        moveTo(14f, 10f); lineTo(18f, 10f); lineTo(18f, 14f); lineTo(14f, 14f); close()
        moveTo(6f, 16f); lineTo(10f, 16f); lineTo(10f, 20f); lineTo(6f, 20f); close()
    }
}

private fun lineIcon(name: String, block: PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = block)
    }.build()

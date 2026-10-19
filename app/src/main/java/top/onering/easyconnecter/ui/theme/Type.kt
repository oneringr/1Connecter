package top.onering.easyconnecter.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.theme.defaultTextStyles

// Keep Chinese glyphs and accessibility font scaling consistent with Android.
val ConnectorTextStyles = defaultTextStyles(
    main = TextStyle(fontFamily = FontFamily.Default, fontSize = 16.sp, lineHeight = 24.sp),
    paragraph = TextStyle(fontFamily = FontFamily.Default, fontSize = 15.sp, lineHeight = 23.sp),
    body1 = TextStyle(fontFamily = FontFamily.Default, fontSize = 16.sp, lineHeight = 24.sp),
    body2 = TextStyle(fontFamily = FontFamily.Default, fontSize = 14.sp, lineHeight = 21.sp),
    button = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 16.sp),
)

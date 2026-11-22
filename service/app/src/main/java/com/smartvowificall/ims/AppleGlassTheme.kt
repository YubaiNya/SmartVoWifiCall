package com.smartvowificall.ims

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

@Immutable
data class ApplePalette(
    val dark: Boolean,
    val canvas: Color,
    val card: Color,
    val cardSubtle: Color,
    val cardBorder: Color,
    val divider: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val accentBlue: Color,
    val accentGreen: Color,
    val accentOrange: Color,
    val accentRed: Color,
    val accentPurple: Color,
    val glassRailSurface: Color,
    val glassRailBorder: Color,
    val glassCapsuleTint: Color,
    val glassCapsuleBorder: Color,
    val heroTopGlow: Color
)

private val LightApplePalette = ApplePalette(
    dark = false,
    canvas = Color(0xFFF5F5F7),
    card = Color(0xFFFFFFFF),
    cardSubtle = Color(0xFFF0F1F5),
    cardBorder = Color(0x0D000000),
    divider = Color(0x12000000),
    textPrimary = Color(0xFF1D1D1F),
    textSecondary = Color(0xFF6E6E73),
    textTertiary = Color(0xFF86868B),
    accentBlue = Color(0xFF0066CC),
    accentGreen = Color(0xFF2D8C3C),
    accentOrange = Color(0xFFE85D04),
    accentRed = Color(0xFFE30000),
    accentPurple = Color(0xFF7B2FBE),
    glassRailSurface = Color(0xB8FFFFFF),
    glassRailBorder = Color(0x80FFFFFF),
    glassCapsuleTint = Color(0x33FFFFFF),
    glassCapsuleBorder = Color(0xB8FFFFFF),
    heroTopGlow = Color(0x160066CC)
)

private val DarkApplePalette = ApplePalette(
    dark = true,
    canvas = Color(0xFF000000),
    card = Color(0xFF1C1C1E),
    cardSubtle = Color(0xFF2C2C2E),
    cardBorder = Color(0x1AFFFFFF),
    divider = Color(0x1FFFFFFF),
    textPrimary = Color(0xFFF5F5F7),
    textSecondary = Color(0xFF98989D),
    textTertiary = Color(0xFF6E6E73),
    accentBlue = Color(0xFF2997FF),
    accentGreen = Color(0xFF30D158),
    accentOrange = Color(0xFFFF9F0A),
    accentRed = Color(0xFFFF453A),
    accentPurple = Color(0xFFBF5AF2),
    glassRailSurface = Color(0x9E1E1E22),
    glassRailBorder = Color(0x38FFFFFF),
    glassCapsuleTint = Color(0x2BFFFFFF),
    glassCapsuleBorder = Color(0x52FFFFFF),
    heroTopGlow = Color(0x242997FF)
)

val LocalApplePalette = staticCompositionLocalOf { LightApplePalette }

@Composable
fun SmartVoWifiCallTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val palette = if (darkTheme) DarkApplePalette else LightApplePalette
    val miuixColors = if (darkTheme) {
        darkColorScheme(
            primary = palette.accentBlue,
            onPrimary = Color.White,
            background = palette.canvas,
            surface = palette.card,
            surfaceContainer = palette.card,
            onBackground = palette.textPrimary,
            onSurface = palette.textPrimary,
            onSurfaceVariantSummary = palette.textSecondary,
            dividerLine = palette.divider
        )
    } else {
        lightColorScheme(
            primary = palette.accentBlue,
            onPrimary = Color.White,
            background = palette.canvas,
            surface = palette.card,
            surfaceContainer = palette.card,
            onBackground = palette.textPrimary,
            onSurface = palette.textPrimary,
            onSurfaceVariantSummary = palette.textSecondary,
            dividerLine = palette.divider
        )
    }

    CompositionLocalProvider(LocalApplePalette provides palette) {
        MiuixTheme(colors = miuixColors) {
            content()
        }
    }
}

@Composable
fun PageHeader(
    title: String,
    subtitle: String,
    trailing: (@Composable () -> Unit)? = null
) {
    val p = LocalApplePalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = p.textPrimary,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.6.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = subtitle,
                color = p.textSecondary,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

@Composable
fun AppleSectionTitle(text: String) {
    val p = LocalApplePalette.current
    SmallTitle(
        text = text,
        textColor = p.textSecondary,
        insideMargin = PaddingValues(horizontal = 24.dp, vertical = 8.dp)
    )
}

@Composable
fun AppleMiuixCard(
    modifier: Modifier = Modifier,
    insideMargin: PaddingValues = PaddingValues(0.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val p = LocalApplePalette.current
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .border(0.75.dp, p.cardBorder, RoundedCornerShape(20.dp)),
        cornerRadius = 20.dp,
        insideMargin = insideMargin,
        colors = CardDefaults.defaultColors(
            color = p.card,
            contentColor = p.textPrimary
        ),
        content = content
    )
}

@Composable
fun AppleHeroCard(
    accent: Color,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val p = LocalApplePalette.current
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(shape)
            .background(p.card)
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        accent.copy(alpha = if (p.dark) 0.22f else 0.10f),
                        Color.Transparent
                    )
                )
            )
            .border(1.dp, accent.copy(alpha = if (p.dark) 0.28f else 0.16f), shape)
            .padding(20.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth(), content = content)
    }
}

@Composable
fun StatusPill(
    label: String,
    color: Color,
    showDot: Boolean = true,
    modifier: Modifier = Modifier
) {
    val p = LocalApplePalette.current
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(color.copy(alpha = if (p.dark) 0.22f else 0.12f))
            .border(0.5.dp, color.copy(alpha = 0.26f), CircleShape)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showDot) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = label,
            color = color,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun ApplePillButton(
    text: String,
    onClick: () -> Unit,
    primary: Boolean = true,
    danger: Boolean = false,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val p = LocalApplePalette.current
    val bg = when {
        !enabled -> p.cardSubtle
        danger -> p.accentRed.copy(alpha = if (p.dark) 0.22f else 0.12f)
        primary -> p.accentBlue
        else -> p.cardSubtle
    }
    val fg = when {
        !enabled -> p.textTertiary
        danger -> p.accentRed
        primary -> Color.White
        else -> p.textPrimary
    }
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(bg)
            .then(
                if (!primary && !danger) Modifier.border(0.5.dp, p.cardBorder, CircleShape)
                else Modifier
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = fg,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun MetricCell(
    label: String,
    value: String,
    valueColor: Color = LocalApplePalette.current.textPrimary,
    modifier: Modifier = Modifier
) {
    val p = LocalApplePalette.current
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(p.cardSubtle.copy(alpha = if (p.dark) 0.7f else 0.85f))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            text = label,
            color = p.textSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = value,
            color = valueColor,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            style = TextStyle(fontFeatureSettings = "tnum"),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "${bytes} B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(java.util.Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return String.format(java.util.Locale.US, "%.2f MB", mb)
    val gb = mb / 1024.0
    return String.format(java.util.Locale.US, "%.2f GB", gb)
}

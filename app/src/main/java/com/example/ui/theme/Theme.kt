package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
data class DuetChatColors(
    val isDark: Boolean,
    val chatBackground: Color,
    val topBarBackground: Color,
    val topBarContent: Color,
    val outgoingBubble: Color,
    val incomingBubble: Color,
    val outgoingText: Color,
    val incomingText: Color,
    val timestampText: Color,
    val composerBackground: Color,
    val composerSurface: Color,
    val accentEmerald: Color,
    val readTickBlue: Color,
    val unreadTickGrey: Color,
    val replyBarBg: Color
)

val LocalDuetChatColors = staticCompositionLocalOf {
    DuetChatColors(
        isDark = true,
        chatBackground = WaDarkBackground,
        topBarBackground = WaDarkSurface,
        topBarContent = WaDarkTextPrimary,
        outgoingBubble = WaDarkOutgoingBubble,
        incomingBubble = WaDarkIncomingBubble,
        outgoingText = WaDarkTextPrimary,
        incomingText = WaDarkTextPrimary,
        timestampText = WaDarkTextSecondary,
        composerBackground = WaDarkBackground,
        composerSurface = WaDarkSurface,
        accentEmerald = WaEmerald,
        readTickBlue = WaReadBlueTick,
        unreadTickGrey = WaDarkTextSecondary,
        replyBarBg = Color(0x33000000)
    )
}

private val DuetDarkColorScheme = darkColorScheme(
    primary = WaEmerald,
    onPrimary = Color.White,
    primaryContainer = WaDarkOutgoingBubble,
    onPrimaryContainer = WaDarkTextPrimary,
    secondary = WaBrightMint,
    onSecondary = Color(0xFF002116),
    background = WaDarkBackground,
    onBackground = WaDarkTextPrimary,
    surface = WaDarkSurface,
    onSurface = WaDarkTextPrimary,
    surfaceVariant = WaDarkIncomingBubble,
    onSurfaceVariant = WaDarkTextSecondary,
    outline = WaDarkDivider,
    error = WaCallRed
)

private val DuetLightColorScheme = lightColorScheme(
    primary = WaLightTopBar,
    onPrimary = Color.White,
    primaryContainer = WaLightOutgoingBubble,
    onPrimaryContainer = WaLightTextPrimary,
    secondary = WaEmerald,
    onSecondary = Color.White,
    background = WaLightBackground,
    onBackground = WaLightTextPrimary,
    surface = WaLightSurface,
    onSurface = WaLightTextPrimary,
    surfaceVariant = WaLightIncomingBubble,
    onSurfaceVariant = WaLightTextSecondary,
    outline = WaLightDivider,
    error = WaCallRed
)

@Composable
fun DuetTheme(
    darkTheme: Boolean = true,
    wallpaper: ChatWallpaper = ChatWallpaper.OBSIDIAN_BOTANICAL,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DuetDarkColorScheme else DuetLightColorScheme
    val chatColors = if (darkTheme) {
        DuetChatColors(
            isDark = true,
            chatBackground = wallpaper.darkTint,
            topBarBackground = WaDarkSurface,
            topBarContent = WaDarkTextPrimary,
            outgoingBubble = WaDarkOutgoingBubble,
            incomingBubble = WaDarkIncomingBubble,
            outgoingText = WaDarkTextPrimary,
            incomingText = WaDarkTextPrimary,
            timestampText = WaDarkTextSecondary,
            composerBackground = WaDarkBackground,
            composerSurface = WaDarkSurface,
            accentEmerald = WaEmerald,
            readTickBlue = WaReadBlueTick,
            unreadTickGrey = WaDarkTextSecondary,
            replyBarBg = Color(0x40000000)
        )
    } else {
        DuetChatColors(
            isDark = false,
            chatBackground = wallpaper.lightTint,
            topBarBackground = WaLightTopBar,
            topBarContent = Color.White,
            outgoingBubble = WaLightOutgoingBubble,
            incomingBubble = WaLightIncomingBubble,
            outgoingText = WaLightTextPrimary,
            incomingText = WaLightTextPrimary,
            timestampText = WaLightTextSecondary,
            composerBackground = wallpaper.lightTint,
            composerSurface = WaLightSurface,
            accentEmerald = WaLightTopBar,
            readTickBlue = WaReadBlueTick,
            unreadTickGrey = WaLightTextSecondary,
            replyBarBg = Color(0x14000000)
        )
    }

    CompositionLocalProvider(LocalDuetChatColors provides chatColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = DuetTypography,
            content = content
        )
    }
}

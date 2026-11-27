package com.example.ui.theme

import androidx.compose.ui.graphics.Color

// WhatsApp Authentic Dark Palette
val WaDarkBackground = Color(0xFF0B141A)
val WaDarkSurface = Color(0xFF1F2C34)
val WaDarkSurfaceElevated = Color(0xFF233138)
val WaDarkOutgoingBubble = Color(0xFF005C4B)
val WaDarkIncomingBubble = Color(0xFF202C33)
val WaDarkTextPrimary = Color(0xFFE9EDEF)
val WaDarkTextSecondary = Color(0xFF8696A0)
val WaDarkDivider = Color(0xFF222D34)

// WhatsApp Authentic Light Palette
val WaLightBackground = Color(0xFFEFEAE2)
val WaLightSurface = Color(0xFFFFFFFF)
val WaLightTopBar = Color(0xFF008069)
val WaLightOutgoingBubble = Color(0xFFD9FDD3)
val WaLightIncomingBubble = Color(0xFFFFFFFF)
val WaLightTextPrimary = Color(0xFF111B21)
val WaLightTextSecondary = Color(0xFF667781)
val WaLightDivider = Color(0xFFE9EDEF)

// Shared Accents
val WaEmerald = Color(0xFF00A884)
val WaBrightMint = Color(0xFF25D366)
val WaReadBlueTick = Color(0xFF53BDEB)
val WaCallRed = Color(0xFFEF4444)
val WaGoldAccent = Color(0xFFF5C451)

// Customizable Wallpaper Tints
enum class ChatWallpaper(val id: String, val label: String, val darkTint: Color, val lightTint: Color) {
    OBSIDIAN_BOTANICAL("botanical", "Botanical Dark", Color(0xFF0B141A), Color(0xFFEFEAE2)),
    MIDNIGHT_SLATE("slate", "Midnight Slate", Color(0xFF0F172A), Color(0xFFE2E8F0)),
    DEEP_EMERALD("emerald", "Deep Emerald", Color(0xFF071A16), Color(0xFFE0F2EE)),
    WARM_VELVET("velvet", "Warm Charcoal", Color(0xFF16131A), Color(0xFFF3ECE8)),
    SOLID_PURE("solid", "Minimal Pure", Color(0xFF080D11), Color(0xFFF6F5F0));

    companion object {
        fun fromId(id: String): ChatWallpaper =
            entries.firstOrNull { it.id == id } ?: OBSIDIAN_BOTANICAL
    }
}

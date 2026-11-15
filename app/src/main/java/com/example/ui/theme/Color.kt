package com.example.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// Abhinav AI — Premium Dark Navy, Electric Blue & Purple Palette
val NavyDeep = Color(0xFF050A18)
val NavySurface = Color(0xFF0B1226)
val NavyCard = Color(0xFF111B36)
val NavyCardElevated = Color(0xFF172448)
val NavyOutline = Color(0xFF23325C)

val ElectricCyan = Color(0xFF38BDF8)
val ElectricBlue = Color(0xFF3B82F6)
val IndigoGlow = Color(0xFF6366F1)
val VibrantPurple = Color(0xFF8B5CF6)
val SoftLavender = Color(0xFFC4B5FD)

val TextPrimary = Color(0xFFF8FAFC)
val TextSecondary = Color(0xFF94A3B8)
val TextMuted = Color(0xFF64748B)

val ErrorRed = Color(0xFFF87171)
val ErrorContainerDark = Color(0xFF3B1219)
val SuccessGreen = Color(0xFF34D399)

val AbhinavPrimaryGradient = Brush.linearGradient(
    colors = listOf(ElectricBlue, IndigoGlow, VibrantPurple)
)

val AbhinavAccentGradient = Brush.linearGradient(
    colors = listOf(ElectricCyan, ElectricBlue, VibrantPurple)
)

val AbhinavBorderGradient = Brush.linearGradient(
    colors = listOf(
        ElectricCyan.copy(alpha = 0.45f),
        ElectricBlue.copy(alpha = 0.35f),
        VibrantPurple.copy(alpha = 0.45f)
    )
)

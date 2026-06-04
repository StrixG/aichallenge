package me.obrekht.wishu.ui

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import me.obrekht.wishu.R

private fun nunito(weight: FontWeight) = Font(
    resId = R.font.nunito,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight))
)

private val Nunito = FontFamily(
    nunito(FontWeight.Light),
    nunito(FontWeight.Normal),
    nunito(FontWeight.Medium),
    nunito(FontWeight.SemiBold),
    nunito(FontWeight.Bold),
)

// Apply the rounded Nunito family to every text style of the given base typography.
fun roundedTypography(base: Typography) = base.copy(
    displayLarge = base.displayLarge.copy(fontFamily = Nunito),
    displayMedium = base.displayMedium.copy(fontFamily = Nunito),
    displaySmall = base.displaySmall.copy(fontFamily = Nunito),
    headlineLarge = base.headlineLarge.copy(fontFamily = Nunito),
    headlineMedium = base.headlineMedium.copy(fontFamily = Nunito),
    headlineSmall = base.headlineSmall.copy(fontFamily = Nunito),
    titleLarge = base.titleLarge.copy(fontFamily = Nunito),
    titleMedium = base.titleMedium.copy(fontFamily = Nunito),
    titleSmall = base.titleSmall.copy(fontFamily = Nunito),
    bodyLarge = base.bodyLarge.copy(fontFamily = Nunito),
    bodyMedium = base.bodyMedium.copy(fontFamily = Nunito),
    bodySmall = base.bodySmall.copy(fontFamily = Nunito),
    labelLarge = base.labelLarge.copy(fontFamily = Nunito),
    labelMedium = base.labelMedium.copy(fontFamily = Nunito),
    labelSmall = base.labelSmall.copy(fontFamily = Nunito),
)

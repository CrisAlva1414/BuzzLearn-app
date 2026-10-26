package com.buzzness.buzzlearn.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.colorResource
import com.buzzness.buzzlearn.R

@Composable
private fun lightColors() =
    lightColorScheme(
        primary = colorResource(R.color.brand_primary),
        onPrimary = colorResource(R.color.brand_on_primary),
        secondary = colorResource(R.color.brand_secondary),
        onSecondary = colorResource(R.color.brand_on_secondary),
        background = colorResource(R.color.brand_background),
        onBackground = colorResource(R.color.brand_on_background),
        surface = colorResource(R.color.brand_surface),
        onSurface = colorResource(R.color.brand_on_surface),
    )

@Composable
private fun darkColors() =
    darkColorScheme(
        primary = colorResource(R.color.brand_primary),
        onPrimary = colorResource(R.color.brand_on_primary),
        secondary = colorResource(R.color.brand_secondary),
        onSecondary = colorResource(R.color.brand_on_secondary),
        background = colorResource(R.color.brand_background),
        onBackground = colorResource(R.color.brand_on_background),
        surface = colorResource(R.color.brand_surface),
        onSurface = colorResource(R.color.brand_on_surface),
    )

@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) darkColors() else lightColors(),
        content = content,
    )
}

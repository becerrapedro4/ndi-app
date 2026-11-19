package com.ndiapp.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

private val NdiColors = darkColorScheme(
    primary = Color(0xFF3E9BFF),
    onPrimary = Color(0xFF02121F),
    primaryContainer = Color(0xFF0A3E86),
    onPrimaryContainer = Color(0xFFD6EAFF),
    secondary = Color(0xFF8CCBFF),
    background = Color(0xFF05070C),
    onBackground = Color(0xFFEAF2FC),
    surface = Color(0xFF0A1017),
    onSurface = Color(0xFFEAF2FC),
    surfaceVariant = Color(0xFF141C27),
    onSurfaceVariant = Color(0xFF9EB0C6)
)

/** Paleta compartida por toda la interfaz glass. */
object NdiGlass {
    val Blue = Color(0xFF3E9BFF)
    val BlueBright = Color(0xFF8CCBFF)
    val BlueDeep = Color(0xFF0A3E86)
    val Scrim = Color(0xFF05070C)
    val Text = Color(0xFFEAF2FC)
    val Muted = Color(0xFF9EB0C6)
}

/**
 * Superficie de vidrio: sombra azulada, relleno translúcido en degradado y borde
 * de luz. Sobre el visor de la cámara se usa con [tint] negro para no lavar la imagen.
 */
fun Modifier.glassSurface(
    shape: Shape = RoundedCornerShape(24.dp),
    tint: Color = Color.White,
    fillAlpha: Float = 0.13f,
    strokeAlpha: Float = 0.28f,
    elevation: Int = 16
): Modifier = this
    .shadow(
        elevation = elevation.dp,
        shape = shape,
        clip = false,
        ambientColor = NdiGlass.Blue.copy(alpha = 0.35f),
        spotColor = Color.Black.copy(alpha = 0.6f)
    )
    .clip(shape)
    .background(
        Brush.linearGradient(
            listOf(
                tint.copy(alpha = fillAlpha),
                tint.copy(alpha = fillAlpha * 0.3f)
            )
        )
    )
    .border(
        width = 1.dp,
        brush = Brush.linearGradient(
            listOf(
                Color.White.copy(alpha = strokeAlpha),
                Color.White.copy(alpha = strokeAlpha * 0.2f)
            )
        ),
        shape = shape
    )

/** Fondo de la app: degradado azul profundo sobre negro. */
@Composable
fun NdiBackdrop(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(
                listOf(
                    Color(0xFF0A1729),
                    NdiGlass.Scrim,
                    Color(0xFF04060A)
                )
            )
        )
    ) {
        content()
    }
}

@Composable
fun NdiAppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = NdiColors, content = content)
}

package com.local.pocketarchive.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke

@Composable
fun TechTrebuchetIcon(
    modifier: Modifier = Modifier,
    primaryColor: Color = Color(0xFFFF9800),
    accentColor: Color = Color(0xFF00E676)
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.08f

        // Base chassis rail
        drawLine(
            color = primaryColor,
            start = Offset(w * 0.12f, h * 0.85f),
            end = Offset(w * 0.88f, h * 0.85f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )

        // A-Frame main support struts
        drawLine(
            color = primaryColor,
            start = Offset(w * 0.25f, h * 0.85f),
            end = Offset(w * 0.48f, h * 0.35f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
        drawLine(
            color = primaryColor,
            start = Offset(w * 0.72f, h * 0.85f),
            end = Offset(w * 0.48f, h * 0.35f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )

        // Trebuchet throwing arm (angled for launch)
        drawLine(
            color = accentColor,
            start = Offset(w * 0.18f, h * 0.58f),
            end = Offset(w * 0.84f, h * 0.18f),
            strokeWidth = stroke * 0.9f,
            cap = StrokeCap.Round
        )

        // Heavy Pixel Counterweight (left end of arm)
        drawCircle(
            color = primaryColor,
            radius = w * 0.11f,
            center = Offset(w * 0.18f, h * 0.65f)
        )

        // Glowing Payload Projectile (top right)
        drawCircle(
            color = accentColor,
            radius = w * 0.08f,
            center = Offset(w * 0.84f, h * 0.18f)
        )
        drawCircle(
            color = primaryColor,
            radius = w * 0.09f,
            center = Offset(w * 0.84f, h * 0.35f),
            style = Stroke(width = stroke * 0.5f)
        )
    }
}

// Shape that renders a point along a Morph between two MaterialShapes polygons — used for the
// expressive play/pause button (rotating cookie while playing, rounded square when paused).
package dev.yuwixx.resonance.presentation.components

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.graphics.shapes.Morph

class MorphShape(
    private val morph: Morph,
    private val progress: Float,
    private val rotationDegrees: Float = 0f,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = Path()
        var first = true
        // MaterialShapes polygons are normalized to the unit square.
        morph.forEachCubic(progress) { cubic ->
            if (first) {
                path.moveTo(cubic.anchor0X, cubic.anchor0Y)
                first = false
            }
            path.cubicTo(cubic.control0X, cubic.control0Y, cubic.control1X, cubic.control1Y, cubic.anchor1X, cubic.anchor1Y)
        }
        path.close()
        val matrix = Matrix().apply {
            scale(size.width, size.height)
            if (rotationDegrees != 0f) {
                translate(0.5f, 0.5f)
                rotateZ(rotationDegrees)
                translate(-0.5f, -0.5f)
            }
        }
        path.transform(matrix)
        return Outline.Generic(path)
    }
}

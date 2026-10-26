/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.chartImage

import java.awt.BasicStroke
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.Shape
import java.awt.font.FontRenderContext
import java.awt.geom.AffineTransform
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.PathIterator
import java.awt.geom.Rectangle2D
import java.util.concurrent.ConcurrentHashMap

/** The horizontal alignment of a text at its position. */
enum class TextAnchor(val svg: String) {
    Start("start"),
    Middle("middle"),
    End("end"),
}

/**
 * Drawing operations of the charts, with the shapes of `java.awt.geom`. [SvgChartCanvas] writes them as SVG,
 * [Graphics2DChartCanvas] draws them for PNG images, so that both formats are drawn by the same code.
 * The coordinates are pixels of the chart, the origin is at the top left corner.
 */
interface ChartCanvas {
    fun fill(shape: Shape, color: ChartColor)

    /**
     * @param dash The lengths of dashes and gaps, `null` for a solid line. A dash of length 0 with [roundCap] is a dot.
     */
    fun stroke(shape: Shape, color: ChartColor, width: Double, dash: List<Double>? = null, roundCap: Boolean = false)

    /**
     * @param y The baseline of the text.
     * @param rotated If the text is rotated by -90°, e.g. the title of a y-axis. It's centered at the position then.
     */
    fun text(text: String, x: Double, y: Double, size: Double, color: ChartColor, anchor: TextAnchor = TextAnchor.Start, bold: Boolean = false, rotated: Boolean = false)

    /** Draws only within the rectangle while [block] runs. */
    fun clip(bounds: Rectangle2D, block: () -> Unit)
}

/**
 * The font of the charts and the width of texts, for the layout of both formats.
 * The PNG images use the logical font `SansSerif`, e.g. DejaVu Sans in the Docker image.
 * Without fonts, e.g. in a minimal JRE, the widths are estimated, the SVG is still rendered.
 */
object ChartFonts {
    /** The fonts of the SVG, the web interface uses Lato. */
    const val SVG_FAMILY = "Lato, 'Helvetica Neue', Arial, 'DejaVu Sans', sans-serif"

    private val renderContext = FontRenderContext(null, true, true)
    private val fonts = ConcurrentHashMap<Pair<Double, Boolean>, Font>()

    @Volatile
    private var fontsUnavailable = false

    fun font(size: Double, bold: Boolean): Font {
        return fonts.computeIfAbsent(size to bold) {
            Font(Font.SANS_SERIF, if (bold) Font.BOLD else Font.PLAIN, 1).deriveFont(size.toFloat())
        }
    }

    fun width(text: String, size: Double, bold: Boolean = false): Double {
        if (!fontsUnavailable) {
            try {
                return font(size, bold).getStringBounds(text, renderContext).width
            } catch (_: Throwable) {
                // e.g. a JRE without fonts and fontconfig throws errors, the widths are estimated then
                fontsUnavailable = true
            }
        }
        return text.length * size * (if (bold) 0.62 else 0.56)
    }
}

/**
 * Writes the drawing operations as SVG elements.
 */
class SvgChartCanvas(private val width: Int, private val height: Int) : ChartCanvas {
    private val body = StringBuilder()
    private var clipCount = 0
    private val definitions = StringBuilder()

    override fun fill(shape: Shape, color: ChartColor) {
        element(shape, "fill=\"${color.hex}\"${opacity("fill-opacity", color)}")
    }

    override fun stroke(shape: Shape, color: ChartColor, width: Double, dash: List<Double>?, roundCap: Boolean) {
        val attributes = StringBuilder("fill=\"none\" stroke=\"${color.hex}\" stroke-width=\"${number(width)}\"")
        attributes.append(opacity("stroke-opacity", color))
        if (dash != null) {
            attributes.append(" stroke-dasharray=\"${dash.joinToString(" ") { number(it) }}\"")
        }
        attributes.append(if (roundCap) " stroke-linecap=\"round\" stroke-linejoin=\"round\"" else " stroke-linejoin=\"round\"")
        element(shape, attributes.toString())
    }

    override fun text(text: String, x: Double, y: Double, size: Double, color: ChartColor, anchor: TextAnchor, bold: Boolean, rotated: Boolean) {
        body.append("<text x=\"${number(x)}\" y=\"${number(y)}\" font-size=\"${number(size)}\" fill=\"${color.hex}\"")
        body.append(opacity("fill-opacity", color))
        if (anchor != TextAnchor.Start) {
            body.append(" text-anchor=\"${anchor.svg}\"")
        }
        if (bold) {
            body.append(" font-weight=\"bold\"")
        }
        if (rotated) {
            body.append(" transform=\"rotate(-90 ${number(x)} ${number(y)})\"")
        }
        body.append(">").append(escape(text)).append("</text>\n")
    }

    override fun clip(bounds: Rectangle2D, block: () -> Unit) {
        val id = "clip${++clipCount}"
        definitions.append("<clipPath id=\"$id\"><rect x=\"${number(bounds.x)}\" y=\"${number(bounds.y)}\" ")
            .append("width=\"${number(bounds.width)}\" height=\"${number(bounds.height)}\"/></clipPath>\n")
        body.append("<g clip-path=\"url(#$id)\">\n")
        block()
        body.append("</g>\n")
    }

    fun toSvg(title: String): String {
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"$width\" height=\"$height\" viewBox=\"0 0 $width $height\" ")
            append("font-family=\"${escape(ChartFonts.SVG_FAMILY)}\">\n")
            append("<title>").append(escape(title)).append("</title>\n")
            if (definitions.isNotEmpty()) {
                append("<defs>\n").append(definitions).append("</defs>\n")
            }
            append(body)
            append("</svg>\n")
        }
    }

    private fun element(shape: Shape, attributes: String) {
        when (shape) {
            is Line2D -> body.append("<line x1=\"${number(shape.x1)}\" y1=\"${number(shape.y1)}\" x2=\"${number(shape.x2)}\" y2=\"${number(shape.y2)}\" $attributes/>\n")
            is Rectangle2D -> body.append("<rect x=\"${number(shape.x)}\" y=\"${number(shape.y)}\" width=\"${number(shape.width)}\" height=\"${number(shape.height)}\" $attributes/>\n")
            is Ellipse2D if shape.width == shape.height -> {
                body.append("<circle cx=\"${number(shape.centerX)}\" cy=\"${number(shape.centerY)}\" r=\"${number(shape.width / 2)}\" $attributes/>\n")
            }

            else -> body.append("<path d=\"${pathData(shape)}\" $attributes/>\n")
        }
    }

    private fun opacity(name: String, color: ChartColor): String {
        return if (color.alpha == 255) "" else " $name=\"${number(color.alpha / 255.0)}\""
    }

    companion object {
        /** @return The number with at most two decimals, without trailing zeros. */
        internal fun number(value: Double): String {
            val rounded = Math.round(value * 100) / 100.0
            return if (rounded == Math.rint(rounded)) rounded.toLong().toString() else rounded.toString()
        }

        internal fun escape(text: String): String {
            return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        }

        private fun pathData(shape: Shape): String {
            val coordinates = DoubleArray(6)
            val data = StringBuilder()
            val iterator = shape.getPathIterator(null)
            while (!iterator.isDone) {
                when (iterator.currentSegment(coordinates)) {
                    PathIterator.SEG_MOVETO -> data.append("M").append(points(coordinates, 1))
                    PathIterator.SEG_LINETO -> data.append("L").append(points(coordinates, 1))
                    PathIterator.SEG_QUADTO -> data.append("Q").append(points(coordinates, 2))
                    PathIterator.SEG_CUBICTO -> data.append("C").append(points(coordinates, 3))
                    PathIterator.SEG_CLOSE -> data.append("Z")
                }
                iterator.next()
            }
            return data.toString()
        }

        private fun points(coordinates: DoubleArray, count: Int): String {
            return (0 until count).joinToString(" ") { "${number(coordinates[2 * it])} ${number(coordinates[2 * it + 1])}" }
        }
    }
}

/**
 * Draws the operations with Java2D, e.g. into an image for PNG.
 */
class Graphics2DChartCanvas(private val graphics: Graphics2D) : ChartCanvas {
    init {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
    }

    override fun fill(shape: Shape, color: ChartColor) {
        graphics.color = color.toAwt()
        graphics.fill(shape)
    }

    override fun stroke(shape: Shape, color: ChartColor, width: Double, dash: List<Double>?, roundCap: Boolean) {
        graphics.color = color.toAwt()
        // Java2D needs dashes longer than 0, a very short dash with round caps is a dot
        val dashes = dash?.map { it.coerceAtLeast(0.01).toFloat() }?.toFloatArray()
        val cap = if (roundCap) BasicStroke.CAP_ROUND else BasicStroke.CAP_BUTT
        graphics.stroke = BasicStroke(width.toFloat(), cap, BasicStroke.JOIN_ROUND, 10f, dashes, 0f)
        graphics.draw(shape)
    }

    override fun text(text: String, x: Double, y: Double, size: Double, color: ChartColor, anchor: TextAnchor, bold: Boolean, rotated: Boolean) {
        val width = ChartFonts.width(text, size, bold)
        val offset = when (anchor) {
            TextAnchor.Start -> 0.0
            TextAnchor.Middle -> -width / 2
            TextAnchor.End -> -width
        }
        graphics.color = color.toAwt()
        graphics.font = ChartFonts.font(size, bold)
        val transform = graphics.transform
        if (rotated) {
            graphics.transform(AffineTransform.getRotateInstance(-Math.PI / 2, x, y))
        }
        graphics.drawString(text, (x + offset).toFloat(), y.toFloat())
        graphics.transform = transform
    }

    override fun clip(bounds: Rectangle2D, block: () -> Unit) {
        val clip = graphics.clip
        graphics.clip(bounds)
        try {
            block()
        } finally {
            graphics.clip = clip
        }
    }
}

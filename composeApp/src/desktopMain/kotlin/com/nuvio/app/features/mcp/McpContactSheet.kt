package com.nuvio.app.features.mcp

import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * Many frames in one picture, each stamped with its time.
 *
 * For finding a moment nobody says anything in. Looking for it a frame at a
 * time costs an image per frame; a sheet of twenty-four costs one, and a
 * thumbnail is enough to tell a rooftop from a kitchen. The time is drawn on
 * each cell rather than listed beside the sheet, so the answer to "which one"
 * is read off the picture instead of counted across a grid.
 *
 * Drawn with Java2D rather than ffmpeg's `tile` and `drawtext`: the second
 * needs a build with freetype and a font it can find, which is the kind of
 * thing that works on the machine it was written on.
 */
internal object McpContactSheet {

    /** Null when none of [frames] could be decoded. */
    fun compose(frames: List<GrabbedFrame>, columns: Int, label: (Long) -> String): ByteArray? {
        val cells = frames.mapNotNull { frame ->
            runCatching { ImageIO.read(ByteArrayInputStream(frame.jpeg)) }.getOrNull()?.let { frame.atMs to it }
        }
        if (cells.isEmpty()) return null

        val cellWidth = cells.first().second.width
        val cellHeight = cells.first().second.height
        val rows = (cells.size + columns - 1) / columns
        val sheet = BufferedImage(
            columns * cellWidth + (columns + 1) * Gutter,
            rows * cellHeight + (rows + 1) * Gutter,
            BufferedImage.TYPE_INT_RGB,
        )
        val graphics = sheet.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            graphics.font = Font(Font.SANS_SERIF, Font.BOLD, (cellHeight / 9).coerceIn(11, 22))
            val metrics = graphics.fontMetrics
            cells.forEachIndexed { index, (atMs, image) ->
                val x = Gutter + (index % columns) * (cellWidth + Gutter)
                val y = Gutter + (index / columns) * (cellHeight + Gutter)
                graphics.drawImage(image, x, y, cellWidth, cellHeight, null)

                val text = label(atMs)
                val plateWidth = metrics.stringWidth(text) + 2 * LabelPadding
                val plateHeight = metrics.height + LabelPadding
                // A plate behind the text: white on a bright frame and black on
                // a dark one are equally unreadable without it.
                graphics.color = Plate
                graphics.fillRect(x, y, plateWidth, plateHeight)
                graphics.color = Color.WHITE
                graphics.drawString(text, x + LabelPadding, y + LabelPadding / 2 + metrics.ascent)
            }
        } finally {
            graphics.dispose()
        }
        return encode(sheet)
    }

    private fun encode(image: BufferedImage): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val bytes = ByteArrayOutputStream()
        try {
            ImageIO.createImageOutputStream(bytes).use { stream ->
                writer.output = stream
                val quality = writer.defaultWriteParam.apply {
                    compressionMode = ImageWriteParam.MODE_EXPLICIT
                    compressionQuality = JpegQuality
                }
                writer.write(null, IIOImage(image, null, null), quality)
            }
        } finally {
            writer.dispose()
        }
        return bytes.toByteArray()
    }

    private const val Gutter = 4
    private const val LabelPadding = 5
    private const val JpegQuality = 0.82f
    private val Plate = Color(0, 0, 0, 170)
}

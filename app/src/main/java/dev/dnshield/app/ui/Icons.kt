package dev.dnshield.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Iconos de linea propios (24x24, trazo redondeado). Se tinen con Icon(tint = ...). */
object Ico {
    private fun line(name: String, vararg d: String, width: Float = 1.8f): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        for (p in d) {
            b.addPath(
                pathData = addPathNodes(p),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return b.build()
    }

    private fun circle(cx: Float, cy: Float, r: Float) = "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0"

    /** Escudo relleno de la marca, con la marca de verificacion en blanco. */
    val brand: ImageVector = ImageVector.Builder("brand", 24.dp, 24.dp, 24f, 24f)
        .addPath(addPathNodes("M12 2.5l8 3.2v5.6c0 5-3.4 9.3-8 10.7-4.6-1.4-8-5.7-8-10.7V5.7l8-3.2z"), fill = SolidColor(Color.Black))
        .build()
    val brandCheck: ImageVector = ImageVector.Builder("brandCheck", 24.dp, 24.dp, 24f, 24f)
        .addPath(addPathNodes("M12 2.5l8 3.2v5.6c0 5-3.4 9.3-8 10.7-4.6-1.4-8-5.7-8-10.7V5.7l8-3.2z"), fill = SolidColor(Color(0xFF3FBF8F)))
        .addPath(addPathNodes("M8.5 12l2.5 2.5 4.5-5"), fill = null, stroke = SolidColor(Color.White), strokeLineWidth = 2.2f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        .build()

    val home = line("home", "M4 10.5L12 4l8 6.5V20h-5.5v-5.5h-5V20H4z")
    val shield = line("shield", "M12 3l7 3v5c0 4.4-3 8.2-7 9.5-4-1.3-7-5.1-7-9.5V6l7-3z")
    val shieldCheck = line("shieldCheck", "M12 3l7 3v5c0 4.4-3 8.2-7 9.5-4-1.3-7-5.1-7-9.5V6l7-3z", "M9 12l2 2 4-4.5")
    val pie = line("pie", "M12 3v9h9a9 9 0 1 1-9-9z", "M15 3.5A9 9 0 0 1 20.5 9H15z")
    val gear = line("gear", circle(12f, 12f, 3f), "M12 2.5v2.5M12 19v2.5M2.5 12H5M19 12h2.5M5.3 5.3l1.8 1.8M16.9 16.9l1.8 1.8M5.3 18.7l1.8-1.8M16.9 7.1l1.8-1.8")
    val refresh = line("refresh", "M20 12a8 8 0 0 1-14.3 4.9M4 12a8 8 0 0 1 14.3-4.9", "M18.5 3v4.2h-4.2M5.5 21v-4.2h4.2", width = 2f)
    val check = line("check", "M5 12.5l4.5 4.5L19 7.5", width = 2.6f)
    val globe = line("globe", circle(12f, 12f, 8f), "M4 12h16M12 4c2.5 2.5 2.5 13.5 0 16M12 4c-2.5 2.5-2.5 13.5 0 16")
    val server = line("server", "M4 5h16v6H4zM4 13h16v6H4z", "M7.5 8h.01M7.5 16h.01")
    val filter = line("filter", "M4 6h16M7 12h10M10 18h4")
    val list = line("list", "M8 6h12M8 12h12M8 18h12M4 6h.01M4 12h.01M4 18h.01")
    val block = line("block", circle(12f, 12f, 8.5f), "M6 6l12 12")
    val hand = line("hand", "M8 13V6.5a1.5 1.5 0 0 1 3 0V12M11 11V5a1.5 1.5 0 0 1 3 0v6M14 11V6.5a1.5 1.5 0 0 1 3 0V14c0 4-2.5 6.5-6 6.5-2.6 0-4.4-1.3-5.6-3.4L4 14.2a1.5 1.5 0 0 1 2.6-1.5L8 15")
    val wifi = line("wifi", "M2.5 9a14 14 0 0 1 19 0M5.5 12.5a9.5 9.5 0 0 1 13 0M8.5 16a5 5 0 0 1 7 0M12 19.5h.01")
    val apps = line("apps", "M4 4h6v6H4zM14 4h6v6h-6zM4 14h6v6H4zM14 14h6v6h-6z")
    val vpn = line("vpn", circle(12f, 12f, 8.5f), "M8 12h8M12 8v8")
    val chevronRight = line("chevronRight", "M9 6l6 6-6 6", width = 2f)
    val chevronDown = line("chevronDown", "M6 9l6 6 6-6", width = 2f)
    val back = line("back", "M15 5l-7 7 7 7", width = 2.2f)
    val search = line("search", circle(11f, 11f, 6.5f), "M20 20l-4-4", width = 2f)
    val plus = line("plus", "M12 5v14M5 12h14", width = 2f)
    val sliders = line("sliders", "M4 7h9M17 7h3M4 17h3M11 17h9", circle(15f, 7f, 2f), circle(9f, 17f, 2f))
    val info = line("info", circle(12f, 12f, 8.5f), "M12 11v5M12 8h.01")
    val pause = line("pause", "M9 6v12M15 6v12", width = 2.2f)
    val tune = line("tune", "M4 6h10M18 6h2M4 12h4M12 12h8M4 18h12M20 18h0", circle(16f, 6f, 2f), circle(10f, 12f, 2f), circle(18f, 18f, 2f))
    val chat = line("chat", "M5 5h14v10H9l-4 4z")
    val trash = line("trash", "M5 7h14M10 7V4.5h4V7M7 7l1 13h8l1-13")
    val share = line("share", "M12 15V4M8 8l4-4 4 4", "M5 13v6h14v-6")
}

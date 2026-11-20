package com.shyfr.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Własny zestaw ikon SHYFR: jednolity styl konturowy (linia 1,8, zaokrąglone końce, siatka 24x24).
 * Rysowane ścieżkami, bez dodatkowych zależności. Kolor nadaje wywołanie (tint).
 */
object Ic {
    private fun v(name: String, stroke: String, fill: String? = null): ImageVector = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        runCatching {
            addPath(
                PathParser().parsePathString(stroke).toNodes(), fill = null,
                stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            )
        }
        if (fill != null) runCatching { addPath(PathParser().parsePathString(fill).toNodes(), fill = SolidColor(Color.Black)) }
    }.build()

    val Chat = v("chat", "M4 6a3 3 0 0 1 3-3h10a3 3 0 0 1 3 3v8a3 3 0 0 1-3 3h-6.5L7 20.5V17a3 3 0 0 1-3-3z")
    val Feed = v("feed", "M6 3h12a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z M8 8h8 M8 12h8 M8 16h5")
    val Group = v("group", "M9 11a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7z M2.5 20c0-3.3 2.9-5.5 6.5-5.5s6.5 2.2 6.5 5.5 M16 4.3a3.5 3.5 0 0 1 0 6.4 M18 14.8c2.1.7 3.5 2.4 3.5 5.2")
    val Bell = v("bell", "M6 16v-5a6 6 0 0 1 12 0v5l1.5 2h-15z M10 21h4")
    val BellOff = v("bellOff", "M6 16v-5a6 6 0 0 1 9-5.2 M18 11v5l1.5 2H8 M10 21h4 M4 4l16 16")
    val Person = v("person", "M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8z M4.5 20.5c0-3.6 3.4-6 7.5-6s7.5 2.4 7.5 6")
    val Settings = v("settings", "M4 7h9 M17 7h3 M4 17h3 M11 17h9 M15 4.5v5 M9 14.5v5")
    val Lock = v("lock", "M6.5 11h11a1.5 1.5 0 0 1 1.5 1.5v6.5a1.5 1.5 0 0 1-1.5 1.5h-11A1.5 1.5 0 0 1 5 19v-6.5A1.5 1.5 0 0 1 6.5 11z M8.5 11V8a3.5 3.5 0 0 1 7 0v3")
    val Search = v("search", "M10.5 17a6.5 6.5 0 1 0 0-13 6.5 6.5 0 0 0 0 13z M15.5 15.5L20 20")
    val Send = v("send", "M20.5 3.5l-17 7 6.5 2.5 2.5 6.5z M10 13l10.5-9.5")
    val Add = v("add", "M12 5v14 M5 12h14")
    val Back = v("back", "M19 12H5 M11 6l-6 6 6 6")
    val Close = v("close", "M6 6l12 12 M18 6L6 18")
    val More = v("more", "M12 12h.01 M12 5h.01 M12 19h.01")
    val Check = v("check", "M5 12.5l4.5 4.5L19 7.5")
    val Pin = v("pin", "M9 4h6 M10 4v6l-3 4h10l-3-4V4 M12 14v6")
    val Shield = v("shield", "M12 3l7 3v5.5c0 4.4-3 7.9-7 9.5-4-1.6-7-5.1-7-9.5V6z")
    val Delete = v("delete", "M5 7h14 M9 7V4.5h6V7 M7 7l1 13h8l1-13 M10 11v5 M14 11v5")
    val Edit = v("edit", "M4 20h4L19 9l-4-4L4 16z M13.5 6.5l4 4")
    val Copy = v("copy", "M9 9h10a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H9a1 1 0 0 1-1-1V10a1 1 0 0 1 1-1z M16 9V5a1 1 0 0 0-1-1H5a1 1 0 0 0-1 1v10a1 1 0 0 0 1 1h3")
    val Forward = v("forward", "M4 12h14 M13 6l6 6-6 6")
    val Reply = v("reply", "M9 6l-6 6 6 6 M3 12h11a6 6 0 0 1 6 6v1")
    val Image = v("image", "M5 4h14a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1z M4 16l5-5 4 4 3-3 4 4 M9 8.5h.01")
    val Attach = v("attach", "M20 11l-8.5 8.5a5 5 0 0 1-7-7L13 4a3.3 3.3 0 0 1 4.7 4.7L9.2 17.2a1.7 1.7 0 0 1-2.4-2.4L14.5 7")
    val Mic = v("mic", "M12 15a3 3 0 0 0 3-3V6a3 3 0 0 0-6 0v6a3 3 0 0 0 3 3z M6 12a6 6 0 0 0 12 0 M12 18v3")
    val Info = v("info", "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z M12 11v5 M12 8h.01")
    val Archive = v("archive", "M4 5h16v4H4z M5.5 9v10h13V9 M10 13h4")
    val Down = v("down", "M6 9l6 6 6-6")
    val Star = v("star", "M12 3.5l2.6 5.4 5.9.8-4.3 4.1 1 5.9L12 16.9l-5.2 2.8 1-5.9L3.5 9.7l5.9-.8z")
    val Qr = v("qr", "M4 4h6v6H4z M14 4h6v6h-6z M4 14h6v6H4z M14 14h2v2h-2z M18 14h2 M14 18h2v2 M18 18v2h2")
    val Block = v("block", "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z M5.6 5.6l12.8 12.8")
    val Link = v("link", "M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1 M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1")
    val Scan = v("scan", "M4 8V5a1 1 0 0 1 1-1h3 M16 4h3a1 1 0 0 1 1 1v3 M20 16v3a1 1 0 0 1-1 1h-3 M8 20H5a1 1 0 0 1-1-1v-3 M4 12h16")
    val Unread = v("unread", "M12 16a4 4 0 1 0 0-8 4 4 0 0 0 0 8z", fill = "M12 16a4 4 0 1 0 0-8 4 4 0 0 0 0 8z")
    val Smile = v("smile", "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z M8.5 14a4.5 4.5 0 0 0 7 0 M9 9.5h.01 M15 9.5h.01")
    val Keyboard = v("keyboard", "M4 6h16a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1z M7 10h.01 M11 10h.01 M15 10h.01 M7 14h10")
    val Refresh = v("refresh", "M20 12a8 8 0 1 1-2.5-5.8 M20 4v4h-4")
}

@Composable
fun Icon24(icon: ImageVector, tint: Color, size: Dp = 24.dp, modifier: Modifier = Modifier) {
    Image(icon, contentDescription = null, modifier = modifier.size(size), colorFilter = ColorFilter.tint(tint))
}

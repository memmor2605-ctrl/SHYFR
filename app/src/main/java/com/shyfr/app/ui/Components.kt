package com.shyfr.app.ui

import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shyfr.app.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Paleta neutralna: grafit i biel, bez ozdobników. Jeden kolor akcentu = kolor tekstu.
// Kolory zależą od aktualnego motywu (jasny/ciemny), więc są funkcjami kompozycyjnymi.
private val isDarkTheme: Boolean @Composable get() = MaterialTheme.colorScheme.background.luminance() < 0.5f
val ShInk: Color @Composable get() = if (isDarkTheme) Color(0xFF0B0B0E) else Color(0xFFFFFFFF)
val ShSurface: Color @Composable get() = if (isDarkTheme) Color(0xFF141418) else Color(0xFFF5F5F7)
val ShSurfaceHi: Color @Composable get() = if (isDarkTheme) Color(0xFF1E1E24) else Color(0xFFEAEAEE)
val ShSilver: Color @Composable get() = if (isDarkTheme) Color(0xFFE9E9ED) else Color(0xFF1A1A1F)
val ShMuted: Color @Composable get() = if (isDarkTheme) Color(0xFFA3A3AC) else Color(0xFF5A5A63)
val ShBubbleOut: Color @Composable get() = if (isDarkTheme) Color(0xFF2A2A31) else Color(0xFFE4E4E9)
val ShQuote: Color @Composable get() = if (isDarkTheme) Color(0x22FFFFFF) else Color(0x14000000)
val ShLine: Color @Composable get() = if (isDarkTheme) Color(0xFF26262C) else Color(0xFFE3E3E8)

val AvatarColors = listOf(
    Color(0xFF4B4F5C), Color(0xFF545061), Color(0xFF45565F), Color(0xFF4C5D52),
    Color(0xFF5E5A4C), Color(0xFF5D4B52), Color(0xFF474F63), Color(0xFF58585F)
)

val ShyfrShapes @Composable get() = Shapes(small = RoundedCornerShape(8.dp), medium = RoundedCornerShape(12.dp), large = RoundedCornerShape(16.dp))

fun shyfrColors(dark: Boolean) = if (dark) darkColorScheme(
    primary = Color(0xFFEDEDF0), onPrimary = Color(0xFF0B0B0E),
    background = Color(0xFF0B0B0E), onBackground = Color(0xFFEDEDF0),
    surface = Color(0xFF141418), onSurface = Color(0xFFEDEDF0),
    surfaceVariant = Color(0xFF1E1E24), onSurfaceVariant = Color(0xFFA3A3AC),
    secondaryContainer = Color(0xFF2A2A31), onSecondaryContainer = Color(0xFFEDEDF0),
    outline = Color(0xFF34343B), error = Color(0xFFE57373)
) else lightColorScheme(
    primary = Color(0xFF15151A), onPrimary = Color(0xFFFFFFFF),
    background = Color(0xFFFFFFFF), onBackground = Color(0xFF15151A),
    surface = Color(0xFFF5F5F7), onSurface = Color(0xFF15151A),
    surfaceVariant = Color(0xFFEAEAEE), onSurfaceVariant = Color(0xFF5A5A63),
    secondaryContainer = Color(0xFFE4E4E9), onSecondaryContainer = Color(0xFF15151A),
    outline = Color(0xFFD3D3D9), error = Color(0xFFBA1A1A)
)

/** Płaskie tło bez gradientu (zostawione, by nie zmieniać wywołań w innych miejscach). */
@Composable
fun Modifier.warmGlow(): Modifier = this.background(MaterialTheme.colorScheme.background)

/** Biały znak na ciemnym motywie, czarny na jasnym. */
@Composable
fun logoRes(): Int = if (isDarkTheme) R.drawable.shyfr_logo else R.drawable.shyfr_logo_ink

@Composable
fun LogoHeader(size: Dp = 96.dp) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Image(painterResource(logoRes()), contentDescription = null, modifier = Modifier.size(size))
        Text("SHYFR", fontSize = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 6.sp, color = ShSilver)
    }
}

@Composable
fun Avatar(name: String, color: Int, emoji: String, size: Dp = 44.dp, photo: ByteArray? = null, online: Boolean = false) {
    val bg = AvatarColors[color.coerceIn(0, AvatarColors.lastIndex)]
    val bmp = remember(photo) { photo?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull() } }
    Box(Modifier.size(size)) {
        Box(Modifier.size(size).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
            if (bmp != null) Image(bmp, contentDescription = null, modifier = Modifier.size(size).clip(CircleShape), contentScale = ContentScale.Crop)
            else {
                val t = if (emoji.isNotBlank()) emoji else name.trim().take(1).uppercase().ifEmpty { "?" }
                Text(t, fontSize = (size.value * 0.42f).sp, color = Color.White)
            }
        }
        if (online) {
            val ring = MaterialTheme.colorScheme.background
            Box(Modifier.align(Alignment.BottomEnd).size((size.value * 0.30f).dp).clip(CircleShape).background(ring).padding((size.value * 0.05f).dp)
                .clip(CircleShape).background(Color(0xFF3DDC84)))
        }
    }
}

/** Przycisk-ikona z dużym polem dotyku (44 dp) i opisem dla TalkBack. */
@Composable
fun IconTap(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color = Color.Unspecified, box: Dp = 44.dp, size: Dp = 22.dp, onClick: () -> Unit) {
    Box(Modifier.size(box).clip(CircleShape).clickable(onClick = onClick).a11y(label), contentAlignment = Alignment.Center) {
        Icon24(icon, if (tint == Color.Unspecified) ShSilver else tint, size)
    }
}

/** Okrągły awatar z ikoną zamiast litery (grupy, notatki). */
@Composable
fun IconAvatar(icon: androidx.compose.ui.graphics.vector.ImageVector, size: Dp = 48.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(ShSurfaceHi), contentAlignment = Alignment.Center) {
        Icon24(icon, ShMuted, (size.value * 0.5f).dp)
    }
}

/** Paleta motywu rozmowy. Motyw jest wspólny dla obu rozmówców (synchronizowany ramką KIND_LOOK). */
data class ChatPalette(val name: String, val out: Color, val inBubble: Color, val bg: Color, val accent: Color, val swatch: Color)

private class ThemeDef(val name: String, val dOut: Long, val lOut: Long, val dBg: Long, val lBg: Long, val dAcc: Long, val lAcc: Long)
private val THEMES = listOf(
    ThemeDef("Domyślny", 0xFF2A2A31, 0xFFE4E4E9, 0xFF0B0B0E, 0xFFFFFFFF, 0xFFEDEDF0, 0xFF15151A),
    ThemeDef("Ocean", 0xFF0F5C7A, 0xFFCDEBF7, 0xFF06141B, 0xFFEFF8FC, 0xFF5CC8F0, 0xFF0B6A8F),
    ThemeDef("Fiolet", 0xFF4B3A82, 0xFFE4DCF8, 0xFF0E0A1A, 0xFFF6F3FC, 0xFFB7A2F5, 0xFF5B44A8),
    ThemeDef("Las", 0xFF2D5A3F, 0xFFD6EEDD, 0xFF08130C, 0xFFF1F8F3, 0xFF7ED9A0, 0xFF2E7A4D),
    ThemeDef("Zachód", 0xFF7A4A1F, 0xFFF9E3CC, 0xFF160E07, 0xFFFDF6EE, 0xFFF0A860, 0xFFB5651D),
    ThemeDef("Róż", 0xFF7A2E55, 0xFFF9D8E8, 0xFF16080F, 0xFFFDF1F6, 0xFFF08CBB, 0xFFB03A78),
    ThemeDef("Grafit", 0xFF3A3F47, 0xFFE0E3E8, 0xFF0B0C0E, 0xFFF4F5F7, 0xFFC4C9D1, 0xFF4A5059),
    ThemeDef("Czerń", 0xFF1E3A5F, 0xFFD3E3F7, 0xFF000000, 0xFFFFFFFF, 0xFF6FA8F0, 0xFF1F5FB5)
)
const val CHAT_THEME_COUNT = 8

@Composable
fun chatPalette(theme: Int): ChatPalette {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val t = THEMES[theme.coerceIn(0, THEMES.lastIndex)]
    val bg = Color(if (dark) t.dBg else t.lBg)
    val onBg = MaterialTheme.colorScheme.onBackground
    return ChatPalette(
        t.name, Color(if (dark) t.dOut else t.lOut),
        androidx.compose.ui.graphics.lerp(bg, onBg, if (dark) 0.13f else 0.07f),
        bg, Color(if (dark) t.dAcc else t.lAcc), Color(if (dark) t.dAcc else t.lAcc)
    )
}

fun fmtTime(ts: Long): String {
    if (ts <= 0) return ""
    val f = SimpleDateFormat(if (DateUtils.isToday(ts)) "HH:mm" else "dd.MM", Locale.getDefault())
    return f.format(Date(ts))
}


/** Opis przycisku zawierającego tylko ikonę, dla czytników ekranu (TalkBack). */
fun Modifier.a11y(label: String): Modifier = this.semantics(mergeDescendants = true) { contentDescription = label; role = Role.Button }

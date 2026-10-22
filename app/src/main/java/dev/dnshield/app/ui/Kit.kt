package dev.dnshield.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/** Paleta al estilo AdGuard: casi negro, verde de marca para lo activo y rojo para lo bloqueado. */
@Immutable
data class Pal(
    val dark: Boolean,
    val bg: Color,
    val surface: Color,
    val field: Color,
    val line: Color,
    val text: Color,
    val muted: Color,
    val faint: Color,
    val accent: Color,
    val onAccent: Color,
    val block: Color,
    val warn: Color,
    val switchOff: Color,
)

fun makePal(dark: Boolean): Pal = if (dark) Pal(
    dark = true,
    bg = Color(0xFF121316), surface = Color(0xFF1B1C20), field = Color(0xFF2A2B31),
    line = Color(0xFF26272C),
    text = Color(0xFFFFFFFF), muted = Color(0xFFA3A3A3), faint = Color(0xFF6E6E6E),
    accent = Color(0xFF3FBF8F), onAccent = Color.White,
    block = Color(0xFFFF6B57), warn = Color(0xFFE8B04A), switchOff = Color(0xFF3A3A3C),
) else Pal(
    dark = false,
    bg = Color(0xFFFFFFFF), surface = Color(0xFFF4F4F5), field = Color(0xFFE9E9EB),
    line = Color(0xFFE3E3E6),
    text = Color(0xFF111111), muted = Color(0xFF6B6B70), faint = Color(0xFFA0A0A6),
    accent = Color(0xFF23A275), onAccent = Color.White,
    block = Color(0xFFE2553F), warn = Color(0xFFC58A14), switchOff = Color(0xFFE2E2E5),
)

val LocalPal = staticCompositionLocalOf { makePal(true) }

@Composable
fun ProvidePal(pal: Pal, content: @Composable () -> Unit) = CompositionLocalProvider(LocalPal provides pal, content = content)

val pal: Pal @Composable get() = LocalPal.current

object Tx {
    @Composable fun body(color: Color = pal.text, size: Int = 16) = TextStyle(fontSize = size.sp, color = color, lineHeight = (size + 5).sp)
    @Composable fun small(color: Color = pal.muted) = TextStyle(fontSize = 13.sp, color = color, lineHeight = 17.sp)
    @Composable fun title(size: Int = 24) = TextStyle(fontSize = size.sp, color = pal.text, fontWeight = FontWeight.Bold, lineHeight = (size + 6).sp)
    @Composable fun figure(color: Color = pal.text, size: Int = 26) = TextStyle(fontSize = size.sp, color = color, fontWeight = FontWeight.SemiBold)
    @Composable fun mono(color: Color = pal.muted, size: Int = 12) = TextStyle(fontFamily = FontFamily.Monospace, fontSize = size.sp, color = color)
    @Composable fun label(color: Color = pal.muted, size: Int = 13) = TextStyle(fontSize = size.sp, color = color, fontWeight = FontWeight.Medium)
}

val Square = RoundedCornerShape(10.dp)

@Composable
fun Hairline(modifier: Modifier = Modifier, strong: Boolean = false) {
    Box(modifier.fillMaxWidth().height(1.dp).background(pal.line))
}

/** Pantalla al estilo AdGuard: flecha atras, icono grande verde, titulo centrado y descripcion gris. */
@Composable
fun Page(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
    icon: ImageVector? = null,
    content: LazyListScope.() -> Unit,
) {
    if (onBack != null) BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
                    Icon(Ico.back, "Atrás", tint = pal.muted, modifier = Modifier.size(22.dp))
                }
            } else Spacer(Modifier.width(44.dp))
            Spacer(Modifier.weight(1f))
            action?.invoke()
        }
        LazyColumn(Modifier.fillMaxSize()) {
            if (title.isNotEmpty()) item {
                Column(
                    Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (icon != null) {
                        Box(
                            Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).background(pal.accent.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center,
                        ) { Icon(icon, null, tint = pal.accent, modifier = Modifier.size(30.dp)) }
                        Spacer(Modifier.height(12.dp))
                    }
                    Text(title, style = Tx.title(24), textAlign = TextAlign.Center)
                    if (subtitle != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(subtitle, style = Tx.body(pal.muted, 14), textAlign = TextAlign.Center)
                    }
                }
            }
            content()
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

/** Encabezado de grupo ("Tema", "Otros"): negrita, alineado a la izquierda. */
fun LazyListScope.section(num: String, title: String, hint: String? = null) {
    item(key = "sec-$num-$title") {
        Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 22.dp, bottom = 6.dp)) {
            Text(title, style = Tx.title(19))
            if (hint != null) {
                Spacer(Modifier.height(4.dp))
                Text(hint, style = Tx.small())
            }
        }
    }
}

@Composable
private fun RowIcon(icon: ImageVector?) {
    if (icon != null) {
        Icon(icon, null, tint = pal.accent, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
    }
}

@Composable
fun NavRow(title: String, subtitle: String? = null, value: String? = null, enabled: Boolean = true, icon: ImageVector? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 18.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowIcon(icon)
        Column(Modifier.weight(1f)) {
            Text(title, style = Tx.body(if (enabled) pal.text else pal.faint))
            if (subtitle != null) Text(subtitle, style = Tx.small())
        }
        if (value != null) {
            Spacer(Modifier.width(12.dp))
            Text(value, style = Tx.body(pal.muted, 15), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 170.dp))
        }
        Spacer(Modifier.width(8.dp))
        Icon(Ico.chevronRight, null, tint = pal.faint, modifier = Modifier.size(18.dp))
    }
    Hairline(Modifier.padding(start = if (icon != null) 58.dp else 18.dp))
}

/** Fila informativa, sin accion. */
@Composable
fun InfoRow(title: String, subtitle: String, icon: ImageVector? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        RowIcon(icon)
        Column {
            Text(title, style = Tx.body())
            Text(subtitle, style = Tx.small())
        }
    }
    Hairline(Modifier.padding(start = if (icon != null) 58.dp else 18.dp))
}

@Composable
fun ToggleRow(title: String, subtitle: String? = null, checked: Boolean, enabled: Boolean = true, icon: ImageVector? = null, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(horizontal = 18.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowIcon(icon)
        Column(Modifier.weight(1f)) {
            Text(title, style = Tx.body(if (enabled) pal.text else pal.faint))
            if (subtitle != null) Text(subtitle, style = Tx.small())
        }
        Spacer(Modifier.width(14.dp))
        Toggle(checked, enabled)
    }
    Hairline(Modifier.padding(start = if (icon != null) 58.dp else 18.dp))
}

/** Fila que abre su pantalla al tocarla y tiene ademas su propio interruptor (como "Protección DNS" en AdGuard). */
@Composable
fun ToggleNavRow(title: String, subtitle: String?, checked: Boolean, icon: ImageVector?, onToggle: (Boolean) -> Unit, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowIcon(icon)
        Column(Modifier.weight(1f)) {
            Text(title, style = Tx.body())
            if (subtitle != null) Text(subtitle, style = Tx.small())
        }
        Spacer(Modifier.width(14.dp))
        Box(Modifier.clip(CircleShape).clickable { onToggle(!checked) }) { Toggle(checked) }
    }
    Hairline(Modifier.padding(start = if (icon != null) 58.dp else 18.dp))
}

/** Interruptor tipo iOS, como el de AdGuard. */
@Composable
fun Toggle(checked: Boolean, enabled: Boolean = true) {
    val x by animateDpAsState(if (checked) 20.dp else 0.dp, tween(180), label = "knob")
    val track by animateColorAsState(if (checked) pal.accent else pal.switchOff, tween(180), label = "track")
    Box(
        Modifier.width(51.dp).height(31.dp).clip(CircleShape)
            .background(if (enabled) track else track.copy(alpha = 0.4f)).padding(2.dp),
    ) {
        Box(Modifier.offset(x = x).size(27.dp).shadow(2.dp, CircleShape).clip(CircleShape).background(Color.White))
    }
}

@Composable
fun RadioRow(title: String, subtitle: String? = null, selected: Boolean, trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Led(selected, ring = true)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = Tx.body())
            if (subtitle != null) Text(subtitle, style = Tx.small())
        }
        trailing?.invoke()
    }
    Hairline(Modifier.padding(start = 58.dp))
}

@Composable
fun CheckRow(title: String, subtitle: String? = null, checked: Boolean, onToggle: () -> Unit, onLongPress: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 18.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(22.dp).clip(RoundedCornerShape(5.dp))
                .background(if (checked) pal.accent else Color.Transparent)
                .border(1.5.dp, if (checked) pal.accent else pal.faint, RoundedCornerShape(5.dp)),
            contentAlignment = Alignment.Center,
        ) { if (checked) Icon(Ico.check, null, tint = Color.White, modifier = Modifier.size(15.dp)) }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = Tx.body(if (checked) pal.text else pal.faint, 15), maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = Tx.small())
        }
        trailing?.invoke()
    }
    Hairline(Modifier.padding(start = 56.dp))
}

/** Circulo de seleccion: anillo gris o circulo verde con punto. */
@Composable
fun Led(on: Boolean, ring: Boolean = false, size: Dp = 22.dp, color: Color = pal.accent) {
    Box(
        Modifier.size(size).clip(CircleShape).border(1.5.dp, if (on) color else pal.faint, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (on) Box(Modifier.size(size - 8.dp).clip(CircleShape).background(color))
    }
}

@Composable
fun Btn(label: String, modifier: Modifier = Modifier.fillMaxWidth(), enabled: Boolean = true, primary: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier.clip(Square)
            .background(if (primary && enabled) pal.accent else pal.surface)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 13.dp, horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, textAlign = TextAlign.Center,
            style = Tx.body(if (!enabled) pal.faint else if (primary) pal.onAccent else pal.accent, 15).copy(fontWeight = FontWeight.SemiBold),
        )
    }
}

/** Accion verde de la cabecera ("Probar", "Actualizar"...). */
@Composable
fun HeaderAction(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(Modifier.clip(Square).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(label, style = Tx.body(if (enabled) pal.accent else pal.faint, 15))
    }
}

/** Fila verde con "+" ("Añadir filtro", "Añadir nueva regla"). */
@Composable
fun AddRow(label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Ico.plus, null, tint = pal.accent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, style = Tx.body(pal.accent))
    }
}

@Composable
fun Field(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    singleLine: Boolean = true,
    minHeight: Dp = 0.dp,
    keyboard: KeyboardType = KeyboardType.Uri,
    leading: ImageVector? = null,
) {
    Row(
        modifier.clip(Square).background(pal.field).heightIn(min = maxOf(minHeight, 42.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
    ) {
        if (leading != null) {
            Icon(leading, null, tint = pal.muted, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(placeholder, style = Tx.body(pal.muted, 15))
            BasicTextField(
                value, onChange, singleLine = singleLine,
                textStyle = Tx.body(pal.text, 15),
                cursorBrush = SolidColor(pal.accent),
                keyboardOptions = KeyboardOptions(keyboardType = keyboard, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Selector segmentado tipo iOS. */
@Composable
fun Segmented(options: List<String>, selected: Int, modifier: Modifier = Modifier.fillMaxWidth(), onSelect: (Int) -> Unit) {
    Row(modifier.clip(Square).background(pal.field).padding(2.dp)) {
        options.forEachIndexed { i, o ->
            val on = i == selected
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                    .background(if (on) (if (pal.dark) Color(0xFF636366) else Color.White) else Color.Transparent)
                    .clickable { onSelect(i) }.padding(vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) { Text(o, style = Tx.body(pal.text, 13).copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal), maxLines = 1) }
        }
    }
}

/** Dialogo: titulo, cuerpo y fila de botones. */
@Composable
fun Sheet(title: String, onDismiss: () -> Unit, buttons: @Composable RowScope.() -> Unit = {}, body: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(pal.surface).padding(20.dp)) {
            Text(title, style = Tx.title(19), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(14.dp))
            body()
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { buttons() }
        }
    }
}

@Composable
fun Confirm(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Sheet(title, onDismiss, buttons = {
        Btn("Cancelar", Modifier.weight(1f), onClick = onDismiss)
        Btn(confirm, Modifier.weight(1f), primary = true) { onConfirm(); onDismiss() }
    }) { Text(text, style = Tx.body(pal.muted, 14)) }
}

@Composable
fun Note(text: String) {
    Text(text, style = Tx.small(), modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp))
}

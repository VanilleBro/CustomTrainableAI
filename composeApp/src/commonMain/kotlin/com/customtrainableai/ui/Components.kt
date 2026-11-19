package com.customtrainableai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.customtrainableai.model.fmt

val AppColors = darkColorScheme(
    primary = Color(0xFF60A5FA),
    onPrimary = Color(0xFF0B1220),
    secondary = Color(0xFF34D399),
    tertiary = Color(0xFFFBBF24),
    background = Color(0xFF0B1220),
    surface = Color(0xFF111A2E),
    surfaceVariant = Color(0xFF1A2540),
    onSurface = Color(0xFFE5E7EB),
    onSurfaceVariant = Color(0xFFA5B0C5),
    error = Color(0xFFF87171),
    outline = Color(0xFF334155),
)

val ColorBest = Color(0xFF34D399)
val ColorAvg = Color(0xFF60A5FA)
val ColorMin = Color(0xFF94A3B8)

@Composable
fun Section(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth().padding(vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
fun Hint(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, fontSize = 13.sp, color = color, lineHeight = 17.sp)
}

@Composable
fun Fields(content: @Composable () -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) { content() }
}

@Composable
fun ButtonRow(content: @Composable () -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) { content() }
}

@Composable
fun LabeledSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(enabled = enabled) { onChange(!checked) }) {
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        Text(label, fontSize = 14.sp)
    }
}

@Composable
fun LabeledCheck(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { onChange(!checked) }) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, fontSize = 14.sp)
    }
}

private fun parseNum(t: String): Double? = t.trim().replace(',', '.').toDoubleOrNull()

@Composable
fun NumField(
    label: String,
    value: Double,
    onValue: (Double) -> Unit,
    modifier: Modifier = Modifier.width(130.dp),
    decimals: Int = 3,
    min: Double = -1e9,
    max: Double = 1e9,
) {
    var text by remember { mutableStateOf(fmt(value, decimals)) }
    LaunchedEffect(value) {
        val parsed = parseNum(text)
        if (parsed == null || kotlin.math.abs(parsed - value) > 1e-6) text = fmt(value, decimals)
    }
    val parsed = parseNum(text)
    OutlinedTextField(
        value = text,
        onValueChange = { t ->
            text = t
            parseNum(t)?.let { onValue(it.coerceIn(min, max)) }
        },
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        singleLine = true,
        isError = parsed == null,
        modifier = modifier,
    )
}

@Composable
fun FloatField(label: String, value: Float, onValue: (Float) -> Unit, modifier: Modifier = Modifier.width(130.dp), min: Float = -1e9f, max: Float = 1e9f) =
    NumField(label, value.toDouble(), { onValue(it.toFloat()) }, modifier, 3, min.toDouble(), max.toDouble())

@Composable
fun IntField(label: String, value: Int, onValue: (Int) -> Unit, modifier: Modifier = Modifier.width(130.dp), min: Int = 0, max: Int = 1_000_000) =
    NumField(label, value.toDouble(), { onValue(it.toInt()) }, modifier, 0, min.toDouble(), max.toDouble())

@Composable
fun TextField(label: String, value: String, onValue: (String) -> Unit, modifier: Modifier = Modifier.widthIn(min = 200.dp)) {
    OutlinedTextField(value = value, onValueChange = onValue, label = { Text(label) }, singleLine = true, modifier = modifier)
}

@Composable
fun <T> Dropdown(label: String, options: List<T>, selected: T, text: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { open = true }) {
            Text("$label: ${text(selected)}  ▾", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o ->
                DropdownMenuItem(text = { Text(text(o)) }, onClick = {
                    open = false
                    onSelect(o)
                })
            }
        }
    }
}

@Composable
fun TabBar(tabs: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        tabs.forEachIndexed { i, t ->
            val sel = i == selected
            Box(
                Modifier
                    .background(if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(20.dp))
                    .clickable { onSelect(i) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(t, color = if (sel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

@Composable
fun ColorSwatch(hex: String) {
    val rgb = com.customtrainableai.model.parseColor(hex)
    Box(
        Modifier.size(22.dp)
            .background(Color(0xFF000000 or rgb.toLong()), RoundedCornerShape(4.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp)),
    )
}

@Composable
fun StatLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
        Text(value, fontWeight = FontWeight.Medium, fontSize = 14.sp)
    }
}

@Composable
fun VSpace(h: Int = 8) = Spacer(Modifier.height(h.dp))

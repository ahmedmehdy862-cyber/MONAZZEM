package com.focusstudy.poc

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The floating dock that replaced the full-width navigation bar.
 *
 * It keeps the person's own sections in one rounded surface over the background, marks the open one
 * with a soft pill, and ends with a fixed «الأقسام» door so nothing is more than two taps away.
 */
@Composable
internal fun FocusDock(
    tabs: List<String>,
    current: String,
    onSelect: (String) -> Unit,
    onOpenSections: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = FocusColors.Surface,
        shadowElevation = 16.dp,
        tonalElevation = 0.dp
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .padding(top = 7.dp)
                    .width(34.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(FocusColors.SurfaceHigh)
                    .clickable { onOpenSections() }
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                tabs.forEach { tab ->
                    DockItem(
                        icon = BottomTab.iconOf(tab),
                        label = BottomTab.labelOf(tab),
                        selected = tab == current,
                        modifier = Modifier.weight(1f),
                        onClick = { onSelect(tab) }
                    )
                }
                Box(Modifier.padding(horizontal = 2.dp).width(1.dp).height(26.dp).background(FocusColors.SurfaceHigh))
                DockItem(
                    icon = Icons.Filled.GridView,
                    label = "الأقسام",
                    selected = false,
                    modifier = Modifier.width(58.dp),
                    onClick = onOpenSections
                )
            }
        }
    }
}

@Composable
private fun DockItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val pill by animateColorAsState(if (selected) FocusColors.SurfaceHigh else Color.Transparent, tween(220), label = "dock-pill")
    val tint by animateColorAsState(if (selected) FocusColors.Blue else FocusColors.TextMuted, tween(220), label = "dock-tint")
    Column(
        modifier = modifier
            .padding(horizontal = 2.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(pill)
            .clickable(onClick = onClick)
            .padding(vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(imageVector = icon, contentDescription = label, modifier = Modifier.size(22.dp), tint = tint)
        Text(
            label,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = tint,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

/**
 * The dock sheet: the shared directory plus the pin controls that belong to the dock.
 *
 * Rows come from [NavigationCatalog], the same source «المزيد» reads, so the two directory entry
 * points can never drift into showing different sections. Pinning still writes the same preference
 * the bar editor used, and the five-slot cap is explained instead of silently refusing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SectionsSheet(
    pinned: List<String>,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    onTogglePin: (String) -> Unit
) {
    val current = remember(pinned) { BottomTab.normalize(pinned) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = FocusColors.Surface) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("كل الأقسام", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = FocusColors.TextPrimary)
                Spacer(Modifier.weight(1f))
                Text("${current.size}/${BottomTab.MAX} في الشريط", style = MaterialTheme.typography.labelMedium, color = FocusColors.TextMuted)
            }
            Text(
                "اضغط أي قسم للانتقال إليه، واضغط «تثبيت» ليظهر في الشريط.",
                style = MaterialTheme.typography.bodySmall,
                color = FocusColors.TextSecondary
            )
            SectionsDirectory(pinned = current, onOpen = onSelect, onTogglePin = onTogglePin)
        }
    }
}

/**
 * The one directory rendering shared by the dock sheet and the «المزيد» screen.
 *
 * [pinned] enables the pin controls; pass null for a plain navigation list. Search, grouping and
 * ordering are identical in both places, so «المزيد» is no longer a second hard-coded directory.
 * [onlyShelves] narrows the list: «المزيد» passes the settings shelves only, the dock sheet
 * passes null and shows the whole directory.
 */
@Composable
internal fun SectionsDirectory(
    pinned: List<String>? = null,
    onlyShelves: Set<SectionShelf>? = null,
    onOpen: (String) -> Unit,
    onTogglePin: (String) -> Unit = {}
) {
    var query by remember { mutableStateOf("") }
    val current = remember(pinned) { pinned?.let(BottomTab::normalize).orEmpty() }
    val pinnableIds = remember { NavigationCatalog.pinnableEntries().map { it.id }.toSet() }
    val canPinMore = pinned != null && BottomTab.canAdd(current)

    Spacer(Modifier.height(14.dp))
    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        singleLine = true,
        label = { Text("ابحث عن قسم") },
        modifier = Modifier.fillMaxWidth()
    )
    SectionShelf.entries.forEach { shelf ->
        if (onlyShelves != null && shelf !in onlyShelves) return@forEach
        val rows = NavigationCatalog.entries
            .filter { it.shelf == shelf }
            .filter { query.isBlank() || it.title.contains(query.trim(), ignoreCase = true) }
        if (rows.isNotEmpty()) {
            Text(
                shelf.title,
                style = MaterialTheme.typography.labelLarge,
                color = FocusColors.TextMuted,
                modifier = Modifier.padding(top = 16.dp, bottom = 2.dp)
            )
            Text(shelf.subtitle, style = MaterialTheme.typography.labelSmall, color = FocusColors.TextSecondary)
            rows.forEach { entry ->
                SectionRow(
                    entry = entry,
                    pinned = pinned != null && entry.id in current,
                    canPin = pinned != null && entry.id in pinnableIds && (entry.id in current || canPinMore),
                    showPin = pinned != null,
                    onOpen = { onOpen(entry.id) },
                    onToggle = { onTogglePin(entry.id) }
                )
            }
        }
    }
    if (pinned != null && !BottomTab.canAdd(current)) {
        Text(
            "الشريط ممتلئ بخمسة أقسام — انزع التثبيت عن واحد لإضافة غيره.",
            style = MaterialTheme.typography.bodySmall,
            color = FocusColors.TextMuted,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun SectionRow(
    entry: SectionEntry,
    pinned: Boolean,
    canPin: Boolean,
    showPin: Boolean,
    onOpen: () -> Unit,
    onToggle: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onOpen)
            .padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.width(34.dp).height(34.dp).clip(RoundedCornerShape(12.dp)).background(FocusColors.SurfaceHigh),
            contentAlignment = Alignment.Center
        ) { Icon(imageVector = entry.icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = FocusColors.Blue) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.title, style = MaterialTheme.typography.bodyLarge, color = FocusColors.TextPrimary)
            Text(entry.description, style = MaterialTheme.typography.labelSmall, color = FocusColors.TextMuted)
        }
        if (showPin) {
            TextButton(onClick = onToggle, enabled = canPin) {
                Text(
                    when {
                        pinned -> "مثبّت ✓"
                        canPin -> "تثبيت"
                        else -> "ممتلئ"
                    },
                    color = if (pinned) FocusColors.Blue else FocusColors.TextMuted
                )
            }
        }
    }
}

package com.focusstudy.poc

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The side drawer (phase 2): the curated fast map of the app.
 *
 * «المزيد» used to carry all twenty sections in four shelves and the bottom bar only holds
 * five, so the daily and growth sections had nowhere uncrowded to live. The drawer groups the
 * same [NavigationCatalog] destinations instead — every row below is a catalog id, so no route
 * is invented here and the drawer can never drift from the directory. The dock sheet
 * («كل الأقسام») stays the complete directory: the drawer deliberately leaves out the
 * long-tail rows (`notes`, `analytics`, `task_types`) and the settings sub-page
 * (`permanent_apps`, reached from «التخصيص»), rather than repeating everything twice.
 *
 * Colours come only from [FocusColors], so the drawer reads correctly in light and dark mode
 * with no hard-coded values. The drawer closes on every selection; the caller decides whether
 * the destination is a bottom-bar root (history cleared) or a secondary screen (pushed).
 *
 * The drawer opens from the right: it is the start edge in this RTL app (ModalNavigationDrawer
 * follows the Rtl layout direction MainActivity sets above AppRoot), and the header menu button
 * sits at the right end of the header for the same reason.
 */

/** One group in the drawer. `ids` are [NavigationCatalog] route ids. */
internal data class DrawerGroup(val title: String, val ids: List<String>)

/** Curated groups: daily life first, settings and app tools last. */
internal val AppDrawerGroups: List<DrawerGroup> = listOf(
    DrawerGroup("يومي", listOf("assistant", "today", "tasks", "planner", "calendar")),
    DrawerGroup("حياتي", listOf("quran", "adhkar", "life")),
    DrawerGroup("تطويري", listOf("goals", "skills", "subjects")),
    DrawerGroup("الإعدادات", listOf("settings", "notifications", "prayer_times", "bottom_bar")),
    DrawerGroup("التطبيق", listOf("messages", "updates", "support", "sync"))
)

/**
 * Drawer sheet content, hosted by a [androidx.compose.material3.ModalDrawerSheet] in MainActivity.
 *
 * The header is the person's profile card: name, profile and their avatar — the
 * cartoon / 3D character (or their photo as-is) chosen in «التخصيص», drawn
 * on-device from [CartoonAvatar]. When no avatar is chosen, a neutral person
 * mark is shown instead.
 */
@Composable
internal fun AppDrawerContent(
    current: String,
    userName: String,
    profileLabel: String,
    onSelect: (String) -> Unit,
    assistantName: String = "منظم",
    avatarPath: String = "",
    avatarSourcePath: String = "",
    avatarMode: AvatarMode = AvatarMode.CARTOON,
    companionStyle: Int = 0,
    modifier: Modifier = Modifier
) {
    Column(modifier.verticalScroll(rememberScrollState())) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (avatarPath.isNotBlank() || (avatarMode == AvatarMode.PHOTO && avatarSourcePath.isNotBlank())) {
                    AvatarPortrait(
                        avatarPath = avatarPath,
                        name = userName,
                        styleIndex = companionStyle,
                        size = 56.dp,
                        mode = avatarMode,
                        sourcePath = avatarSourcePath
                    )
                } else {
                    Box(
                        Modifier.size(56.dp).clip(CircleShape).background(FocusColors.SurfaceHigh),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Person,
                            contentDescription = null,
                            modifier = Modifier.size(32.dp),
                            tint = FocusColors.TextSecondary
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = userName.ifBlank { "أهلًا بك" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = FocusColors.TextPrimary
                    )
                    Text(
                        text = profileLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = FocusColors.TextSecondary
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = when (avatarMode) {
                    AvatarMode.DEPTH_3D -> "شخصيتك ثلاثية الأبعاد — بدّلها من «التخصيص»."
                    AvatarMode.PHOTO -> "صورتك الشخصية — بدّلها من «التخصيص»."
                    AvatarMode.CARTOON -> if (avatarPath.isBlank()) "أضف صورتك الكرتونية من «التخصيص»."
                    else "شخصيتك الكرتونية — بدّلها من «التخصيص»."
                },
                style = MaterialTheme.typography.labelSmall,
                color = FocusColors.TextMuted
            )
        }
        AppDrawerGroups.forEach { group ->
            Text(
                text = group.title,
                style = MaterialTheme.typography.labelLarge,
                color = FocusColors.TextMuted,
                modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp)
            )
            group.ids.forEach { id ->
                val entry = NavigationCatalog.entry(id) ?: return@forEach
                // The assistant row shows the name the person calls it by, not the
                // generic section title — the name is theirs to change in its screen.
                val rowTitle = if (id == "assistant" && assistantName.isNotBlank()) assistantName else entry.title
                NavigationDrawerItem(
                    label = { Text(rowTitle) },
                    selected = id == current,
                    onClick = { onSelect(id) },
                    icon = { Icon(imageVector = entry.icon, contentDescription = null) },
                    colors = NavigationDrawerItemDefaults.colors(
                        selectedContainerColor = FocusColors.SurfaceHigh,
                        unselectedContainerColor = Color.Transparent,
                        selectedIconColor = FocusColors.Blue,
                        unselectedIconColor = FocusColors.TextSecondary,
                        selectedTextColor = FocusColors.TextPrimary,
                        unselectedTextColor = FocusColors.TextPrimary
                    ),
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

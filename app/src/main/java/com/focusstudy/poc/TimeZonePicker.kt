package com.focusstudy.poc

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.Locale

/**
 * Searchable IANA zone picker shared by first launch and settings.
 *
 * Typing filters the full device zone database; only a canonical id is reported upwards, so the
 * saved value is always something [AppTimeZone.resolve] can parse.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimeZonePicker(
    selectedId: String,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var text by rememberSaveable(selectedId) { mutableStateOf(selectedId) }
    var expanded by remember { mutableStateOf(false) }
    val allIds = remember { AppTimeZone.availableIds() }
    val matches = remember(text, allIds) {
        val query = text.trim().lowercase(Locale.ROOT)
        if (query.isEmpty()) {
            AppTimeZone.SUGGESTED_IDS
        } else {
            allIds.filter { it.lowercase(Locale.ROOT).contains(query) }.take(40)
        }
    }
    val valid = text.isBlank() || AppTimeZone.isValid(text)

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.take(64); expanded = true },
            label = { Text("المنطقة الزمنية (IANA)") },
            placeholder = { Text(AppTimeZone.deviceId()) },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable, true),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            supportingText = {
                Text(
                    text = if (valid) "مثال: Africa/Cairo. تُحفظ محليًا وتُستخدم للمواقيت والتذكيرات."
                    else "اكتب معرّف IANA صالحًا مثل Africa/Cairo، أو اتركه فارغًا لاتباع الجهاز.",
                    color = if (valid) FocusColors.TextSecondary else FocusColors.Amber
                )
            }
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.exposedDropdownSize()
        ) {
            matches.forEach { id ->
                DropdownMenuItem(
                    text = { Text(AppTimeZone.displayLabel(id)) },
                    onClick = {
                        text = id
                        onSelected(id)
                        expanded = false
                    }
                )
            }
        }
    }
}

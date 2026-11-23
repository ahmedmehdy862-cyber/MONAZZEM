package com.focusstudy.poc

import android.app.DatePickerDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import java.time.LocalDate

/**
 * A tap-to-open native date picker. The value is shown on its own line so it can never be appended
 * to a subject or task name by a text field.
 */
@Composable
internal fun DateChoice(label: String, date: LocalDate?, onSelected: (LocalDate) -> Unit) {
    val context = LocalContext.current
    val initial = date ?: LocalDate.now()
    TextButton(onClick = {
        DatePickerDialog(
            context,
            { _, year, month, day -> onSelected(LocalDate.of(year, month + 1, day)) },
            initial.year,
            initial.monthValue - 1,
            initial.dayOfMonth
        ).show()
    }) {
        Text(if (date == null) label else "$label: ${date.dayOfMonth}-${date.monthValue}-${date.year}")
    }
}

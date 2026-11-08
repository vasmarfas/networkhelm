package com.vasmarfas.networkhelm.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Calendar
import com.composables.icons.lucide.ChevronLeft
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide
import com.composeunstyled.Text
import com.composeunstyled.UnstyledDropdownMenu
import com.vasmarfas.networkhelm.ui.theme.Theme
import com.vasmarfas.networkhelm.ui.theme.on
import kotlin.time.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minusMonth
import kotlinx.datetime.onDay
import kotlinx.datetime.plusMonth
import kotlinx.datetime.todayIn
import kotlinx.datetime.yearMonth
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource
import networkhelm.app.shared.generated.resources.Res
import networkhelm.app.shared.generated.resources.form_calendar
import networkhelm.app.shared.generated.resources.form_month_earlier
import networkhelm.app.shared.generated.resources.form_month_later
import networkhelm.app.shared.generated.resources.form_months
import networkhelm.app.shared.generated.resources.form_today
import networkhelm.app.shared.generated.resources.form_weekdays

@Composable
fun Calendar(day: LocalDate?, onPick: (LocalDate) -> Unit) {
    var open by remember { mutableStateOf(false) }
    UnstyledDropdownMenu(
        expanded = open,
        onExpandedChange = { open = it },
        panel = {
            MenuPanel {
                Month(day) { picked ->
                    onPick(picked)
                    open = false
                }
            }
        },
        anchor = { IconButton(Lucide.Calendar, stringResource(Res.string.form_calendar), { open = true }) },
    )
}

@Composable
private fun Month(day: LocalDate?, onPick: (LocalDate) -> Unit) {
    val palette = Theme.palette
    val shape = RoundedCornerShape(Theme.metrics.radius)
    val today = remember { Clock.System.todayIn(TimeZone.currentSystemDefault()) }
    var month by remember { mutableStateOf((day ?: today).yearMonth) }
    val names = stringArrayResource(Res.array.form_months)
    val cell = if (LocalCompact.current) 40.dp else 30.dp
    val lead = month.firstDay.dayOfWeek.isoDayNumber - 1
    Column(Modifier.padding(horizontal = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.width(cell * 7), verticalAlignment = Alignment.CenterVertically) {
            IconButton(Lucide.ChevronLeft, stringResource(Res.string.form_month_earlier), { month = month.minusMonth() })
            Text("${names[month.month.ordinal]} ${month.year}", Modifier.weight(1f), style = Theme.type.strong, textAlign = TextAlign.Center)
            IconButton(Lucide.ChevronRight, stringResource(Res.string.form_month_later), { month = month.plusMonth() })
        }
        Row {
            for (weekday in stringArrayResource(Res.array.form_weekdays)) {
                Text(weekday, Modifier.width(cell), color = palette.muted, style = Theme.type.small, textAlign = TextAlign.Center)
            }
        }
        for (week in 0 until (lead + month.numberOfDays + 6) / 7) {
            Row {
                for (weekday in 0..6) {
                    val number = week * 7 + weekday - lead + 1
                    Box(Modifier.size(cell).padding(1.dp), contentAlignment = Alignment.Center) {
                        if (number !in 1..month.numberOfDays) return@Box
                        val date = month.onDay(number)
                        val chosen = date == day
                        Box(
                            Modifier
                                .fillMaxSize()
                                .clip(shape)
                                .background(if (chosen) palette.accent else palette.surface)
                                .then(if (date == today) Modifier.border(1.dp, palette.accent, shape) else Modifier)
                                .clickable(role = Role.Button) { onPick(date) }
                                .hand(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("$number", color = if (chosen) palette.on(palette.accent) else palette.text)
                        }
                    }
                }
            }
        }
        Button(stringResource(Res.string.form_today), { onPick(today) }, Modifier.padding(vertical = 4.dp))
    }
}

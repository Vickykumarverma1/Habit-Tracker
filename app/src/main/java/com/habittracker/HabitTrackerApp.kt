package com.habittracker

import android.app.Application
import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch


private val DAY_LOCK_TIME: LocalTime = LocalTime.of(1, 0)
private val MissedColor = Color(0xFFD45D5D)

private val AppColors = lightColorScheme(
    primary = Color(0xFF2B6E4F),
    onPrimary = Color.White,
    secondary = Color(0xFFF5B971),
    background = Color(0xFFF6F1E7),
    surface = Color(0xFFFFFBF4),
    surfaceVariant = Color(0xFFE7E2D7),
    onSurface = Color(0xFF1E1B18),
    outline = Color(0xFFC7BFAF)
)

@Entity(tableName = "habits")
data class HabitEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String
)

@Entity(tableName = "habit_completions", primaryKeys = ["habitId", "date"])
data class HabitCompletionEntity(
    val habitId: Int,
    val date: String
)

@Dao
interface HabitTrackerDao {
    @Query("SELECT * FROM habits ORDER BY id ASC")
    fun observeHabits(): kotlinx.coroutines.flow.Flow<List<HabitEntity>>

    @Query("SELECT * FROM habit_completions WHERE date LIKE :monthPrefix || '%'")
    fun observeCompletionsForMonth(monthPrefix: String): kotlinx.coroutines.flow.Flow<List<HabitCompletionEntity>>

    @Insert
    suspend fun insertHabit(habit: HabitEntity)

    @Query("DELETE FROM habits WHERE id = :habitId")
    suspend fun deleteHabitById(habitId: Int)

    @Query("DELETE FROM habit_completions WHERE habitId = :habitId")
    suspend fun deleteCompletionsByHabit(habitId: Int)

    @Query("SELECT COUNT(*) FROM habits")
    suspend fun getHabitCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCompletion(entry: HabitCompletionEntity)

    @Query("SELECT * FROM habit_completions WHERE habitId = :habitId AND date = :date LIMIT 1")
    suspend fun findCompletion(habitId: Int, date: String): HabitCompletionEntity?

    @Query("DELETE FROM habit_completions WHERE habitId = :habitId AND date = :date")
    suspend fun deleteCompletion(habitId: Int, date: String)
}

@Database(
    entities = [HabitEntity::class, HabitCompletionEntity::class],
    version = 1,
    exportSchema = false
)
abstract class HabitTrackerDatabase : RoomDatabase() {
    abstract fun dao(): HabitTrackerDao

    companion object {
        @Volatile
        private var instance: HabitTrackerDatabase? = null

        fun get(context: Context): HabitTrackerDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    HabitTrackerDatabase::class.java,
                    "habit-tracker.db"
                ).build().also { instance = it }
            }
        }
    }
}

data class HabitRowUi(
    val id: Int,
    val name: String,
    val completedDates: Set<LocalDate>
)

data class HabitTrackerUiState(
    val selectedMonth: YearMonth = YearMonth.now(),
    val habitRows: List<HabitRowUi> = emptyList(),
    val dailyTotals: Map<LocalDate, Int> = emptyMap(),

)

class HabitTrackerViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = HabitTrackerDatabase.get(application).dao()
    private val selectedMonth = MutableStateFlow(YearMonth.now())

    val uiState: StateFlow<HabitTrackerUiState> = combine(
        selectedMonth,
        dao.observeHabits(),
        selectedMonth.flatMapLatest { month ->
            dao.observeCompletionsForMonth(month.toString())
        }
    ) { month, habits, completions ->
        val completionsByHabit = completions
            .groupBy { it.habitId }
            .mapValues { (_, items) -> items.map { LocalDate.parse(it.date) }.toSet() }

        val dailyTotals = completions
            .map { LocalDate.parse(it.date) }
            .groupingBy { it }
            .eachCount()

        HabitTrackerUiState(
            selectedMonth = month,
            habitRows = habits.map { habit ->
                HabitRowUi(
                    id = habit.id,
                    name = habit.name,
                    completedDates = completionsByHabit[habit.id].orEmpty()
                )
            },
            dailyTotals = dailyTotals
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HabitTrackerUiState()
    )

    fun addHabit(name: String) {
        val cleanedName = name.trim()
        if (cleanedName.isBlank()) return

        viewModelScope.launch {
            dao.insertHabit(HabitEntity(name = cleanedName))
        }
    }

    fun deleteHabit(habitId: Int) {
        viewModelScope.launch {
            dao.deleteCompletionsByHabit(habitId)
            dao.deleteHabitById(habitId)
        }
    }

    fun toggleHabit(habitId: Int, date: LocalDate) {
        if (date != activeTrackingDate(LocalDateTime.now())) return

        viewModelScope.launch {
            val existing = dao.findCompletion(habitId, date.toString())
            if (existing == null) {
                dao.upsertCompletion(HabitCompletionEntity(habitId = habitId, date = date.toString()))
            } else {
                dao.deleteCompletion(habitId, date.toString())
            }
        }
    }

    /**
     * Toggle habit for a past date (within the last 3 days).
     * Called from the long-press confirmation dialog.
     */
    fun togglePastHabit(habitId: Int, date: LocalDate) {
        val today = activeTrackingDate(LocalDateTime.now())
        val threeDaysAgo = today.minusDays(3)
        // Only allow editing for dates within the past 3 days (exclusive of today which uses normal toggle)
        if (date.isBefore(threeDaysAgo) || date.isAfter(today)) return

        viewModelScope.launch {
            val existing = dao.findCompletion(habitId, date.toString())
            if (existing == null) {
                dao.upsertCompletion(HabitCompletionEntity(habitId = habitId, date = date.toString()))
            } else {
                dao.deleteCompletion(habitId, date.toString())
            }
        }
    }

    fun showPreviousMonth() {
        selectedMonth.value = selectedMonth.value.minusMonths(1)
    }

    fun showNextMonth() {
        val currentMonth = YearMonth.now()
        if (selectedMonth.value < currentMonth) {
            selectedMonth.value = selectedMonth.value.plusMonths(1)
        }
    }
}

@Composable
fun HabitTrackerApp() {
    MaterialTheme(colorScheme = AppColors) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val trackerViewModel: HabitTrackerViewModel = viewModel(
                factory = ViewModelProvider.AndroidViewModelFactory.getInstance(
                    LocalContext.current.applicationContext as Application
                )
            )
            val state by trackerViewModel.uiState.collectAsState()

            HabitTrackerScreen(
                state = state,
                onAddHabit = trackerViewModel::addHabit,
                onDeleteHabit = trackerViewModel::deleteHabit,
                onToggleHabit = trackerViewModel::toggleHabit,
                onTogglePastHabit = trackerViewModel::togglePastHabit,
                onPreviousMonth = trackerViewModel::showPreviousMonth,
                onNextMonth = trackerViewModel::showNextMonth
            )
        }
    }
}

@Composable
private fun HabitTrackerScreen(
    state: HabitTrackerUiState,
    onAddHabit: (String) -> Unit,
    onDeleteHabit: (Int) -> Unit,
    onToggleHabit: (Int, LocalDate) -> Unit,
    onTogglePastHabit: (Int, LocalDate) -> Unit,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit
) {
    val currentDateTime by produceState(initialValue = LocalDateTime.now()) {
        while (true) {
            value = LocalDateTime.now()
            delay(60_000)
        }
    }
    val days = remember(state.selectedMonth) { daysInMonth(state.selectedMonth) }
    val activeTrackingDate = remember(currentDateTime) { activeTrackingDate(currentDateTime) }
    val todayDoneCount = state.dailyTotals[activeTrackingDate] ?: 0
    var habitName by rememberSaveable { mutableStateOf("") }

    Scaffold { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            HeaderCard(
                selectedMonth = state.selectedMonth,
                todayDoneCount = todayDoneCount,
                habitCount = state.habitRows.size,
                activeTrackingDate = activeTrackingDate,
                onPreviousMonth = onPreviousMonth,
                onNextMonth = onNextMonth
            )

            AddHabitCard(
                habitName = habitName,
                onHabitNameChange = { habitName = it },
                onAddHabit = {
                    onAddHabit(habitName)
                    habitName = ""
                }
            )

            MonthTrackerCard(
                days = days,
                habitRows = state.habitRows,
                activeTrackingDate = activeTrackingDate,
                onDeleteHabit = onDeleteHabit,
                onToggleHabit = onToggleHabit,
                onTogglePastHabit = onTogglePastHabit
            )

            ProgressGraphCard(
                days = days,
                dailyTotals = state.dailyTotals,
                todayDoneCount = todayDoneCount,
                activeTrackingDate = activeTrackingDate,
                habitCount = state.habitRows.size
            )

            ReminderSettingsCard()
        }
    }
}

@Composable
private fun HeaderCard(
    selectedMonth: YearMonth,
    todayDoneCount: Int,
    habitCount: Int,
    activeTrackingDate: LocalDate,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "Habit Pulse",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Track your habits, tick what you completed today, and watch the graph grow automatically.",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
            )
            Text(
                text = "Open day: ${activeTrackingDate.format(DateTimeFormatter.ofPattern("dd MMM"))}. After 1 AM, unfinished boxes turn red. Long-press (3s) any box from the past 3 days to change it.",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                fontSize = 13.sp
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                MonthButton(label = "Prev", onClick = onPreviousMonth)
                Text(
                    text = selectedMonth.format(DateTimeFormatter.ofPattern("MMMM yyyy")),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                MonthButton(label = "Next", onClick = onNextMonth)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricBadge(title = "Open day ticks", value = "$todayDoneCount / $habitCount")
                MetricBadge(title = "Active habits", value = "$habitCount")
            }
        }
    }
}

@Composable
private fun MonthButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
    ) {
        Text(text = label)
    }
}

@Composable
private fun MetricBadge(title: String, value: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
            )
            Text(text = value, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun AddHabitCard(
    habitName: String,
    onHabitNameChange: (String) -> Unit,
    onAddHabit: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Add habit",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            OutlinedTextField(
                value = habitName,
                onValueChange = onHabitNameChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Habit name") },
                placeholder = { Text("Reading, workout, water...") }
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Add as many habits as you want.",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Button(onClick = onAddHabit, enabled = habitName.isNotBlank()) {
                    Text("Save")
                }
            }
        }
    }
}

@Composable
private fun MonthTrackerCard(
    days: List<LocalDate>,
    habitRows: List<HabitRowUi>,
    activeTrackingDate: LocalDate,
    onDeleteHabit: (Int) -> Unit,
    onToggleHabit: (Int, LocalDate) -> Unit,
    onTogglePastHabit: (Int, LocalDate) -> Unit
) {
    // Calculate the editable date range: past 3 days from the active tracking date
    val editableStartDate = remember(activeTrackingDate) { activeTrackingDate.minusDays(3) }

    // State for long-press confirmation dialog
    var longPressTarget by remember { mutableStateOf<Triple<Int, LocalDate, Boolean>?>(null) }

    // Long-press confirmation dialog
    longPressTarget?.let { (habitId, date, currentlyDone) ->
        val dateStr = date.format(DateTimeFormatter.ofPattern("dd MMM yyyy"))
        val actionText = if (currentlyDone) "mark as NOT completed" else "mark as completed"
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { longPressTarget = null },
            title = { Text("Change Past Completion") },
            text = {
                Text("Do you want to $actionText for $dateStr?")
            },
            confirmButton = {
                TextButton(onClick = {
                    onTogglePastHabit(habitId, date)
                    longPressTarget = null
                }) {
                    Text(
                        if (currentlyDone) "Mark Uncompleted" else "Mark Completed",
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { longPressTarget = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "Monthly tracker",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            if (habitRows.isEmpty()) {
                Text(
                    text = "Add your first habit to start ticking the month tracker.",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
                )
            } else {
                val horizontalState = rememberScrollState()
                val cellSize = 38.dp
                val labelColumnWidth = 132.dp
                val headerHeight = 42.dp
                val rowHeight = 76.dp
                val gridWidth = cellSize * days.size
                val density = LocalDensity.current

                // Auto-scroll to the active tracking date
                LaunchedEffect(activeTrackingDate, days) {
                    val dayIndex = days.indexOfFirst { it == activeTrackingDate }
                    if (dayIndex > 0) {
                        val cellPx = with(density) { cellSize.toPx() }
                        // Scroll so the active date is roughly centered
                        val targetScroll = (dayIndex * cellPx - cellPx * 2).toInt().coerceAtLeast(0)
                        horizontalState.scrollTo(targetScroll)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top
                ) {
                    Column(modifier = Modifier.width(labelColumnWidth)) {
                        Box(
                            modifier = Modifier.height(headerHeight),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Text(
                                text = "Habit",
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        habitRows.forEach { habit ->
                            HabitNameCell(
                                name = habit.name,
                                rowHeight = rowHeight,
                                onDelete = { onDeleteHabit(habit.id) }
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(horizontalState)
                    ) {
                        Column(modifier = Modifier.width(gridWidth)) {
                            Row(
                                modifier = Modifier.height(headerHeight),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                days.forEach { day ->
                                    DayHeaderCell(day = day, cellSize = cellSize)
                                }
                            }

                            habitRows.forEach { habit ->
                                Row(
                                    modifier = Modifier.height(rowHeight),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    days.forEach { day ->
                                        val isLocked = day.isBefore(activeTrackingDate)
                                        val isEditable = isLocked &&
                                            !day.isBefore(editableStartDate) &&
                                            day.isBefore(activeTrackingDate)
                                        TrackerDayCell(
                                            cellSize = cellSize,
                                            isDone = habit.completedDates.contains(day),
                                            isLocked = isLocked,
                                            isFuture = day.isAfter(activeTrackingDate),
                                            isEditable = isEditable,
                                            onClick = { onToggleHabit(habit.id, day) },
                                            onLongPress = {
                                                longPressTarget = Triple(
                                                    habit.id,
                                                    day,
                                                    habit.completedDates.contains(day)
                                                )
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HabitNameCell(name: String, rowHeight: Dp, onDelete: () -> Unit) {
    var showConfirmDialog by remember { mutableStateOf(false) }

    if (showConfirmDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            title = { Text("Remove Habit") },
            text = {
                Text("Are you sure you want to remove \"$name\"? All its tracking data will be deleted.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showConfirmDialog = false
                    onDelete()
                }) {
                    Text("Remove", color = MissedColor)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .width(132.dp)
            .height(rowHeight)
            .padding(end = 8.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = name,
            fontWeight = FontWeight.Medium,
            maxLines = 2
        )
        Spacer(modifier = Modifier.height(6.dp))
        TextButton(
            onClick = { showConfirmDialog = true },
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
        ) {
            Text("Remove")
        }
    }
}

@Composable
private fun DayHeaderCell(day: LocalDate, cellSize: Dp) {
    Column(
        modifier = Modifier.width(cellSize),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = day.dayOfMonth.toString(),
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
        Text(
            text = day.dayOfWeek.name.take(1),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackerDayCell(
    cellSize: Dp,
    isDone: Boolean,
    isLocked: Boolean,
    isFuture: Boolean,
    isEditable: Boolean = false,
    onClick: () -> Unit,
    onLongPress: () -> Unit = {}
) {
    // Editable past days get a distinct visual style
    val EditableColor = Color(0xFF3B82F6) // blue accent for editable past days

    val background = when {
        isDone && isEditable -> MaterialTheme.colorScheme.primary
        isDone -> MaterialTheme.colorScheme.primary
        isEditable && isLocked -> EditableColor.copy(alpha = 0.12f)
        isLocked -> MissedColor.copy(alpha = 0.18f)
        isFuture -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        else -> Color.Transparent
    }

    val contentColor = when {
        isDone -> MaterialTheme.colorScheme.onPrimary
        isEditable && isLocked -> EditableColor
        isLocked -> MissedColor
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
    }

    val borderColor = when {
        isDone && isEditable -> EditableColor
        isDone -> MaterialTheme.colorScheme.primary
        isEditable -> EditableColor.copy(alpha = 0.5f)
        isLocked -> MissedColor
        else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
    }

    Box(
        modifier = Modifier
            .padding(vertical = 4.dp)
            .size(cellSize)
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .border(
                width = if (isEditable) 1.5.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(12.dp)
            )
            .combinedClickable(
                enabled = !isFuture && (!isLocked || isEditable),
                onClick = {
                    if (!isLocked) onClick()
                    // Normal click on editable locked cells does nothing — must long-press
                },
                onLongClick = {
                    if (isEditable) onLongPress()
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = when {
                isDone -> "✓"
                isEditable && isLocked -> "·" // middle dot to hint it's editable
                isLocked -> "✕"
                else -> ""
            },
            fontWeight = FontWeight.Bold,
            color = contentColor
        )
    }
}

@Composable
private fun ProgressGraphCard(
    days: List<LocalDate>,
    dailyTotals: Map<LocalDate, Int>,
    todayDoneCount: Int,
    activeTrackingDate: LocalDate,
    habitCount: Int
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "Progress graph",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Shows your last 5 days of progress. Each bar displays your completed habit count.",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
            )
            MetricBadge(
                title = "Open day",
                value = "${activeTrackingDate.format(DateTimeFormatter.ofPattern("dd MMM"))}  $todayDoneCount / $habitCount"
            )
            DailyProgressGraph(
                days = days,
                dailyTotals = dailyTotals,
                activeTrackingDate = activeTrackingDate,
                habitCount = habitCount
            )
        }
    }
}

@Composable
private fun DailyProgressGraph(
    days: List<LocalDate>,
    dailyTotals: Map<LocalDate, Int>,
    activeTrackingDate: LocalDate,
    habitCount: Int
) {
    val chartHeight = 220.dp
    val plottedDays = remember(days, activeTrackingDate) { days.filter { !it.isAfter(activeTrackingDate) } }

    if (plottedDays.isEmpty()) {
        Text(
            text = "No progress to show yet for this month.",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
        return
    }

    val graphScrollState = rememberScrollState()
    val density = LocalDensity.current
    val barWidthWithSpacing = with(density) { (48.dp + 12.dp).toPx() }

    // Auto-scroll to active tracking date
    LaunchedEffect(activeTrackingDate, plottedDays) {
        val dayIndex = plottedDays.indexOfFirst { it == activeTrackingDate }
        if (dayIndex > 0) {
            val targetScroll = (dayIndex * barWidthWithSpacing - barWidthWithSpacing * 2).toInt().coerceAtLeast(0)
            graphScrollState.scrollTo(targetScroll)
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom
    ) {
        Column(
            modifier = Modifier.height(chartHeight),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.End
        ) {
            val maxLabel = habitCount.coerceAtLeast(1)
            val midLabel = maxLabel / 2
            Text("$maxLabel", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            Text("$midLabel", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            Text("0", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }

        Spacer(modifier = Modifier.width(10.dp))

        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(graphScrollState),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            plottedDays.forEach { day ->
                val value = dailyTotals[day] ?: 0
                DayProgressBar(
                    day = day,
                    value = value,
                    chartHeight = chartHeight,
                    habitCount = habitCount
                )
            }
        }
    }
}

@Composable
private fun DayProgressBar(day: LocalDate, value: Int, chartHeight: Dp, habitCount: Int) {
    val maxValue = habitCount.coerceAtLeast(1)
    val barHeight = remember(value, maxValue) {
        val minBarHeight = 32f // minimum height so the count label is always visible
        ((value.coerceIn(0, maxValue) / maxValue.toFloat()) * 160f).dp.coerceAtLeast(minBarHeight.dp)
    }

    val isToday = day == activeTrackingDate(LocalDateTime.now())

    Column(
        modifier = Modifier.widthIn(min = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Bottom
    ) {
        Box(
            modifier = Modifier.height(chartHeight - 40.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            Box(
                modifier = Modifier
                    .width(40.dp)
                    .height(barHeight)
                    .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                    .background(
                        if (isToday) MaterialTheme.colorScheme.primary
                        else if (value > 0) MaterialTheme.colorScheme.secondary
                        else MaterialTheme.colorScheme.surfaceVariant
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "$value",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = if (isToday) MaterialTheme.colorScheme.onPrimary
                            else if (value > 0) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = day.dayOfMonth.toString(),
            fontSize = 12.sp,
            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
            color = if (isToday) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
        )
        Text(
            text = day.dayOfWeek.name.take(3),
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
    }
}

@Composable
private fun ReminderSettingsCard() {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences("habit_prefs", Context.MODE_PRIVATE)
    }

    var reminderEnabled by remember {
        mutableStateOf(prefs.getBoolean("reminder_enabled", false))
    }

    fun saveAndSchedule() {
        prefs.edit()
            .putBoolean("reminder_enabled", reminderEnabled)
            .apply()
        if (reminderEnabled) {
            HabitReminderReceiver.schedule(context)
        } else {
            HabitReminderReceiver.cancel(context)
        }
    }

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "Auto Reminders",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Get reminded every 2.5 hours from 6:00 AM to 11:00 PM to complete your habits.",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Enable reminders",
                    fontWeight = FontWeight.Medium
                )
                Switch(
                    checked = reminderEnabled,
                    onCheckedChange = {
                        reminderEnabled = it
                        saveAndSchedule()
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary
                    )
                )
            }

            if (reminderEnabled) {
                Text(
                    text = "Schedule: 6:00 \u2022 8:30 \u2022 11:00 \u2022 13:30 \u2022 16:00 \u2022 18:30 \u2022 21:00 \u2022 23:00",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp
                )
                Text(
                    text = "You\u2019ll receive 8 reminders throughout the day, automatically.",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    fontSize = 13.sp
                )
            }
        }
    }
}

private fun daysInMonth(month: YearMonth): List<LocalDate> {
    return (1..month.lengthOfMonth()).map(month::atDay)
}

private fun activeTrackingDate(now: LocalDateTime): LocalDate {
    return if (now.toLocalTime().isBefore(DAY_LOCK_TIME)) {
        now.toLocalDate().minusDays(1)
    } else {
        now.toLocalDate()
    }
}

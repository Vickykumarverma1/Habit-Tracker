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
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
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

    /** All completions ever recorded — used for cross-month streak calculation. */
    @Query("SELECT * FROM habit_completions")
    fun observeAllCompletions(): kotlinx.coroutines.flow.Flow<List<HabitCompletionEntity>>

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

    @Query("UPDATE habits SET name = :newName WHERE id = :habitId")
    suspend fun updateHabitName(habitId: Int, newName: String)
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
    /** Completion counts across ALL months — used for cross-month streak calculation. */
    val allDailyTotals: Map<LocalDate, Int> = emptyMap(),
)

class HabitTrackerViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = HabitTrackerDatabase.get(application).dao()
    private val selectedMonth = MutableStateFlow(YearMonth.now())

    val uiState: StateFlow<HabitTrackerUiState> = combine(
        selectedMonth,
        dao.observeHabits(),
        selectedMonth.flatMapLatest { month ->
            dao.observeCompletionsForMonth(month.toString())
        },
        dao.observeAllCompletions()
    ) { month, habits, completions, allCompletions ->
        val completionsByHabit = completions
            .groupBy { it.habitId }
            .mapValues { (_, items) -> items.map { LocalDate.parse(it.date) }.toSet() }

        val dailyTotals = completions
            .map { LocalDate.parse(it.date) }
            .groupingBy { it }
            .eachCount()

        // All-time daily totals for cross-month streak calculation
        val allDailyTotals = allCompletions
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
            dailyTotals = dailyTotals,
            allDailyTotals = allDailyTotals
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

    fun renameHabit(habitId: Int, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            dao.updateHabitName(habitId, trimmed)
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
                onRenameHabit = trackerViewModel::renameHabit,
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
    onRenameHabit: (Int, String) -> Unit,
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
                onRenameHabit = onRenameHabit,
                onToggleHabit = onToggleHabit,
                onTogglePastHabit = onTogglePastHabit
            )

            ProgressGraphCard(
                days = days,
                dailyTotals = state.dailyTotals,
                allDailyTotals = state.allDailyTotals,
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
    onRenameHabit: (Int, String) -> Unit,
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
                                onDelete = { onDeleteHabit(habit.id) },
                                onRename = { newName -> onRenameHabit(habit.id, newName) }
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
private fun HabitNameCell(
    name: String,
    rowHeight: Dp,
    onDelete: () -> Unit,
    onRename: (String) -> Unit
) {
    var showConfirmDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var editedName by remember(name) { mutableStateOf(name) }

    // Delete confirmation dialog
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

    // Rename dialog
    if (showRenameDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {
                showRenameDialog = false
                editedName = name
            },
            title = { Text("Edit Habit Name") },
            text = {
                OutlinedTextField(
                    value = editedName,
                    onValueChange = { editedName = it },
                    singleLine = true,
                    label = { Text("Habit name") }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (editedName.isNotBlank()) {
                            onRename(editedName)
                        }
                        showRenameDialog = false
                    },
                    enabled = editedName.isNotBlank()
                ) {
                    Text("Save", color = MaterialTheme.colorScheme.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showRenameDialog = false
                    editedName = name
                }) {
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
        Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
            TextButton(
                onClick = { showRenameDialog = true },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
            ) {
                Text("Edit", color = MaterialTheme.colorScheme.primary)
            }
            TextButton(
                onClick = { showConfirmDialog = true },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
            ) {
                Text("Remove", color = MissedColor)
            }
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
    val background = when {
        isDone -> MaterialTheme.colorScheme.primary
        isLocked -> MissedColor.copy(alpha = 0.18f)
        isFuture -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        else -> Color.Transparent
    }

    val contentColor = when {
        isDone -> MaterialTheme.colorScheme.onPrimary
        isLocked -> MissedColor
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
    }

    Box(
        modifier = Modifier
            .padding(vertical = 4.dp)
            .size(cellSize)
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .border(
                width = 1.dp,
                color = when {
                    isDone -> MaterialTheme.colorScheme.primary
                    isLocked -> MissedColor
                    else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
                },
                shape = RoundedCornerShape(12.dp)
            )
            .combinedClickable(
                enabled = !isFuture && (!isLocked || isEditable),
                onClick = {
                    if (!isLocked) onClick()
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
    allDailyTotals: Map<LocalDate, Int>,
    todayDoneCount: Int,
    activeTrackingDate: LocalDate,
    habitCount: Int
) {
    // Compute streak: consecutive days (going backwards from activeTrackingDate) with >= 70% done.
    // Uses allDailyTotals so the streak carries across month boundaries (like LeetCode / GitHub).
    val streak = remember(allDailyTotals, activeTrackingDate, habitCount) {
        if (habitCount == 0) return@remember 0
        val threshold = (habitCount * 0.7f).toInt().coerceAtLeast(1)
        var count = 0
        var checkDay = activeTrackingDate
        while (true) {
            val done = allDailyTotals[checkDay] ?: 0
            if (done >= threshold) {
                count++
                checkDay = checkDay.minusDays(1)
            } else {
                break
            }
        }
        count
    }

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Progress graph",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                if (streak > 0) {
                    Surface(
                        color = Color(0xFFFF6B35).copy(alpha = 0.15f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(text = "\uD83D\uDD25", fontSize = 16.sp)
                            Text(
                                text = "$streak day streak!",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFBF4C00)
                            )
                        }
                    }
                }
            }
            Text(
                text = "Tracks your daily progress as a continuous slope. Each point shows your completed habit count.",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricBadge(
                    title = "Open day",
                    value = "${activeTrackingDate.format(DateTimeFormatter.ofPattern("dd MMM"))}  $todayDoneCount / $habitCount"
                )
                if (streak >= 3) {
                    MetricBadge(
                        title = "Best streak",
                        value = "\uD83D\uDD25 $streak days"
                    )
                }
            }
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
    val chartHeight = 240.dp
    val plottedDays = remember(days, activeTrackingDate) { days.filter { !it.isAfter(activeTrackingDate) } }

    if (plottedDays.isEmpty()) {
        Text(
            text = "No progress to show yet for this month.",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
        return
    }

    // If there's only one real data point (day 1 of month), prepend a virtual Day 0 anchor at value=0
    // so the graph draws a slope line instead of a lone dot.
    val hasVirtualOrigin = plottedDays.size == 1
    val virtualOriginDay: LocalDate? = if (hasVirtualOrigin) plottedDays[0].minusDays(1) else null

    val graphScrollState = rememberScrollState()
    val density = LocalDensity.current
    val pointSpacing = 60.dp
    val pointSpacingPx = with(density) { pointSpacing.toPx() }
    val paddingLeft = with(density) { 16.dp.toPx() }
    val paddingRight = with(density) { 16.dp.toPx() }
    val paddingTop = with(density) { 32.dp.toPx() }
    val paddingBottom = with(density) { 48.dp.toPx() }

    // For width: if virtual origin is added, count it as an extra spacing slot
    val realPointCount = plottedDays.size
    val totalSlots = if (hasVirtualOrigin) realPointCount else (realPointCount - 1).coerceAtLeast(0)
    val totalGraphWidth = paddingLeft + paddingRight + totalSlots * pointSpacingPx
    val totalGraphWidthDp = with(density) { totalGraphWidth.toDp() }.coerceAtLeast(200.dp)

    val maxValue = habitCount.coerceAtLeast(1)

    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary
    val surfaceTextColor = MaterialTheme.colorScheme.onSurface
    val todayColor = MaterialTheme.colorScheme.primary
    val gridLineColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)

    // Auto-scroll to active tracking date (offset by 1 if virtual origin exists)
    LaunchedEffect(activeTrackingDate, plottedDays, hasVirtualOrigin) {
        val dayIndex = plottedDays.indexOfFirst { it == activeTrackingDate }
        if (dayIndex >= 0) {
            val adjustedIndex = if (hasVirtualOrigin) dayIndex + 1 else dayIndex
            if (adjustedIndex > 0) {
                val targetScroll = (adjustedIndex * pointSpacingPx - pointSpacingPx * 3).toInt().coerceAtLeast(0)
                graphScrollState.scrollTo(targetScroll)
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom
    ) {
        // Y-axis labels
        Column(
            modifier = Modifier.height(chartHeight),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.End
        ) {
            val midLabel = maxValue / 2
            Text("$maxValue", color = surfaceTextColor.copy(alpha = 0.6f), fontSize = 12.sp)
            Text("$midLabel", color = surfaceTextColor.copy(alpha = 0.6f), fontSize = 12.sp)
            Text("0", color = surfaceTextColor.copy(alpha = 0.6f), fontSize = 12.sp)
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Scrollable chart area
        Box(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(graphScrollState)
        ) {
            Canvas(
                modifier = Modifier
                    .width(totalGraphWidthDp)
                    .height(chartHeight)
            ) {
                val canvasWidth = size.width
                val canvasHeight = size.height
                val drawAreaHeight = canvasHeight - paddingTop - paddingBottom
                val drawAreaBottom = canvasHeight - paddingBottom

                // Draw horizontal grid lines
                for (i in 0..4) {
                    val y = drawAreaBottom - (drawAreaHeight * i / 4f)
                    drawLine(
                        color = gridLineColor,
                        start = Offset(paddingLeft, y),
                        end = Offset(canvasWidth - paddingRight, y),
                        strokeWidth = 1f
                    )
                }

                // Calculate data points — prepend virtual Day 0 anchor if needed
                val allPointDays: List<LocalDate?> = if (hasVirtualOrigin) {
                    listOf(null) + plottedDays   // null = virtual origin
                } else {
                    plottedDays.map { it as LocalDate? }
                }
                val points = allPointDays.mapIndexed { index, day ->
                    val value = if (day == null) 0 else (dailyTotals[day] ?: 0)
                    val x = paddingLeft + index * pointSpacingPx
                    val fraction = value.toFloat() / maxValue
                    val y = drawAreaBottom - (fraction * drawAreaHeight)
                    Triple(x, y, value)
                }

                // Build smooth curve path using Catmull-Rom to cubic Bezier conversion
                val curvePath = Path()
                curvePath.moveTo(points[0].first, points[0].second)

                for (i in 0 until points.size - 1) {
                    val p0 = if (i > 0) points[i - 1] else points[i]
                    val p1 = points[i]
                    val p2 = points[i + 1]
                    val p3 = if (i + 2 < points.size) points[i + 2] else points[i + 1]

                    val tension = 0.3f
                    val cp1x = p1.first + (p2.first - p0.first) * tension
                    val cp1y = p1.second + (p2.second - p0.second) * tension
                    val cp2x = p2.first - (p3.first - p1.first) * tension
                    val cp2y = p2.second - (p3.second - p1.second) * tension

                    curvePath.cubicTo(cp1x, cp1y, cp2x, cp2y, p2.first, p2.second)
                }

                // Draw filled area under the curve
                val fillPath = Path()
                fillPath.addPath(curvePath)
                fillPath.lineTo(points.last().first, drawAreaBottom)
                fillPath.lineTo(points.first().first, drawAreaBottom)
                fillPath.close()

                drawPath(
                    path = fillPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            primaryColor.copy(alpha = 0.35f),
                            primaryColor.copy(alpha = 0.05f)
                        ),
                        startY = paddingTop,
                        endY = drawAreaBottom
                    )
                )

                // Draw the curve line
                drawPath(
                    path = curvePath,
                    color = primaryColor,
                    style = Stroke(
                        width = 6f,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )

                // Draw data points, count labels, and day labels
                // If virtual origin exists, index 0 is the fake anchor — skip dot/label for it
                points.forEachIndexed { index, (x, y, value) ->
                    val isVirtualPoint = hasVirtualOrigin && index == 0
                    val day: LocalDate? = if (isVirtualPoint) null else plottedDays[if (hasVirtualOrigin) index - 1 else index]
                    val isToday = day == activeTrackingDate

                    // Skip decorations for the virtual origin anchor
                    if (isVirtualPoint) return@forEachIndexed

                    // Outer glow circle for today
                    if (isToday) {
                        drawCircle(
                            color = todayColor.copy(alpha = 0.2f),
                            radius = 18f,
                            center = Offset(x, y)
                        )
                    }

                    // Data point dot
                    drawCircle(
                        color = Color.White,
                        radius = 10f,
                        center = Offset(x, y)
                    )
                    drawCircle(
                        color = if (isToday) todayColor else secondaryColor,
                        radius = 7f,
                        center = Offset(x, y)
                    )

                    // Completion count label above the dot
                    drawContext.canvas.nativeCanvas.apply {
                        val paint = android.graphics.Paint().apply {
                            textAlign = android.graphics.Paint.Align.CENTER
                            textSize = 32f
                            isFakeBoldText = true
                            color = if (isToday) android.graphics.Color.parseColor("#2B6E4F")
                                    else android.graphics.Color.parseColor("#1E1B18")
                            isAntiAlias = true
                        }
                        // Draw a small rounded rect background behind the count
                        val textBounds = android.graphics.Rect()
                        paint.getTextBounds("$value", 0, "$value".length, textBounds)
                        val bgPaint = android.graphics.Paint().apply {
                            color = android.graphics.Color.parseColor("#FFFBF4")
                            isAntiAlias = true
                        }
                        val labelY = y - 24f
                        val bgLeft = x - textBounds.width() / 2f - 8f
                        val bgTop = labelY - textBounds.height() - 4f
                        val bgRight = x + textBounds.width() / 2f + 8f
                        val bgBottom = labelY + 6f
                        drawRoundRect(
                            bgLeft, bgTop, bgRight, bgBottom,
                            12f, 12f, bgPaint
                        )
                        drawText("$value", x, labelY, paint)
                    }

                    // Day number below the chart
                    drawContext.canvas.nativeCanvas.apply {
                        val paint = android.graphics.Paint().apply {
                            textAlign = android.graphics.Paint.Align.CENTER
                            textSize = 28f
                            isFakeBoldText = isToday
                            color = if (isToday) android.graphics.Color.parseColor("#2B6E4F")
                                    else android.graphics.Color.parseColor("#6B6560")
                            isAntiAlias = true
                        }
                        drawText(day!!.dayOfMonth.toString(), x, drawAreaBottom + 30f, paint)
                    }

                    // Day-of-week abbreviation below day number
                    drawContext.canvas.nativeCanvas.apply {
                        val paint = android.graphics.Paint().apply {
                            textAlign = android.graphics.Paint.Align.CENTER
                            textSize = 22f
                            color = android.graphics.Color.parseColor("#9E9890")
                            isAntiAlias = true
                        }
                        drawText(day!!.dayOfWeek.name.take(3), x, drawAreaBottom + 48f, paint)
                    }
                }
            }
        }
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

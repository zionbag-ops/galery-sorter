package com.zion.gallerysorter

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class HomeState(
    val galleryCount: Int = 0,
    val stats: Stats = Stats(0, 0.0, 0, 0),
    val counts: Map<Category, Int> = emptyMap(),
    val unsure: Int = 0,
    val running: Boolean = false,
    val waitingForCharger: Boolean = false,
    val progressDone: Int = 0,
    val progressTotal: Int = 0,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val db = PhotoDb.get(app)
    private val _home = MutableStateFlow(HomeState())
    val home: StateFlow<HomeState> = _home

    private val _photos = MutableStateFlow<List<PhotoRow>>(emptyList())
    val photos: StateFlow<List<PhotoRow>> = _photos

    init {
        viewModelScope.launch {
            WorkManager.getInstance(app)
                .getWorkInfosForUniqueWorkFlow(ScanWorker.UNIQUE)
                .collect { infos ->
                    val info = infos.firstOrNull()
                    val state = info?.state
                    val running = state == WorkInfo.State.RUNNING
                    _home.value = _home.value.copy(
                        running = running,
                        waitingForCharger = state == WorkInfo.State.ENQUEUED,
                        progressDone = info?.progress?.getInt(ScanWorker.KEY_DONE, 0) ?: 0,
                        progressTotal = info?.progress?.getInt(ScanWorker.KEY_TOTAL, 0) ?: 0,
                    )
                    refresh()
                }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val fresh = withContext(Dispatchers.IO) {
                val app = getApplication<Application>()
                Triple(
                    runCatching { MediaQuery.count(app) }.getOrDefault(0),
                    db.stats(),
                    db.counts() to db.unsureCount()
                )
            }
            _home.value = _home.value.copy(
                galleryCount = fresh.first,
                stats = fresh.second,
                counts = fresh.third.first,
                unsure = fresh.third.second,
            )
        }
    }

    fun startTrial() = ScanWorker.start(getApplication(), limit = 200, onlyWhileCharging = false)

    fun startFull(onlyWhileCharging: Boolean) =
        ScanWorker.start(getApplication(), limit = 0, onlyWhileCharging = onlyWhileCharging)

    fun stop() = ScanWorker.stop(getApplication())

    fun loadPhotos(category: Category?, unsure: Boolean) {
        viewModelScope.launch {
            _photos.value = withContext(Dispatchers.IO) { db.list(category, unsure) }
        }
    }

    fun correct(row: PhotoRow, category: Category, reloadCategory: Category?, reloadUnsure: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { db.setUserCategory(row.id, category) }
            loadPhotos(reloadCategory, reloadUnsure)
            refresh()
        }
    }

    /** Plain-text report the user can send back, to tune the rules for phase 2. */
    suspend fun report(): String = withContext(Dispatchers.IO) {
        val s = db.stats()
        val counts = db.counts()
        val sb = StringBuilder()
        sb.appendLine("דוח מסדר גלריה — שלב 1")
        sb.appendLine("נסרקו: ${s.processed} · זמן ממוצע לתמונה: ${s.avgMs.toInt()} ms")
        sb.appendLine("נבדקו ידנית: ${s.reviewed} · נכונות: ${s.reviewedCorrect}")
        sb.appendLine()
        sb.appendLine("תיקיות:")
        Category.entries.forEach { c -> sb.appendLine("  ${c.he}: ${counts[c] ?: 0}") }
        sb.appendLine("  לא בטוח: ${db.unsureCount()}")
        sb.appendLine()
        sb.appendLine("בדיקה לפי תיקייה (נבדקו / נכונות):")
        db.reviewByCategory().forEach { (c, n, ok) -> sb.appendLine("  ${c.he}: $n / $ok") }
        sb.appendLine()
        sb.appendLine("התוויות הנפוצות:")
        db.labelFrequency(50).forEach { (l, n) -> sb.appendLine("  $l: $n") }
        sb.toString()
    }
}

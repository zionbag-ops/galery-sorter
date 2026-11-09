package com.zion.gallerysorter

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

private val Blue = Color(0xFF1565C0)
private val Teal = Color(0xFF00897B)
private val Amber = Color(0xFFFFB300)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Blue, secondary = Teal)) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Surface(Modifier.fillMaxSize(), color = Color(0xFFF5F7FB)) {
                        App()
                    }
                }
            }
        }
    }
}

private sealed class Screen {
    data object Home : Screen()
    data class Folder(val title: String, val category: Category?, val unsure: Boolean) : Screen()
}

private fun photoPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.POST_NOTIFICATIONS)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

private fun hasPhotoAccess(context: android.content.Context): Boolean {
    val p = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
    return ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
}

@Composable
private fun App(vm: AppViewModel = viewModel()) {
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    when (val s = screen) {
        is Screen.Home -> HomeScreen(vm) { screen = it }
        is Screen.Folder -> {
            BackHandler { screen = Screen.Home; vm.refresh() }
            FolderScreen(vm, s) { screen = Screen.Home; vm.refresh() }
        }
    }
}

@Composable
private fun HomeScreen(vm: AppViewModel, open: (Screen) -> Unit) {
    val context = LocalContext.current
    val state by vm.home.collectAsStateWithLifecycle()
    var granted by remember { mutableStateOf(hasPhotoAccess(context)) }
    var onlyCharging by remember { mutableStateOf(true) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        granted = hasPhotoAccess(context)
        vm.refresh()
    }
    LaunchedEffect(Unit) { vm.refresh() }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("מסדר גלריה", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Blue)
        Text(
            "שלב 1: רק קורא וממיין לאלבומים בתוך האפליקציה. שום קובץ לא זז ולא נמחק.",
            color = Color(0xFF455A64)
        )

        if (!granted) {
            InfoCard(Color(0xFFFFF3E0)) {
                Text("צריך גישה לכל התמונות", fontWeight = FontWeight.Bold)
                Text("אם אנדרואיד שואל — בחר \"לאפשר הכול\", לא \"תמונות נבחרות\". בלי זה אין מיון.")
                Spacer(Modifier.height(8.dp))
                Button(onClick = { launcher.launch(photoPermissions()) }) { Text("לתת הרשאה") }
            }
        } else {
            GrantedContent(vm, state, onlyCharging, { onlyCharging = it }, open)
        }
    }
}

@Composable
private fun GrantedContent(
    vm: AppViewModel,
    state: HomeState,
    onlyCharging: Boolean,
    setOnlyCharging: (Boolean) -> Unit,
    open: (Screen) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        InfoCard(Color.White) {
            Text("בגלריה: ${state.galleryCount} תמונות · נסרקו: ${state.stats.processed}")
            when {
                state.running -> {
                    val total = state.progressTotal.coerceAtLeast(1)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { state.progressDone.toFloat() / total },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("ממיין… ${state.progressDone} מתוך ${state.progressTotal}")
                    OutlinedButton(onClick = { vm.stop() }) { Text("עצור") }
                }
                state.waitingForCharger -> {
                    Text("הסריקה ממתינה למטען. חבר את הטלפון לחשמל והיא תתחיל לבד.", color = Teal)
                    OutlinedButton(onClick = { vm.stop() }) { Text("בטל") }
                }
                else -> {
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { vm.startTrial() }, modifier = Modifier.fillMaxWidth()) {
                        Text("הרצת ניסיון: 200 תמונות")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = onlyCharging, onCheckedChange = setOnlyCharging)
                        Text("סריקה מלאה רק כשהטלפון בטעינה")
                    }
                    OutlinedButton(
                        onClick = { vm.startFull(onlyCharging) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("סריקה מלאה של כל הגלריה") }
                }
            }
        }

        if (state.stats.processed > 0) {
            StatsCard(state)
            Text("תיקיות", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Category.entries.forEach { c ->
                val n = state.counts[c] ?: 0
                FolderRow(c.he, n, Blue) { open(Screen.Folder(c.he, c, false)) }
            }
            FolderRow("לא בטוח · יעבור ל-Gemini בשלב 3", state.unsure, Amber) {
                open(Screen.Folder("לא בטוח", null, true))
            }
            Spacer(Modifier.height(4.dp))
            OutlinedButton(
                onClick = {
                    scope.launch {
                        val text = vm.report()
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, text)
                        }
                        context.startActivity(Intent.createChooser(send, "שליחת דוח"))
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("שליחת דוח (לשיפור הכללים)") }
        }
    }
}

@Composable
private fun StatsCard(state: HomeState) {
    val s = state.stats
    InfoCard(Color.White) {
        val per1000 = s.avgMs * 1000 / 60000.0
        val remaining = (state.galleryCount - s.processed).coerceAtLeast(0)
        val remainingMin = s.avgMs * remaining / 60000.0
        Text("מדידות", fontWeight = FontWeight.Bold)
        Text("זמן ממוצע לתמונה: ${s.avgMs.toInt()} ms")
        Text("ל-1,000 תמונות: כ-${per1000.toInt().coerceAtLeast(1)} דקות")
        if (remaining > 0) Text("לשאר הגלריה ($remaining): כ-${remainingMin.toInt().coerceAtLeast(1)} דקות")
        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        if (s.reviewed == 0) {
            Text("דיוק: עוד לא בדקת. פתח תיקייה, גע בתמונה וסמן נכון או תקן.")
        } else {
            val pct = s.reviewedCorrect * 100 / s.reviewed
            val color = if (pct >= 85) Teal else Color(0xFFC62828)
            Text(
                "דיוק לפי הבדיקה שלך: $pct% (נבדקו ${s.reviewed}) · היעד: 85%",
                color = color,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun InfoCard(bg: Color, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = bg),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) { content() }
    }
}

@Composable
private fun FolderRow(title: String, count: Int, accent: Color, onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = count > 0, onClick = onClick)
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .padding(end = 12.dp)
                    .height(24.dp)
                    .aspectRatio(0.25f)
                    .background(accent)
            )
            Text(title, Modifier.weight(1f), fontSize = 17.sp)
            Text("$count", fontWeight = FontWeight.Bold, fontSize = 17.sp)
        }
    }
}

@Composable
private fun FolderScreen(vm: AppViewModel, s: Screen.Folder, back: () -> Unit) {
    val photos by vm.photos.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<PhotoRow?>(null) }
    LaunchedEffect(s) { vm.loadPhotos(s.category, s.unsure) }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = back) { Text("→ חזרה") }
            Text(
                "${s.title} (${photos.size})",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Text(
            "גע בתמונה כדי לסמן אם המיון נכון.",
            Modifier.padding(horizontal = 16.dp),
            color = Color(0xFF455A64)
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier
                .fillMaxSize()
                .padding(4.dp)
        ) {
            items(photos, key = { it.id }) { row ->
                Box(
                    Modifier
                        .padding(2.dp)
                        .aspectRatio(1f)
                        .clickable { selected = row }
                ) {
                    AsyncImage(
                        model = row.uri,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    if (row.reviewed) {
                        Text(
                            "✓",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .background(Teal)
                                .padding(horizontal = 6.dp)
                        )
                    }
                }
            }
        }
    }

    selected?.let { row ->
        PhotoDialog(
            row = row,
            onDismiss = { selected = null },
            onPick = { cat ->
                vm.correct(row, cat, s.category, s.unsure)
                selected = null
            }
        )
    }
}

@Composable
private fun PhotoDialog(row: PhotoRow, onDismiss: () -> Unit, onPick: (Category) -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(
                    Modifier
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    AsyncImage(
                        model = row.uri,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(260.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "מוין ל: ${row.category.he} · ביטחון ${(row.confidence * 100).toInt()}%",
                        fontWeight = FontWeight.Bold
                    )
                    row.userCategory?.let { Text("תיקנת ל: ${it.he}", color = Teal) }
                    Text("פנים: ${row.faces} · שורות טקסט: ${row.textLines} · ${row.ms} ms", fontSize = 13.sp)
                    if (row.labels.isNotBlank()) {
                        Text("זוהה: ${row.labels.replace("|", ", ")}", fontSize = 13.sp, color = Color(0xFF607D8B))
                    }
                    Text(row.path + row.name, fontSize = 11.sp, color = Color(0xFF90A4AE))
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = { onPick(row.category) }, modifier = Modifier.fillMaxWidth()) {
                        Text("נכון ✓")
                    }
                    Text("לא נכון? המקום הנכון:", Modifier.padding(top = 8.dp))
                    Category.entries.filter { it != row.category }.forEach { c ->
                        TextButton(onClick = { onPick(c) }, modifier = Modifier.fillMaxWidth()) {
                            Text(c.he)
                        }
                    }
                }
            }
        }
    }
}

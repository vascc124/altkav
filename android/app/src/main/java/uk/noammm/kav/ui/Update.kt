package uk.noammm.kav.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch
import uk.noammm.kav.KavModel
import uk.noammm.kav.R
import uk.noammm.kav.data.Updates

@Composable
fun UpdatePrompt(model: KavModel) {
    val release = model.update ?: return
    if (model.updateDismissed) return
    val ctx = LocalContext.current
    Dialog(onDismissRequest = { model.updateDismissed = true }) {
        Column(
            Modifier.fillMaxWidth().panel(K.rCard, solid = true).padding(K.gap5),
            verticalArrangement = Arrangement.spacedBy(K.gap4),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(K.gap4)) {
                AppIcon(56.dp)
                Column {
                    Text("AltKav+", fontSize = 20.sp, color = K.text, fontWeight = FontWeight.SemiBold)
                    Text(
                        T.ltr("${Updates.installedVersion(ctx)} → ${release.version}"),
                        fontSize = 15.sp, color = K.accent, fontWeight = FontWeight.Medium,
                    )
                }
            }
            ReleaseNotes(release, maxHeight = 260.dp)
            UpdateProgress(model)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(K.gap2)) {
                Box(
                    Modifier.weight(1f).heightIn(min = 46.dp).panel(K.rPill)
                        .clickable(role = Role.Button) { model.updateDismissed = true },
                    contentAlignment = Alignment.Center,
                ) { Text(T("No", "לא"), fontSize = 15.sp, color = K.text) }
                UpdateButton(model, Modifier.weight(1f)) { model.startUpdate(ctx) }
            }
        }
    }
}

@Composable
private fun AppIcon(size: androidx.compose.ui.unit.Dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(size / 4)), contentAlignment = Alignment.Center) {
        Image(
            painterResource(R.drawable.ic_launcher_background), contentDescription = null,
            modifier = Modifier.fillMaxSize().scale(1.5f),
        )
        Image(
            painterResource(R.drawable.ic_launcher_foreground), contentDescription = "AltKav+",
            modifier = Modifier.fillMaxSize().scale(1.5f),
        )
    }
}

@Composable
private fun ReleaseNotes(release: Updates.Release, maxHeight: androidx.compose.ui.unit.Dp) {
    val notes = release.notes.trim().ifBlank { release.name.ifBlank { T("No release notes.", "אין הערות גרסה.") } }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(0.5.dp, K.text.copy(alpha = .14f), RoundedCornerShape(14.dp))
            .heightIn(max = maxHeight).verticalScroll(rememberScrollState()).padding(K.gap3),
    ) {
        Text(notes, fontSize = 13.sp, color = K.muted, lineHeight = 19.sp)
    }
}

@Composable
private fun UpdateProgress(model: KavModel) {
    model.updateProgress?.let { p ->
        Column(verticalArrangement = Arrangement.spacedBy(K.gap1)) {
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(999.dp)).background(K.surface4)) {
                Box(Modifier.fillMaxWidth(p.coerceIn(0.02f, 1f)).fillMaxHeight().background(K.accent))
            }
            Text(
                if (p >= 1f) T("Opening the installer…", "פותחים את ההתקנה…")
                else T("Downloading… ${(p * 100).toInt()}%", "מורידים… ${(p * 100).toInt()}%"),
                fontSize = 12.sp, color = K.dim,
            )
        }
    }
    model.updateError?.let { Text(T("Could not update: $it", "העדכון נכשל: $it"), fontSize = 12.sp, color = K.critical, lineHeight = 17.sp) }
}

@Composable
private fun UpdateButton(model: KavModel, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val busy = model.updateProgress != null
    Box(
        modifier.heightIn(min = 46.dp).clip(RoundedCornerShape(K.rPill))
            .background(if (busy) K.surface4 else K.accent)
            .clickable(enabled = !busy, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (busy) T("Updating…", "מעדכנים…") else T("Update", "עדכון"), fontSize = 15.sp,
            color = if (busy) K.muted else K.onAccent, fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
fun UpdateSection(model: KavModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val release = model.update
    var checking by remember { mutableStateOf(false) }
    Column(
        Modifier.padding(horizontal = K.gap3).fillMaxWidth().panel(14.dp)
            .padding(K.gap3),
        verticalArrangement = Arrangement.spacedBy(K.gap3),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(K.gap3)) {
            AppIcon(40.dp)
            Column(Modifier.weight(1f)) {
                Text(T.ltr("AltKav+ ${Updates.installedVersion(ctx)}"), fontSize = 14.sp, color = K.text)
                Text(
                    when {
                        release != null -> T("${release.version} is available", "גרסה ${release.version} זמינה")
                        checking -> T("Looking…", "בודקים…")
                        model.updateFailed -> T("Couldn't check for updates", "לא הצלחנו לבדוק אם יש עדכונים")
                        model.updateChecked -> T("This is the newest release", "זו הגרסה העדכנית ביותר")
                        else -> T("Not checked yet", "עוד לא נבדק")
                    },
                    fontSize = 12.sp, color = if (release != null) K.accent else K.dim,
                )
            }
            if (release != null) UpdateButton(model) { model.startUpdate(ctx) }
            else Chip(if (checking) T("Checking…", "בודקים…") else T("Check for updates", "בדקו עדכונים"), false) {
                if (checking) return@Chip
                scope.launch {
                    checking = true
                    model.checkForUpdate(ctx)
                    checking = false
                }
            }
        }
        if (release != null) ReleaseNotes(release, maxHeight = 200.dp)
        UpdateProgress(model)
    }
}

// Kav+: which week's timetable is in use, and a way to fetch this week's now.
@Composable
fun TimetableSection() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var since by remember { mutableStateOf(uk.noammm.kav.data.TimetableUpdate.inUseSince(ctx)) }
    var status by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    val day = java.text.SimpleDateFormat("d/M/yyyy", java.util.Locale.US).apply { timeZone = uk.noammm.kav.ui.ISRAEL }
    Column(
        Modifier.padding(horizontal = K.gap3).fillMaxWidth().panel(14.dp).padding(K.gap3),
        verticalArrangement = Arrangement.spacedBy(K.gap2),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(K.gap3)) {
            Column(Modifier.weight(1f)) {
                Text(T("Timetable", "לוח זמנים"), fontSize = 14.sp, color = K.text)
                Text(
                    status ?: T("Built ${day.format(java.util.Date(since))}", "נבנה ב-${day.format(java.util.Date(since))}"),
                    fontSize = 12.sp, color = K.dim,
                )
            }
            Chip(if (checking) T("Checking…", "בודקים…") else T("Check now", "בדקו עכשיו"), false) {
                if (checking) return@Chip
                scope.launch {
                    checking = true
                    val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching { uk.noammm.kav.data.TimetableUpdate.check(ctx, force = true) }
                    }
                    status = when {
                        r.isFailure -> T("Couldn't check", "לא הצלחנו לבדוק")
                        r.getOrNull() == true -> T("A newer week was downloaded. Close and reopen AltKav+ to use it.", "הורד שבוע חדש יותר. סגרו ופתחו מחדש את AltKav+ כדי להשתמש בו.")
                        else -> T("Up to date", "מעודכן")
                    }
                    since = uk.noammm.kav.data.TimetableUpdate.inUseSince(ctx)
                    checking = false
                }
            }
        }
        Text(
            T(
                "Rebuilt every Saturday night from the Ministry of Transport feed; AltKav+ fetches it by itself, at most once a day.",
                "נבנה מחדש בכל מוצאי שבת מנתוני משרד התחבורה; AltKav+ מוריד אותו לבד, לכל היותר פעם ביום.",
            ),
            fontSize = 11.sp, color = K.dim, lineHeight = 16.sp,
        )
    }
}

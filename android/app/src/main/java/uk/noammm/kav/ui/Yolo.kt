@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package uk.noammm.kav.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uk.noammm.kav.KavModel
import uk.noammm.kav.data.MoovitLink
import uk.noammm.kav.data.OfflinePlanner
import uk.noammm.kav.data.Yolo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// AltKav+: YOLO. Somewhere worth going, by public transport: a Shabbat outing on the weekend lines, nature
// any day, a line that goes far with few stops, or a surprise. Ranked with the weather for that day.
private val hmY = SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = ISRAEL }
private val dayY = SimpleDateFormat("EEE", Locale("he")).apply { timeZone = ISRAEL }

private fun kindLabel(kind: String) = when (kind) {
    "reserve" -> T("🌿 Nature reserve / park", "🌿 שמורה / גן לאומי")
    "park" -> T("🌳 Park", "🌳 פארק")
    "forest" -> T("🌲 Forest", "🌲 יער")
    "beach" -> T("🏖 Beach", "🏖 חוף")
    "water" -> T("💧 Spring / waterfall", "💧 מעיין / מפל")
    "view" -> T("🔭 Viewpoint", "🔭 תצפית")
    "museum" -> T("🏛 Museum", "🏛 מוזיאון")
    "zoo" -> T("🦒 Zoo", "🦒 גן חיות")
    else -> T("✨ Attraction", "✨ אטרקציה")
}

@Composable
fun YoloScreen(model: KavModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var mode by remember { mutableStateOf(Yolo.Mode.SHABBAT) }
    var walk by remember { mutableIntStateOf(1000) }
    var nearFirst by remember { mutableStateOf(false) }
    var natureOnShabbat by remember { mutableStateOf(false) }
    var days by remember { mutableStateOf<List<Yolo.Day>>(emptyList()) }
    var places by remember { mutableStateOf<List<OfflinePlanner.Reached>?>(null) }
    var lines by remember { mutableStateOf<List<OfflinePlanner.FarLine>?>(null) }
    var surprise by remember { mutableStateOf<OfflinePlanner.Reached?>(null) }
    var busy by remember { mutableStateOf(false) }
    val here = model.here

    val atMs = when (mode) {
        Yolo.Mode.SHABBAT -> Yolo.nextShabbat()
        Yolo.Mode.NATURE -> if (natureOnShabbat) Yolo.nextShabbat() else Yolo.dayStart()
        else -> Yolo.dayStart()
    }
    LaunchedEffect(here == null) {
        if (here == null) uk.noammm.kav.requestLocationOnce(ctx) { model.locate(it.first, it.second) }
        else days = withContext(Dispatchers.IO) { runCatching { Yolo.forecast(here) }.getOrDefault(emptyList()) }
    }
    LaunchedEffect(mode, walk, nearFirst, natureOnShabbat, here, days) {
        val from = here ?: return@LaunchedEffect
        busy = true; places = null; lines = null; surprise = null
        val net = model.net ?: runCatching { uk.noammm.kav.loadNet(ctx) }.getOrNull()?.also { model.net = it } ?: run { busy = false; return@LaunchedEffect }
        val day = Yolo.dayOf(days, atMs)
        withContext(Dispatchers.Default) {
            when (mode) {
                Yolo.Mode.LINE -> {
                    val all = OfflinePlanner.farLines(net, from, atMs)
                    lines = if (nearFirst) all.sortedBy { it.boardWalkM } else all
                }
                Yolo.Mode.SURPRISE -> {
                    val all = Yolo.suggest(ctx, net, Yolo.Mode.SHABBAT, from, atMs, walk.toDouble(), false, day)
                    places = all; surprise = Yolo.surprise(all)
                }
                else -> places = Yolo.suggest(ctx, net, mode, from, atMs, walk.toDouble(), nearFirst, day)
            }
        }
        busy = false
    }

    fun go(name: String, lat: Double, lon: Double, depMs: Long) {
        model.pendingLink = MoovitLink.Plan(null, null, null, name, lat, lon, if (depMs > System.currentTimeMillis() + 60_000) depMs else 0L, autoRun = true)
        onBack()
    }

    Column(Modifier.fillMaxSize().background(K.bg)) {
        ScreenHeader("YOLO", "", back = onBack)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = K.gap3, end = K.gap3, bottom = LocalBottomBarInset.current + K.gap4),
            verticalArrangement = Arrangement.spacedBy(K.gap2)) {
            item {
                if (days.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(bottom = K.gap2), horizontalArrangement = Arrangement.spacedBy(K.gap2)) {
                    for (d in days.take(7)) Column(Modifier.weight(1f).panel(14.dp).padding(vertical = K.gap2), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (T.rtl) dayY.format(Date(d.dateUtc * 1000)) else SimpleDateFormat("EEE", Locale.US).format(Date(d.dateUtc * 1000)), fontSize = 11.sp, color = K.dim)
                        Text(d.icon, fontSize = 18.sp)
                        Text("${d.tMax}°", fontSize = 12.sp, color = K.text)
                        if (d.rain >= 30) Text("${d.rain}%", fontSize = 10.sp, color = K.accent)
                    }
                }
            }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(K.gap2), verticalArrangement = Arrangement.spacedBy(K.gap2)) {
                    for ((m, label) in listOf(
                        Yolo.Mode.SHABBAT to T("Shabbat trip", "שבטיול"), Yolo.Mode.NATURE to T("Nature time", "טבע-טיים"),
                        Yolo.Mode.LINE to T("Interesting line", "קו מעניין"), Yolo.Mode.SURPRISE to T("Surprise me", "תפתיע אותי"),
                    )) Chip(label, mode == m) { mode = m }
                }
                Spacer(Modifier.height(K.gap2))
                Text(when (mode) {
                    Yolo.Mode.SHABBAT -> T("Places to reach this Saturday on the weekend lines (Na'im BaSofash and more).", "מקומות שאפשר להגיע אליהם בשבת הקרובה בקווי סוף השבוע (נעים בסופ״ש ועוד).")
                    Yolo.Mode.NATURE -> T("Nature reserves, parks, springs and beaches you can reach.", "שמורות, פארקים, מעיינות וחופים שאפשר להגיע אליהם.")
                    Yolo.Mode.LINE -> T("Lines from near you that go far with few stops.", "קווים מקרוב אליכם שנוסעים רחוק עם מעט תחנות.")
                    Yolo.Mode.SURPRISE -> T("One pick for today. We'll make this mode smarter together.", "בחירה אחת להיום. את המצב הזה נשכלל יחד בהמשך.")
                }, fontSize = 12.sp, color = K.dim, lineHeight = 17.sp)
                Spacer(Modifier.height(K.gap2))
                if (mode != Yolo.Mode.LINE) FlowRow(horizontalArrangement = Arrangement.spacedBy(K.gap2), verticalArrangement = Arrangement.spacedBy(K.gap2)) {
                    for ((m, label) in listOf(500 to T("Walk 500 m", "הליכה 500 מ׳"), 1000 to T("1 km", "1 ק״מ"), 2000 to T("2 km", "2 ק״מ"), 3000 to T("No limit", "ללא הגבלה")))
                        Chip(label, walk == m) { walk = m }
                }
                Spacer(Modifier.height(K.gap2))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(K.gap2), verticalArrangement = Arrangement.spacedBy(K.gap2)) {
                    if (mode != Yolo.Mode.SURPRISE) Chip(T("Close to me first", "קרוב אליי קודם"), nearFirst) { nearFirst = !nearFirst }
                    if (mode == Yolo.Mode.NATURE) {
                        Chip(T("Today", "היום"), !natureOnShabbat) { natureOnShabbat = false }
                        Chip(T("This Saturday", "בשבת"), natureOnShabbat) { natureOnShabbat = true }
                    }
                }
                Yolo.dayOf(days, atMs)?.let { d ->
                    val note = when {
                        d.rainy -> T("Rain likely that day — indoor places come first.", "צפוי גשם באותו יום — מקומות מקורים קודם.")
                        d.hot -> T("A hot day — beaches and springs come first.", "יום חם — חופים ומעיינות קודם.")
                        else -> T("Good weather for being outside.", "מזג אוויר טוב לבילוי בחוץ.")
                    }
                    Text("${d.icon} $note", fontSize = 12.sp, color = K.muted, modifier = Modifier.padding(top = K.gap2))
                }
                Spacer(Modifier.height(K.gap2))
            }
            if (here == null) item { Note(T("Waiting for your location…", "ממתינים למיקום שלכם…")) }
            else if (busy) item { LoadingPulse(T("Looking for places", "מחפשים מקומות")) }
            surprise?.let { s -> item {
                Column(Modifier.fillMaxWidth().panel().padding(K.gap4)) {
                    Text(T("How about…", "מה דעתכם על…"), fontSize = 12.sp, color = K.dim)
                    Text(s.place.name, fontSize = 20.sp, color = K.text, fontWeight = FontWeight.SemiBold)
                    Text(kindLabel(s.place.kind), fontSize = 12.sp, color = K.muted)
                    Text(reachLine(s), fontSize = 13.sp, color = K.accent, modifier = Modifier.padding(top = K.gap2))
                    Row(Modifier.padding(top = K.gap3), horizontalArrangement = Arrangement.spacedBy(K.gap2)) {
                        Chip(T("Take me there", "קחו אותי לשם"), true) { go(s.place.name, s.place.lat, s.place.lon, atMs) }
                        Chip(T("Another one", "עוד אחד"), false) { surprise = Yolo.surprise(places.orEmpty()) }
                    }
                }
            } }
            if (mode != Yolo.Mode.SURPRISE) places?.let { list ->
                if (list.isEmpty() && !busy) item { Note(T("Nothing reachable with these settings. Try a longer walk.", "לא נמצא מקום עם ההגדרות האלה. נסו הליכה ארוכה יותר.")) }
                items(list) { r ->
                    Column(Modifier.fillMaxWidth().panel().clickable(role = Role.Button) { go(r.place.name, r.place.lat, r.place.lon, atMs) }
                        .padding(K.gap3)) {
                        Text(r.place.name, fontSize = 15.sp, color = K.text, fontWeight = FontWeight.Medium)
                        Text(kindLabel(r.place.kind), fontSize = 12.sp, color = K.muted)
                        Text(reachLine(r), fontSize = 12.sp, color = K.accent, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
            item {
                Text(T("Places © OpenStreetMap contributors · weather by Open-Meteo.com", "מקומות © תורמי OpenStreetMap · מזג אוויר: Open-Meteo.com"),
                    fontSize = 10.sp, color = K.dim, modifier = Modifier.padding(top = K.gap3))
            }
            lines?.let { list ->
                if (list.isEmpty() && !busy) item { Note(T("No far-going lines near you in the next three hours.", "אין בסביבה קווים שנוסעים רחוק בשלוש השעות הקרובות.")) }
                items(list.take(30)) { l ->
                    Column(Modifier.fillMaxWidth().panel().clickable(role = Role.Button) { go(l.terminus, l.to.first, l.to.second, l.depUtc * 1000) }
                        .padding(K.gap3)) {
                        Text(if (l.line.any(Char::isDigit)) T("Line ${l.line} → ${l.terminus}", "קו ${l.line} ← ${l.terminus}") else "${l.line} ← ${l.terminus}", fontSize = 15.sp, color = K.text, fontWeight = FontWeight.Medium)
                        if (l.terminusCity.isNotBlank()) Text(l.terminusCity, fontSize = 12.sp, color = K.muted)
                        Text(T("${l.km} km · ${l.stops} stops · ${dur(l.minutes * 60)} · from ${l.boardName} at ${hmY.format(Date(l.depUtc * 1000))}",
                            "${l.km} ק״מ · ${l.stops} תחנות · ${dur(l.minutes * 60)} · מ${l.boardName} ב-${hmY.format(Date(l.depUtc * 1000))}"),
                            fontSize = 12.sp, color = K.accent, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
        }
    }
}

private fun reachLine(r: OfflinePlanner.Reached): String {
    val by = when {
        r.line.isBlank() -> T("on foot", "ברגל")
        r.line.any(Char::isDigit) -> T("line ${r.line}", "קו ${r.line}")
        else -> r.line
    }
    val walk = if (r.walkM > 0 && r.line.isNotBlank()) T(" · walk ${r.walkM} m", " · הליכה ${r.walkM} מ׳") else ""
    return T("${dur(r.minutes * 60)} · $by$walk · arrive ${hmY.format(Date(r.arriveUtc * 1000))}",
        "${dur(r.minutes * 60)} · $by$walk · הגעה ב-${hmY.format(Date(r.arriveUtc * 1000))}")
}

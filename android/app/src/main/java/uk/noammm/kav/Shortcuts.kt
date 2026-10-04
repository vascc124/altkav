package uk.noammm.kav

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import uk.noammm.kav.data.MoovitLink
import uk.noammm.kav.ui.Favourite
import uk.noammm.kav.ui.T

// Long-press the launcher icon: one shortcut per saved favourite, each a directions link
// to that place, which MainActivity already plans from wherever the rider is.
object Shortcuts {
    fun sync(ctx: Context, favourites: List<Favourite>) {
        val app = ctx.applicationContext
        val max = ShortcutManagerCompat.getMaxShortcutCountPerActivity(app).coerceIn(1, 4)
        val list = favourites.filter { it.place != null }.take(max).mapIndexed { rank, f ->
            val p = f.place!!
            val label = if (f.id == Favourite.HOME && f.name == "Home") T("Home", "בית") else f.name
            val link = MoovitLink.share(null, null, null, label, p.lat, p.lon)
            ShortcutInfoCompat.Builder(app, "fav-${f.id}")
                .setShortLabel(label)
                .setLongLabel(T("Directions to $label", "מסלול אל $label"))
                .setIcon(IconCompat.createWithResource(app, iconFor(f.icon)))
                .setIntent(Intent(Intent.ACTION_VIEW, Uri.parse(link), app, MainActivity::class.java))
                .setRank(rank)
                .build()
        }
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(app, list) }
    }

    private fun iconFor(icon: String) = when (icon) {
        "home", "apartment", "cottage", "villa", "cabin", "castle" -> R.drawable.ic_shortcut_home
        "work", "business", "school" -> R.drawable.ic_shortcut_work
        else -> R.drawable.ic_shortcut_place
    }
}

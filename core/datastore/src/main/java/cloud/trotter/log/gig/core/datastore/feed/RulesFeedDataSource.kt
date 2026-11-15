package cloud.trotter.log.gig.core.datastore.feed

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import cloud.trotter.log.gig.core.datastore.di.RulesFeedPreferences
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The rules feed's stored choices (#640): "Keep recognition rules up to date" (off unless the dasher turned it on) and
 * when the last foreground check started. Device-local, excluded from backup and transfer like every consent choice.
 *
 * Read once at start by its owner, `RulesFeedSwitch`; nothing collects this store, so a write never has to reach a
 * mirror before it counts. Failures propagate to the owner.
 */
@Singleton
class RulesFeedDataSource @Inject constructor(
    @param:RulesFeedPreferences private val ds: DataStore<Preferences>,
) {
    private object Keys {
        val ENABLED = booleanPreferencesKey("rules_updates_enabled")
        val LAST_FOREGROUND_CHECK_AT = longPreferencesKey("last_foreground_check_at")
    }

    class Stored(val enabled: Boolean, val lastForegroundCheckAt: Long?)

    suspend fun read(): Stored {
        val prefs = ds.data.first()
        return Stored(prefs[Keys.ENABLED] ?: false, prefs[Keys.LAST_FOREGROUND_CHECK_AT])
    }

    suspend fun setEnabled(enabled: Boolean) {
        ds.edit { it[Keys.ENABLED] = enabled }
    }

    suspend fun setLastForegroundCheckAt(at: Long) {
        ds.edit { it[Keys.LAST_FOREGROUND_CHECK_AT] = at }
    }
}

package app.pulse4.android.di

import app.pulse4.android.data.AgeProfileManager
import app.pulse4.android.data.ContactDao
import app.pulse4.android.data.ContactRow
import app.pulse4.android.data.GuardianConsentDao
import app.pulse4.android.data.GuardianConsentRow
import app.pulse4.sharing.AgeProfileRecord
import app.pulse4.sharing.AgeSource
import app.pulse4.sharing.AgeState
import app.pulse4.sync.Wire
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** In-memory DAOs for the hand-written `PulseDataAccess` fakes of older tests. */
internal class InMemoryContactDao : ContactDao {
    val map = LinkedHashMap<String, ContactRow>()
    override fun upsert(row: ContactRow) { map[row.identity] = row }
    override fun all(): Flow<List<ContactRow>> = emptyFlow()
    override fun allNow() = map.values.toList()
    override fun count(id: String) = if (id in map) 1 else 0
    override fun get(id: String) = map[id]
    override fun delete(id: String) { map.remove(id) }
    override fun deleteAll() { map.clear() }
}

internal class InMemoryGuardianConsentDao : GuardianConsentDao {
    val map = LinkedHashMap<String, GuardianConsentRow>()
    override fun all(): Flow<List<GuardianConsentRow>> = emptyFlow()
    override fun allNow() = map.values.sortedBy { it.createdAtMs }
    override fun byRole(role: String) = allNow().filter { it.role == role }
    override fun get(id: String) = map[id]
    override fun upsert(row: GuardianConsentRow) { map[row.requestId] = row }
}

/** Raw settings JSON for a stored age declaration (what [AgeProfileManager] writes). */
internal fun ageRecordJson(state: AgeState): String =
    Wire.json.encodeToString(AgeProfileRecord.serializer(), AgeProfileRecord(state, AgeSource.DECLARED, 1L))

internal fun seedAge(raw: MutableMap<String, String>, state: AgeState) {
    raw[AgeProfileManager.KEY_AGE_V2] = ageRecordJson(state)
}

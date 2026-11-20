package app.pulse4.android.data

import app.pulse4.sharing.AgeProfilePolicy
import app.pulse4.sharing.AgeProfileRecord
import app.pulse4.sharing.AgeSource
import app.pulse4.sharing.AgeState
import app.pulse4.sharing.FamilyLink
import app.pulse4.sharing.GuardianAgeRelease
import app.pulse4.sharing.ConsentRecord
import app.pulse4.sharing.ReleaseOutcome
import app.pulse4.sharing.SignatureVerifierResolver
import app.pulse4.sync.Wire
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/**
 * The ONLY writer of the local age state (settings key [KEY_AGE_V2], JSON [AgeProfileRecord]). Every transition
 * goes through [AgeProfilePolicy], so MINOR -> ADULT is only possible before any guardian link was ever verified
 * (first-run undo) or with a verified, signed guardian release. The legacy `ownAgeProfile` key is read-only and
 * only consulted by the one-time migration; nothing here (or anywhere) writes it any more.
 *
 * Backed by plain getRaw/put lambdas so it works over [Settings] and over test fakes.
 */
class AgeProfileManager(
    private val getRaw: (String) -> String?,
    private val put: (String, String) -> Unit,
) {
    private val lock = Any()

    fun current(): AgeProfileRecord = synchronized(lock) { read() }

    val state: AgeState get() = current().state

    /** True once any guardian link has ever become VERIFIED on this device (even if removed later). */
    fun guardianLinkEverVerified(): Boolean = getRaw(KEY_EVER_VERIFIED) == "1"

    fun markGuardianLinkVerified() = synchronized(lock) {
        if (getRaw(KEY_EVER_VERIFIED) != "1") put(KEY_EVER_VERIFIED, "1")
    }

    /** User declaration (first-run choice, ADULT -> MINOR, or the undo of a self-declared MINOR). `null` = not allowed. */
    fun declare(target: AgeState, nowMs: Long): AgeProfileRecord? = synchronized(lock) {
        val cur = read()
        val next = AgeProfilePolicy.declare(cur, target, nowMs, guardianLinkEverVerified = guardianLinkEverVerified())
            ?: return@synchronized null
        if (next != cur) write(next)
        next
    }

    /** Accepting/verifying a guardian link of this child ratchets the profile to MINOR (idempotent). */
    fun onLinkAccepted(link: FamilyLink, selfId: String, nowMs: Long): AgeProfileRecord = synchronized(lock) {
        val cur = read()
        val next = AgeProfilePolicy.onLinkAccepted(cur, link, selfId, nowMs)
        if (next != cur) write(next)
        next
    }

    /** Applies a signed guardian age release (consumes its nonce on success). */
    fun applyRelease(
        selfId: String,
        envelopeAuthor: String,
        release: GuardianAgeRelease,
        links: List<FamilyLink>,
        records: List<ConsentRecord>,
        nowMs: Long,
        verifierFor: SignatureVerifierResolver,
    ): ReleaseOutcome = synchronized(lock) {
        val cur = read()
        val outcome = AgeProfilePolicy.applyRelease(
            cur, selfId, envelopeAuthor, release, links, records, usedNonces(), nowMs, verifierFor,
        )
        if (outcome is ReleaseOutcome.Released) {
            write(outcome.record)
            put(KEY_RELEASE_NONCES, Wire.json.encodeToString(ListSerializer(String.serializer()), (usedNonces() + release.nonce).toList()))
        }
        outcome
    }

    /** Migration only: store a record when none exists yet. Returns false when a v2 record is already present. */
    fun initializeIfAbsent(record: AgeProfileRecord): Boolean = synchronized(lock) {
        if (getRaw(KEY_AGE_V2) != null) return@synchronized false
        write(record)
        true
    }

    fun hasRecord(): Boolean = getRaw(KEY_AGE_V2) != null

    fun grandfatheredGrantIds(): Set<String> =
        getRaw(KEY_GRANDFATHERED)?.let {
            runCatching { Wire.json.decodeFromString(ListSerializer(String.serializer()), it).toSet() }.getOrNull()
        }.orEmpty()

    fun setGrandfatheredGrantIds(ids: Set<String>) {
        put(KEY_GRANDFATHERED, Wire.json.encodeToString(ListSerializer(String.serializer()), ids.sorted()))
    }

    /** Circles joined before the migration; they keep working while the age is still UNKNOWN. */
    fun grandfatheredCircleIds(): Set<String> =
        getRaw(KEY_GRANDFATHERED_CIRCLES)?.let {
            runCatching { Wire.json.decodeFromString(ListSerializer(String.serializer()), it).toSet() }.getOrNull()
        }.orEmpty()

    fun setGrandfatheredCircleIds(ids: Set<String>) {
        put(KEY_GRANDFATHERED_CIRCLES, Wire.json.encodeToString(ListSerializer(String.serializer()), ids.sorted()))
    }

    private fun usedNonces(): Set<String> =
        getRaw(KEY_RELEASE_NONCES)?.let {
            runCatching { Wire.json.decodeFromString(ListSerializer(String.serializer()), it).toSet() }.getOrNull()
        }.orEmpty()

    private fun read(): AgeProfileRecord =
        getRaw(KEY_AGE_V2)?.let {
            runCatching { Wire.json.decodeFromString(AgeProfileRecord.serializer(), it) }.getOrNull()
        } ?: AgeProfileRecord()

    private fun write(record: AgeProfileRecord) {
        put(KEY_AGE_V2, Wire.json.encodeToString(AgeProfileRecord.serializer(), record))
    }

    companion object {
        const val KEY_AGE_V2 = "ageProfile.v2"
        const val KEY_GRANDFATHERED = "consent.grandfatheredGrantIds"
        const val KEY_GRANDFATHERED_CIRCLES = "consent.grandfatheredCircleIds"
        const val KEY_EVER_VERIFIED = "consent.guardianLinkEverVerified"
        const val KEY_RELEASE_NONCES = "consent.ageReleaseNonces"
        /** Legacy key: read-only, only the one-time migration reads it. */
        const val KEY_LEGACY_OWN_AGE = "ownAgeProfile"
    }
}

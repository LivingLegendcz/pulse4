package app.pulse4.sharing

import java.time.LocalDate
import kotlinx.serialization.Serializable

@Serializable
enum class Category { SLEEP, BODY_BATTERY, HEART_RATE, HRV, ACTIVITY, BODY_COMPOSITION }

/**
 * Pozitivní výčet sdílitelných polí. Výchozí stav je nesdílet nic a nová hodnota přidaná
 * do této množiny se nikomu sama nezveřejní – musí ji vlastník výslovně přidat do `ShareGrant.fields`.
 */
@Serializable
enum class Field(val category: Category) {
    SLEEP_DURATION(Category.SLEEP),
    SLEEP_SCORE(Category.SLEEP),
    SLEEP_STAGES(Category.SLEEP),
    BB_LAST(Category.BODY_BATTERY),
    BB_MIN_MAX(Category.BODY_BATTERY),
    BB_SERIES(Category.BODY_BATTERY),
    HR_DAILY(Category.HEART_RATE),
    HR_SERIES(Category.HEART_RATE),
    HRV_DAILY(Category.HRV),
    HRV_SERIES(Category.HRV),
    ACT_SUMMARY(Category.ACTIVITY),
    ACT_CHARTS(Category.ACTIVITY),
    ACT_ROUTE(Category.ACTIVITY),
    ACT_FULL_RECORD(Category.ACTIVITY),
    /** Přesný čas je samostatná volba: sám může prozradit citlivý kontext. */
    ACT_EXACT_TIME(Category.ACTIVITY),
    WEIGHT(Category.BODY_COMPOSITION),
    BODY_FAT(Category.BODY_COMPOSITION),
}

/** Hotové volby z obrazovky sdílení. */
object Presets {
    val sleepDurationOnly = setOf(Field.SLEEP_DURATION)
    val sleepDurationAndScore = setOf(Field.SLEEP_DURATION, Field.SLEEP_SCORE)
    val sleepFull = setOf(Field.SLEEP_DURATION, Field.SLEEP_SCORE, Field.SLEEP_STAGES)
    val bodyBatteryLast = setOf(Field.BB_LAST)
    val bodyBatteryRange = setOf(Field.BB_LAST, Field.BB_MIN_MAX)
    val activitySummary = setOf(Field.ACT_SUMMARY)
}

@Serializable
enum class PlanRight { VIEW, COPY, PROPOSE, EDIT }

@Serializable
data class TimeScope(val kind: Kind, val from: String? = null, val to: String? = null) {
    @Serializable enum class Kind { ONCE, FROM, CONTINUOUS }

    fun covers(date: LocalDate): Boolean {
        val f = from?.let(LocalDate::parse)
        val t = to?.let(LocalDate::parse)
        return when (kind) {
            Kind.ONCE -> f != null && t != null && !date.isBefore(f) && !date.isAfter(t)
            Kind.FROM, Kind.CONTINUOUS -> f != null && !date.isBefore(f)
        }
    }

    companion object {
        fun once(from: LocalDate, to: LocalDate) = TimeScope(Kind.ONCE, from.toString(), to.toString())
        fun from(date: LocalDate) = TimeScope(Kind.FROM, date.toString())
        /** Průběžné sdílení začíná dnem vytvoření: nikdy neotevírá starou historii. */
        fun continuousFrom(creationDate: LocalDate) = TimeScope(Kind.CONTINUOUS, creationDate.toString())
    }
}

@Serializable
data class ShareGrant(
    val id: String,
    val ownerId: String,
    /** Konkrétní kontakty; členství ve skupině samo příjemce nepřidává. */
    val recipients: Set<String>,
    val fields: Set<Field>,
    val planRights: Map<String, PlanRight> = emptyMap(),
    val scope: TimeScope,
    val groupId: String? = null,
    val createdAtMs: Long,
    val revokedAtMs: Long? = null,
) {
    val active: Boolean get() = revokedAtMs == null
    fun allows(f: Field) = active && f in fields
    fun planRight(planId: String): PlanRight? = if (active) planRights[planId] else null
}

@Serializable
data class Group(
    val id: String,
    val name: String,
    val ownerId: String,
    val adminId: String,
    val members: Set<String>,
    val version: Long,
)

object MembershipPolicy {
    /**
     * Přidání člena sdílení nerozšiřuje: vlastník dat musí rozšíření příjemců sám potvrdit.
     * Vrací členy, kterým grant zatím nic neposílá a kteří čekají na jeho potvrzení.
     */
    fun awaitingConsent(grant: ShareGrant, group: Group): Set<String> =
        group.members - grant.recipients - grant.ownerId

    /** Skutečný seznam příjemců: potvrzení ∩ aktuální členové (odebraný člen přestane dostávat data). */
    fun effectiveRecipients(grant: ShareGrant, group: Group?): Set<String> {
        if (!grant.active) return emptySet()
        val base = grant.recipients - grant.ownerId
        return if (grant.groupId != null && group != null && group.id == grant.groupId) base.intersect(group.members) else base
    }
}

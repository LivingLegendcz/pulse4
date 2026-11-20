package app.pulse4.sharing

import app.pulse4.domain.Provider
import java.time.LocalDate
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** HMAC stand-in for Ed25519 so the policy can be tested without a crypto dependency. */
internal class FakeKeys(private val secrets: Map<String, String>) {
    fun sign(identity: String, bytes: ByteArray): String = hmac(secrets.getValue(identity), bytes)

    /** Resolver: identities without a registered key (unknown contact / key not derived to id) -> null. */
    val resolver: SignatureVerifierResolver = { id ->
        secrets[id]?.let { secret -> { bytes: ByteArray, sig: String -> sig == hmac(secret, bytes) } }
    }

    private fun hmac(secret: String, bytes: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return mac.doFinal(bytes).joinToString("") { "%02x".format(it) }
    }
}

internal val GuardianConsentRequest.t: GrantTerms get() = terms!!

internal object Fx {
    const val CHILD = "child"
    const val GUARDIAN = "guardian"
    const val GUARDIAN2 = "guardian2"
    const val STRANGER = "stranger"
    const val NOW = 1_000_000L
    const val NONCE = "00112233445566778899aabbccddeeff"
    val day: LocalDate = LocalDate.of(2026, 1, 10)

    val keys = FakeKeys(
        mapOf(
            CHILD to "child-secret", GUARDIAN to "guardian-secret", GUARDIAN2 to "guardian2-secret",
            STRANGER to "stranger-secret",
        ),
    )

    fun nonce(n: Int) = "%032x".format(n)

    fun link(
        id: String = "fl-1",
        parent: String = GUARDIAN,
        child: String = CHILD,
        status: FamilyLinkStatus = FamilyLinkStatus.VERIFIED,
        profile: AgeProfile = AgeProfile.MINOR,
        version: Long = 2L,
        verifiedAtMs: Long = 2L,
    ) = FamilyLink(id, parent, child, status, profile, createdAtMs = 1L, verifiedAtMs = verifiedAtMs, version = version)

    fun grant(
        id: String = "g1",
        recipients: Set<String> = setOf("coach"),
        fields: Set<Field> = setOf(Field.SLEEP_DURATION, Field.SLEEP_SCORE),
        planRights: Map<String, PlanRight> = emptyMap(),
        scope: TimeScope = TimeScope.from(day),
        sources: Set<Provider>? = null,
        revokedAtMs: Long? = null,
    ) = ShareGrant(
        id = id, ownerId = CHILD, recipients = recipients, fields = fields, planRights = planRights,
        scope = scope, createdAtMs = 10L, revokedAtMs = revokedAtMs, sources = sources,
    )

    fun request(
        grant: ShareGrant = grant(),
        guardians: Set<String> = setOf(GUARDIAN),
        requestId: String = "r1",
        nonce: String = NONCE,
        version: Long = 1L,
        baseline: GrantTerms? = null,
        issuedAtMs: Long = NOW - 1_000,
        ttl: Long = GuardianConsentPolicy.DEFAULT_REQUEST_TTL_MS,
    ) = GuardianConsentPolicy.newRequest(requestId, nonce, guardians, grant, version, baseline, issuedAtMs, ttl)

    fun membership(
        circleId: String = "circle-1",
        guardians: Set<String> = setOf(GUARDIAN),
        requestId: String = "m1",
        nonce: String = "11112222333344445555666677778888",
        version: Long = 1L,
        action: MembershipAction = MembershipAction.JOIN,
        name: String = "Family circle",
        admin: String = "admin-1",
    ) = GuardianConsentPolicy.newMembershipRequest(
        requestId, nonce, guardians, CHILD, circleId, name, admin, action, version, NOW - 1_000,
    )

    fun guardianAdd(
        newGuardian: String = GUARDIAN2,
        existing: Set<String> = setOf(GUARDIAN),
        requestId: String = "a1",
        nonce: String = "aaaa0000bbbb1111cccc2222dddd3333",
        version: Long = 1L,
        keyDigest: String = "ab".repeat(32),
    ) = GuardianConsentPolicy.newGuardianAddRequest(
        requestId, nonce, existing, CHILD, newGuardian, "New parent", keyDigest, version, NOW - 1_000,
    )

    fun decide(
        req: GuardianConsentRequest,
        verdict: ConsentVerdict = ConsentVerdict.APPROVED,
        signer: String = GUARDIAN,
        guardianId: String = signer,
        digest: String = ConsentCanonical.requestDigestHex(req),
        decidedAtMs: Long = NOW,
    ): GuardianDecision {
        val unsigned = GuardianDecision(
            verdict = verdict, requestId = req.requestId, guardianId = guardianId, childId = req.childId,
            requestDigest = digest, decidedAtMs = decidedAtMs, signatureHex = "",
        )
        return unsigned.copy(signatureHex = keys.sign(signer, ConsentCanonical.decision(unsigned)))
    }

    fun approved(req: GuardianConsentRequest, decision: GuardianDecision = decide(req)) =
        ConsentRecord(req, ConsentStatus.APPROVED, decision)

    fun pending(req: GuardianConsentRequest) = ConsentRecord(req, ConsentStatus.PENDING)

    fun revocation(
        req: GuardianConsentRequest,
        signer: String = GUARDIAN,
        guardianId: String = signer,
        permissionId: String = req.permissionId,
    ): GuardianRevocation {
        val u = GuardianRevocation(
            requestId = req.requestId, guardianId = guardianId, childId = req.childId,
            permissionId = permissionId, revokedAtMs = NOW, signatureHex = "",
        )
        return u.copy(signatureHex = keys.sign(signer, ConsentCanonical.revocation(u)))
    }

    /** Two guardians: the founder (guardian) and guardian2 added with an APPROVED GUARDIAN_ADD record. */
    val founderLink = link()
    val secondLink = link(id = "fl-2", parent = GUARDIAN2, verifiedAtMs = 5L)
    val addApproved: ConsentRecord by lazy { approved(guardianAdd()) }
}

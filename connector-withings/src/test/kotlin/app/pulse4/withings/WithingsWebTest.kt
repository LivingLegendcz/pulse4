package app.pulse4.withings

import app.pulse4.domain.EcgAfibClassification
import app.pulse4.domain.MeasurementExclusions
import app.pulse4.domain.MetricObservation
import app.pulse4.domain.MetricType
import app.pulse4.domain.Provider
import app.pulse4.domain.Quality
import app.pulse4.provider.AreaResult
import app.pulse4.provider.AreaState
import app.pulse4.provider.AreaStateStore
import app.pulse4.provider.HttpGateway
import app.pulse4.provider.HttpResponse
import app.pulse4.provider.SyncArea
import app.pulse4.provider.SyncEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WithingsWebTest {

    @Test
    fun testGetUsersListParsesCorrectly() {
        val fakeHttp = object : HttpGateway {
            override fun get(url: String, headers: Map<String, String>): HttpResponse = error("unused")
            override fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>): HttpResponse {
                assertEquals("getuserslist", form["action"])
                assertEquals("sessionid=abc123xyz", headers["Cookie"])
                assertEquals("abc123xyz", form["sessionid"])
                val responseJson = """
                    {
                      "status": 0,
                      "body": {
                        "users": [
                          {
                            "id": 1001,
                            "firstname": "Petr",
                            "lastname": "Novák",
                            "shortname": "PN",
                            "gender": 0,
                            "birthdate": 631152000,
                            "timezone": "Europe/Prague",
                            "is_primary": 1
                          },
                          {
                            "id": 1002,
                            "firstname": "Tomáš",
                            "lastname": "Novák",
                            "shortname": "TN",
                            "gender": 0,
                            "birthdate": 1388534400,
                            "timezone": "Europe/Prague",
                            "is_primary": 0
                          }
                        ]
                      }
                    }
                """.trimIndent()
                return HttpResponse(200, emptyMap(), responseJson)
            }
        }

        val client = WithingsWebClient(fakeHttp)
        val users = client.getUsersList("sessionid=abc123xyz")

        assertEquals(2, users.size)
        assertEquals(1001L, users[0].id)
        assertEquals("Petr Novák", users[0].displayName)
        assertEquals("Europe/Prague", users[0].timezone)
        assertTrue(users[0].isPrimary)

        assertEquals(1002L, users[1].id)
        assertEquals("Tomáš Novák", users[1].displayName)
        assertFalse(users[1].isPrimary)
    }

    @Test
    fun testGetMeasuresFullBodyCompositionAndSegmentalDedup() {
        val fakeHttp = object : HttpGateway {
            override fun get(url: String, headers: Map<String, String>): HttpResponse = error("unused")
            override fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>): HttpResponse {
                assertEquals("getmeas", form["action"])
                assertEquals("1001", form["userid"])
                val responseJson = """
                    {
                      "status": 0,
                      "body": {
                        "updatetime": 1730811234,
                        "more": 0,
                        "offset": 0,
                        "timezone": "Europe/Prague",
                        "measuregrps": [
                          {
                            "grpid": 555666777,
                            "attrib": 0,
                            "date": 1730800000,
                            "created": 1730800000,
                            "modified": 1730800010,
                            "category": 1,
                            "deviceid": "dev123",
                            "timezone": "Europe/Prague",
                            "measures": [
                              { "value": 78400, "unit": -3, "type": 1 },
                              { "value": 172, "unit": -1, "type": 6 },
                              { "value": 64900, "unit": -3, "type": 5 },
                              { "value": 61500, "unit": -3, "type": 76 },
                              { "value": 3400, "unit": -3, "type": 88 },
                              { "value": 585, "unit": -1, "type": 77 },
                              { "value": 62, "unit": 0, "type": 11 },
                              { "value": 68, "unit": -1, "type": 91 },
                              { "value": 34, "unit": 0, "type": 155 },
                              { "value": 25, "unit": -1, "type": 170 },
                              { "value": 1780, "unit": 0, "type": 226 },
                              { "value": 31, "unit": 0, "type": 227 },
                              { "value": 8500, "unit": -3, "type": 76, "position": 0, "algo": 3 },
                              { "value": 8600, "unit": -3, "type": 76, "position": 1, "algo": 3 },
                              { "value": 150, "unit": -1, "type": 6, "position": 8, "algo": 3 }
                            ]
                          }
                        ]
                      }
                    }
                """.trimIndent()
                return HttpResponse(200, emptyMap(), responseJson)
            }
        }

        val client = WithingsWebClient(fakeHttp)
        val groups = client.getMeasures("sessionid=abc", 1001L)
        assertEquals(1, groups.size)

        val mapResult = WithingsWebMapper.map(groups, 1001L, "Europe/Prague")
        assertEquals(0, mapResult.skipped)
        assertEquals(12, mapResult.items.size)
        // Ensure all mapped IDs are strictly unique (no segmental collisions!)
        assertEquals(12, mapResult.items.map { it.id }.toSet().size)

        val byType = mapResult.items.associateBy { it.type }
        assertEquals(78.4, byType.getValue(MetricType.WEIGHT).value, 0.001)
        assertEquals(Quality.OK, byType.getValue(MetricType.WEIGHT).quality)
        assertEquals(Provider.WITHINGS_API, byType.getValue(MetricType.WEIGHT).provider)
        assertEquals("1001", byType.getValue(MetricType.WEIGHT).accountId)

        assertEquals(17.2, byType.getValue(MetricType.BODY_FAT).value, 0.001)
        assertEquals(64.9, byType.getValue(MetricType.FAT_FREE_MASS).value, 0.001)
        assertEquals(61.5, byType.getValue(MetricType.MUSCLE_MASS).value, 0.001)
        assertEquals(3.4, byType.getValue(MetricType.BONE_MASS).value, 0.001)
        assertEquals((58.5 / 78.4) * 100.0, byType.getValue(MetricType.BODY_WATER).value, 0.01)
        assertEquals(62.0, byType.getValue(MetricType.HEART_RATE).value, 0.001)
        assertEquals(6.8, byType.getValue(MetricType.PULSE_WAVE_VELOCITY).value, 0.001)
        assertEquals(34.0, byType.getValue(MetricType.VASCULAR_AGE).value, 0.001)
        assertEquals(2.5, byType.getValue(MetricType.VISCERAL_FAT).value, 0.001)
        assertEquals(1780.0, byType.getValue(MetricType.BMR).value, 0.001)
        assertEquals(31.0, byType.getValue(MetricType.METABOLIC_AGE).value, 0.001)
    }

    @Test
    fun testDeletedGroupsAndIncrementalSyncWithSessionExtend() {
        var measCallCount = 0
        var extendCalled = false

        val fakeHttp = object : HttpGateway {
            override fun get(url: String, headers: Map<String, String>): HttpResponse = error("unused")
            override fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>): HttpResponse {
                return when (form["action"]) {
                    "extend" -> {
                        extendCalled = true
                        HttpResponse(
                            200,
                            emptyMap(),
                            """{"status":0,"body":{"sessionid":"renewed-tok-999","session_token":"renewed-tok-999"}}"""
                        )
                    }
                    "getmeas" -> {
                        measCallCount++
                        if (measCallCount == 1) {
                            // Simulate Withings status 2555 (The session-token is invalid)
                            HttpResponse(200, emptyMap(), """{"status":2555,"error":"The session-token is invalid"}""")
                        } else {
                            assertEquals("renewed-tok-999", form["session_token"])
                            HttpResponse(
                                200,
                                emptyMap(),
                                """
                                {
                                  "status": 0,
                                  "body": {
                                    "updatetime": 1730900000,
                                    "more": 0,
                                    "timezone": "Europe/Prague",
                                    "measuregrps": [
                                      {
                                        "grpid": 9001,
                                        "attrib": 0,
                                        "date": 1730850000,
                                        "modified": 1730850005,
                                        "category": 1,
                                        "measures": [
                                          { "value": 77500, "unit": -3, "type": 1 }
                                        ]
                                      },
                                      {
                                        "grpid": 9002,
                                        "attrib": 0,
                                        "date": 1730840000,
                                        "modified": 1730860000,
                                        "category": 1,
                                        "deleted": 1,
                                        "measures": []
                                      }
                                    ]
                                  }
                                }
                                """.trimIndent()
                            )
                        }
                    }
                    "list" -> {
                        HttpResponse(
                            200,
                            emptyMap(),
                            """
                            {
                              "status": 0,
                              "body": {
                                "more": false,
                                "series": [
                                  {
                                    "signalid": 77701,
                                    "timestamp": 1730850100,
                                    "afib": 0,
                                    "heart_rate": 64,
                                    "qrs": 88,
                                    "pr": 148,
                                    "qt": 390,
                                    "qtc": 402
                                  }
                                ]
                              }
                            }
                            """.trimIndent()
                        )
                    }
                    else -> error("Unexpected action ${form["action"]}")
                }
            }
        }

        var session = WithingsWebSession(
            cookies = "session_token=old-tok-111",
            sessionToken = "old-tok-111",
            primaryUserId = 1001L,
            primaryUserName = "Petr",
            lastExtendedMs = 1730900000_000L // so proactive keep-alive lets getmeas trigger first to test status 2555 recovery
        )
        assertFalse(session.toString().contains("old-tok-111"))

        val storedObs = mutableListOf<MetricObservation>()
        val deletedIds = mutableListOf<String>()
        val existingIdForDeletedGroup = "WITHINGS_API:1001:WEIGHT:1730840000000|id:9002:1"

        val stateMap = mutableMapOf(
            SyncArea.WEIGHT to AreaState(cursorMs = 1730800000_000L, lastSuccessMs = 1730800000_000L)
        )
        val stateStore = object : AreaStateStore {
            override fun get(area: SyncArea): AreaState = stateMap[area] ?: AreaState()
            override fun put(area: SyncArea, state: AreaState) { stateMap[area] = state }
        }

        val fetcher = WithingsWebFetcher(
            client = WithingsWebClient(fakeHttp),
            sessionProvider = { session },
            onSessionExtended = { session = it },
            defaultZone = "Europe/Prague",
            existingIds = { _, _ -> setOf(existingIdForDeletedGroup) },
            exclusions = MeasurementExclusions { false },
            nowMs = { 1730900000_000L },
            config = WithingsWebConfig()
        )

        val engine = SyncEngine(
            fetcher = fetcher,
            sink = { _, batch ->
                storedObs += batch.items
                deletedIds += batch.tombstones
            },
            states = stateStore
        )

        val res = engine.runArea(SyncArea.WEIGHT, 1730900000_000L)
        assertTrue(res is AreaResult.Success)
        assertTrue(extendCalled)
        assertEquals("renewed-tok-999", session.sessionToken)
        assertTrue(session.cookies.contains("session_token=renewed-tok-999"))
        assertEquals(5, storedObs.size) // 1 WEIGHT + 4 typed ECG metrics
        assertTrue(storedObs.any { it.type == MetricType.WEIGHT && it.value == 77.5 })
        assertTrue(storedObs.any { it.type == MetricType.ECG_QTC && it.value == 402.0 })
        assertTrue(storedObs.any { it.type == MetricType.ECG_QRS && it.value == 88.0 })
        assertTrue(storedObs.any { it.type == MetricType.ECG_HR && it.value == 64.0 })
        assertTrue(storedObs.any { it.type == MetricType.ECG_AFIB && it.value == EcgAfibClassification.NONE_DETECTED.code })
        assertTrue(deletedIds.contains(existingIdForDeletedGroup))
    }

    @Test
    fun testProactiveSessionKeepAliveExtendsBeforeExpiration() {
        var extendCount = 0
        val fakeHttp = object : HttpGateway {
            override fun get(url: String, headers: Map<String, String>): HttpResponse = error("unused")
            override fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>): HttpResponse {
                return when (form["action"]) {
                    "extend" -> {
                        extendCount++
                        HttpResponse(200, emptyMap(), """{"status":0,"body":{"session_token":"proactive-tok-888"}}""")
                    }
                    "getmeas" -> {
                        assertEquals("proactive-tok-888", form["session_token"])
                        HttpResponse(200, emptyMap(), """{"status":0,"body":{"updatetime":1730950000,"more":0,"measuregrps":[]}}""")
                    }
                    "list" -> {
                        HttpResponse(200, emptyMap(), """{"status":0,"body":{"more":false,"series":[]}}""")
                    }
                    else -> error("Unexpected action ${form["action"]}")
                }
            }
        }

        var session = WithingsWebSession(
            cookies = "session_token=old-tok-555",
            sessionToken = "old-tok-555",
            primaryUserId = 1001L,
            primaryUserName = "Petr",
            lastExtendedMs = 1730000000_000L // much older than 2 hours
        )

        val fetcher = WithingsWebFetcher(
            client = WithingsWebClient(fakeHttp),
            sessionProvider = { session },
            onSessionExtended = { session = it },
            defaultZone = "Europe/Prague",
            existingIds = { _, _ -> emptySet() },
            nowMs = { 1730950000_000L }
        )

        val batch = fetcher.fetch(SyncArea.WEIGHT, 1730900000_000L)
        assertEquals(1, extendCount)
        assertEquals("proactive-tok-888", session.sessionToken)
        assertTrue(session.cookies.contains("session_token=proactive-tok-888"))
        assertEquals(1730950000_000L, session.lastExtendedMs)
    }

    @Test
    fun testSessionKeyCookieIsNotReplacedBySessionTokenAsSessionid() {
        val fakeHttp = object : HttpGateway {
            override fun get(url: String, headers: Map<String, String>): HttpResponse = error("unused")
            override fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>): HttpResponse {
                // Regrese 401: session_token se nesmí posílat jako sessionid (server pak vrací status 250)
                assertFalse(form.containsKey("sessionid"))
                assertEquals("tok123", form["session_token"])
                assertEquals("hmw", form["appname"])
                val cookie = headers["Cookie"].orEmpty()
                assertTrue(cookie.contains("session_key=key456"))
                assertFalse(cookie.contains("sessionid="))
                return HttpResponse(200, emptyMap(), """{"status":0,"body":{"users":[{"id":1,"firstname":"A","lastname":"B","is_primary":1}]}}""")
            }
        }
        val users = WithingsWebClient(fakeHttp).getUsersList("session_key=key456; session_token=tok123; w_uuid=x", "tok123")
        assertEquals(1, users.size)
    }

    @Test
    fun testExtendSessionCapturesLowercaseHttp2SetCookieHeader() {
        val fakeHttp = object : HttpGateway {
            override fun get(url: String, headers: Map<String, String>): HttpResponse = error("unused")
            override fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>): HttpResponse {
                return HttpResponse(
                    code = 200,
                    headers = mapOf(
                        "set-cookie" to "session_key=rotated-key-777; Path=/; HttpOnly; Secure; session_token=rotated-tok-888; Path=/; Priority=High"
                    ),
                    body = """{"status":0,"body":{"sessionid":"rotated-key-777"}}"""
                )
            }
        }
        val extended = requireNotNull(WithingsWebClient(fakeHttp).extendSession("session_key=old-key-111; w_uuid=abc", null))
        assertEquals("rotated-tok-888", extended.sessionToken)
        assertTrue(extended.cookies.contains("session_key=rotated-key-777"))
        assertTrue(extended.cookies.contains("session_token=rotated-tok-888"))
        assertTrue(extended.cookies.contains("w_uuid=abc"))
        assertFalse(extended.cookies.contains("Priority="))
    }

    @Test
    fun testRefreshSessionUsesFallbackWhenExtendFails() {
        val fakeHttp = object : HttpGateway {
            override fun get(url: String, headers: Map<String, String>): HttpResponse = error("unused")
            override fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>): HttpResponse {
                return HttpResponse(200, emptyMap(), """{"status":2555,"body":{}}""")
            }
        }
        var session = WithingsWebSession(
            cookies = "session_key=expired",
            sessionToken = "expired",
            primaryUserId = 1001L,
            primaryUserName = "Petr",
            lastExtendedMs = 1730950000_000L
        )
        var fallbackCalled = false
        val fetcher = WithingsWebFetcher(
            client = WithingsWebClient(fakeHttp),
            sessionProvider = { session },
            onSessionExtended = { session = it },
            defaultZone = "Europe/Prague",
            existingIds = { _, _ -> emptySet() },
            nowMs = { 1730950000_000L },
            onRefreshFallback = {
                fallbackCalled = true
                session.copy(cookies = "session_key=fresh-from-sso", sessionToken = "fresh-tok")
            }
        )
        fetcher.refreshSession()
        assertTrue(fallbackCalled)
        assertEquals("fresh-tok", session.sessionToken)
        assertTrue(session.cookies.contains("session_key=fresh-from-sso"))
    }

    @Test
    fun testHydrationWithoutWeightIsSkippedAndOutOfRangeIsSuspect() {
        val noWeightGroup = MeasureGroup(
            grpId = 100L,
            date = 1730800000L,
            modified = 1730800000L,
            attrib = 0,
            category = 1,
            deviceId = null,
            timezone = null,
            measures = listOf(Measure(WithingsWebTypes.HYDRATION, 45.0))
        )
        val noWeightRes = WithingsWebMapper.map(listOf(noWeightGroup), 1001L, "Europe/Prague")
        assertEquals(0, noWeightRes.items.size)
        assertEquals(1, noWeightRes.skipped)

        val outOfRangeGroup = MeasureGroup(
            grpId = 101L,
            date = 1730800100L,
            modified = 1730800100L,
            attrib = 0,
            category = 1,
            deviceId = null,
            timezone = null,
            measures = listOf(
                Measure(WithingsWebTypes.WEIGHT, 80.0),
                Measure(WithingsWebTypes.HYDRATION, 16.0) // 20% -> < 25% -> SUSPECT
            )
        )
        val suspectRes = WithingsWebMapper.map(listOf(outOfRangeGroup), 1001L, "Europe/Prague")
        val water = suspectRes.items.first { it.type == MetricType.BODY_WATER }
        assertEquals(20.0, water.value, 0.01)
        assertEquals(Quality.SUSPECT, water.quality)
    }

    @Test
    fun testEcgWithoutNumericValuesEmitsOnlyAfibAndNever72Fallback() {
        val obs = WithingsWebMapper.mapEcgSummaries(
            summaries = listOf(WithingsEcgSummary(signalId = 999L, timestampSec = 1730850100L, heartRate = null, afibClassification = 1)),
            userId = 1001L,
            defaultZone = "Europe/Prague",
        )
        assertEquals(1, obs.size)
        assertEquals(MetricType.ECG_AFIB, obs[0].type)
        assertEquals(EcgAfibClassification.AFIB.code, obs[0].value)
        assertFalse(obs.any { it.value == 72.0 })
    }

    @Test
    fun testEcgFetchFailureDoesNotTombstoneExistingEcgObservations() {
        val fakeHttp = object : HttpGateway {
            override fun get(url: String, headers: Map<String, String>): HttpResponse = error("unused")
            override fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>): HttpResponse {
                return when (form["action"]) {
                    "getmeas" -> HttpResponse(
                        200, emptyMap(),
                        """{"status":0,"body":{"updatetime":1730900000,"more":0,"measuregrps":[{"grpid":9001,"attrib":0,"date":1730850000,"category":1,"measures":[{"value":77500,"unit":-3,"type":1}]}]}}"""
                    )
                    "list" -> HttpResponse(500, emptyMap(), """{"status":500}""")
                    else -> error("Unexpected action ${form["action"]}")
                }
            }
        }
        val session = WithingsWebSession("session_token=tok", "tok", 1001L, "Petr", lastExtendedMs = 1730900000_000L)
        val existingEcgId = "WITHINGS_API|1001|id:ecg:77701:afib"
        val fetcher = WithingsWebFetcher(
            client = WithingsWebClient(fakeHttp),
            sessionProvider = { session },
            defaultZone = "Europe/Prague",
            existingIds = { _, _ -> setOf(existingEcgId) },
            nowMs = { 1730900000_000L }
        )
        val batch = fetcher.fetch(SyncArea.WEIGHT, 1730800000_000L)
        assertFalse(batch.tombstones.contains(existingEcgId))
    }

    @Test
    fun testExplicitDeleteOutsideWindowIsTombstoned() {
        val fakeHttp = object : HttpGateway {
            override fun get(url: String, headers: Map<String, String>): HttpResponse = error("unused")
            override fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>): HttpResponse {
                return when (form["action"]) {
                    "getmeas" -> HttpResponse(
                        200, emptyMap(),
                        """{"status":0,"body":{"updatetime":1730900000,"more":0,"measuregrps":[{"grpid":5001,"attrib":0,"date":1700000000,"category":1,"deleted":1,"measures":[]}]}}"""
                    )
                    "list" -> HttpResponse(200, emptyMap(), """{"status":0,"body":{"more":false,"series":[]}}""")
                    else -> error("Unexpected action ${form["action"]}")
                }
            }
        }
        val session = WithingsWebSession("session_token=tok", "tok", 1001L, "Petr", lastExtendedMs = 1730900000_000L)
        val oldId = "WITHINGS_API|1001|id:5001:1"
        val fetcher = WithingsWebFetcher(
            client = WithingsWebClient(fakeHttp),
            sessionProvider = { session },
            defaultZone = "Europe/Prague",
            existingIds = { _, _ -> emptySet() },
            existingIdsByGrpIds = { grpIds -> if (5001L in grpIds) setOf(oldId) else emptySet() },
            nowMs = { 1730900000_000L }
        )
        val batch = fetcher.fetch(SyncArea.WEIGHT, 1730800000_000L)
        assertTrue(batch.tombstones.contains(oldId))
    }
}

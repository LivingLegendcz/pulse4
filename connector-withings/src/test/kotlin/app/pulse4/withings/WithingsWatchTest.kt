package app.pulse4.withings

import app.pulse4.domain.Activity
import app.pulse4.domain.MetricType
import app.pulse4.domain.Provider
import app.pulse4.domain.Sport
import app.pulse4.provider.HttpGateway
import app.pulse4.provider.HttpResponse
import app.pulse4.provider.SyncArea
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WithingsWatchTest {

    @Test
    fun getmeasMapsSpo2Type54InWebAndOAuthMappers() {
        val group = MeasureGroup(
            grpId = 42001L,
            date = 1763700000L,
            modified = 1763700010L,
            attrib = 0,
            category = 1,
            deviceId = "scanwatch2",
            timezone = "Europe/Prague",
            measures = listOf(
                Measure(type = WithingsWebTypes.SPO2, value = 98.0),
                Measure(type = WithingsWebTypes.HEART_RATE, value = 61.0),
            ),
        )
        val webRes = WithingsWebMapper.map(listOf(group), 1001L, "Europe/Prague")
        val webByType = webRes.items.associateBy { it.type }
        assertEquals(98.0, webByType.getValue(MetricType.SPO2).value, 0.001)
        assertEquals(61.0, webByType.getValue(MetricType.HEART_RATE).value, 0.001)

        val oauthRes = WithingsMapper.map(
            listOf(group),
            "1001",
            "Europe/Prague",
            WithingsConfig(redirectUri = "https://pulse4.example.invalid/withings/callback"),
        )
        val oauthByType = oauthRes.items.associateBy { it.type }
        assertEquals(98.0, oauthByType.getValue(MetricType.SPO2).value, 0.001)
    }

    @Test
    fun webClientFetchesAndMapsDailyActivitySleepAndWorkoutsWithPagination() {
        val fakeHttp = object : HttpGateway {
            override fun get(url: String, headers: Map<String, String>): HttpResponse = error("unused")
            override fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>): HttpResponse {
                return when (form["action"]) {
                    "getactivity" -> {
                        if (form["offset"] == null) {
                            HttpResponse(
                                200,
                                emptyMap(),
                                """
                                {
                                  "status": 0,
                                  "body": {
                                    "more": true,
                                    "offset": 1,
                                    "activities": [
                                      {
                                        "date": "2026-11-20",
                                        "timezone": "Europe/Prague",
                                        "deviceid": "sw2-1",
                                        "steps": 9840,
                                        "distance": 7420.5,
                                        "elevation": 18.0,
                                        "calories": 510.0,
                                        "hr_average": 68,
                                        "hr_min": 51,
                                        "hr_max": 149
                                      },
                                      {
                                        "date": "2026-11-19",
                                        "is_missing": true,
                                        "steps": 0
                                      }
                                    ]
                                  }
                                }
                                """.trimIndent(),
                            )
                        } else {
                            HttpResponse(
                                200,
                                emptyMap(),
                                """
                                {
                                  "status": 0,
                                  "body": {
                                    "more": false,
                                    "offset": 0,
                                    "activities": [
                                      {
                                        "date": "2026-11-21",
                                        "timezone": "Europe/Prague",
                                        "deviceid": "sw2-1",
                                        "steps": 4120,
                                        "hr_average": 70
                                      }
                                    ]
                                  }
                                }
                                """.trimIndent(),
                            )
                        }
                    }
                    "getsummary" -> HttpResponse(
                        200,
                        emptyMap(),
                        """
                        {
                          "status": 0,
                          "body": {
                            "more": false,
                            "series": [
                              {
                                "id": 88001,
                                "timezone": "Europe/Prague",
                                "model": 16,
                                "startdate": 1763676000,
                                "enddate": 1763704800,
                                "date": "2026-11-21",
                                "data": {
                                  "total_sleep_time": 26100,
                                  "total_timeinbed": 28800,
                                  "deepsleepduration": 6300,
                                  "lightsleepduration": 14400,
                                  "remsleepduration": 5400,
                                  "waso": 900,
                                  "wakeupcount": 2,
                                  "sleep_score": 84,
                                  "hr_average": 56,
                                  "hr_min": 49,
                                  "rr_average": 14
                                }
                              }
                            ]
                          }
                        }
                        """.trimIndent(),
                    )
                    "getworkouts" -> HttpResponse(
                        200,
                        emptyMap(),
                        """
                        {
                          "status": 0,
                          "body": {
                            "more": false,
                            "series": [
                              {
                                "id": 99001,
                                "category": 2,
                                "timezone": "Europe/Prague",
                                "startdate": 1763710000,
                                "enddate": 1763713600,
                                "date": "2026-11-21",
                                "deviceid": "sw2-1",
                                "data": {
                                  "calories": 640.5,
                                  "effduration": 3420,
                                  "steps": 8500,
                                  "distance": 10200.0,
                                  "elevation": 85.0,
                                  "hr_average": 152,
                                  "hr_max": 178,
                                  "hr_zone_0": 120,
                                  "hr_zone_1": 600,
                                  "hr_zone_2": 1800,
                                  "hr_zone_3": 900,
                                  "spo2_average": 97.5
                                }
                              },
                              {
                                "id": 99002,
                                "category": 7,
                                "timezone": "Europe/Prague",
                                "startdate": 1763620000,
                                "enddate": 1763622400,
                                "data": {
                                  "calories": 420.0,
                                  "distance": 1500.0,
                                  "strokes": 620,
                                  "pool_length": 25.0,
                                  "hr_average": 134
                                }
                              }
                            ]
                          }
                        }
                        """.trimIndent(),
                    )
                    "getmeas" -> HttpResponse(200, emptyMap(), """{"status":0,"body":{"measuregrps":[]}}""")
                    "list" -> HttpResponse(200, emptyMap(), """{"status":0,"body":{"series":[]}}""")
                    else -> error("Unexpected action: ${form["action"]}")
                }
            }
        }

        val client = WithingsWebClient(fakeHttp)
        val savedWorkouts = mutableListOf<Activity>()
        val nowMs = 1763740000_000L
        val session = WithingsWebSession(
            cookies = "session_key=ok",
            primaryUserId = 1001L,
            primaryUserName = "Test",
            primaryUserTimezone = "Europe/Prague",
            lastExtendedMs = nowMs,
        )
        val fetcher = WithingsWebFetcher(
            client = client,
            sessionProvider = { session },
            defaultZone = "Europe/Prague",
            existingIds = { _, _ -> emptySet() },
            nowMs = { nowMs },
            onWorkouts = { savedWorkouts += it },
        )

        val batch = fetcher.fetch(SyncArea.WEIGHT, sinceMs = nowMs - 7L * 86_400_000L)

        // Daily activity (2 days paginated, is_missing skipped)
        val stepsByDate = batch.items.filter { it.type == MetricType.STEPS }.associate { it.localDate to it.value }
        assertEquals(9840.0, stepsByDate["2026-11-20"])
        assertEquals(4120.0, stepsByDate["2026-11-21"])
        assertEquals(2, stepsByDate.size)

        // Sleep summary -> all 9 Pulse4 sleep/recovery metrics
        val sleepByType = batch.items.filter { it.externalId?.startsWith("sleep:") == true }.associateBy { it.type }
        assertEquals(26100.0, sleepByType.getValue(MetricType.SLEEP_DURATION).value, 0.001)
        assertEquals(84.0, sleepByType.getValue(MetricType.SLEEP_SCORE).value, 0.001)
        assertEquals(6300.0, sleepByType.getValue(MetricType.SLEEP_DEEP).value, 0.001)
        assertEquals(14400.0, sleepByType.getValue(MetricType.SLEEP_LIGHT).value, 0.001)
        assertEquals(5400.0, sleepByType.getValue(MetricType.SLEEP_REM).value, 0.001)
        assertEquals(900.0, sleepByType.getValue(MetricType.SLEEP_AWAKE).value, 0.001)
        assertEquals(2.0, sleepByType.getValue(MetricType.SLEEP_AWAKE_COUNT).value, 0.001)
        assertEquals(14.0, sleepByType.getValue(MetricType.RESPIRATION).value, 0.001)
        assertEquals(49.0, sleepByType.getValue(MetricType.RESTING_HEART_RATE).value, 0.001)

        // Workout SpO2 observation
        val wktSpo2 = batch.items.firstOrNull { it.type == MetricType.SPO2 && it.externalId == "wkt:99001:spo2" }
        assertEquals(97.5, wktSpo2?.value)

        // Mapped domain Activities
        assertEquals(2, savedWorkouts.size)
        val run = savedWorkouts.first { it.id == "withings-99001" }
        assertEquals(Provider.WITHINGS_API, run.provider)
        assertEquals(Sport.RUNNING, run.sport)
        assertEquals("Běh", run.title)
        assertEquals(3600.0, run.totalTimeS, 0.001)
        assertEquals(3420.0, run.movingTimeS ?: 0.0, 0.001)
        assertEquals(10200.0, run.distanceM ?: 0.0, 0.001)
        assertEquals(85.0, run.ascentM ?: 0.0, 0.001)
        assertEquals(152, run.avgHeartRate)
        assertEquals(178, run.maxHeartRate)
        assertEquals(listOf(120.0, 600.0, 1800.0, 900.0), run.hrZoneTimesS)
        assertTrue("withings" in run.tags)

        val swim = savedWorkouts.first { it.id == "withings-99002" }
        assertEquals(Sport.SWIMMING, swim.sport)
        assertEquals("Plavání", swim.title)
        assertEquals(620, swim.totalStrokes)
        assertEquals(25.0, swim.poolLengthM)
    }

    @Test
    fun failedWatchEndpointsNeverBreakWeightSyncOrCreateWatchTombstones() {
        val fakeHttp = object : HttpGateway {
            override fun get(url: String, headers: Map<String, String>): HttpResponse = error("unused")
            override fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>): HttpResponse =
                when (form["action"]) {
                    "getmeas" -> HttpResponse(
                        200,
                        emptyMap(),
                        """
                        {
                          "status": 0,
                          "body": {
                            "measuregrps": [
                              {
                                "grpid": 5001,
                                "attrib": 0,
                                "date": 1763700000,
                                "modified": 1763700000,
                                "category": 1,
                                "measures": [{ "value": 81200, "unit": -3, "type": 1 }]
                              }
                            ]
                          }
                        }
                        """.trimIndent(),
                    )
                    "list" -> HttpResponse(200, emptyMap(), """{"status":0,"body":{"series":[]}}""")
                    else -> HttpResponse(200, emptyMap(), """{"status":503,"error":"unsupported"}""")
                }
        }

        val existing = setOf(
            "WITHINGS_API|1001|id:act:2026-11-20:steps",
            "WITHINGS_API|1001|id:sleep:88001:dur",
            "WITHINGS_API|1001|id:wkt:99001:spo2",
        )
        val nowMs = 1763740000_000L
        val session = WithingsWebSession(cookies = "session_key=ok", primaryUserId = 1001L, lastExtendedMs = nowMs)
        val fetcher = WithingsWebFetcher(
            client = WithingsWebClient(fakeHttp),
            sessionProvider = { session },
            defaultZone = "Europe/Prague",
            existingIds = { _, _ -> existing },
            nowMs = { nowMs },
        )

        val batch = fetcher.fetch(SyncArea.WEIGHT, sinceMs = nowMs - 7L * 86_400_000L)
        assertEquals(1, batch.items.size)
        assertEquals(81.2, batch.items.first().value, 0.001)
        assertTrue(batch.tombstones.isEmpty(), "Watch observation IDs must never be tombstoned when watch endpoints fail")
    }

    @Test
    fun officialOAuthApiFetchesWatchActivitySleepAndWorkoutsWhenScopeIncludesUserActivity() {
        val nowMs = 1763740000_000L
        val cfg = WithingsConfig(redirectUri = "https://pulse4.example.invalid/withings/callback")
        var stored: WithingsTokens? = WithingsTokens(
            userId = "u42",
            accessToken = "tok-ok",
            refreshToken = "ref-ok",
            scope = "user.metrics,user.activity",
            expiresAtMs = nowMs + 3_600_000L,
            issuedAtMs = nowMs,
        )
        val vault = object : TokenVault {
            override fun load(): WithingsTokens? = stored
            override fun save(t: WithingsTokens) { stored = t }
            override fun clear() { stored = null }
        }
        val fakeHttp = object : HttpGateway {
            override fun get(url: String, headers: Map<String, String>): HttpResponse = error("unused")
            override fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>): HttpResponse {
                assertEquals("Bearer tok-ok", headers["Authorization"])
                return when (form["action"]) {
                    "getmeas" -> HttpResponse(200, emptyMap(), """{"status":0,"body":{"measuregrps":[]}}""")
                    "getactivity" -> {
                        assertEquals(cfg.measureV2Url, url)
                        HttpResponse(
                            200,
                            emptyMap(),
                            """{"status":0,"body":{"activities":[{"date":"2026-11-21","steps":6543,"hr_average":66}]}}""",
                        )
                    }
                    "getsummary" -> {
                        assertEquals(cfg.sleepV2Url, url)
                        HttpResponse(
                            200,
                            emptyMap(),
                            """{"status":0,"body":{"series":[{"id":701,"startdate":1763676000,"enddate":1763704800,"date":"2026-11-21","data":{"total_sleep_time":25200,"sleep_score":81}}]}}""",
                        )
                    }
                    "getworkouts" -> {
                        assertEquals(cfg.measureV2Url, url)
                        HttpResponse(
                            200,
                            emptyMap(),
                            """{"status":0,"body":{"series":[{"id":801,"category":6,"startdate":1763710000,"enddate":1763713600,"data":{"distance":24500.0,"calories":710.0,"hr_average":144}}]}}""",
                        )
                    }
                    else -> error("Unexpected action ${form["action"]}")
                }
            }
        }
        val session = WithingsSession(cfg, { WithingsCredentials("id", "sec") }, fakeHttp, vault, now = { nowMs })
        val api = WithingsApi(cfg, session, fakeHttp)
        val workouts = mutableListOf<Activity>()
        val fetcher = WithingsFetcher(
            config = cfg,
            api = api,
            session = session,
            userId = { "u42" },
            zoneId = "Europe/Prague",
            existingIds = { _, _ -> emptySet() },
            nowMs = { nowMs },
            onWorkouts = { workouts += it },
        )
        val batch = fetcher.fetch(SyncArea.WEIGHT, sinceMs = nowMs - 3L * 86_400_000L)
        assertEquals(6543.0, batch.items.first { it.type == MetricType.STEPS }.value, 0.001)
        assertEquals(25200.0, batch.items.first { it.type == MetricType.SLEEP_DURATION }.value, 0.001)
        assertEquals(81.0, batch.items.first { it.type == MetricType.SLEEP_SCORE }.value, 0.001)
        assertEquals(1, workouts.size)
        assertEquals("withings-801", workouts.single().id)
        assertEquals(Sport.CYCLING, workouts.single().sport)
        assertEquals("Cyklistika", workouts.single().title)
    }
}

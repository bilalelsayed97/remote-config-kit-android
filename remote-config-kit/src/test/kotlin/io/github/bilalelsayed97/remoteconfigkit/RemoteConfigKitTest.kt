@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.bilalelsayed97.remoteconfigkit

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

class RemoteConfigKitTest {
    private val store = InMemoryKeyValueStore()

    @Test
    fun rolloutHashFollowsSharedUtf8Fnv1aVectors() {
        assertEquals(0x4f9f2cabL, fnv1a32("hello"))
        assertEquals(0x1e9de8c1L, fnv1a32("é"))
    }

    @Test
    fun downloadRemainsStagedUntilActivationAndSurvivesANewClient() = runTest {
        val payload = document(
            parameters = listOf(
                parameter("enabled", "boolean", true),
                parameter("limits", "json", mapOf("daily" to 4)),
            ),
        )
        val kit = createKit({ _, _, _ -> ok(payload) }, store, defaults = mapOf("enabled" to false))

        assertFalse(kit.getBool("enabled"))
        assertEquals(ConfigSource.LOCAL_DEFAULT, kit.getSource("enabled"))
        assertEquals(FetchStatus.FETCHED, kit.fetch())
        assertFalse(kit.getBool("enabled"))
        assertTrue(kit.activate())
        assertTrue(kit.getBool("enabled"))
        assertEquals(ConfigSource.REMOTE, kit.getSource("enabled"))
        assertEquals(jsonObjectOf("daily" to 4), kit.getJson("limits"))

        val reopened = createKit({ _, _, _ -> offline() }, store)
        assertTrue(reopened.getBool("enabled"))
        assertEquals(kit.installationId, reopened.installationId)
        assertEquals(kit.firstOpen, reopened.firstOpen)
        assertFailsWith<IOException> { reopened.fetch() }
        assertTrue(reopened.getBool("enabled"))
        kit.close()
        reopened.close()
    }

    @Test
    fun etagRevalidationAndMinimumIntervalAvoidRedundantDownloads() = runTest {
        var requests = 0
        val kit = createKit({ _, headers, _ ->
            requests++
            if (requests == 1) {
                ok(document(parameters = listOf(parameter("message", "string", "ready"))), mapOf("ETag" to "\"revision-1\""))
            } else {
                assertEquals("\"revision-1\"", headers["If-None-Match"])
                HttpResponse(304)
            }
        }, store)
        assertEquals(FetchStatus.FETCHED, kit.fetch())
        assertEquals(FetchStatus.UNCHANGED, kit.fetch())
        assertTrue(kit.activate())
        assertEquals("ready", kit.getString("message"))
        kit.close()

        val cached = createKit(
            { _, _, _ -> fail("Minimum interval should skip the network") },
            store,
            minimumFetchInterval = 1.hours,
        )
        assertEquals(FetchStatus.SKIPPED, cached.fetch())
        cached.close()
    }

    @Test
    fun warmStartExposesActiveCacheWhileOfflineRefreshIsPending() = runTest {
        store.putString(cachePrefix + "active", document(parameters = listOf(parameter("message", "string", "cached"))))
        val requestStarted = CompletableDeferred<Unit>()
        val response = CompletableDeferred<HttpResponse>()
        val kit = createKit({ _, _, _ ->
            requestStarted.complete(Unit)
            response.await()
        }, store)

        kit.start()
        assertEquals("cached", kit.getString("message"))
        runCurrent()
        assertTrue(requestStarted.isCompleted)
        response.completeExceptionally(IOException("offline"))
        runCurrent()
        assertEquals("cached", kit.getString("message"))
        kit.close()
    }

    @Test
    fun coldStartWaitsThroughAnOfflineFailureAndActivatesRetryResult() = runTest {
        val requestTimes = mutableListOf<Long>()
        val kit = createKit({ _, _, _ ->
            requestTimes += testScheduler.currentTime
            if (requestTimes.size == 1) offline()
            ok(document(parameters = listOf(parameter("message", "string", "recovered"))))
        }, store)

        val startup = async { kit.start() }
        runCurrent()
        assertEquals(1, requestTimes.size)
        advanceTimeBy(20)
        assertFalse(startup.isCompleted)
        assertNull(kit.activeVersion)

        advanceTimeBy(1_000)
        runCurrent()
        startup.await()
        assertEquals(2, requestTimes.size)
        assertTrue(requestTimes[1] - requestTimes[0] >= 1_000)
        assertEquals("recovered", kit.getString("message"))
        kit.close()
    }

    @Test
    fun useDefaultsReturnsLocalDefaultsWhileOfflineAndActivatesRecovery() = runTest {
        val firstResponse = CompletableDeferred<HttpResponse>()
        var requests = 0
        val kit = createKit({ _, _, _ ->
            requests++
            if (requests == 1) {
                firstResponse.await()
            } else {
                ok(document(parameters = listOf(parameter("message", "string", "remote"))))
            }
        }, store, defaults = mapOf("message" to "local"))
        val recovered = async { kit.changes.first { kit.activeVersion == 1 } }

        kit.start(InitialFetchPolicy.USE_DEFAULTS)
        assertNull(kit.activeVersion)
        assertEquals("local", kit.getString("message"))
        assertEquals(ConfigSource.LOCAL_DEFAULT, kit.getSource("message"))

        runCurrent()
        assertEquals(1, requests)
        firstResponse.completeExceptionally(IOException("offline"))
        advanceTimeBy(1_001)
        recovered.await()
        assertEquals(2, requests)
        assertEquals("remote", kit.getString("message"))
        assertEquals(ConfigSource.REMOTE, kit.getSource("message"))
        assertEquals(1L, kit.revision.value)
        kit.close()
    }

    @Test
    fun pendingCacheIsActivatedBeforeAnOfflineRefreshCompletes() = runTest {
        store.putString(cachePrefix + "pending", document(parameters = listOf(parameter("message", "string", "staged"))))
        val response = CompletableDeferred<HttpResponse>()
        val kit = createKit({ _, _, _ -> response.await() }, store)

        kit.start()
        assertEquals("staged", kit.getString("message"))
        runCurrent()
        response.completeExceptionally(IOException("offline"))
        runCurrent()

        val reopened = createKit({ _, _, _ -> offline() }, store)
        assertEquals("staged", reopened.getString("message"))
        kit.close()
        reopened.close()
    }

    @Test
    fun resumingTheAppRefreshesAnActiveCachedDocument() = runTest {
        store.putString(cachePrefix + "active", document(parameters = listOf(parameter("message", "string", "initial"))))
        var requests = 0
        val foreground = ManualForegroundSource()
        val kit = createKit({ _, _, _ ->
            requests++
            ok(document(version = requests + 1, parameters = listOf(parameter("message", "string", "version $requests"))))
        }, store, foreground = foreground)

        kit.start()
        runCurrent()
        assertEquals(2, kit.activeVersion)
        assertEquals("version 1", kit.getString("message"))

        foreground.set(false)
        foreground.set(true)
        runCurrent()
        assertEquals(3, kit.activeVersion)
        assertEquals("version 2", kit.getString("message"))
        assertEquals(2, requests)
        kit.close()
    }

    @Test
    fun backgroundedAppDoesNotRefresh() = runTest {
        store.putString(cachePrefix + "active", document(parameters = listOf(parameter("message", "string", "initial"))))
        var requests = 0
        val foreground = ManualForegroundSource(initial = false)
        val kit = createKit({ _, _, _ ->
            requests++
            HttpResponse(304)
        }, store, foreground = foreground)

        kit.start()
        advanceTimeBy(120_000)
        assertEquals(0, requests)
        foreground.set(true)
        runCurrent()
        assertEquals(1, requests)
        kit.close()
    }

    @Test
    fun foregroundTimerRefreshesAnActiveCachedDocument() = runTest {
        store.putString(cachePrefix + "active", document(parameters = listOf(parameter("message", "string", "initial"))))
        var requests = 0
        val kit = createKit({ _, _, _ ->
            requests++
            if (requests > 2) {
                HttpResponse(304)
            } else {
                ok(
                    document(
                        version = requests + 1,
                        parameters = listOf(parameter("message", "string", if (requests == 1) "startup" else "periodic")),
                    ),
                )
            }
        }, store, minimumFetchInterval = 20.milliseconds)

        kit.start()
        advanceTimeBy(100)
        assertEquals(3, kit.activeVersion)
        assertEquals("periodic", kit.getString("message"))
        kit.close()
    }

    @Test
    fun lastModifiedRevalidatesACachedDocumentAfterRestart() = runTest {
        val modified = "Fri, 02 Oct 2026 12:00:00 GMT"
        val first = createKit({ _, _, _ ->
            ok(document(parameters = listOf(parameter("message", "string", "cached"))), mapOf("Last-Modified" to modified))
        }, store)
        assertEquals(FetchStatus.FETCHED, first.fetch())
        assertTrue(first.activate())

        val reopened = createKit({ _, headers, _ ->
            assertEquals(modified, headers["If-Modified-Since"])
            assertFalse("If-None-Match" in headers)
            HttpResponse(304)
        }, store)
        assertEquals(FetchStatus.UNCHANGED, reopened.fetch())
        assertEquals("cached", reopened.getString("message"))
        first.close()
        reopened.close()
    }

    @Test
    fun corruptPendingCacheClearsValidatorsAndFetchInterval() = runTest {
        store.putString(cachePrefix + "active", document(parameters = listOf(parameter("message", "string", "old"))))
        store.putString(cachePrefix + "pending", "{broken")
        store.putString(cachePrefix + "etag", "\"stale\"")
        store.putString(cachePrefix + "last_modified", "Fri, 02 Oct 2026 12:00:00 GMT")
        store.putString(cachePrefix + "fetched_at", Instant.parse("2026-11-03T12:00:00Z").toString())
        val kit = createKit({ _, headers, _ ->
            assertFalse("If-None-Match" in headers)
            assertFalse("If-Modified-Since" in headers)
            ok(document(version = 2, parameters = listOf(parameter("message", "string", "new"))))
        }, store, minimumFetchInterval = 1.hours)

        assertEquals("old", kit.getString("message"))
        assertNull(kit.lastFetchTime)
        assertEquals(FetchStatus.FETCHED, kit.fetch())
        assertTrue(kit.activate())
        assertEquals("new", kit.getString("message"))
        kit.close()
    }

    @Test
    fun coldHttp404FailsWithoutRetrying() = runTest {
        var requests = 0
        val kit = createKit({ _, _, _ ->
            requests++
            HttpResponse(404)
        }, store)

        val error = assertFailsWith<ConfigFetchHttpException> { kit.start() }
        assertEquals(404, error.statusCode)
        assertEquals(1, requests)
        kit.close()
    }

    @Test
    fun coldMalformedDocumentFailsWithoutRetrying() = runTest {
        var requests = 0
        val kit = createKit({ _, _, _ ->
            requests++
            ok("""{"schemaVersion":2}""")
        }, store)

        assertFailsWith<ConfigFormatException> { kit.start() }
        assertEquals(1, requests)
        kit.close()
    }

    @Test
    fun closingStopsAStartupWaitingForRemote() = runTest {
        val kit = createKit({ _, _, _ -> offline() }, store)
        val startup = async { runCatching { kit.start() } }
        advanceTimeBy(500)
        kit.close()
        runCurrent()
        val error = startup.await().exceptionOrNull()
        assertTrue(error is IllegalStateException, "Expected closed error, got $error")
    }

    @Test
    fun firstMatchingConditionWinsAndContextChangesRecalculateValues() = runTest {
        val payload = document(
            conditions = listOf(
                condition("ios-old", listOf(rule("platform", "eq", "ios"), rule("app_version", "lt", "2.5.74"))),
                condition("ios", listOf(rule("platform", "eq", "ios"))),
            ),
            parameters = listOf(
                parameter(
                    "banner", "string", "default",
                    overrides = listOf(override("ios", "ios"), override("ios-old", "old ios")),
                ),
            ),
        )
        val kit = createKit(
            { _, _, _ -> ok(payload) },
            store,
            context = TargetingContext(platform = "ios", appVersion = "2.5.35"),
        )
        kit.fetchAndActivate()
        assertEquals("old ios", kit.getString("banner"))
        kit.updateContext(TargetingContext(platform = "ios", appVersion = "2.5.74"))
        assertEquals("ios", kit.getString("banner"))
        kit.updateContext(TargetingContext(platform = "android", appVersion = "2.5.35"))
        assertEquals("default", kit.getString("banner"))
        kit.close()
    }

    @Test
    fun invalidFetchedDocumentLeavesTheActivatedVersionIntact() = runTest {
        var requests = 0
        val kit = createKit({ _, _, _ ->
            requests++
            if (requests == 1) {
                ok(document(parameters = listOf(parameter("enabled", "boolean", true))))
            } else {
                ok("""{"schemaVersion":2}""")
            }
        }, store)
        kit.fetchAndActivate()
        assertFailsWith<ConfigFormatException> { kit.fetch() }
        assertEquals(1, kit.activeVersion)
        assertTrue(kit.getBool("enabled"))
        kit.close()
    }

    @Test
    fun corruptCachedDocumentFallsBackToDefaultsAndRefetches() = runTest {
        store.putString(cachePrefix + "active", "{broken")
        store.putString(cachePrefix + "etag", "\"bad-revision\"")
        store.putString(cachePrefix + "fetched_at", "2026-11-03T12:00:00Z")
        val kit = createKit(
            { _, _, _ -> ok(document(parameters = listOf(parameter("enabled", "boolean", true)))) },
            store,
            defaults = mapOf("enabled" to false),
            minimumFetchInterval = 1.hours,
        )
        assertFalse(kit.getBool("enabled"))
        assertEquals(FetchStatus.FETCHED, kit.fetch())
        assertTrue(kit.activate())
        assertTrue(kit.getBool("enabled"))
        kit.close()
    }

    @Test
    fun percentageRolloutIsStableAndRespectsZeroAndFullRollout() = runTest {
        val payload = document(
            conditions = listOf(
                condition("zero", listOf(rule("percentage", "lt", 0))),
                condition("full", listOf(rule("percentage", "lt", 100))),
            ),
            parameters = listOf(
                parameter("feature", "string", "off", overrides = listOf(override("zero", "never"), override("full", "always"))),
            ),
        )
        val kit = createKit({ _, _, _ -> ok(payload) }, store)
        kit.fetchAndActivate()
        assertEquals("always", kit.getString("feature"))
        assertEquals("always", kit.getString("feature"))
        kit.close()
    }

    @Test
    fun appAndUserSignalsCombineWithDateAndBuildRanges() = runTest {
        val payload = document(
            conditions = listOf(
                condition(
                    "target",
                    listOf(
                        rule("app_id", "eq", "consumer-app"),
                        rule("build_number", "between", listOf(35, 40)),
                        rule("country", "eq", "SA"),
                        rule("user_exists", "eq", true),
                        rule("audience", "in", listOf("beta")),
                        rule("user_property.tier", "eq", "gold"),
                        rule("custom_signal.promo", "eq", true),
                        rule("first_open", "gte", "2020-01-01T00:00:00Z"),
                        rule("date_time", "lt", "2100-01-01T00:00:00Z"),
                    ),
                ),
            ),
            parameters = listOf(parameter("offer", "string", "none", overrides = listOf(override("target", "visible")))),
        )
        val kit = createKit(
            { _, _, _ -> ok(payload) },
            store,
            context = TargetingContext(
                appId = "consumer-app",
                buildNumber = "36",
                country = "SA",
                userId = "customer-1",
                audienceIds = setOf("beta"),
                userProperties = mapOf("tier" to "gold"),
                customSignals = mapOf("promo" to true),
            ),
        )
        kit.fetchAndActivate()
        assertEquals("visible", kit.getString("offer"))
        kit.updateContext(TargetingContext(appId = "consumer-app"))
        assertEquals("none", kit.getString("offer"))
        kit.close()
    }

    @Test
    fun inspectionExposesExactAutoFilledTargetingAndSelectedCondition() = runTest {
        val payload = document(
            conditions = listOf(
                condition(
                    "app-build",
                    listOf(
                        rule("app_id", "eq", "com.example.app"),
                        rule("app_version", "eq", "2.5.74"),
                        rule("build_number", "eq", "36"),
                        rule("user_exists", "eq", true),
                    ),
                ),
            ),
            parameters = listOf(parameter("enabled", "boolean", false, overrides = listOf(override("app-build", true)))),
        )
        val kit = createKit({ _, _, _ -> ok(payload) }, store)
        kit.fetchAndActivate()

        val inspection = kit.inspect("enabled")
        assertEquals(JsonPrimitive(true), inspection.value)
        assertEquals(ConfigSource.REMOTE, inspection.source)
        assertEquals("app-build", inspection.matchedConditionId)
        val attributes = inspection.targeting.attributes
        assertEquals(primitive("com.example.app"), attributes["app_id"])
        assertEquals(primitive("2.5.74"), attributes["app_version"])
        assertEquals(primitive("36"), attributes["build_number"])
        assertEquals(JsonPrimitive(true), attributes["user_exists"])
        assertEquals(JsonNullValue, attributes["user_id"])
        assertEquals(primitive(kit.installationId), attributes["installation_id"])
        assertEquals(primitive(formatInstant(kit.firstOpen)), inspection.targeting.toJson()["first_open"])
        assertNull(kit.inspect("missing").value)

        kit.updateContext(TargetingContext(appId = "other.app"))
        assertEquals(primitive("other.app"), kit.targetingSnapshot().attributes["app_id"])
        assertEquals(primitive("2.5.74"), kit.targetingSnapshot().attributes["app_version"])
        assertNull(kit.inspect("enabled").matchedConditionId)
        kit.close()
    }

    @Test
    fun typedGettersRejectMismatchedTypesAndUseStaticFallbacks() = runTest {
        val payload = document(parameters = listOf(parameter("count", "number", 3), parameter("ratio", "number", 0.5)))
        val kit = createKit({ _, _, _ -> ok(payload) }, store)
        kit.fetchAndActivate()
        assertEquals(3, kit.getInt("count"))
        assertEquals(3L, kit.getLong("count"))
        assertEquals(0.5, kit.getDouble("ratio"))
        assertFailsWith<IllegalStateException> { kit.getInt("ratio") }
        assertFailsWith<IllegalStateException> { kit.getBool("count") }
        assertFalse(kit.getBool("missing"))
        assertEquals("", kit.getString("missing"))
        assertEquals(0, kit.getInt("missing"))
        assertEquals(0.0, kit.getDouble("missing"))
        assertNull(kit.getJson("missing"))
        assertEquals(ConfigSource.STATIC_FALLBACK, kit.getSource("missing"))
        kit.close()
    }

    @Test
    fun targetingSnapshotsRetainTheSignalValuesTheyCaptured() = runTest {
        val tags = mutableListOf("initial")
        val kit = createKit({ _, _, _ -> offline() }, store, context = TargetingContext(customSignals = mapOf("tags" to tags)))
        val snapshot = kit.targetingSnapshot()
        tags += "later"
        assertEquals(jsonArrayOf("initial"), snapshot.attributes["custom_signal.tags"])
        kit.close()
    }

    @Test
    fun evaluationTimeChangesOnActivationAndSurvivesOfflineRestart() = runTest {
        val payload = document(parameters = listOf(parameter("enabled", "boolean", true)))
        val kit = createKit({ _, _, _ -> ok(payload) }, store)
        val beforeFetch = kit.targetingSnapshot().attributes["date_time"]
        advanceTimeBy(10)
        kit.fetch()
        assertEquals(beforeFetch, kit.targetingSnapshot().attributes["date_time"])
        kit.activate()
        val firstActivation = kit.targetingSnapshot().attributes["date_time"]
        assertTrue(firstActivation != beforeFetch)
        advanceTimeBy(10)
        assertEquals(firstActivation, kit.targetingSnapshot().attributes["date_time"])
        kit.fetch()
        assertEquals(firstActivation, kit.targetingSnapshot().attributes["date_time"])
        kit.activate()
        val secondActivation = kit.targetingSnapshot().attributes["date_time"]
        assertTrue(secondActivation != firstActivation)

        val reopened = createKit({ _, _, _ -> offline() }, store)
        assertEquals(secondActivation, reopened.targetingSnapshot().attributes["date_time"])
        assertTrue(reopened.getBool("enabled"))
        kit.close()
        reopened.close()
    }

    @Test
    fun sharedRolloutSeedAndNotContainsEvaluatePublishedRules() = runTest {
        store.putString("remote_config_kit.installation_id", "known-installation")
        val payload = document(
            conditions = listOf(
                condition(
                    "different",
                    listOf(mapOf("attribute" to "percentage", "operator" to "lt", "value" to 60, "seed" to "shared")),
                ),
                condition(
                    "safe-message",
                    listOf(
                        rule("custom_signal.message", "not_contains", "blocked"),
                        rule("custom_signal.tags", "not_contains", "blocked"),
                    ),
                ),
            ),
            parameters = listOf(
                parameter("rollout", "string", "off", overrides = listOf(override("different", "on"))),
                parameter("safe", "boolean", false, overrides = listOf(override("safe-message", true))),
            ),
        )
        val kit = createKit(
            { _, _, _ -> ok(payload) },
            store,
            context = TargetingContext(customSignals = mapOf("message" to "ready", "tags" to listOf("ready"))),
        )
        kit.fetchAndActivate()
        assertEquals("off", kit.getString("rollout"))
        assertTrue(kit.getBool("safe"))
        assertEquals(74.46635728701949, kit.targetingSnapshot().percentageFor("different", seed = "shared"), 0.000001)
        kit.updateContext(TargetingContext(customSignals = mapOf("message" to "blocked", "tags" to listOf("ready"))))
        assertFalse(kit.getBool("safe"))
        kit.updateContext(TargetingContext(customSignals = mapOf("message" to "ready", "tags" to listOf("blocked"))))
        assertFalse(kit.getBool("safe"))
        kit.close()
    }

    @Test
    fun invalidRolloutSeedsAreRejectedBeforeStaging() = runTest {
        for (badRule in listOf(
            mapOf("attribute" to "percentage", "operator" to "lt", "value" to 50, "seed" to ""),
            mapOf("attribute" to "percentage", "operator" to "lt", "value" to 50, "seed" to 5),
            mapOf("attribute" to "percentage", "operator" to "lt", "value" to 50, "seed" to null),
            mapOf("attribute" to "platform", "operator" to "eq", "value" to "ios", "seed" to "x"),
        )) {
            val payload = document(
                conditions = listOf(condition("bad", listOf(badRule))),
                parameters = listOf(parameter("enabled", "boolean", false)),
            )
            val kit = createKit({ _, _, _ -> ok(payload) }, store)
            assertFailsWith<ConfigFormatException> { kit.fetch() }
            assertNull(kit.activeVersion)
            kit.close()
        }
    }

    @Test
    fun nonRawGitHubUrlsAreRejected() = runTest {
        for (url in listOf(
            "http://raw.githubusercontent.com/o/r/main/c.json",
            "https://github.com/o/r/main/c.json",
            "https://raw.githubusercontent.com/o/r/c.json",
            "https://user@raw.githubusercontent.com/o/r/main/c.json",
            "https://raw.githubusercontent.com/o/r/main/c.json#x",
        )) {
            assertFailsWith<IllegalArgumentException>(url) {
                RemoteConfigKit.create(
                    options = RemoteConfigOptions(url = url),
                    store = InMemoryKeyValueStore(),
                    appMetadata = AppMetadata("a", "1", "1"),
                )
            }
        }
    }

    @Test
    fun startupWaitsForChangesFromAnotherCoroutine() = runTest {
        val kit = createKit({ _, _, _ -> ok(document(parameters = listOf(parameter("x", "number", 1)))) }, store)
        val seen = mutableListOf<Long>()
        val collector = launch { kit.revision.collect { seen += it } }
        kit.start()
        runCurrent()
        assertEquals(listOf(0L, 1L), seen)
        collector.cancel()
        kit.close()
    }
}

# Remote Config Kit for Android

Kotlin library for Android apps that read a JSON config from a **public** GitHub repository and evaluate its conditions on device. It is the native counterpart of [remote-config-kit-flutter](https://github.com/bilalelsayed97/remote-config-kit-flutter): same document format, targeting attributes, rollout hash, caching, and refresh behavior. The app does not need a GitHub token.

Built with Kotlin 2.4, AGP 9.5 (built-in Kotlin), Gradle 9.8, coroutines and Flow, and kotlinx.serialization's `JsonElement`. `minSdk` 26.

## Add the library

### JitPack

After this repository is public on GitHub, add JitPack to `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

and the dependency:

```kotlin
dependencies {
    implementation("com.github.bilalelsayed97.remote-config-kit-android:remote-config-kit:0.1.0")
}
```

`jitpack.yml` builds with JDK 17 and publishes the `remote-config-kit` module.

### Maven Central or a local checkout

The module publishes as `io.github.bilalelsayed97:remote-config-kit` with `maven-publish`. Try it locally:

```bash
./gradlew :remote-config-kit:publishToMavenLocal
```

then add `mavenLocal()` to the app's repositories. Maven Central additionally needs a license in the POM, signing, and Central Portal credentials (for example through the `com.vanniktech.maven.publish` plugin). Neither is set up yet. For a source checkout, use `includeBuild("../remote-config-kit-android")` in the app's `settings.gradle.kts`.

## Use it

```kotlin
val config = RemoteConfigKit.create(
    context,
    RemoteConfigOptions(
        url = "https://raw.githubusercontent.com/OWNER/REPO/main/remote-config.json",
        context = TargetingContext(country = "SA"),
        defaults = mapOf("new_checkout_enabled" to false),
    ),
)
config.start(InitialFetchPolicy.USE_DEFAULTS)

val enabled = config.getBool("new_checkout_enabled")
```

In Compose, read `revision` so the view recomposes after each activation or context change:

```kotlin
@Composable
fun Checkout(config: RemoteConfigKit) {
    val revision by config.revision.collectAsStateWithLifecycle()
    Text(if (config.getBool("new_checkout_enabled")) "New checkout enabled" else "New checkout disabled")
}
```

The [`sample`](sample) app shows the full loading / error / ready flow and a debug view. Outside Compose, collect `changes` (a `SharedFlow<Unit>`). Call `close()` when the client is no longer needed.

`create()` is a suspend function that loads saved state from `SharedPreferences` without fetching. `start()` activates a saved pending document, if any. With cached active or pending data, either initial fetch policy returns promptly and checks GitHub in the background.

With `USE_DEFAULTS`, on a first install with no valid saved document, `start()` returns promptly and getters use local `defaults` while the SDK fetches in the background. Connection failures (`IOException`), timeouts (`ConfigFetchTimeoutException`), and HTTP 403, 408, 429, and 5xx responses are retried with backoff from one to 30 seconds until a valid document is activated. Other HTTP errors or an invalid document are recorded in `lastRefreshError`; later foreground checks can try again.

If your app must wait for a remote document on a first install, call `start()` without an argument (the default is `WAIT_FOR_REMOTE`). It keeps suspending through those retryable failures; other HTTP errors (`ConfigFetchHttpException`) or an invalid document (`ConfigFormatException`) throw. Run it in a lifecycle-aware scope so leaving the screen cancels the wait.

After startup, the SDK checks again while the process is in the foreground (`ProcessLifecycleOwner` started) on a timer of `minimumFetchInterval` (one hour by default) and whenever the app returns to the foreground. When the interval is zero, requested fetches are not throttled and the timer runs once per minute.

`fetch()` downloads and stages a valid document. `activate()` makes it visible to getters and returns whether the active snapshot changed. `fetchAndActivate()` calls both. Values are saved in the `remote_config_kit` `SharedPreferences` file and survive an offline restart. A failed fetch throws and leaves the active document intact. Corrupt saved data is cleared at creation. Fetches respect `minimumFetchInterval` and `fetchTimeout` (20 seconds). A saved ETag is sent as `If-None-Match`; without one, a saved `Last-Modified` value is sent as `If-Modified-Since`. HTTP 304 reuses the saved document. The default `UrlConnectionHttpClient` disables `HttpURLConnection` caching so these validators decide revalidation.

`getBool`, `getString`, `getInt`, `getLong`, `getDouble`, and `getJson` read typed values synchronously from any thread. `getSource(key)` returns `REMOTE`, `LOCAL_DEFAULT`, or `STATIC_FALLBACK`. Missing keys fall back to `false`, `""`, `0`, or `null`. A getter throws `IllegalStateException` when an existing value has another type; `getInt` and `getLong` also throw for fractional numbers. `getJson` returns an immutable `JsonElement`.

Every collaborator can be replaced through the second `create` overload: `KeyValueStore` (`SharedPreferencesStore`, `InMemoryKeyValueStore`), `ConfigHttpClient`, `AppMetadata`, `ForegroundSource` (`ProcessLifecycleForegroundSource`, `ManualForegroundSource`), a `java.time.Clock`, and the `CoroutineDispatcher`. The unit tests use this with virtual time.

### Flutter API mapping

| Flutter | Kotlin |
| --- | --- |
| `await RemoteConfigKit.create(options)` | `RemoteConfigKit.create(context, options)` (suspend) |
| `getBool` … `getJson` | same names; `getJson` returns `JsonElement?`, plus `getLong` |
| `changes` stream | `changes: SharedFlow<Unit>` and `revision: StateFlow<Long>` |
| `ConfigFetchHttpException` | `ConfigFetchHttpException` (`statusCode`, `isRetryable`) |
| `FormatException` | `ConfigFormatException` |
| `StateError` | `IllegalStateException` |
| `ConfigSource.localDefault` etc. | `ConfigSource.LOCAL_DEFAULT` etc. |
| `WidgetsBindingObserver` lifecycle | `ProcessLifecycleOwner` |
| `package_info_plus` | `PackageManager` (package name, `versionName`, `longVersionCode`) |
| `SharedPreferences` (plugin) | Android `SharedPreferences` via `KeyValueStore` |

`TargetingContext.userProperties`, `customSignals`, and `RemoteConfigOptions.defaults` accept JSON-compatible values: `null`, `Boolean`, finite numbers, `String`, lists, arrays, maps with `String` keys, and `JsonElement`.

## Installation ID and targeting diagnostics

`config.installationId` is a kit-generated 128-bit random hex string held in the store; all config URLs in the app share it. It is **not** a Firebase installation ID. It remains stable while app data remains, and there is no reset API. Auto Backup may restore `SharedPreferences`, so do not assume every reinstall produces a new ID. The app never sends this ID or its targeting attributes to GitHub; GitHub receives only the public GET request.

```kotlin
val inspection = config.inspect("new_checkout_enabled")
Log.d("Config", "Installation ID: ${config.installationId}")
Log.d("Config", "Matched condition: ${inspection.matchedConditionId}")
Log.d("Config", "Targeting: ${inspection.targeting.toJson()}")
```

`targetingSnapshot().attributes` exposes app ID, platform, version, build, locale, country, user ID, audience IDs, flattened `user_property.KEY` and `custom_signal.KEY` fields, installation ID, first-open time, and the pinned evaluation time. `targetingSnapshot().percentageFor(conditionId, seed)` shows a condition's exact rollout bucket. `inspect(key)` returns the raw selected `value`, `source`, `activeVersion`, `matchedConditionId`, and the snapshot used. A missing key has `value == null`. Snapshots may contain personal data supplied by the app; keep such a view private.

`firstOpen` is when this SDK first initialized with the current store. `date_time` is captured when an HTTP 200 document is fetched and becomes active on activation; offline restarts retain it. Before any activation it is set at SDK creation. `updateContext(...)` recalculates other attributes immediately but does not change the pinned `date_time`.

Platform defaults to `android` and locale to `Locale.getDefault().toString()` (for example `en_US`); app ID, version, and build come from the installed package. Explicit `TargetingContext` values override them.

## Document format

The format is shared with the Flutter and iOS packages:

```json
{
  "schemaVersion": 1,
  "version": 1,
  "publishedAt": "2026-10-02T12:00:00Z",
  "groups": [
    {"id": "checkout", "name": "Checkout", "description": "Checkout flags"}
  ],
  "conditions": [
    {
      "id": "android_old",
      "name": "Older Android builds",
      "color": "blue",
      "match": "all",
      "rules": [
        {"attribute": "platform", "operator": "eq", "value": "android"},
        {"attribute": "app_version", "operator": "lt", "value": "2.5.74"}
      ]
    }
  ],
  "parameters": [
    {
      "key": "new_checkout_enabled",
      "type": "boolean",
      "default": false,
      "description": "New checkout screen",
      "groupId": "checkout",
      "overrides": [{"conditionId": "android_old", "value": true}]
    }
  ]
}
```

Parameter types are `boolean`, `string`, `number`, and `json`; defaults and overrides must match. Conditions are evaluated **in array order**; the first matching condition with an override for that parameter wins. `match: "all"` requires every rule; `"any"` requires one. Malformed or unsupported documents are rejected before they replace the staged config.

| Attribute | Supplied by |
| --- | --- |
| `app_id`, `app_version`, `build_number` | Installed package, overridden by `TargetingContext` |
| `platform`, `locale` | `TargetingContext`, or operating system default |
| `country`, `user_id` | App-supplied `TargetingContext` |
| `user_exists` | Always true for an initialized kit; it does not mean signed in |
| `audience` | `TargetingContext.audienceIds` |
| `user_property.KEY`, `custom_signal.KEY` | `TargetingContext.userProperties` / `customSignals` |
| `installation_id`, `first_open` | Persisted by the SDK on first initialization |
| `date_time` | Pinned HTTP 200 fetch time after activation, in UTC |
| `percentage` | Stable installation bucket described below |

Operators: `eq`, `neq`, `in`, `not_in`, `lt`, `lte`, `gt`, `gte`, `contains`, `not_contains`, and `between` (`[lower, upper]`, inclusive). `contains` / `not_contains` are case-sensitive substring checks for strings and element checks for arrays; other values match neither. Dotted numeric app versions such as `2.5.74` compare by component; prerelease suffixes are not supported. Date rules use ISO 8601 timestamps. Missing app-supplied attributes do not match.

For a rollout, use `{"attribute":"percentage","operator":"lt","value":10}` for 10% of installations. The bucket is `FNV-1a-32(utf8((seed ?? conditionId) + ':' + installationId)) / 2^32 * 100`, identical across the Flutter, iOS, and Android kits. An optional nonempty `"seed"` keeps assignment stable across condition renames or coordinates conditions.

The SDK evaluates conditions on device. It is not a drop-in Firebase Remote Config replacement: no analytics audiences, A/B measurement, or push updates. The public JSON exposes **all** values and rules, so do not store secrets there. Device time and app-supplied targeting fields can be changed by the user and should not authorize privileged behavior.

## Development

```bash
./gradlew build test
```

```bash
./gradlew :sample:installDebug
```

The sample's `CONFIG_URL` is a placeholder; point it at a real document first.

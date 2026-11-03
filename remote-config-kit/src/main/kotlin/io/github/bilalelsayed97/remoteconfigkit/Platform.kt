package io.github.bilalelsayed97.remoteconfigkit

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

// Storage

/** String storage for the installation ID and cached documents. */
public interface KeyValueStore {
    public suspend fun getString(key: String): String?

    /** @throws IllegalStateException when the value could not be persisted. */
    public suspend fun putString(key: String, value: String)

    public suspend fun remove(key: String)
}

/** Stores values in [SharedPreferences], committing each write on [Dispatchers.IO]. */
public class SharedPreferencesStore(private val preferences: SharedPreferences) : KeyValueStore {
    public constructor(context: Context, name: String = DEFAULT_NAME) :
        this(context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE))

    override suspend fun getString(key: String): String? =
        withContext(Dispatchers.IO) { preferences.getString(key, null) }

    override suspend fun putString(key: String, value: String) {
        val saved = withContext(Dispatchers.IO) { preferences.edit().putString(key, value).commit() }
        check(saved) { "Could not persist remote config" }
    }

    override suspend fun remove(key: String) {
        withContext(Dispatchers.IO) { preferences.edit().remove(key).commit() }
    }

    public companion object {
        public const val DEFAULT_NAME: String = "remote_config_kit"
    }
}

/** A store that keeps values in memory, for tests and previews. */
public class InMemoryKeyValueStore(initialValues: Map<String, String> = emptyMap()) : KeyValueStore {
    private val values = ConcurrentHashMap(initialValues)

    public val snapshot: Map<String, String> get() = values.toMap()

    override suspend fun getString(key: String): String? = values[key]

    override suspend fun putString(key: String, value: String) {
        values[key] = value
    }

    override suspend fun remove(key: String) {
        values.remove(key)
    }
}

// HTTP

public class HttpResponse(
    public val statusCode: Int,
    headers: Map<String, String> = emptyMap(),
    public val body: ByteArray = ByteArray(0),
) {
    /** Response headers with lowercase names. */
    public val headers: Map<String, String> = headers.mapKeys { it.key.lowercase() }
}

/** Performs the config GET request. Inject a custom client for tests or custom networking. */
public fun interface ConfigHttpClient {
    public suspend fun get(url: String, headers: Map<String, String>, timeout: Duration): HttpResponse
}

/**
 * The default client, using [HttpURLConnection] on [Dispatchers.IO] with HTTP caching off, so the
 * kit's own `If-None-Match` / `If-Modified-Since` validators decide when GitHub returns 304.
 */
public object UrlConnectionHttpClient : ConfigHttpClient {
    override suspend fun get(url: String, headers: Map<String, String>, timeout: Duration): HttpResponse =
        withContext(Dispatchers.IO) {
            runInterruptible {
                val connection = URL(url).openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "GET"
                    connection.useCaches = false
                    connection.connectTimeout = timeout.inWholeMilliseconds.toInt()
                    connection.readTimeout = timeout.inWholeMilliseconds.toInt()
                    headers.forEach(connection::setRequestProperty)
                    val status = connection.responseCode
                    val body = (if (status >= 400) connection.errorStream else connection.inputStream)
                        ?.use { it.readBytes() } ?: ByteArray(0)
                    val responseHeaders = connection.headerFields
                        .filterKeys { it != null }
                        .mapValues { it.value.lastOrNull().orEmpty() }
                    HttpResponse(status, responseHeaders, body)
                } finally {
                    connection.disconnect()
                }
            }
        }
}

// App metadata

/** App ID, version, and build used as targeting defaults. */
public data class AppMetadata(
    val appId: String,
    val appVersion: String,
    val buildNumber: String,
) {
    public companion object {
        /** The package name, `versionName`, and `versionCode` of the running app. */
        public fun from(context: Context): AppMetadata {
            val manager = context.packageManager
            val name = context.packageName
            val info = if (Build.VERSION.SDK_INT >= 33) {
                manager.getPackageInfo(name, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                manager.getPackageInfo(name, 0)
            }
            val build = if (Build.VERSION.SDK_INT >= 28) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            return AppMetadata(name, info.versionName.orEmpty(), build.toString())
        }
    }
}

// Lifecycle

/** Reports whether the app is in the foreground, which gates automatic refreshes. */
public fun interface ForegroundSource {
    /** Emits the current state on collection, then each change. */
    public fun isForeground(): Flow<Boolean>
}

/** The process lifecycle: foreground while any activity is started. */
public object ProcessLifecycleForegroundSource : ForegroundSource {
    override fun isForeground(): Flow<Boolean> =
        ProcessLifecycleOwner.get().lifecycle.currentStateFlow
            .map { it.isAtLeast(Lifecycle.State.STARTED) }
            .distinctUntilChanged()
}

/** A foreground source the app or a test drives directly; every [set] call is delivered. */
public class ManualForegroundSource(initial: Boolean = true) : ForegroundSource {
    private val events = MutableSharedFlow<Boolean>(replay = 1, extraBufferCapacity = 64).apply { tryEmit(initial) }

    public fun set(foreground: Boolean) {
        events.tryEmit(foreground)
    }

    override fun isForeground(): Flow<Boolean> = events
}

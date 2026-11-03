package io.github.bilalelsayed97.remoteconfigkit

import java.io.IOException

/** GitHub answered with a status other than 200 or 304. */
public class ConfigFetchHttpException(
    public val statusCode: Int,
    public val url: String,
) : IOException("Config fetch failed: HTTP $statusCode") {
    /** Whether a startup fetch keeps retrying (HTTP 403, 408, 429, and 5xx). */
    public val isRetryable: Boolean
        get() = statusCode == 403 || statusCode == 408 || statusCode == 429 || statusCode >= 500
}

/** The request did not finish within [RemoteConfigOptions.fetchTimeout]. */
public class ConfigFetchTimeoutException(message: String) : IOException(message)

/** A downloaded or cached document is malformed or unsupported. */
public class ConfigFormatException(message: String) : IllegalArgumentException(message)

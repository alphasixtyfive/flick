package app.flick.weather

import app.flick.platform.cancelAsync
import app.flick.platform.closeAsync
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject

class WeatherRepository {
    private val client = OkHttpClient.Builder().callTimeout(8, TimeUnit.SECONDS).followRedirects(false).build()
    private val closed = AtomicBoolean()
    private val conditionsLock = Mutex()
    private data class CachedConditions(val location: WeatherLocation, val reading: WeatherReading, val atNanos: Long)
    private var cached: CachedConditions? = null

    suspend fun search(query: String): List<PlaceResult> = withContext(Dispatchers.IO) {
        check(!closed.get()) { "Weather is closed" }
        val name = query.trim()
        require(name.length in 2..100) { "Enter at least two characters" }
        val url = "https://geocoding-api.open-meteo.com/v1/search".toHttpUrl().newBuilder()
            .addQueryParameter("name", name).addQueryParameter("count", "5").addQueryParameter("language", "en").build()
        val result = read(url.toString()).optJSONArray("results") ?: return@withContext emptyList()
        (0 until minOf(result.length(), 5)).mapNotNull { index ->
            val row = result.getJSONObject(index)
            val latitude = row.optDouble("latitude")
            val longitude = row.optDouble("longitude")
            val place = row.optString("name").take(100)
            if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0 || place.isBlank()) null else
                PlaceResult(place, listOf(row.optString("admin1"), row.optString("country")).filter(String::isNotBlank).distinct().joinToString(", "), latitude, longitude)
        }
    }

    suspend fun conditions(location: WeatherLocation): WeatherReading = conditionsLock.withLock {
        check(!closed.get()) { "Weather is closed" }
        cached?.takeIf { it.location == location && System.nanoTime() - it.atNanos < 600_000_000_000L }
            ?.let { return@withLock it.reading }
        val url = "https://api.open-meteo.com/v1/forecast".toHttpUrl().newBuilder()
            .addQueryParameter("latitude", location.latitude.toString()).addQueryParameter("longitude", location.longitude.toString())
            .addQueryParameter("current", "temperature_2m,weather_code")
            .addQueryParameter("temperature_unit", if (location.fahrenheit) "fahrenheit" else "celsius").build()
        val current = read(url.toString()).getJSONObject("current")
        WeatherReading(kotlin.math.round(current.getDouble("temperature_2m")).toInt(), current.getInt("weather_code"))
            .also { cached = CachedConditions(location, it, System.nanoTime()) }
    }

    private suspend fun read(url: String): JSONObject = suspendCancellableCoroutine { continuation ->
        if (closed.get()) {
            continuation.resumeWithException(IllegalStateException("Weather is closed"))
            return@suspendCancellableCoroutine
        }
        val call = client.newCall(Request.Builder().url(url).build())
        continuation.invokeOnCancellation { client.cancelAsync(call) }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(IOException("Weather is unavailable"))
            }

            override fun onResponse(call: Call, response: Response) {
                val result = runCatching {
                    response.use {
                        check(it.isSuccessful) { "Weather is unavailable" }
                        val source = requireNotNull(it.body).source()
                        require(!source.request(256 * 1024L + 1)) { "Weather response is too large" }
                        JSONObject(source.readUtf8())
                    }
                }
                if (continuation.isActive) result.fold(continuation::resume) {
                    continuation.resumeWithException(IOException("Weather is unavailable"))
                }
            }
        })
    }

    fun close() {
        if (closed.compareAndSet(false, true)) client.closeAsync()
    }
}

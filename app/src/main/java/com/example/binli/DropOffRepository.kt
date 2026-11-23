package com.example.binli

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class Area(val latitude: Double, val longitude: Double, val label: String, val approximate: Boolean,
    val source:String=if(approximate)"ip" else "device")
data class DropOff(val id: String, val latitude: Double, val longitude: Double, val name: String, val materials: List<String>, val hours: String)

class DropOffRepository {
    private val cache=java.util.concurrent.ConcurrentHashMap<String,Pair<Long,List<DropOff>>>()
    private val geocodes=java.util.concurrent.ConcurrentHashMap<String,Area>()
    private val geocodeMutex=Mutex()
    private var lastGeocode=0L
    suspend fun approximateArea():Area=withContext(Dispatchers.IO) {
        val json=JSONObject(request("https://ipwho.is/",null))
        check(json.optBoolean("success")) { "IP location unavailable" }
        Area(json.getDouble("latitude"),json.getDouble("longitude"),
            listOf(json.optString("city"),json.optString("country")).filter(String::isNotBlank).joinToString(", "),true,"ip")
    }
    /** Called only by an explicit search action, never for autocomplete. */
    suspend fun searchCity(query:String):Area=geocodeMutex.withLock {
        val clean=query.trim().take(120)
        require(clean.length>=2)
        geocodes[clean.lowercase(java.util.Locale.ROOT)]?.let { return@withLock it }
        val wait=1100L-(System.currentTimeMillis()-lastGeocode)
        if(wait>0)delay(wait)
        lastGeocode=System.currentTimeMillis()
        val rows=withContext(Dispatchers.IO) { JSONArray(request(
            "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=1&q="+URLEncoder.encode(clean,"UTF-8"),null)) }
        check(rows.length()>0) { "City not found" }
        val row=rows.getJSONObject(0)
        val area=Area(row.getString("lat").toDouble(),row.getString("lon").toDouble(),row.getString("display_name"),false,"search")
        if(geocodes.size>50)geocodes.clear()
        geocodes[clean.lowercase(java.util.Locale.ROOT)]=area
        area
    }
    suspend fun nearby(lat:Double,lon:Double):List<DropOff> = withContext(Dispatchers.IO) {
        require(lat.isFinite() && lon.isFinite() && lat in -85.0..85.0 && lon in -180.0..180.0)
        val key=java.lang.String.format(java.util.Locale.ROOT,"%.3f,%.3f",lat,lon)
        cache[key]?.takeIf { System.currentTimeMillis()-it.first<600_000 }?.let { return@withContext it.second }
        val query="[out:json][timeout:18];nwr[amenity=recycling](around:10000,$lat,$lon);out center tags;"
        val encoded="data="+URLEncoder.encode(query,"UTF-8")
        var failure:Exception?=null
        for(endpoint in listOf("https://overpass-api.de/api/interpreter","https://overpass.kumi.systems/api/interpreter")) {
            currentCoroutineContext().ensureActive()
            try {
                val items=parsePoints(JSONObject(request(endpoint,encoded)),lat,lon)
                if(cache.size>50)cache.clear()
                cache[key]=System.currentTimeMillis() to items
                return@withContext items
            } catch(cancel:CancellationException) { throw cancel }
            catch(error:Exception) { failure=error }
        }
        throw failure ?: IllegalStateException("Collection service unavailable")
    }

    internal fun parsePoints(json:JSONObject,lat:Double,lon:Double):List<DropOff> {
        check(!json.has("remark")) { "Collection service busy" }
        val elements=json.getJSONArray("elements")
        return (0 until elements.length()).mapNotNull { index ->
            val entry=elements.getJSONObject(index); val centre=entry.optJSONObject("center") ?: entry
            val tags=entry.optJSONObject("tags") ?: JSONObject()
            val x=centre.optDouble("lat",Double.NaN); val y=centre.optDouble("lon",Double.NaN)
            if(!x.isFinite() || !y.isFinite() || x !in -85.0..85.0 || y !in -180.0..180.0 ||
                tags.optString("access") in listOf("private","no")) null
            else DropOff(entry.optString("type")+entry.optString("id"),x,y,tags.optString("name"),
                tags.keys().asSequence().filter { it.startsWith("recycling:") && tags.optString(it)=="yes" }
                    .map { it.removePrefix("recycling:").replace('_',' ') }.sorted().toList(),tags.optString("opening_hours"))
        }.distinctBy { it.id }.sortedBy {
            val longitudeScale=kotlin.math.cos(Math.toRadians(lat))
            (it.latitude-lat)*(it.latitude-lat)+(it.longitude-lon)*(it.longitude-lon)*longitudeScale*longitudeScale
        }.take(80)
    }
    private fun request(address:String,body:String?):String {
        val connection=URL(address).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout=8000; connection.readTimeout=22000
            connection.setRequestProperty("User-Agent","Binly/1.0 (Android; com.example.binli)")
            connection.setRequestProperty("Accept","application/json")
            if(body!=null) {
                connection.requestMethod="POST"; connection.doOutput=true
                connection.setRequestProperty("Content-Type","application/x-www-form-urlencoded")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            check(connection.responseCode in 200..299) { "Location service returned "+connection.responseCode }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally { connection.disconnect() }
    }
}

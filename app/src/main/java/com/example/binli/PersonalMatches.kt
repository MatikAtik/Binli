package com.example.binli

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.sqrt

object VisualSimilarity {
    fun normalized(values:FloatArray):FloatArray? {
        if(values.isEmpty() || values.any { !it.isFinite() })return null
        val norm=sqrt(values.sumOf { it.toDouble()*it })
        if(norm<1e-8)return null
        return FloatArray(values.size) { (values[it]/norm).toFloat() }
    }
    fun cosine(first:FloatArray,second:FloatArray):Double {
        if(first.size!=second.size)return -1.0
        val a=normalized(first) ?: return -1.0; val b=normalized(second) ?: return -1.0
        return a.indices.sumOf { a[it].toDouble()*b[it] }.coerceIn(-1.0,1.0)
    }
    fun best(query:FloatArray,examples:List<VisualExample>):VisualExample? {
        val ranked=examples.map { it to cosine(query,it.features) }.sortedByDescending { it.second }
        val best=ranked.firstOrNull() ?: return null
        val competing=ranked.firstOrNull { it.first.kind!=best.first.kind || it.first.identity!=best.first.identity }?.second ?: -1.0
        return best.first.takeIf { best.second>=0.965 && best.second-competing>=0.035 }
    }
}
data class VisualExample(val kind:WasteKind,val identity:ObjectIdentity?,val features:FloatArray)

/** Stores feature vectors and user-selected categories only. No photos are retained or uploaded. */
class PersonalMatches(context:Context,namespace:String="personal_matches_v1") {
    private val prefs=context.getSharedPreferences(namespace,Context.MODE_PRIVATE)
    fun count()=prefs.getInt("count",0)
    fun match(features:FloatArray)=VisualSimilarity.best(features,examples())
    fun save(features:FloatArray,kind:WasteKind,identity:ObjectIdentity?)=synchronized(lock) {
        require(kind!=WasteKind.UNKNOWN)
        val vector=requireNotNull(VisualSimilarity.normalized(features))
        val items=examples().filter { VisualSimilarity.cosine(vector,it.features)<0.9995 }.takeLast(59)+VisualExample(kind,identity,vector)
        val json=JSONArray()
        items.forEach { item ->
            val values=JSONArray(); item.features.forEach { values.put(it.toDouble()) }
            json.put(JSONObject().put("kind",item.kind.name).put("identity",item.identity?.name ?: "").put("features",values))
        }
        check(prefs.edit().putString("examples",json.toString()).putInt("count",items.size).commit())
    }
    fun clear()=synchronized(lock) { check(prefs.edit().clear().commit()) }
    private fun examples():List<VisualExample> = runCatching {
        val json=JSONArray(prefs.getString("examples","[]"))
        (0 until json.length()).mapNotNull { index -> runCatching {
            val row=json.getJSONObject(index); val values=row.getJSONArray("features")
            val features=VisualSimilarity.normalized(FloatArray(values.length()) { values.getDouble(it).toFloat() }) ?: return@runCatching null
            VisualExample(WasteKind.valueOf(row.getString("kind")),
                row.optString("identity").takeIf { it.isNotEmpty() }?.let(ObjectIdentity::valueOf),features)
        }.getOrNull() }
    }.getOrDefault(emptyList())
    companion object { private val lock=Any() }
}

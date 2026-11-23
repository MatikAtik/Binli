package com.example.binli

import android.content.Context
import android.graphics.BitmapFactory
import android.util.AtomicFile
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

data class ReferenceCandidate(val example:VisualExample,val similarity:Double,val support:Double,val margin:Double)

/** A reject-capable nearest-neighbour classifier, including an explicit non-recyclable class. */
object ReferenceClassifier {
    fun rank(query:FloatArray,examples:List<VisualExample>):ReferenceCandidate? {
        val ranked=examples.map { it to VisualSimilarity.cosine(query,it.features) }.sortedByDescending { it.second }
        val first=ranked.firstOrNull() ?: return null
        fun same(a:VisualExample,b:VisualExample)=a.kind==b.kind && a.identity==b.identity
        val own=ranked.filter { same(it.first,first.first) }.take(3)
        val support=own.map { it.second }.average()
        val competing=ranked.firstOrNull { !same(it.first,first.first) }?.second ?: -1.0
        return ReferenceCandidate(first.first,first.second,support,first.second-competing)
    }
    fun match(query:FloatArray,examples:List<VisualExample>):VisualExample? {
        val candidate=rank(query,examples) ?: return null
        if(candidate.example.kind==WasteKind.UNKNOWN)return null
        val accepted=if(candidate.example.identity!=null) {
            candidate.similarity>=0.93 && candidate.margin>=0.06
        } else {
            // Chosen using the separate 120-photo calibration partition, never the evaluation photos.
            candidate.similarity>=0.50 && candidate.support>=0.45 && candidate.margin>=0.075
        }
        return candidate.example.takeIf { accepted }
    }
}

/** Precomputed public-photo vectors avoid expensive reference inference during a user scan. */
internal object ReferencePhotos {
    private var loaded:List<VisualExample>?=null
    private var measured:List<VisualExample>?=null
    // Accessed exclusively from ObjectVision's single inference thread.
    fun load(context:Context):List<VisualExample> {
        loaded?.let { return it }
        val index=context.assets.open("reference_index.txt").bufferedReader().use { it.readLines() }
        val lines=context.assets.open("reference_vectors_v1.txt").bufferedReader().use { it.readLines() }
        check(lines.first()=="mobilenet-v3-small-float32-v1|"+index.size)
        check(lines.size==index.size+1)
        val examples=lines.drop(1).mapIndexed { position,row ->
            val parts=row.split('|'); val expected=index[position].split('|')
            check(parts.size==5 && parts[0].toInt()==position && parts[1]==expected[1] && parts[2]==expected[2])
            val scale=parts[3].toFloat(); check(scale.isFinite() && scale>0f)
            val bytes=android.util.Base64.decode(parts[4],android.util.Base64.NO_WRAP)
            check(bytes.size in 16..4096)
            val features=VisualSimilarity.normalized(FloatArray(bytes.size) { bytes[it]*scale }) ?: error("Invalid reference vector")
            VisualExample(WasteKind.valueOf(parts[1]),parts[2].takeIf { it.isNotEmpty() }?.let(ObjectIdentity::valueOf),features)
        }
        check(examples.all { it.features.size==examples.first().features.size })
        loaded=examples
        return examples
    }

    /** Development export only. Production recognition never computes the bank on the phone. */
    fun measure(context:Context,embed:(android.graphics.Bitmap)->FloatArray):List<VisualExample> {
        measured?.let { return it }
        val index=context.assets.open("reference_index.txt").bufferedReader().use { it.readLines() }
        val version=java.security.MessageDigest.getInstance("SHA-256")
            .digest(("mobilenet-v3-small-v1-squash224|"+index.joinToString("\n")).toByteArray())
            .joinToString("") { "%02x".format(it) }
        val file=AtomicFile(File(context.cacheDir,"references-"+version+".bin"))
        val cached=runCatching { DataInputStream(file.openRead()).use { input ->
            check(input.readInt()==index.size)
            index.map { row ->
                val parts=row.split('|')
                val size=input.readInt(); check(size in 16..4096)
                val features=FloatArray(size) { input.readFloat() }
                check(VisualSimilarity.normalized(features)!=null)
                VisualExample(WasteKind.valueOf(parts[1]),parts[2].takeIf { it.isNotEmpty() }?.let(ObjectIdentity::valueOf),features)
            }
        } }.getOrNull()
        val examples=cached ?: index.map { row ->
            val parts=row.split('|')
            val bitmap=context.assets.open(parts[0]).use { BitmapFactory.decodeStream(it) } ?: error("Invalid reference image")
            try { VisualExample(WasteKind.valueOf(parts[1]),parts[2].takeIf { it.isNotEmpty() }?.let(ObjectIdentity::valueOf),embed(bitmap)) }
            finally { bitmap.recycle() }
        }.also { examples ->
            // A cache write failure must not disable recognition.
            runCatching {
                val stream=file.startWrite()
                try {
                    val output=DataOutputStream(stream)
                    output.writeInt(examples.size)
                    examples.forEach { example -> output.writeInt(example.features.size); example.features.forEach(output::writeFloat) }
                    output.flush(); file.finishWrite(stream)
                } catch(error:Exception) { file.failWrite(stream); throw error }
            }
        }
        measured=examples
        return examples
    }
}

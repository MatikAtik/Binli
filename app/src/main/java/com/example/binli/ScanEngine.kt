package com.example.binli

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

data class FocusBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val valid get() = listOf(left,top,right,bottom).all { it.isFinite() && it in 0f..1f } &&
        right-left >= 0.03f && bottom-top >= 0.03f
    fun toArray()=floatArrayOf(left,top,right,bottom)
    companion object {
        fun from(values:FloatArray?):FocusBox? = values?.takeIf { it.size==4 }?.let { FocusBox(it[0],it[1],it[2],it[3]) }?.takeIf { it.valid }
    }
}
data class ScanResult(val labels: List<RecognizedLabel>, val kind: WasteKind,
    val focus: FocusBox?, val multipleObjects: Boolean, val marking: String?,
    val identity:ObjectIdentity?, val identityFromText:Boolean, val visualUnavailable:Boolean,
    val features:FloatArray?=null, val personalMatch:Boolean=false, val ambiguous:Boolean=false)

class ScanEngine(private val context: Context) {
    private val mutex=Mutex()

    // Serial inference bounds memory if the user replaces a photo during an active scan.
    suspend fun recognize(uri: Uri, requestedFocus: FocusBox? = null): ScanResult = mutex.withLock {
        withContext(Dispatchers.Default) {
            val bitmap = thumbnail(uri)
            // These tasks are not cancellable. Keep their inputs and clients alive until every task finishes.
            try { withContext(NonCancellable) { analyze(bitmap,requestedFocus?.takeIf { it.valid }) } }
            finally { bitmap.recycle() }
        }
    }

    private suspend fun analyze(bitmap:Bitmap, requestedFocus:FocusBox?):ScanResult {
        // Always evaluate the user's framing first. A detector's largest box may be a desk,
        // a hand, or just a fragment of a keyboard; it must never replace a good full-photo result.
        val full=analyzeRegion(bitmap,requestedFocus)
        if(requestedFocus!=null || full.kind!=WasteKind.UNKNOWN || full.ambiguous)return full
        val detector=ObjectDetection.getClient(ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE).enableMultipleObjects().build())
        try {
            val detections=detector.process(InputImage.fromBitmap(bitmap,0)).await()
                .map { it.boundingBox }.filter { it.width()>0 && it.height()>0 }
                .sortedByDescending { it.width().toLong()*it.height() }
            val first=detections.firstOrNull() ?: return full
            val area=first.width().toLong()*first.height()
            val ratio=area.toDouble()/(bitmap.width.toLong()*bitmap.height)
            if(ratio !in 0.08..0.85)return full
            val multiple=detections.drop(1).any { other ->
                val intersection=android.graphics.Rect(first)
                val overlap=if(intersection.intersect(other))intersection.width().toLong()*intersection.height() else 0
                val otherArea=other.width().toLong()*other.height()
                otherArea>area*0.75 && overlap<otherArea*0.5
            }
            if(multiple)return full.copy(multipleObjects=true)
            // Generous context keeps a thin vape or keyboard from becoming an unrecognizable sliver.
            val x=first.width()*0.18f; val y=first.height()*0.18f
            val box=FocusBox(((first.left-x)/bitmap.width).coerceIn(0f,1f),
                ((first.top-y)/bitmap.height).coerceIn(0f,1f),
                ((first.right+x)/bitmap.width).coerceIn(0f,1f),
                ((first.bottom+y)/bitmap.height).coerceIn(0f,1f)).takeIf { it.valid } ?: return full
            val cropped=analyzeRegion(bitmap,box)
            return if(cropped.kind!=WasteKind.UNKNOWN)cropped else full
        } catch(_:Exception) {
            return full // The optional detector downloading/failing does not discard full-photo evidence.
        } finally { detector.close() }
    }

    private suspend fun analyzeRegion(bitmap:Bitmap,focus:FocusBox?):ScanResult {
        val selected=focus?.let { box ->
            val l=(box.left*bitmap.width).toInt().coerceIn(0,bitmap.width-1)
            val t=(box.top*bitmap.height).toInt().coerceIn(0,bitmap.height-1)
            val r=(box.right*bitmap.width).toInt().coerceIn(l+1,bitmap.width)
            val b=(box.bottom*bitmap.height).toInt().coerceIn(t+1,bitmap.height)
            Bitmap.createBitmap(bitmap,l,t,r-l,b-t)
        } ?: bitmap
        val reader=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val (visualResult,printed)=supervisorScope {
                val visual=async { runCatching { ObjectVision.classify(context,selected) } }
                val writing=async {
                    try { reader.process(InputImage.fromBitmap(selected,0)).await().text }
                    catch(_:Exception) { "" }
                }
                visual.await() to writing.await()
            }
            var labels=visualResult.getOrDefault(emptyList())
            var decision=WasteRules.decide(labels,printed)
            val features=runCatching { ObjectVision.embedding(context,selected) }.getOrNull()
            var personal=false
            if(features!=null && decision.kind==WasteKind.UNKNOWN && !decision.ambiguous) {
                val learned=PersonalMatches(context).match(features)
                if(learned!=null) {
                    decision=WasteDecision(learned.kind,identity=learned.identity)
                    personal=true
                } else {
                    val reference=runCatching { ReferenceClassifier.match(features,ObjectVision.referenceExamples(context)) }.getOrNull()
                    if(reference!=null) {
                        decision=WasteDecision(reference.kind,identity=reference.identity)
                        labels=labels+RecognizedLabel("Reference photos",0f,LabelSource.REFERENCE)
                    }
                }
            }
            return ScanResult(labels,decision.kind,focus,false,decision.marking,decision.identity,
                decision.identityFromText,visualResult.isFailure,features,personal,decision.ambiguous)
        } finally {
            reader.close()
            if(selected!==bitmap)selected.recycle()
        }
    }

    suspend fun preview(uri: Uri): Bitmap = withContext(Dispatchers.IO) { thumbnail(uri) }

    private fun thumbnail(uri: Uri): Bitmap {
        val resolver=context.contentResolver
        val options=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        resolver.openInputStream(uri).use { checkNotNull(it); BitmapFactory.decodeStream(it,null,options) }
        check(options.outWidth>0 && options.outHeight>0)
        options.inSampleSize=1
        while(maxOf(options.outWidth,options.outHeight)/options.inSampleSize>2048) options.inSampleSize*=2
        options.inJustDecodeBounds=false
        val original=resolver.openInputStream(uri).use { checkNotNull(it); checkNotNull(BitmapFactory.decodeStream(it,null,options)) }
        val orientation=resolver.openInputStream(uri).use {
            if(it==null) ExifInterface.ORIENTATION_NORMAL else ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL)
        }
        val matrix=Matrix()
        when(orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f,1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f,-1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f,1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(270f); matrix.postScale(-1f,1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(270f)
        }
        if(matrix.isIdentity) return original
        return Bitmap.createBitmap(original,0,0,original.width,original.height,matrix,true).also { if(it!==original)original.recycle() }
    }
}

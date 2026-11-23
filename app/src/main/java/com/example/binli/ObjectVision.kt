package com.example.binli

import android.content.Context
import android.graphics.Bitmap
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import com.google.android.gms.litert.LiteRt
import com.google.android.gms.litert.client.LiteRtInitializationOptions
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.tasks.await
import java.util.concurrent.Executors

/** The runtime and every native model operation stay on one background thread. */
object ObjectVision {
    private val dispatcher=Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable,"binly-object-vision").apply { isDaemon=true }
    }.asCoroutineDispatcher()
    private var environment:Environment?=null

    suspend fun classify(context:Context,bitmap:Bitmap):List<RecognizedLabel> = withContext(dispatcher) {
        val runtime=environment ?: LiteRt.initialize(context.applicationContext,
            LiteRtInitializationOptions.builder().setEnableGpu(false).build()).await().also { environment=it }
        ImageNetClassifier(context,runtime).use { it.classify(bitmap) }
    }

    suspend fun embedding(context:Context,bitmap:Bitmap):FloatArray = withContext(dispatcher) {
        val runtime=environment ?: LiteRt.initialize(context.applicationContext,
            LiteRtInitializationOptions.builder().setEnableGpu(false).build()).await().also { environment=it }
        PersonalEmbedder(context,runtime).use { it.embedding(bitmap) }
    }

    internal suspend fun referenceExamples(context:Context,measure:Boolean=false):List<VisualExample> = withContext(dispatcher) {
        if(!measure)return@withContext ReferencePhotos.load(context)
        val runtime=environment ?: LiteRt.initialize(context.applicationContext,
            LiteRtInitializationOptions.builder().setEnableGpu(false).build()).await().also { environment=it }
        PersonalEmbedder(context,runtime).use { embedder ->
            ReferencePhotos.measure(context) { embedder.embedding(it) }
        }
    }

    // Exposed to instrumentation so reference and app paths exercise the same preprocessing.
    internal suspend fun scores(context:Context,bitmap:Bitmap):FloatArray = withContext(dispatcher) {
        val runtime=environment ?: LiteRt.initialize(context.applicationContext,
            LiteRtInitializationOptions.builder().setEnableGpu(false).build()).await().also { environment=it }
        ImageNetClassifier(context,runtime).use { it.infer(it.preprocess(bitmap)) }
    }
}

private class PersonalEmbedder(context:Context,environment:Environment):AutoCloseable {
    private val model=CompiledModel.create(context.assets,"personal_embedder_v1.tflite",
        CompiledModel.Options(Accelerator.CPU),environment)
    private val inputs=mutableListOf<TensorBuffer>()
    private val outputs=mutableListOf<TensorBuffer>()
    private val normalization=context.assets.open("embedding_normalization.txt").bufferedReader().use { it.readText().split('|').map(String::toFloat) }
    init {
        try {
            inputs+=model.createInputBuffers(); outputs+=model.createOutputBuffers()
            run(FloatArray(224*224*3))
        } catch(error:Exception) { close(); throw error }
    }
    private fun run(input:FloatArray):FloatArray {
        inputs[0].writeFloat(input); model.run(inputs,outputs); return outputs[0].readFloat()
    }
    fun embedding(bitmap:Bitmap):FloatArray {
        val scaled=Bitmap.createScaledBitmap(bitmap,224,224,true)
        try {
            val pixels=IntArray(224*224); scaled.getPixels(pixels,0,224,0,0,224,224)
            val values=FloatArray(pixels.size*3)
            pixels.forEachIndexed { index,pixel ->
                values[index*3]=((pixel shr 16 and 255)-normalization[0])/normalization[1]
                values[index*3+1]=((pixel shr 8 and 255)-normalization[0])/normalization[1]
                values[index*3+2]=((pixel and 255)-normalization[0])/normalization[1]
            }
            return VisualSimilarity.normalized(run(values)) ?: error("Invalid visual features")
        } finally { if(scaled!==bitmap)scaled.recycle() }
    }
    override fun close() { (inputs+outputs).forEach { it.close() }; model.close() }
}

private class ImageNetClassifier(context:Context,environment:Environment):AutoCloseable {
    private val names=context.assets.open("object_labels.txt").bufferedReader().use { it.readLines() }
    private val model=CompiledModel.create(context.assets,"efficientnet_lite0_v1.tflite",
        CompiledModel.Options(Accelerator.CPU),environment)
    private val inputs=mutableListOf<TensorBuffer>()
    private val outputs=mutableListOf<TensorBuffer>()

    init {
        try {
            inputs+=model.createInputBuffers()
            outputs+=model.createOutputBuffers()
            val warmup=infer(FloatArray(224*224*3))
            check(warmup.size==names.size) { "Recognition model labels do not match its outputs" }
        } catch(error:Exception) { close(); throw error }
    }
    fun infer(values:FloatArray):FloatArray {
        check(values.size==224*224*3)
        inputs[0].writeFloat(values)
        model.run(inputs,outputs)
        return outputs[0].readFloat()
    }
    fun classify(bitmap:Bitmap):List<RecognizedLabel> {
        val probabilities=infer(preprocess(bitmap))
        check(probabilities.all { it.isFinite() })
        return probabilities.indices.sortedByDescending { probabilities[it] }.take(5).map {
            RecognizedLabel(names[it],probabilities[it].coerceIn(0f,1f),LabelSource.OBJECT_MODEL)
        }
    }
    fun preprocess(bitmap:Bitmap):FloatArray {
        // EfficientNet-Lite0 float32 v1: RGB NHWC 224x224; metadata mean/std are verified at asset preparation.
        val scaled=Bitmap.createScaledBitmap(bitmap,224,224,true)
        try {
            val pixels=IntArray(224*224)
            scaled.getPixels(pixels,0,224,0,0,224,224)
            val values=FloatArray(pixels.size*3)
            pixels.forEachIndexed { index,pixel ->
                values[index*3]=((pixel shr 16 and 255)-127f)/128f
                values[index*3+1]=((pixel shr 8 and 255)-127f)/128f
                values[index*3+2]=((pixel and 255)-127f)/128f
            }
            return values
        } finally { if(scaled!==bitmap)scaled.recycle() }
    }
    override fun close() {
        (inputs+outputs).forEach { it.close() }
        model.close()
    }
}

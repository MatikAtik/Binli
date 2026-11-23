package com.example.binli

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Exercises actual bundled OCR and the complete decoder/crop/decision pipeline without private photos. */
@RunWith(AndroidJUnit4::class)
class ScannerInstrumentedTest {
    @Test fun printedPetCodeIsReadOnDevice() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File.createTempFile("ocr_fixture_",".png",context.cacheDir)
        try {
            fixture(file,false)
            val result=ScanEngine(context).recognize(Uri.fromFile(file),FocusBox(0f,0f,1f,1f))
            assertEquals(WasteKind.PLASTIC,result.kind)
            assertNotNull(result.marking)
        } finally { file.delete() }
    }

    @Test fun cropExcludesAnotherItemsMaterialCode() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File.createTempFile("crop_fixture_",".png",context.cacheDir)
        try {
            fixture(file,true)
            val engine=ScanEngine(context)
            val whole=engine.recognize(Uri.fromFile(file),FocusBox(0f,0f,1f,1f))
            assertEquals(WasteKind.UNKNOWN,whole.kind)
            val selected=engine.recognize(Uri.fromFile(file),FocusBox(0f,0f,0.49f,1f))
            assertEquals(WasteKind.PLASTIC,selected.kind)
        } finally { file.delete() }
    }

    @Test fun broaderModelRecognizesRealComputerKeyboardOnCpu() = runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val bitmap=instrumentation.context.assets.open("keyboard.jpg").use { android.graphics.BitmapFactory.decodeStream(it) }!!
        try {
            val predictions=ObjectVision.classify(context,bitmap)
            val first=predictions.first()
            assertTrue("Keyboard predictions: "+predictions,first.text.contains("keyboard",ignoreCase=true) || first.text.contains("keypad",ignoreCase=true))
            val decision=WasteRules.decide(predictions)
            assertEquals("Keyboard predictions: "+predictions,ObjectIdentity.KEYBOARD,decision.identity)
            assertEquals(WasteKind.ELECTRONICS,decision.kind)
            val scores=ObjectVision.scores(context,bitmap)
            assertTrue(scores.size in 1000..1001)
            assertTrue(scores.all { it.isFinite() && it in 0f..1f })
            assertEquals(1.0,scores.sum().toDouble(),0.02)
        } finally { bitmap.recycle() }
    }

    @Test fun actualOcrRecognizesVapeMarkingInsteadOfMusicalInstrument() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File.createTempFile("vape_fixture_",".png",context.cacheDir)
        val bitmap=Bitmap.createBitmap(1200,800,Bitmap.Config.ARGB_8888)
        try {
            val canvas=Canvas(bitmap); canvas.drawColor(Color.WHITE)
            val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.BLACK; textSize=120f; typeface=Typeface.DEFAULT_BOLD }
            canvas.drawText("E-CIGARETTE",50f,300f,paint)
            paint.textSize=90f; canvas.drawText("Li-ion",50f,480f,paint)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
            val result=ScanEngine(context).recognize(Uri.fromFile(file),FocusBox(0f,0f,1f,1f))
            assertFalse("The new visual runtime must load successfully",result.visualUnavailable)
            assertEquals(WasteKind.VAPE,result.kind)
            assertEquals(ObjectIdentity.VAPE,result.identity)
        } finally { bitmap.recycle(); file.delete() }
    }

    @Test fun correctionPersistsAndMatchesAResizedPhoto() = runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val namespace="personal_test_"+java.util.UUID.randomUUID()
        val store=PersonalMatches(context,namespace)
        val bitmap=instrumentation.context.assets.open("keyboard.jpg").use { android.graphics.BitmapFactory.decodeStream(it) }!!
        val resized=Bitmap.createScaledBitmap(bitmap,512,384,true)
        try {
            val features=ObjectVision.embedding(context,bitmap)
            assertTrue(features.isNotEmpty())
            assertEquals(1.0,kotlin.math.sqrt(features.sumOf { it.toDouble()*it }),0.0001)
            store.save(features,WasteKind.ELECTRONICS,ObjectIdentity.KEYBOARD)
            val query=ObjectVision.embedding(context,resized)
            val reopened=PersonalMatches(context,namespace)
            assertEquals(1,reopened.count())
            val match=reopened.match(query)
            assertNotNull("Resized-photo similarity="+VisualSimilarity.cosine(features,query),match)
            assertEquals(ObjectIdentity.KEYBOARD,match?.identity)
            // Re-teaching the same image replaces the previous correction instead of keeping contradictory labels.
            reopened.save(features,WasteKind.VAPE,ObjectIdentity.VAPE)
            assertEquals(1,reopened.count())
            assertEquals(WasteKind.VAPE,reopened.match(features)?.kind)
        } finally { store.clear(); resized.recycle(); bitmap.recycle() }
    }

    @Test fun publicReferenceBankIsSeparateFromEvaluationAndRejectsUncertainMatches() = runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val examples=ObjectVision.referenceExamples(context)
        assertEquals(369,examples.size)
        assertTrue(examples.any { it.kind==WasteKind.UNKNOWN })
        val training=context.assets.open("reference_index.txt").bufferedReader().use { it.readLines() }
        val evaluation=instrumentation.context.assets.open("evaluation_index.txt").bufferedReader().use { it.readLines() }
        assertEquals(60,evaluation.size)
        assertTrue(training.map { it.substringBefore('|').substringAfterLast('/') }.toSet()
            .intersect(evaluation.map { it.substringBefore('|').substringAfterLast('/') }.toSet()).isEmpty())
        var baselineCorrect=0; var baselineKnownCorrect=0; var accepted=0; var correct=0
        evaluation.forEach { row ->
            val parts=row.split('|'); val expected=WasteKind.valueOf(parts[1])
            val bitmap=instrumentation.context.assets.open(parts[0]).use { android.graphics.BitmapFactory.decodeStream(it) }!!
            try {
                val baseline=WasteRules.decide(ObjectVision.classify(context,bitmap))
                if(baseline.kind==expected) {
                    baselineCorrect++
                    if(baseline.kind!=WasteKind.UNKNOWN)baselineKnownCorrect++
                }
                val features=ObjectVision.embedding(context,bitmap)
                val reference=ReferenceClassifier.match(features,examples)
                val final=if(baseline.kind!=WasteKind.UNKNOWN)baseline.kind else reference?.kind ?: WasteKind.UNKNOWN
                if(final!=WasteKind.UNKNOWN) { accepted++; if(final==expected)correct++ }
                val candidate=ReferenceClassifier.rank(features,examples)
                instrumentation.sendStatus(0,android.os.Bundle().apply { putString("stream",
                    "\nREFERENCE "+parts[0]+" expected="+expected+" baseline="+baseline.kind+" result="+final+" nearest="+candidate?.example?.kind+" similarity="+candidate?.similarity+" support="+candidate?.support+" margin="+candidate?.margin+"\n") })
            } finally { bitmap.recycle() }
        }
        instrumentation.sendStatus(0,android.os.Bundle().apply { putString("stream",
            "\nREFERENCE_SUMMARY baselineCorrect="+baselineCorrect+"/60 accepted="+accepted+" correctAccepted="+correct+"\n") })
        // This is a small development set, not a claim of field accuracy.
        assertTrue("The reference bank must contribute correct recognitions",correct>baselineKnownCorrect)
        assertTrue("Accepted suggestions should remain reliable on these development photos",correct.toDouble()/accepted>=0.9)
    }

    @Test fun actualFullPhotoKeepsKeyboardInsteadOfBlindlyCropping() = runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val file=File.createTempFile("full_keyboard_",".jpg",context.cacheDir)
        try {
            instrumentation.context.assets.open("keyboard.jpg").use { input -> file.outputStream().use { input.copyTo(it) } }
            val result=ScanEngine(context).recognize(Uri.fromFile(file))
            assertEquals(ObjectIdentity.KEYBOARD,result.identity)
            assertEquals(WasteKind.ELECTRONICS,result.kind)
            assertEquals(null,result.focus)
        } finally { file.delete() }
    }

    @Test fun vapeReferenceRecognizesResizedDeviceWithoutTextRecognition() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val examples=ObjectVision.referenceExamples(context)
        for(name in listOf("vape_pod.jpg","vape_pen.jpg","vape_pod_rotation1.jpg","vape_pen_rotation3.jpg")) {
            val bitmap=context.assets.open("references/"+name).use { android.graphics.BitmapFactory.decodeStream(it) }!!
            val resized=Bitmap.createScaledBitmap(bitmap,maxOf(1,bitmap.width*3/4),maxOf(1,bitmap.height*3/4),true)
            try {
                val match=ReferenceClassifier.match(ObjectVision.embedding(context,resized),examples)
                assertEquals(name,ObjectIdentity.VAPE,match?.identity)
            } finally { resized.recycle(); bitmap.recycle() }
        }
    }

    @Test fun referencePhotosReachTheActualPhotoScanner() = runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val fixtures=listOf("plastic105.jpg" to WasteKind.PLASTIC,"glass103.jpg" to WasteKind.GLASS,
            "metal107.jpg" to WasteKind.METAL)
        for((name,expected) in fixtures) {
            val file=File.createTempFile("real_waste_",".jpg",context.cacheDir)
            try {
                instrumentation.context.assets.open("evaluation/"+name).use { input -> file.outputStream().use { input.copyTo(it) } }
                val result=ScanEngine(context).recognize(Uri.fromFile(file),FocusBox(0f,0f,1f,1f))
                assertFalse(result.visualUnavailable)
                assertEquals(name,expected,result.kind)
            } finally { file.delete() }
        }
    }

    @Test fun bundledVectorsAgreeWithActualCpuFeatures() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val started=android.os.SystemClock.elapsedRealtime()
        val examples=ObjectVision.referenceExamples(context)
        InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply {
            putString("stream","\nBUNDLED_REFERENCE_LOAD_MS="+(android.os.SystemClock.elapsedRealtime()-started)+"\n")
        })
        val index=context.assets.open("reference_index.txt").bufferedReader().use { it.readLines() }
        for(position in listOf(0,180,300,361,368)) {
            val bitmap=context.assets.open(index[position].substringBefore('|')).use { android.graphics.BitmapFactory.decodeStream(it) }!!
            try { assertTrue("Reference "+position,VisualSimilarity.cosine(examples[position].features,
                ObjectVision.embedding(context,bitmap))>0.999) }
            finally { bitmap.recycle() }
        }
    }

    /** Development export: the app bundles these measured CPU features, avoiding first-scan training. */
    @Test fun exportReferenceVectors() = runBlocking {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("exportReferenceBank")=="true")
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val examples=ObjectVision.referenceExamples(instrumentation.targetContext,measure=true)
        val rows=instrumentation.context.assets.open("calibration_index.txt").bufferedReader().use { it.readLines() }
        val calibration=rows.map { row ->
            val parts=row.split('|')
            val bitmap=instrumentation.context.assets.open(parts[0]).use { android.graphics.BitmapFactory.decodeStream(it) }!!
            try { WasteKind.valueOf(parts[1]) to ReferenceClassifier.rank(ObjectVision.embedding(instrumentation.targetContext,bitmap),examples)!! }
            finally { bitmap.recycle() }
        }
        val choices=mutableListOf<String>()
        for(nearest in listOf(0.50,0.55,0.60,0.65,0.70,0.75))
            for(support in listOf(0.40,0.45,0.50,0.55,0.60))
                for(margin in listOf(0.05,0.075,0.10,0.15,0.20)) {
                    val accepted=calibration.filter { (_,candidate) -> candidate.example.kind!=WasteKind.UNKNOWN &&
                        candidate.similarity>=nearest && candidate.support>=support && candidate.margin>=margin }
                    val correct=accepted.count { (kind,candidate) -> candidate.example.kind==kind }
                    if(accepted.isNotEmpty() && correct.toDouble()/accepted.size>=0.95)
                        choices.add("correct="+correct+" accepted="+accepted.size+" nearest="+nearest+" support="+support+" margin="+margin)
                }
        instrumentation.sendStatus(0,android.os.Bundle().apply { putString("stream","\nCALIBRATION_CANDIDATES\n"+
            choices.sortedByDescending { it.substringAfter("correct=").substringBefore(' ').toInt() }.take(10).joinToString("\n")+"\n") })
        examples.forEachIndexed { index,example ->
            val scale=example.features.maxOf { kotlin.math.abs(it) }/127f
            val bytes=ByteArray(example.features.size) { (example.features[it]/scale).toInt().coerceIn(-127,127).toByte() }
            val quantized=FloatArray(bytes.size) { bytes[it]*scale }
            assertTrue(VisualSimilarity.cosine(example.features,quantized)>0.999)
            val encoded=android.util.Base64.encodeToString(bytes,android.util.Base64.NO_WRAP)
            instrumentation.sendStatus(0,android.os.Bundle().apply { putString("stream",
                "\nBINLY_REFERENCE_VECTOR|"+index+"|"+example.kind.name+"|"+(example.identity?.name ?: "")+"|"+scale+"|"+encoded+"\n") })
        }
    }

    private fun fixture(file:File,secondCode:Boolean) {
        val bitmap=Bitmap.createBitmap(1600,1000,Bitmap.Config.ARGB_8888)
        try {
            val canvas=Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.BLACK; textSize=110f; typeface=Typeface.DEFAULT_BOLD }
            canvas.drawText("01 PET",80f,450f,paint)
            if(secondCode)canvas.drawText("70 GL",900f,450f,paint)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        } finally { bitmap.recycle() }
    }
}

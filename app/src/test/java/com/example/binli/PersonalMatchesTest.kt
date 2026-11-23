package com.example.binli

import org.junit.Assert.*
import org.junit.Test

class PersonalMatchesTest {
    @Test fun savedExampleRecognizesSimilarVector() {
        val example=VisualExample(WasteKind.VAPE,ObjectIdentity.VAPE,floatArrayOf(1f,0f,0f))
        assertSame(example,VisualSimilarity.best(floatArrayOf(0.999f,0.01f,0f),listOf(example)))
    }
    @Test fun unrelatedItemDoesNotInheritVapeLabel() {
        val example=VisualExample(WasteKind.VAPE,ObjectIdentity.VAPE,floatArrayOf(1f,0f))
        assertNull(VisualSimilarity.best(floatArrayOf(0f,1f),listOf(example)))
    }
    @Test fun conflictingCloseExamplesRequireConfirmation() {
        val first=VisualExample(WasteKind.VAPE,ObjectIdentity.VAPE,floatArrayOf(1f,0.01f))
        val second=VisualExample(WasteKind.BATTERY,null,floatArrayOf(1f,0.02f))
        assertNull(VisualSimilarity.best(floatArrayOf(1f,0f),listOf(first,second)))
    }
    @Test fun invalidVectorsDoNotMatch() {
        assertNull(VisualSimilarity.normalized(floatArrayOf(Float.NaN,1f)))
        assertNull(VisualSimilarity.normalized(floatArrayOf(0f,0f)))
        assertEquals(-1.0,VisualSimilarity.cosine(floatArrayOf(1f),floatArrayOf(1f,0f)),0.0)
    }
}

package com.example.binli

import org.junit.Assert.*
import org.junit.Test

class ReferenceClassifierTest {
    private fun sample(kind:WasteKind,x:Float,y:Float)=VisualExample(kind,null,floatArrayOf(x,y))
    @Test fun multipleSimilarLabeledPicturesSupportAResult() {
        val examples=listOf(sample(WasteKind.PLASTIC,1f,0f),sample(WasteKind.PLASTIC,1f,0.1f),
            sample(WasteKind.PLASTIC,1f,-0.1f),sample(WasteKind.METAL,0f,1f))
        assertEquals(WasteKind.PLASTIC,ReferenceClassifier.match(floatArrayOf(1f,0f),examples)?.kind)
    }
    @Test fun similarButConflictingMaterialsAreRejected() {
        val examples=listOf(sample(WasteKind.PLASTIC,1f,0f),sample(WasteKind.GLASS,1f,0.02f))
        assertNull(ReferenceClassifier.match(floatArrayOf(1f,0f),examples))
    }
    @Test fun negativeExamplePreventsUnrelatedRecyclingAdvice() {
        val examples=listOf(sample(WasteKind.UNKNOWN,1f,0f),sample(WasteKind.PLASTIC,0f,1f))
        assertNull(ReferenceClassifier.match(floatArrayOf(1f,0f),examples))
    }
    @Test fun unrelatedOrInvalidQueryDoesNotForceAClass() {
        val examples=listOf(sample(WasteKind.METAL,1f,0f))
        assertNull(ReferenceClassifier.match(floatArrayOf(0f,1f),examples))
        assertNull(ReferenceClassifier.match(floatArrayOf(Float.NaN,0f),examples))
    }
    @Test fun conflictingPrintedCodesStayAmbiguousForVisualFallback() {
        val decision=WasteRules.decide(emptyList(),"01 PET\n70 GL")
        assertEquals(WasteKind.UNKNOWN,decision.kind)
        assertTrue(decision.ambiguous)
    }
}

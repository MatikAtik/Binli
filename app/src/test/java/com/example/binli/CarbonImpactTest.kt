package com.example.binli

import org.junit.Assert.*
import org.junit.Test

class CarbonImpactTest {
    @Test fun petUsesLandfillMinusRecyclingAndConvertsShortTons() {
        assertEquals(1.06,ImpactMaterial.PET.estimateKg(907.18474),1e-8)
    }
    @Test fun aluminiumCanEstimateScalesWithUserWeight() {
        assertEquals(0.15129345,ImpactMaterial.ALUMINUM.estimateKg(15.0),0.00001)
    }
    @Test fun cardboardIncludesBothScenarios() {
        assertEquals(3.32,ImpactMaterial.CARDBOARD.estimateKg(907.18474),1e-8)
    }
    @Test fun complexDevicesDoNotGetInventedCarbonSavings() {
        assertTrue(ImpactMaterial.allowed(WasteKind.VAPE).isEmpty())
        assertTrue(ImpactMaterial.allowed(WasteKind.BATTERY).isEmpty())
    }
    @Test fun invalidWeightIsRejected() {
        for(value in listOf(Double.NaN,Double.POSITIVE_INFINITY,0.0,-10.0,1_000_001.0))
            assertTrue(runCatching { ImpactMaterial.PET.estimateKg(value) }.isFailure)
    }
}

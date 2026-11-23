package com.example.binli

/** EPA WARM v16, Exhibits 2-2 and 6-17. US average recycling versus landfill, not a local measurement. */
enum class ImpactMaterial(val title:Int,val landfill:Double,val recycling:Double) {
    PET(R.string.impact_pet,0.02,-1.04),
    GLASS(R.string.impact_glass,0.02,-0.28),
    ALUMINUM(R.string.impact_aluminum,0.02,-9.13),
    CARDBOARD(R.string.impact_cardboard,0.18,-3.14);
    fun estimateKg(grams:Double):Double {
        require(grams.isFinite() && grams>0 && grams<=1_000_000)
        // Factors are metric tonnes CO2e per US short ton (907.18474 kg) of recovered material.
        return (grams/1000.0)*(landfill-recycling)*1000.0/907.18474
    }
    companion object {
        const val SOURCE="https://www.epa.gov/system/files/documents/2024-01/warm_management_practices_v16_dec.pdf"
        fun allowed(kind:WasteKind)=when(kind) {
            WasteKind.PLASTIC->listOf(PET)
            WasteKind.GLASS->listOf(GLASS)
            WasteKind.METAL->listOf(ALUMINUM)
            WasteKind.PAPER->listOf(CARDBOARD)
            else->emptyList()
        }
    }
}

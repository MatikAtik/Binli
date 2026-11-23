package com.example.binli

import org.junit.Assert.assertEquals
import org.junit.Test

class WasteRulesTest {
    @Test fun ambiguousBottleDoesNotGuessMaterial() {
        assertEquals(WasteKind.UNKNOWN,WasteRules.classify(listOf(RecognizedLabel("Bottle",0.99f))))
    }
    @Test fun lowConfidenceDoesNotRecommendBin() {
        assertEquals(WasteKind.UNKNOWN,WasteRules.classify(listOf(RecognizedLabel("Plastic bottle",0.60f))))
    }
    @Test fun batteryTakesPriorityOverOtherLabels() {
        assertEquals(WasteKind.BATTERY,WasteRules.classify(listOf(RecognizedLabel("Paper",0.98f),RecognizedLabel("Battery",0.90f))))
    }
    @Test fun recognizedPaperHasPaperGuide() {
        assertEquals(WasteKind.PAPER,WasteRules.classify(listOf(RecognizedLabel("Paper",0.92f))))
    }
    @Test fun drinkingGlassIsNotAutomaticallyPackagingGlass() {
        assertEquals(WasteKind.UNKNOWN,WasteRules.classify(listOf(RecognizedLabel("Glass",0.99f),RecognizedLabel("Tableware",0.92f))))
    }
    @Test fun realModelNewspaperLabelIsPaper() {
        assertEquals(WasteKind.PAPER,WasteRules.classify(listOf(RecognizedLabel("Newspaper",0.94f))))
    }
    @Test fun televisionAndSubwooferAreElectronicWaste() {
        for(label in listOf("Television","Subwoofer"))
            assertEquals(WasteKind.ELECTRONICS,WasteRules.classify(listOf(RecognizedLabel(label,0.91f))))
    }
    @Test fun batteryMarkOnPhoneDoesNotRecommendRemovingBuiltInBattery() {
        assertEquals(WasteKind.ELECTRONICS,WasteRules.decide(listOf(RecognizedLabel("Mobile phone",0.95f)),"Li-ion").kind)
    }
    @Test fun printedPetCodeEstablishesPlasticMaterial() {
        val decision=WasteRules.decide(emptyList(),"01 PET")
        assertEquals(WasteKind.PLASTIC,decision.kind)
        assertEquals("01 PET",decision.marking)
    }
    @Test fun splitRecyclingCodeIsRecognized() {
        assertEquals(WasteKind.PLASTIC,WasteRules.decide(emptyList(),"1\nPET").kind)
    }
    @Test fun materialCodesWorkInBothOrders() {
        for(code in listOf("02 HDPE","HDPE 2","5 PP","PP 05","PAP 20","21 PAP","GL 70","71 GL","ALU 41","40 FE")) {
            val expected=when {
                code.contains("PAP")->WasteKind.PAPER
                code.contains("GL")->WasteKind.GLASS
                code.contains("ALU") || code.contains("FE")->WasteKind.METAL
                else->WasteKind.PLASTIC
            }
            assertEquals(code,expected,WasteRules.decide(emptyList(),code).kind)
        }
    }
    @Test fun mixedMaterialCodesNeedConfirmation() {
        assertEquals(WasteKind.UNKNOWN,WasteRules.decide(emptyList(),"1 PET\n70 GL").kind)
    }
    @Test fun negatedMaterialClaimDoesNotBecomeEvidence() {
        assertEquals(WasteKind.UNKNOWN,WasteRules.decide(emptyList(),"NO PET 1").kind)
        assertEquals(WasteKind.UNKNOWN,WasteRules.decide(emptyList(),"PET FREE").kind)
    }
    @Test fun compositePackagingDoesNotBecomePaper() {
        assertEquals(WasteKind.UNKNOWN,WasteRules.decide(emptyList(),"C/PAP 81").kind)
    }
    @Test fun batteryChemistryAndModelNumbersAreRecognized() {
        for(code in listOf("LR6","CR2032","Ni-MH"))
            assertEquals(WasteKind.BATTERY,WasteRules.decide(emptyList(),code).kind)
    }
    @Test fun multipleObjectsDoNotCombineIntoSingleDisposalGuide() {
        assertEquals(WasteKind.UNKNOWN,WasteRules.decide(listOf(RecognizedLabel("Paper",0.99f)),"1 PET",true).kind)
    }
    @Test fun receiptDoesNotGoInCleanPaperStream() {
        assertEquals(WasteKind.UNKNOWN,WasteRules.classify(listOf(RecognizedLabel("Receipt",0.97f),RecognizedLabel("Paper",0.99f))))
    }
    @Test fun cupAndFoodLabelsDoNotIdentifyCupMaterial() {
        assertEquals(WasteKind.UNKNOWN,WasteRules.classify(listOf(RecognizedLabel("Cup",0.97f),RecognizedLabel("Food",0.93f))))
    }
    @Test fun conflictingVisualStreamsNeedConfirmation() {
        assertEquals(WasteKind.UNKNOWN,WasteRules.classify(listOf(RecognizedLabel("Paper",0.97f),RecognizedLabel("Glass bottle",0.93f))))
    }
    @Test fun lowAndInvalidScoresAreIgnored() {
        for(score in listOf(Float.NaN,Float.POSITIVE_INFINITY,1.5f,-1f,0.6f))
            assertEquals(WasteKind.UNKNOWN,WasteRules.classify(listOf(RecognizedLabel("Paper",score))))
    }
    @Test fun keyboardControlsOverrideWrongMusicalInstrumentGuess() {
        val decision=WasteRules.decide(listOf(RecognizedLabel("Musical instrument",0.99f)),"CTRL SHIFT ALT ENTER BACKSPACE")
        assertEquals(WasteKind.ELECTRONICS,decision.kind)
        assertEquals(ObjectIdentity.KEYBOARD,decision.identity)
    }
    @Test fun qwertyAndTwoControlKeysIdentifyComputerKeyboard() {
        assertEquals(ObjectIdentity.KEYBOARD,WasteRules.decide(emptyList(),"Q W E R T Y\nCTRL ALT").identity)
    }
    @Test fun broaderClassifierRecognizesComputerKeyboard() {
        val labels=listOf(RecognizedLabel("typewriter keyboard",0.62f,LabelSource.OBJECT_MODEL),
            RecognizedLabel("space bar",0.12f,LabelSource.OBJECT_MODEL))
        assertEquals(ObjectIdentity.KEYBOARD,WasteRules.decide(labels).identity)
        assertEquals(WasteKind.ELECTRONICS,WasteRules.classify(labels))
    }
    @Test fun similarKeyboardClassesCombineWithoutCountingOtherObjects() {
        val labels=listOf(RecognizedLabel("computer keyboard, keypad",0.40f,LabelSource.OBJECT_MODEL),
            RecognizedLabel("typewriter keyboard",0.30f,LabelSource.OBJECT_MODEL),
            RecognizedLabel("space bar",0.14f,LabelSource.OBJECT_MODEL))
        assertEquals(ObjectIdentity.KEYBOARD,WasteRules.decide(labels).identity)
    }
    @Test fun closeModelPredictionsRequireConfirmation() {
        val labels=listOf(RecognizedLabel("typewriter keyboard",0.48f,LabelSource.OBJECT_MODEL),
            RecognizedLabel("grand piano",0.43f,LabelSource.OBJECT_MODEL))
        assertEquals(WasteKind.UNKNOWN,WasteRules.classify(labels))
    }
    @Test fun vapeMarkingsOverrideMusicalInstrumentAndBatteryChemistry() {
        val decision=WasteRules.decide(listOf(RecognizedLabel("Musical instrument",0.99f)),"E-CIGARETTE\nLi-ion\nNICOTINE")
        assertEquals(WasteKind.VAPE,decision.kind)
        assertEquals(ObjectIdentity.VAPE,decision.identity)
    }
    @Test fun vapeBrandNeedsSupportingDeviceDetails() {
        assertEquals(WasteKind.VAPE,WasteRules.decide(emptyList(),"ELF BAR\n600 PUFFS").kind)
        assertEquals(WasteKind.UNKNOWN,WasteRules.decide(emptyList(),"ELF BAR").kind)
    }
    @Test fun genericMusicLabelDoesNotGetRenamedToKeyboardOrVape() {
        val decision=WasteRules.decide(listOf(RecognizedLabel("Musical instrument",0.99f)))
        assertEquals(WasteKind.UNKNOWN,decision.kind)
        assertEquals(null,decision.identity)
    }
    @Test fun lighterIsNotAutomaticallyAVape() {
        assertEquals(WasteKind.UNKNOWN,WasteRules.classify(listOf(RecognizedLabel("lighter",0.98f,LabelSource.OBJECT_MODEL))))
    }
    @Test fun unknownObjectDoesNotDefaultToPlastic() {
        assertEquals(WasteKind.UNKNOWN,WasteRules.classify(listOf(RecognizedLabel("Dog",0.98f))))
    }
}

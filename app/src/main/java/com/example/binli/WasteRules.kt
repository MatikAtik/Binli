package com.example.binli

import java.util.Locale

enum class WasteKind(val title: Int, val guide: Int, val symbol: String) {
    PLASTIC(R.string.plastic, R.string.plastic_steps, "🧴"),
    GLASS(R.string.glass, R.string.glass_steps, "🍾"),
    PAPER(R.string.paper, R.string.paper_steps, "📦"),
    METAL(R.string.metal, R.string.metal_steps, "🥫"),
    BATTERY(R.string.battery, R.string.battery_steps, "🔋"),
    ELECTRONICS(R.string.electronics, R.string.electronics_steps, "📱"),
    VAPE(R.string.vape, R.string.vape_steps, "🔌"),
    FOOD(R.string.food, R.string.food_steps, "🍎"),
    UNKNOWN(R.string.unknown, R.string.unknown_steps, "?")
}

enum class LabelSource { LEGACY, OBJECT_MODEL, REFERENCE }
enum class ObjectIdentity(val title:Int) { KEYBOARD(R.string.computer_keyboard), VAPE(R.string.vape) }
data class RecognizedLabel(val text: String, val confidence: Float, val source:LabelSource=LabelSource.LEGACY)
data class WasteDecision(val kind: WasteKind, val marking: String? = null, val identity:ObjectIdentity?=null, val identityFromText:Boolean=false, val ambiguous:Boolean=false)

object WasteRules {
    private val electronics = setOf("mobile phone","cell phone","computer","laptop","keyboard","electronic device","electronics","headphones","television","subwoofer","typewriter keyboard","computer keyboard","keypad","desktop computer","notebook computer","laptop computer","mouse","computer mouse","remote control","cellular telephone","cellphone","modem","monitor","ipod")
    private val keyboardNames=setOf("typewriter keyboard","computer keyboard","keypad")
    private val paper = setOf("paper","cardboard","carton","newspaper","envelope")
    private val food = setOf("food","fruit","vegetable","banana","apple","orange","bread","cookie","cake","pizza","sushi","meal","pie","pasta","cheeseburger","hot dog")
    private val excludedPaper = setOf("receipt","passport","money")
    private val packaging = setOf("bottle","plastic bottle","glass bottle","glass jar","jar","packaging","carton","product","bag","tin can","aluminum can","aluminium can")
    private val batteryMark = Regex("""\b(?:LR(?:03|6|14|20|44)|CR(?:20(?:16|25|32)|24(?:30|50))|HR(?:03|6)|NI[\s-]?MH|LI[\s-]?ION)\b""")
    private val codeGroups = listOf(
        WasteKind.PLASTIC to Regex("""(?<![A-Z0-9])(?:0?1[\s-]*PETE?|PETE?[\s-]*0?1|0?2[\s-]*(?:HDPE|PE-HD)|(?:HDPE|PE-HD)[\s-]*0?2|0?3[\s-]*PVC|PVC[\s-]*0?3|0?4[\s-]*(?:LDPE|PE-LD)|(?:LDPE|PE-LD)[\s-]*0?4|0?5[\s-]*PP|PP[\s-]*0?5|0?6[\s-]*PS|PS[\s-]*0?6)(?![A-Z0-9])"""),
        WasteKind.PAPER to Regex("""(?<![A-Z0-9])(?:PAP[\s-]*2[012]|2[012][\s-]*PAP)(?![A-Z0-9])"""),
        WasteKind.GLASS to Regex("""(?<![A-Z0-9])(?:GL[\s-]*7[012]|7[012][\s-]*GL)(?![A-Z0-9])"""),
        WasteKind.METAL to Regex("""(?<![A-Z0-9])(?:ALU[\s-]*41|41[\s-]*ALU|FE[\s-]*40|40[\s-]*FE)(?![A-Z0-9])""")
    )
    // These abbreviations are accepted without numbers only as a standalone marking,
    // never as a word somewhere in advertising or a negated material claim.
    private val standalone = mapOf("PET" to WasteKind.PLASTIC,"PETE" to WasteKind.PLASTIC,
        "HDPE" to WasteKind.PLASTIC,"PE-HD" to WasteKind.PLASTIC,
        "LDPE" to WasteKind.PLASTIC,"PE-LD" to WasteKind.PLASTIC)
    private val conflictText = Regex("""\b(?:FREE|NO|NOT|WITHOUT|BEZ|NEOBSAHUJE|DOES\s+NOT|DO\s+NOT)\b""")

    fun classify(labels: List<RecognizedLabel>): WasteKind = decide(labels).kind

    fun decide(labels: List<RecognizedLabel>, printedText: String = "", multipleObjects: Boolean = false): WasteDecision {
        if(multipleObjects)return WasteDecision(WasteKind.UNKNOWN,ambiguous=true)
        val top=labels.filter { it.source==LabelSource.OBJECT_MODEL && it.confidence.isFinite() && it.confidence in 0f..1f }.sortedByDescending { it.confidence }
        fun hasName(label:RecognizedLabel,names:Set<String>)=label.text.split(',').any { normalize(it) in names }
        val keyboardMass=top.filter { hasName(it,keyboardNames) }.sumOf { it.confidence.toDouble() }
        val keyboardAlternative=top.firstOrNull { !hasName(it,keyboardNames) }?.confidence ?: 0f
        val visualKeyboard=keyboardMass>=0.55 && keyboardMass-keyboardAlternative>=0.20
        val electronicsMass=top.filter { hasName(it,electronics) }.sumOf { it.confidence.toDouble() }
        val electronicsAlternative=top.firstOrNull { !hasName(it,electronics) }?.confidence ?: 0f
        val visualElectronics=electronicsMass>=0.60 && electronicsMass-electronicsAlternative>=0.20
        val separated=top.firstOrNull()?.takeIf { it.confidence>=0.45f && it.confidence-(top.getOrNull(1)?.confidence ?: 0f)>=0.15f }
        val trusted=labels.filter { it.source==LabelSource.LEGACY && it.confidence.isFinite() && it.confidence in 0.75f..1f }+listOfNotNull(separated)
        val confident=trusted.flatMap { it.text.split(',') }.map { normalize(it) }.toSet()
        val text=printedText.uppercase(Locale.ROOT).replace('\u00a0',' ')
        val controls=setOf("CTRL","CONTROL","SHIFT","ALT","ENTER","ESC","BACKSPACE","CAPS")
        val keyCount=Regex("[A-Z]+").findAll(text).map { it.value }.toSet().intersect(controls).size
        val keyboardKeys=keyCount>=4 || (keyCount>=2 && Regex("""\bQ\s*W\s*E\s*R\s*T\s*Y\b""").containsMatchIn(text))
        val vapeText=Regex("""\b(?:VAPE|VAPING|E[\s-]?CIG(?:ARETTE)?|ELECTRONIC\s+CIGARETTE)\b""").containsMatchIn(text)
        val vapeBrand=Regex("""\b(?:ELF\s*BAR|LOST\s+MARY|VAPORESSO|VOOPOO|GEEK\s*VAPE|GEEK\s*BAR|INNOKIN|OXVA)\b""").containsMatchIn(text)
        val vapeDetails=Regex("""\b(?:NICOTINE|NIKOTIN|PUFFS?|MESH\s+COIL)\b""").containsMatchIn(text)
        if(vapeText || (vapeBrand && vapeDetails) || confident.any { it in setOf("vape","electronic cigarette","e-cigarette") })
            return WasteDecision(WasteKind.VAPE,identity=ObjectIdentity.VAPE,identityFromText=vapeText || vapeBrand)
        if(keyboardKeys || visualKeyboard || confident.any { it in keyboardNames })
            return WasteDecision(WasteKind.ELECTRONICS,identity=ObjectIdentity.KEYBOARD,identityFromText=keyboardKeys)
        // Device labels describe the whole item, even if its label mentions a battery or plastic enclosure.
        if(visualElectronics || confident.any { it in electronics })return WasteDecision(WasteKind.ELECTRONICS)
        if(confident.any { it=="battery" || it=="batteries" })return WasteDecision(WasteKind.BATTERY)

        val marks=mutableListOf<Pair<WasteKind,String>>()
        val lines=text.lines().map { it.trim() }
        val splitCodes=lines.zipWithNext().mapNotNull { (first,second) ->
            if(first.matches(Regex("\\d{1,2}")) || second.matches(Regex("\\d{1,2}")))
                (first+" "+second).takeIf { it.matches(Regex("(?:\\d{1,2}\\s+[A-Z-]{2,5}|[A-Z-]{2,5}\\s+\\d{1,2})")) }
            else null
        }
        for(line in lines+splitCodes) {
            if(conflictText.containsMatchIn(line) || line.contains("C/"))continue
            codeGroups.forEach { (kind,pattern) -> pattern.findAll(line).forEach { marks.add(kind to it.value) } }
            standalone[line.trim()]?.let { marks.add(it to line.trim()) }
        }
        // Multiple material codes may refer to separate caps, labels or composite packaging.
        val markedKinds=marks.map { it.first }.toSet()
        if(markedKinds.size>1)return WasteDecision(WasteKind.UNKNOWN,ambiguous=true)
        val mark=marks.firstOrNull()
        if(mark!=null)return WasteDecision(mark.first,mark.second)
        // A chemistry marking without an electronic-device or packaging context identifies a battery.
        if(confident.none { it in packaging } && batteryMark.containsMatchIn(text))
            return WasteDecision(WasteKind.BATTERY,batteryMark.find(text)?.value)
        if(confident.any { it in excludedPaper })return WasteDecision(WasteKind.UNKNOWN)

        val candidates=buildSet {
            if(confident.any { it in paper })add(WasteKind.PAPER)
            if(confident.any { it in setOf("tin can","aluminum can","aluminium can") })add(WasteKind.METAL)
            if(confident.any { it in setOf("plastic bottle","plastic packaging") })add(WasteKind.PLASTIC)
            if(confident.any { it in setOf("glass bottle","glass jar") })add(WasteKind.GLASS)
            // Drinks photographed in containers do not establish whether the container is recyclable.
            if(confident.any { it in food } && confident.none { it in packaging || it=="tableware" || it=="cup" })
                add(WasteKind.FOOD)
        }
        return WasteDecision(candidates.singleOrNull() ?: WasteKind.UNKNOWN)
    }

    private fun normalize(value: String) = value.lowercase(Locale.ROOT).trim().replace(Regex("\\s+")," ")
}

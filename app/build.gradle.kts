import java.net.URI
import java.util.zip.ZipFile
import java.security.MessageDigest
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.awt.image.BufferedImage
import java.awt.RenderingHints

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.binli"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.binli"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
        for (field in listOf("FIREBASE_PROJECT_ID", "FIREBASE_APP_ID")) {
            val configured = providers.gradleProperty("BINLY_$field").orElse("").get()
            buildConfigField("String", field, "\""+configured.replace("\\", "\\\\").replace("\"", "\\\"")+"\"")
        }
        val authKey = providers.gradleProperty("BINLY_FIREBASE_API_KEY").orElse("").get()
        buildConfigField("String", "FIREBASE_API_KEY", "\""+authKey.replace("\\", "\\\\").replace("\"", "\\\"")+"\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// Generate model assets in build/, leaving IDE-managed source files untouched.
abstract class PrepareObjectModel : DefaultTask() {
    @get:Input abstract val modelUrl: org.gradle.api.provider.Property<String>
    @get:Input abstract val embeddingModel: org.gradle.api.provider.Property<Boolean>
    @get:OutputDirectory abstract val outputDirectory: org.gradle.api.file.DirectoryProperty
    @TaskAction fun prepare() {
        val embedding=embeddingModel.get()
        val directory=outputDirectory.get().asFile.apply { mkdirs() }
        val model=directory.resolve(if(embedding)"personal_embedder_v1.tflite" else "efficientnet_lite0_v1.tflite")
        val temporary=directory.resolve("model.download")
        val connection=URI(modelUrl.get()).toURL().openConnection().apply {
            connectTimeout=30000; readTimeout=120000
        }
        try {
            connection.getInputStream().use { input -> temporary.outputStream().use { input.copyTo(it) } }
            val bytes=temporary.readBytes()
            check(bytes.size>1_000_000 && bytes.copyOfRange(4,8).toString(Charsets.US_ASCII)=="TFL3") { "Invalid recognition model" }
            if(!embedding)ZipFile(temporary).use { archive ->
                val labels=archive.entries().asSequence().first { !it.isDirectory && it.name.contains("label",ignoreCase=true) }
                archive.getInputStream(labels).use { input ->
                    directory.resolve("object_labels.txt").outputStream().use { input.copyTo(it) }
                }
            }
            fun field(b:ByteBuffer,table:Int,index:Int):Int {
                val v=table-b.getInt(table)
                val offset=4+index*2
                if(offset >= (b.getShort(v).toInt() and 65535))return 0
                val relative=b.getShort(v+offset).toInt() and 65535
                return if(relative==0)0 else table+relative
            }
            fun vector(b:ByteBuffer,table:Int,index:Int):Int {
                val f=field(b,table,index); check(f!=0) { "Missing model contract field "+index }
                return f+b.getInt(f)
            }
            fun tables(b:ByteBuffer,table:Int,index:Int):List<Int> {
                val v=vector(b,table,index)
                return List(b.getInt(v)) { n -> val p=v+4+n*4; p+b.getInt(p) }
            }
            fun string(b:ByteBuffer,table:Int,index:Int):String {
                val v=vector(b,table,index)
                return ByteArray(b.getInt(v)) { n -> b.get(v+4+n) }.toString(Charsets.UTF_8)
            }
            val graph=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val root=graph.getInt(0)
            val subgraph=tables(graph,root,2).first()
            val inputIndices=vector(graph,subgraph,1)
            val input=tables(graph,subgraph,0)[graph.getInt(inputIndices+4)]
            val shape=vector(graph,input,0)
            val dimensions=List(graph.getInt(shape)) { graph.getInt(shape+4+it*4) }
            check(dimensions==listOf(1,224,224,3)) { "Unexpected recognition input: "+dimensions }
            val typeField=field(graph,input,1)
            check(typeField==0 || graph.get(typeField).toInt()==0) { "Recognition input must be float32" }
            val metaEntry=tables(graph,root,6).first { string(graph,it,0)=="TFLITE_METADATA" }
            val metadataBuffer=graph.getInt(field(graph,metaEntry,1))
            val modelBuffer=tables(graph,root,4)[metadataBuffer]
            val data=vector(graph,modelBuffer,0)
            val metadataBytes=ByteArray(graph.getInt(data)) { graph.get(data+4+it) }
            val metadata=ByteBuffer.wrap(metadataBytes).order(ByteOrder.LITTLE_ENDIAN)
            val metaRoot=metadata.getInt(0)
            val subgraphMeta=tables(metadata,metaRoot,3).first()
            val inputMeta=tables(metadata,subgraphMeta,2).first()
            val normalization=tables(metadata,inputMeta,4).first { metadata.get(field(metadata,it,0)).toInt()==1 }
            val optionsField=field(metadata,normalization,1)
            val options=optionsField+metadata.getInt(optionsField)
            val means=vector(metadata,options,0); val stds=vector(metadata,options,1)
            val mean=metadata.getFloat(means+4); val std=metadata.getFloat(stds+4)
            check(if(embedding)mean.isFinite() && std.isFinite() && std>0f else mean==127f && std==128f) { "Recognition normalization: mean="+mean+", std="+std }
            directory.resolve("model_contract.txt").writeText("RGB NHWC "+dimensions+"; float32; mean="+mean+"; std="+std)
            if(embedding)directory.resolve("embedding_normalization.txt").writeText(mean.toString()+"|"+std)
            val labelCount=if(embedding)0 else directory.resolve("object_labels.txt").readLines().size
            check(embedding || labelCount in 1000..1001) { "Unexpected model labels: "+labelCount }
            check(temporary.renameTo(model) || run { temporary.copyTo(model,overwrite=true); temporary.delete() })
            val digest=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            logger.lifecycle("Object model: "+labelCount+" labels; SHA-256 "+digest)
        } finally { temporary.delete() }
    }
}
val prepareObjectModel=tasks.register<PrepareObjectModel>("prepareObjectModel") {
    modelUrl.set("https://storage.googleapis.com/mediapipe-models/image_classifier/efficientnet_lite0/float32/1/efficientnet_lite0.tflite")
    embeddingModel.set(false)
    outputDirectory.set(layout.buildDirectory.dir("generated/objectModelAssets"))
}
val preparePersonalEmbedder=tasks.register<PrepareObjectModel>("preparePersonalEmbedder") {
    modelUrl.set("https://storage.googleapis.com/mediapipe-models/image_embedder/mobilenet_v3_small/float32/1/mobilenet_v3_small.tflite")
    embeddingModel.set(true)
    outputDirectory.set(layout.buildDirectory.dir("generated/personalEmbedderAssets"))
}
abstract class PrepareRecognitionFixture : DefaultTask() {
    @get:Input abstract val sourceUrl: org.gradle.api.provider.Property<String>
    @get:OutputDirectory abstract val outputDirectory: org.gradle.api.file.DirectoryProperty
    @TaskAction fun prepare() {
        val directory=outputDirectory.get().asFile.apply { mkdirs() }
        val connection=URI(sourceUrl.get()).toURL().openConnection().apply {
            connectTimeout=30000; readTimeout=60000
            setRequestProperty("User-Agent","BinlyDevelopment/1.0 (recognition regression fixture)")
        }
        connection.getInputStream().use { input ->
            directory.resolve("keyboard.jpg").outputStream().use { input.copyTo(it) }
        }
    }
}
val prepareRecognitionFixture=tasks.register<PrepareRecognitionFixture>("prepareRecognitionFixture") {
    sourceUrl.set("https://upload.wikimedia.org/wikipedia/commons/3/32/Standard_white_computer_keyboard_keys.jpg")
    outputDirectory.set(layout.buildDirectory.dir("generated/recognitionTestAssets"))
}

/** Small, reproducible reference bank; held-out images are packaged ONLY with tests. */
abstract class PrepareWasteExamples : DefaultTask() {
    @get:OutputDirectory abstract val referenceDirectory: org.gradle.api.file.DirectoryProperty
    @get:OutputDirectory abstract val testDirectory: org.gradle.api.file.DirectoryProperty
    @TaskAction fun prepare() {
        val references=referenceDirectory.get().asFile.apply { deleteRecursively(); mkdirs() }
        val tests=testDirectory.get().asFile.apply { deleteRecursively(); mkdirs() }
        fun download(url:String,file:java.io.File) {
            if(file.isFile && file.length()>1000)return
            val connection=URI(url).toURL().openConnection().apply {
                connectTimeout=30000; readTimeout=120000
                setRequestProperty("User-Agent","BinlyDevelopment/1.0 (public recognition examples)")
            }
            file.parentFile.mkdirs()
            val partial=File(file.parentFile,file.name+".partial")
            try {
                connection.getInputStream().use { input -> partial.outputStream().use { input.copyTo(it) } }
                partial.copyTo(file,overwrite=true)
            } finally { partial.delete() }
        }
        val archive=temporaryDir.resolve("trashnet.zip")
        download("https://raw.githubusercontent.com/garythung/trashnet/master/data/dataset-resized.zip",archive)
        val digest=MessageDigest.getInstance("SHA-256").digest(archive.readBytes()).joinToString("") { "%02x".format(it) }
        check(digest=="0bf472790f8b20e5c950d5b5012a9d38af0d3392efd65f8ce171334fc16b07c2") { "Reference archive content changed; review before updating" }
        logger.lifecycle("TrashNet reference archive SHA-256 "+digest)
        val kinds=mapOf("plastic" to "PLASTIC","glass" to "GLASS","paper" to "PAPER",
            "cardboard" to "PAPER","metal" to "METAL","trash" to "UNKNOWN")
        val train=mutableListOf<String>(); val test=mutableListOf<String>(); val calibration=mutableListOf<String>()
        ZipFile(archive).use { zip ->
            kinds.forEach { (category,kind) ->
                // Disjoint image IDs. No held-out image, nor any transformation of it, enters the bank.
                for(index in (1..80)+(101..110)) {
                    val name=category+index+".jpg"
                    val entry=zip.getEntry("dataset-resized/"+category+"/"+name) ?: error("Missing example "+name)
                    val heldOut=index>60
                    val relative=(if(index>=101)"evaluation/" else if(heldOut)"calibration/" else "references/")+name
                    val file=(if(heldOut)tests else references).resolve(relative).apply { parentFile.mkdirs() }
                    zip.getInputStream(entry).use { input -> file.outputStream().use { input.copyTo(it) } }
                    (if(index>=101)test else if(heldOut)calibration else train).add(relative+"|"+kind+"|")
                }
            }
        }
        val extras=listOf(
            Triple("keyboard.jpg","ELECTRONICS|KEYBOARD","https://upload.wikimedia.org/wikipedia/commons/3/32/Standard_white_computer_keyboard_keys.jpg"),
            Triple("vape_pod.jpg","VAPE|VAPE","https://upload.wikimedia.org/wikipedia/commons/d/da/2023_Lost_Vape_Ursa_Nano_Pod.jpg"),
            Triple("vape_pen.jpg","VAPE|VAPE","https://upload.wikimedia.org/wikipedia/commons/f/f5/Flerbar_M_Mango_Ice-9467.jpg")
        )
        extras.forEach { (name,label,url) ->
            // Resize large Commons photographs to bound APK size and decoder memory.
            val original=temporaryDir.resolve(name); download(url,original)
            val image=javax.imageio.ImageIO.read(original) ?: error("Invalid reference "+name)
            val scale=minOf(1.0,640.0/maxOf(image.width,image.height))
            val small=BufferedImage((image.width*scale).toInt(),(image.height*scale).toInt(),BufferedImage.TYPE_INT_RGB)
            val graphics=small.createGraphics()
            try { graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC); graphics.drawImage(image,0,0,small.width,small.height,null) } finally { graphics.dispose() }
            javax.imageio.ImageIO.write(small,"jpg",references.resolve("references/"+name))
            train.add("references/"+name+"|"+label)
            if(label=="VAPE|VAPE")for(turn in 1..3) {
                val angle=turn*Math.PI/2
                val rotated=BufferedImage(if(turn%2==0)small.width else small.height,
                    if(turn%2==0)small.height else small.width,BufferedImage.TYPE_INT_RGB)
                val painter=rotated.createGraphics()
                try {
                    painter.translate(rotated.width/2.0,rotated.height/2.0)
                    painter.rotate(angle)
                    painter.drawImage(small,-small.width/2,-small.height/2,null)
                } finally { painter.dispose() }
                val rotatedName=name.removeSuffix(".jpg")+"_rotation"+turn+".jpg"
                javax.imageio.ImageIO.write(rotated,"jpg",references.resolve("references/"+rotatedName))
                train.add("references/"+rotatedName+"|"+label)
            }
        }
        references.resolve("reference_index.txt").writeText(train.joinToString("\n"))
        tests.resolve("evaluation_index.txt").writeText(test.joinToString("\n"))
        tests.resolve("calibration_index.txt").writeText(calibration.joinToString("\n"))
        logger.lifecycle("Recognition examples: "+train.size+" references; "+test.size+" disjoint evaluation photos")
    }
}
val prepareWasteExamples=tasks.register<PrepareWasteExamples>("prepareWasteExamples") {
    referenceDirectory.set(layout.buildDirectory.dir("generated/wasteReferenceAssets"))
    testDirectory.set(layout.buildDirectory.dir("generated/wasteEvaluationAssets"))
}

androidComponents {
    onVariants(selector().all()) { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(prepareObjectModel,PrepareObjectModel::outputDirectory)
        variant.sources.assets?.addGeneratedSourceDirectory(preparePersonalEmbedder,PrepareObjectModel::outputDirectory)
        variant.sources.assets?.addGeneratedSourceDirectory(prepareWasteExamples,PrepareWasteExamples::referenceDirectory)
        variant.androidTest?.sources?.assets?.addGeneratedSourceDirectory(prepareWasteExamples,PrepareWasteExamples::testDirectory)
        variant.androidTest?.sources?.assets?.addGeneratedSourceDirectory(prepareRecognitionFixture,PrepareRecognitionFixture::outputDirectory)
    }
}

dependencies {
    implementation("com.google.android.gms:play-services-litert:17.0.0")
    implementation(platform("com.google.firebase:firebase-bom:34.1.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
    implementation("com.google.mlkit:object-detection:17.0.2")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
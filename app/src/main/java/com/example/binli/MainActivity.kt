package com.example.binli

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.provider.Settings
import android.view.*
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.location.LocationManagerCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import org.osmdroid.config.Configuration as MapConfiguration
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume

class MainActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences("binly", MODE_PRIVATE) }
    private val dark get() = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private val ink get() = Color.parseColor(if(dark) "#E4EFDF" else "#0F3E2A")
    private val accent get() = Color.parseColor(if(dark) "#A3D77D" else "#2F8941")
    private val secondary get() = Color.parseColor(if(dark) "#A9BBAA" else "#6E8071")
    private val backgroundColor get() = Color.parseColor(if(dark) "#111C16" else "#F6F8EF")
    private val surface get() = Color.parseColor(if(dark) "#203126" else "#FFFFFF")
    private val soft get() = Color.parseColor(if(dark) "#2D402B" else "#E8F1DC")
    private val forest = Color.parseColor("#0F3E2A")
    private var tab = 0
    private var quizIndex = 0
    private var quizSelection:Int?=null
    private var quizAwarded=false
    private var scanFeatures:FloatArray?=null
    private var personalMatched=false
    private var mapQuery=""
    private var mapSearch:EditText?=null
    private var impactRecordId=java.util.UUID.randomUUID().toString()
    private var kind = WasteKind.UNKNOWN
    private var photoUri: Uri? = null
    private var cameraUri: Uri? = null
    private var labels = emptyList<RecognizedLabel>()
    private var scanFocus: FocusBox? = null
    private var multipleScanObjects = false
    private var materialMarking: String? = null
    private var objectIdentity:ObjectIdentity?=null
    private var identityFromText=false
    private var visualUnavailable=false
    private var objectConfirmed=false
    private var scanning = false
    private var scanFailed = false
    private var scanJob: Job? = null
    private var mapJob: Job? = null
    private var previewJob: Job? = null
    private var map: MapView? = null
    private var mapNeedsRetry=false
    private var area: Area? = null
    private var mapZoom = 12.0
    private lateinit var body: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var mapStatus: TextView
    private lateinit var mapResults: LinearLayout
    private val dropOffs = DropOffRepository()
    private val scanEngine by lazy { ScanEngine(applicationContext) }
    private val auth by lazy { AuthGateway.get(this) }
    private val authStateListener = com.google.firebase.auth.FirebaseAuth.AuthStateListener {
        if(::body.isInitialized && (tab==4 || tab==8))render()
    }
    private var xp: Int
        get() = prefs.getInt("xp",0)
        set(value) { prefs.edit().putInt("xp",value).apply() }

    private val picker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if(uri != null) {
            try { contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: SecurityException) { }
            identify(uri)
        }
    }
    private val camera = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if(success) cameraUri?.let { identify(it) }
    }
    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if(tab!=2)return@registerForActivityResult
        if(granted) locate(true)
        else {
            locate(false)
            if(!shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                AlertDialog.Builder(this).setMessage(R.string.permission_settings)
                    .setPositiveButton(R.string.open_settings) { _,_ ->
                        openUri(Uri.parse("package:"+packageName),Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    }.setNegativeButton(R.string.cancel,null).show()
            }
        }
    }

    override fun attachBaseContext(base: Context) {
        val language=base.getSharedPreferences("binly",MODE_PRIVATE).getString("language","en") ?: "en"
        val config=Configuration(base.resources.configuration)
        config.setLocale(Locale.forLanguageTag(language))
        super.attachBaseContext(base.createConfigurationContext(config))
    }
    override fun onCreate(state: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(when(prefs.getString("theme","system")) {
            "dark" -> AppCompatDelegate.MODE_NIGHT_YES
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        })
        super.onCreate(state)
        WindowCompat.setDecorFitsSystemWindows(window,false)
        tab=state?.getInt("tab") ?: 0
        quizIndex=(state?.getInt("quiz") ?: 0).coerceIn(0,2)
        quizSelection=state?.getInt("quizSelection",0)?.takeIf { it!=0 }
        quizAwarded=state?.getBoolean("quizAwarded") ?: false
        scanFeatures=state?.getFloatArray("scanFeatures")
        personalMatched=state?.getBoolean("personalMatched") ?: false
        mapQuery=state?.getString("mapQuery") ?: ""
        impactRecordId=state?.getString("impactRecordId") ?: java.util.UUID.randomUUID().toString()
        kind=runCatching { WasteKind.valueOf(state?.getString("kind") ?: "UNKNOWN") }.getOrDefault(WasteKind.UNKNOWN)
        photoUri=state?.getString("photo")?.let(Uri::parse)
        scanFocus=FocusBox.from(state?.getFloatArray("scanFocus"))
        multipleScanObjects=state?.getBoolean("multipleScanObjects") ?: false
        materialMarking=state?.getString("materialMarking")
        objectIdentity=state?.getString("objectIdentity")?.let { runCatching { ObjectIdentity.valueOf(it) }.getOrNull() }
        identityFromText=state?.getBoolean("identityFromText") ?: false
        visualUnavailable=state?.getBoolean("visualUnavailable") ?: false
        objectConfirmed=state?.getBoolean("objectConfirmed") ?: false
        cameraUri=state?.getString("camera")?.let(Uri::parse)
        val names=state?.getStringArrayList("labelNames") ?: arrayListOf()
        val scores=state?.getFloatArray("labelScores") ?: floatArrayOf()
        val sources=state?.getStringArrayList("labelSources") ?: arrayListOf()
        labels=names.mapIndexed { i,name -> RecognizedLabel(name,scores.getOrElse(i) { 0f },
            sources.getOrNull(i)?.let { runCatching { LabelSource.valueOf(it) }.getOrNull() } ?: LabelSource.LEGACY) }
        if(state?.containsKey("lat")==true) area=Area(state.getDouble("lat"),state.getDouble("lon"),state.getString("areaLabel") ?: "",state.getBoolean("approximate"),state.getString("areaSource") ?: if(state.getBoolean("approximate"))"ip" else "device")
        mapZoom=state?.getDouble("zoom",12.0) ?: 12.0
        MapConfiguration.getInstance().apply {
            userAgentValue="Binly/1.0 (Android; com.example.binli)"
            osmdroidBasePath=File(cacheDir,"maps")
            osmdroidTileCache=File(cacheDir,"maps/tiles")
            expirationOverrideDuration=7L*24*60*60*1000
        }
        onBackPressedDispatcher.addCallback(this,object:OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if(tab!=0) { tab=if(tab in 5..7) 0 else if(tab==8)4 else 0; render() }
                else { isEnabled=false; onBackPressedDispatcher.onBackPressed() }
            }
        })
        render()
        if(state?.getBoolean("scanning")==true) photoUri?.let { identify(it,scanFocus) }
    }
    override fun onSaveInstanceState(out: Bundle) {
        captureMap()
        out.putInt("tab",tab); out.putInt("quiz",quizIndex); out.putString("kind",kind.name)
        out.putString("photo",photoUri?.toString()); out.putString("camera",cameraUri?.toString())
        out.putBoolean("scanning",scanning)
        out.putInt("quizSelection",quizSelection ?: 0)
        out.putBoolean("quizAwarded",quizAwarded)
        out.putFloatArray("scanFeatures",scanFeatures)
        out.putBoolean("personalMatched",personalMatched)
        mapQuery=mapSearch?.text?.toString() ?: mapQuery
        out.putString("mapQuery",mapQuery)
        out.putString("impactRecordId",impactRecordId)
        area?.let { out.putString("areaSource",it.source) }
        out.putFloatArray("scanFocus",scanFocus?.toArray())
        out.putBoolean("multipleScanObjects",multipleScanObjects)
        out.putString("materialMarking",materialMarking)
        out.putString("objectIdentity",objectIdentity?.name)
        out.putBoolean("identityFromText",identityFromText)
        out.putBoolean("visualUnavailable",visualUnavailable)
        out.putBoolean("objectConfirmed",objectConfirmed)
        out.putStringArrayList("labelSources",ArrayList(labels.map { it.source.name }))
        out.putStringArrayList("labelNames",ArrayList(labels.map { it.text }))
        out.putFloatArray("labelScores",labels.map { it.confidence }.toFloatArray())
        area?.let { out.putDouble("lat",it.latitude); out.putDouble("lon",it.longitude); out.putString("areaLabel",it.label); out.putBoolean("approximate",it.approximate) }
        out.putDouble("zoom",mapZoom)
        super.onSaveInstanceState(out)
    }
    override fun onStart() { super.onStart(); auth?.addAuthStateListener(authStateListener) }
    override fun onStop() { auth?.removeAuthStateListener(authStateListener); super.onStop() }
    override fun onResume() {
        super.onResume(); map?.onResume()
        if(tab==2 && mapNeedsRetry) {
            mapNeedsRetry=false
            area?.let { loadPoints(it.latitude,it.longitude) } ?: locate(hasLocationPermission())
        }
    }
    override fun onPause() { map?.onPause(); super.onPause() }
    override fun onDestroy() { mapJob?.cancel(); previewJob?.cancel(); map?.onDetach(); map=null; super.onDestroy() }

    private fun dp(n: Int)=(n*resources.displayMetrics.density).toInt()
    private fun s(id: Int)=getString(id)
    private fun col()=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
    private fun row()=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
    // Horizontal children wrap by default; only deliberately weighted children consume spare width.
    private fun LinearLayout.add(v: View,w: Int=if(orientation==LinearLayout.HORIZONTAL)-2 else -1,h: Int=-2,weight: Float=0f) {
        addView(v,LinearLayout.LayoutParams(if(w>0)dp(w) else w,if(h>0)dp(h) else h,weight))
    }
    private fun pad(v: View,n: Int) { v.setPadding(dp(n),dp(n),dp(n),dp(n)) }
    private fun gap(parent: LinearLayout,n: Int=14) { parent.add(Space(this),-1,n) }
    private fun shape(color: Int,radius: Int=22)=GradientDrawable().apply { setColor(color); cornerRadius=dp(radius).toFloat() }
    private fun text(value: String,size: Int=14,color: Int=ink,bold: Boolean=false)=TextView(this).apply {
        text=value; textSize=size.toFloat(); setTextColor(color); includeFontPadding=false
        setLineSpacing(dp(3).toFloat(),1f)
        if(bold) typeface=Typeface.create("sans-serif",Typeface.BOLD)
    }
    private fun card(color: Int=surface)=col().apply { background=shape(color); pad(this,18) }
    private fun button(label: String,action:()->Unit)=text(label,15,Color.WHITE,true).apply {
        gravity=Gravity.CENTER; background=android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33ffffff),shape(Color.parseColor("#2F8941"),15),shape(Color.WHITE,15)); pad(this,15); minimumHeight=dp(52)
        isFocusable=true; setOnClickListener { action() }
    }
    private fun heading(title: Int,sub: Int?=null) {
        body.add(text(s(title),28,ink,true))
        if(sub!=null) { gap(body,10); body.add(text(s(sub),14,secondary)) }
        gap(body,22)
    }
    private fun section(id: Int) { body.add(text(s(id),19,ink,true)); gap(body) }
    private fun go(destination: Int) { tab=destination; render() }
    private fun render() {
        captureMap(); mapQuery=mapSearch?.text?.toString() ?: mapQuery; mapSearch=null
        mapJob?.cancel(); previewJob?.cancel()
        map?.onPause(); map?.onDetach(); map=null
        val root=col().apply { setBackgroundColor(backgroundColor) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view,insets ->
            val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime=insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left,bars.top,bars.right,maxOf(bars.bottom,ime.bottom)); insets
        }
        WindowCompat.getInsetsController(window,root).apply {
            isAppearanceLightStatusBars=!dark; isAppearanceLightNavigationBars=!dark
        }
        scroll=ScrollView(this).apply { id=R.id.main_scroll; isFillViewport=true; clipToPadding=false }
        val wrap=FrameLayout(this)
        body=col().apply { setPadding(dp(18),dp(22),dp(18),dp(24)) }
        wrap.addView(body,FrameLayout.LayoutParams(-1,-2,Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        wrap.addOnLayoutChangeListener { _,l,_,r,_,_,_,_,_ ->
            val target=minOf(r-l,dp(680))
            if(target>0 && body.layoutParams.width!=target) body.layoutParams=(body.layoutParams as FrameLayout.LayoutParams).apply { width=target }
        }
        scroll.addView(wrap); root.add(scroll,-1,0,1f)
        setContentView(root)
        when(tab) { 0->home(); 1->learn(); 2->locations(); 3->leaderboard(); 4->profile(); 5->scan(); 6->quiz(); 7->result(); 8->settings() }
        val nav=row().apply { setBackgroundColor(surface); setPadding(dp(6),dp(8),dp(6),dp(8)); elevation=dp(5).toFloat() }
        val active=when(tab) { 8->4; 6->1; 5,7->0; else->tab }
        val items=listOf("⌂" to R.string.home,"♧" to R.string.learn,"⌖" to R.string.dropoffs,"♜" to R.string.ranks,"☺" to R.string.profile)
        items.forEachIndexed { i,item ->
            val cell=col().apply { gravity=Gravity.CENTER; minimumHeight=dp(58); pad(this,5); if(i==active)background=shape(soft,14) }
            cell.add(text(item.first,22,if(i==active)accent else secondary,true).apply { gravity=Gravity.CENTER })
            gap(cell,4); cell.add(text(s(item.second),11,if(i==active)accent else secondary,i==active).apply { gravity=Gravity.CENTER })
            cell.isFocusable=true; cell.contentDescription=s(item.second); cell.setOnClickListener { go(i) }
            nav.add(cell,0,-2,1f)
        }
        root.add(nav); ViewCompat.requestApplyInsets(root)
    }
    private fun home() {
        body.add(text(s(R.string.kicker),11,secondary,true)); gap(body,8); heading(R.string.hello)
        val hero=card(forest)
        val heroRow=row(); val copy=col()
        copy.add(text(s(R.string.hero_title),27,Color.WHITE,true)); gap(copy,12); copy.add(text(s(R.string.hero_sub),14,0xffd1dfcf.toInt()))
        if(resources.configuration.screenWidthDp>=380 && resources.configuration.fontScale<1.3f) {
            heroRow.add(copy,0,-2,1f); heroRow.add(MascotView(this),112,170)
        } else { heroRow.add(copy,0,-2,1f); heroRow.add(MascotView(this),78,130) }
        hero.add(heroRow); gap(hero,20); hero.add(button("◎  "+s(R.string.scan)) { choosePhoto() }); body.add(hero); gap(body)
        val actions=row()
        listOf(R.string.camera,R.string.gallery,R.string.guide).forEachIndexed { i,id ->
            val b=button(s(id)) { when(i) { 0->takePhoto();1->pickPhoto();else->go(5) } }
            // Buttons can grow with translated text and accessibility font settings.
            actions.add(b,0,-2,1f); if(i<2)actions.add(Space(this),8,1)
        }
        body.add(actions); gap(body,24); section(R.string.daily)
        val challenge=card(soft); challenge.add(text(s(R.string.daily_title),17,ink,true)); gap(challenge,8); challenge.add(text(s(R.string.daily_sub),13,secondary)); gap(challenge,12)
        val count=prefs.getInt("quizCount",0).coerceIn(0,3)
        challenge.add(progress(count,3),-1,8); gap(challenge,8); challenge.add(text(getString(R.string.progress_count,count)+"  ·  +60 XP",12,accent,true))
        challenge.setOnClickListener { quizIndex=0; quizSelection=null; quizAwarded=false; go(6) }; body.add(challenge); gap(body,24)
        section(R.string.grow); val stats=card()
        stats.add(text(getString(R.string.level,xp/100+1,xp),17,ink,true)); gap(stats,14); stats.add(progress(xp%100,100),-1,8); gap(stats,10)
        stats.add(text(getString(R.string.next_level,100-xp%100),13,secondary)); body.add(stats); gap(body)
        val tip=card(soft); tip.add(text(s(R.string.tip_title),11,accent,true)); gap(tip,10); tip.add(text(s(R.string.tip),14)); body.add(tip)
    }
    private fun progress(value:Int,total:Int)=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply {
        max=total; progress=value; progressTintList=android.content.res.ColorStateList.valueOf(accent)
        progressBackgroundTintList=android.content.res.ColorStateList.valueOf(if(dark)0xff4a5c46.toInt() else 0xffd4dfcc.toInt())
    }
    private fun learn() {
        heading(R.string.learn_title,R.string.learn_sub)
        val banner=card(soft); banner.add(MascotView(this),-1,150); gap(banner,8); banner.add(text(s(R.string.learn_banner),21,ink,true)); body.add(banner); gap(body,24)
        section(R.string.path)
        listOf(R.string.basics,R.string.plastic_lesson,R.string.paper_lesson,R.string.battery_lesson).forEachIndexed { i,id ->
            val c=card(); val r=row(); r.add(text("0"+(i+1),22,accent,true),42)
            r.add(text(s(id),17,ink,true),0,-2,1f); r.add(text(" →",22,accent)); c.add(r)
            c.minimumHeight=dp(76); c.setOnClickListener { quizIndex=when(i) { 1->1;2->2;else->0 }; quizSelection=null; quizAwarded=false; go(6) }
            body.add(c); gap(body,12)
        }
    }
    private fun quiz() {
        heading(R.string.quiz_title)
        body.add(text((quizIndex+1).toString()+" / 3",14,accent,true)); gap(body)
        body.add(progress(quizIndex,3),-1,8); gap(body,24)
        val question=listOf(R.string.q_battery,R.string.q_plastic,R.string.q_paper)[quizIndex]
        val answers=listOf(
            listOf(R.string.a_battery,R.string.a_waste,R.string.a_plastic),
            listOf(R.string.a_empty,R.string.a_liquid,R.string.a_paper),
            listOf(R.string.a_paper,R.string.a_food,R.string.a_battery)
        )[quizIndex]
        val selected=quizSelection?.takeIf { it in answers }
        body.add(text(s(question),24,ink,true)); gap(body)
        val image=card(soft)
        image.add(text(listOf("🔋","🧴","📦")[quizIndex],72).apply { gravity=Gravity.CENTER })
        body.add(image); gap(body,20)
        answers.shuffled(kotlin.random.Random(quizIndex+42)).forEachIndexed { index,id ->
            val correct=selected!=null && id==answers[0]
            val wrong=selected==id && id!=answers[0]
            val border=if(correct)Color.parseColor("#55AD49") else if(wrong)Color.parseColor("#DF755F") else if(dark)0xff48604b.toInt() else 0xffd9e4d1.toInt()
            val fill=if(correct)soft else if(wrong) { if(dark)0xff4b2a26.toInt() else 0xffffe9e2.toInt() } else surface
            val answer=row().apply {
                minimumHeight=dp(76); pad(this,16)
                val base=shape(fill,20).apply { setStroke(dp(if(correct || wrong)2 else 1),border) }
                background=android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x2255ad49),base,shape(Color.WHITE,20))
                isFocusable=true; isClickable=selected==null
                contentDescription=s(id)+(if(correct)" ✓" else if(wrong)" ✕" else "")
            }
            answer.add(text(if(correct)"✓" else if(wrong)"✕" else ('A'+index).toString(),19,if(wrong)0xffdf755f.toInt() else accent,true).apply {
                gravity=Gravity.CENTER; background=shape(soft,12)
            },38,38)
            answer.add(Space(this),14,1)
            answer.add(text(s(id),16,ink,true),0,-2,1f)
            if(selected!=null && !correct && !wrong)answer.alpha=0.60f
            if(selected==null)answer.setOnClickListener {
                quizSelection=id; quizAwarded=false
                if(id==answers[0] && !prefs.getBoolean("answer_"+quizIndex,false)) {
                    xp+=20; quizAwarded=true
                    prefs.edit().putBoolean("answer_"+quizIndex,true).putInt("quizCount",prefs.getInt("quizCount",0)+1).apply()
                }
                answer.performHapticFeedback(if(android.os.Build.VERSION.SDK_INT>=30) {
                    if(id==answers[0])HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.REJECT
                } else HapticFeedbackConstants.KEYBOARD_TAP)
                render()
                if(id==answers[0] && animationsEnabled()) {
                    val overlay=CelebrationView(this)
                    findViewById<ViewGroup>(android.R.id.content).addView(overlay,ViewGroup.LayoutParams(-1,-1)); overlay.play()
                }
            }
            body.add(answer); gap(body,12)
        }
        if(selected!=null) {
            val right=selected==answers[0]
            val feedback=card(if(right)soft else surface)
            feedback.add(text(s(if(right)R.string.correct else R.string.incorrect),21,ink,true)); gap(feedback,10)
            feedback.add(text(s(listOf(R.string.quiz_explain_battery,R.string.quiz_explain_plastic,R.string.quiz_explain_paper)[quizIndex]),15))
            if(right && quizAwarded) { gap(feedback,12); feedback.add(text(s(R.string.quiz_xp),16,accent,true)) }
            gap(feedback,18)
            feedback.add(button(s(if(quizIndex<2)R.string.next else R.string.finish)) {
                quizSelection=null; quizAwarded=false
                if(quizIndex<2) { quizIndex++; render() } else go(1)
            })
            if(!right) { gap(feedback,10); feedback.add(button(s(R.string.quiz_retry)) { quizSelection=null; render() }) }
            body.add(feedback)
            if(animationsEnabled()) {
                feedback.alpha=0f; feedback.translationY=dp(12).toFloat()
                feedback.animate().alpha(1f).translationY(0f).setDuration(220).start()
            }
            scroll.post { scroll.smoothScrollTo(0,feedback.top-dp(100)) }
        }
    }
    private fun animationsEnabled()=if(android.os.Build.VERSION.SDK_INT>=26)android.animation.ValueAnimator.areAnimatorsEnabled()
        else Settings.Global.getFloat(contentResolver,Settings.Global.ANIMATOR_DURATION_SCALE,1f)>0f

    private fun choosePhoto() {
        AlertDialog.Builder(this).setTitle(R.string.scan)
            .setItems(arrayOf(s(R.string.gallery),s(R.string.camera))) { _,which -> if(which==0)pickPhoto() else takePhoto() }
            .setNegativeButton(R.string.cancel,null).show()
    }
    private fun pickPhoto() { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    private fun takePhoto() {
        try {
            val folder=File(cacheDir,"photos").apply { mkdirs() }
            val file=File.createTempFile("scan_",".jpg",folder)
            cameraUri=FileProvider.getUriForFile(this,packageName+".photos",file)
            camera.launch(cameraUri!!)
        } catch (_: android.content.ActivityNotFoundException) { toast(R.string.photo_unavailable) }
    }
    private fun identify(uri: Uri, focus:FocusBox?=null) {
        scanJob?.cancel()
        if(photoUri!=uri)impactRecordId=java.security.MessageDigest.getInstance("SHA-256").digest(uri.toString().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        photoUri=uri; labels=emptyList(); scanFocus=focus; scanFeatures=null; personalMatched=false
        multipleScanObjects=false; materialMarking=null; objectIdentity=null; objectConfirmed=false
        identityFromText=false; visualUnavailable=false; scanFailed=false; scanning=true; go(5)
        scanJob=lifecycleScope.launch {
            try {
                val found=scanEngine.recognize(uri,focus)
                labels=found.labels; kind=found.kind; scanFocus=found.focus
                multipleScanObjects=found.multipleObjects; materialMarking=found.marking
                objectIdentity=found.identity; identityFromText=found.identityFromText; visualUnavailable=found.visualUnavailable
                scanFeatures=found.features; personalMatched=found.personalMatch; scanning=false
                if(tab==5 || tab==7)go(7)
            } catch(e: CancellationException) { throw e }
            catch(_: Exception) { scanning=false; scanFailed=true; if(tab==5)render() }
        }
    }
    private fun scan() {
        heading(R.string.scan_title,R.string.scan_sub)
        body.add(text(s(R.string.scan_tip),13,secondary)); gap(body)
        val c=card(soft); c.add(text("◎",64,accent).apply { gravity=Gravity.CENTER }); gap(c,16)
        if(scanning) { c.add(ProgressBar(this),-1,48); gap(c); c.add(text(s(R.string.scan_busy),16,ink,true)) }
        else {
            if(scanFailed) { c.add(text(s(R.string.scan_error),14)); gap(c) }
            c.add(button(s(R.string.gallery)) { pickPhoto() }); gap(c,12); c.add(button(s(R.string.camera)) { takePhoto() })
            if(photoUri!=null && !scanFailed) { gap(c); c.add(button(s(R.string.guide)) { go(7) }) }
        }
        body.add(c); gap(body,24); section(R.string.examples)
        WasteKind.entries.filter { it!=WasteKind.UNKNOWN }.forEach { item ->
            val guide=card(); guide.add(text(item.symbol+"  "+s(item.title),16,ink,true))
            guide.setOnClickListener { kind=item; photoUri=null; labels=emptyList(); go(7) }; body.add(guide); gap(body,10)
        }
    }
    private fun result() {
        heading(if(photoUri!=null)R.string.object_result else kind.title)
        if(photoUri!=null) {
            val identity=objectIdentity
            body.add(text(if(identity!=null)getString(R.string.possible_object,s(identity.title)) else s(if(kind==WasteKind.UNKNOWN)R.string.uncertain_object else kind.title),22,ink,true)); gap(body)
            if(personalMatched && !objectConfirmed) { body.add(text(s(R.string.personal_match),14,accent,true)); gap(body) }
            if(labels.any { it.source==LabelSource.REFERENCE } && !objectConfirmed) { body.add(text(s(R.string.reference_match),14,accent,true)); gap(body) }
            if(objectConfirmed) { body.add(text(s(R.string.item_confirmed),13,secondary)); gap(body) }
            else if(identityFromText) { body.add(text(s(R.string.marking_hint),13,secondary)); gap(body) }
            if(visualUnavailable) { body.add(text(s(R.string.visual_unavailable),13,secondary)); gap(body) }
        }
        photoUri?.let { uri ->
            val preview=FrameLayout(this).apply { background=shape(soft) }
            body.add(preview,-1,220); gap(body)
            previewJob=lifecycleScope.launch {
                try {
                    val bitmap=scanEngine.preview(uri)
                    val image=ScanFocusView(this@MainActivity,bitmap,scanFocus,false).apply { contentDescription=s(R.string.scan) }
                    preview.addView(image,FrameLayout.LayoutParams(-1,-1))
                } catch(e:CancellationException) { throw e } catch(_:Exception) { }
            }
            body.add(button(s(R.string.refine_crop)) { refineCrop(uri) }); gap(body)
            if(multipleScanObjects) { body.add(text(s(R.string.multiple_items),15,accent,true)); gap(body) }
            materialMarking?.let { code -> body.add(text(getString(R.string.material_marking,code),15,accent,true)); gap(body) }
            // A raw model label is a candidate, not a confirmed object or a calibrated accuracy percentage.
            if(objectIdentity==null && WasteRules.classify(labels)!=WasteKind.UNKNOWN) {
                labels.firstOrNull { it.source==LabelSource.OBJECT_MODEL }?.let { candidate ->
                    body.add(text(getString(R.string.possible_object,candidate.text.substringBefore(',')),14,secondary)); gap(body)
                }
            }
            body.add(button(s(R.string.correct_item)) { correctObject() }); gap(body)
        }
        val guide=card(soft)
        guide.add(text(kind.symbol,56).apply { gravity=Gravity.CENTER }); gap(guide,14)
        guide.add(text(s(R.string.general_guide),11,accent,true)); gap(guide,12)
        if(kind==WasteKind.UNKNOWN) {
            guide.add(text(s(if(labels.isEmpty())R.string.scan_none else R.string.uncertain_object_help),15)); gap(guide)
        } else { guide.add(text(s(kind.guide),16)); gap(guide,14) }
        guide.add(text(s(R.string.local_rules),13,secondary)); body.add(guide); gap(body)
        body.add(button(s(R.string.confirm_material)) { chooseMaterial() }); gap(body,12)
        addImpactCard()
        body.add(button(s(R.string.find_dropoff)) { go(2) }); gap(body,12); body.add(button(s(R.string.scan_another)) { choosePhoto() })
    }
    private fun refineCrop(uri:Uri) {
        lifecycleScope.launch {
            try {
                val bitmap=scanEngine.preview(uri)
                val form=col(); pad(form,18)
                form.add(text(s(R.string.crop_help),14)); gap(form)
                val selector=ScanFocusView(this@MainActivity,bitmap,scanFocus,true).apply {
                    contentDescription=s(R.string.crop_accessibility)
                }
                form.add(selector,-1,300)
                AlertDialog.Builder(this@MainActivity).setTitle(R.string.refine_crop).setView(form)
                    .setPositiveButton(R.string.rescan_crop) { _,_ -> identify(uri,selector.focus) }
                    .setNegativeButton(R.string.cancel,null).show()
            } catch(e:CancellationException) { throw e } catch(_:Exception) { toast(R.string.scan_error) }
        }
    }
    private fun addImpactCard() {
        val c=card(soft); c.add(text(s(R.string.impact_title),19,ink,true)); gap(c,10)
        val choices=ImpactMaterial.allowed(kind)
        if(choices.isEmpty())c.add(text(s(R.string.impact_not_available),14,secondary))
        else {
            c.add(text(s(R.string.impact_confirm_material),13,secondary)); gap(c)
            c.add(button(s(R.string.impact_estimate)) {
                choiceDialog(R.string.impact_title,choices.map { s(it.title) }) { carbonDialog(choices[it]) }
            })
        }
        body.add(c); gap(body)
    }
    private fun totalCarbonText():String {
        val kg=prefs.getLong("carbon_kg_millionths",0L)/1_000_000.0
        return getString(R.string.impact_total,java.text.NumberFormat.getNumberInstance(resources.configuration.locales[0]).apply { maximumFractionDigits=2 }.format(kg))
    }
    private fun carbonDialog(material:ImpactMaterial) {
        val form=col(); pad(form,22)
        form.add(text(s(material.title),23,ink,true)); gap(form)
        val weight=EditText(this).apply {
            hint=s(R.string.impact_weight); inputType=android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setTextColor(ink); setHintTextColor(secondary); minimumHeight=dp(54); setSingleLine()
        }
        form.add(weight); gap(form)
        val confirmed=CheckBox(this).apply { text=s(R.string.impact_confirm_material); setTextColor(ink); textSize=13f }
        form.add(confirmed); gap(form)
        val estimate=text("",22,accent,true); form.add(estimate); gap(form)
        form.add(text(s(R.string.impact_method),13,secondary)); gap(form)
        val scrollContent=ScrollView(this).apply { addView(form) }
        val dialog=AlertDialog.Builder(this).setView(scrollContent).create()
        fun calculate():Double? {
            val grams=weight.text.toString().trim().replace(',','.').toDoubleOrNull()
            if(!confirmed.isChecked) { toast(R.string.impact_confirm_material); return null }
            val kg=runCatching { material.estimateKg(grams ?: Double.NaN) }.getOrNull()
            if(kg==null) { weight.error=s(R.string.impact_invalid); return null }
            estimate.text=getString(R.string.impact_result,java.text.NumberFormat.getNumberInstance(resources.configuration.locales[0]).apply { maximumFractionDigits=3 }.format(kg))
            return kg
        }
        form.add(button(s(R.string.impact_calculate)) { calculate() }); gap(form,10)
        val recordedKey="recycled_"+impactRecordId
        val log=button(s(R.string.impact_log)) {
            val kg=calculate()
            if(kg!=null && !prefs.getBoolean(recordedKey,false)) {
                val units=kotlin.math.round(kg*1_000_000).toLong()
                prefs.edit().putBoolean(recordedKey,true).putLong("impact_"+impactRecordId,units).putLong("carbon_kg_millionths",prefs.getLong("carbon_kg_millionths",0L)+units).apply()
                toast(R.string.impact_logged); dialog.dismiss()
                if(tab==4)render()
            }
        }
        log.isEnabled=!prefs.getBoolean(recordedKey,false); log.alpha=if(log.isEnabled)1f else 0.5f
        form.add(log); gap(form,10)
        if(prefs.getBoolean(recordedKey,false)) {
            form.add(button(s(R.string.impact_undo)) {
                val units=prefs.getLong("impact_"+impactRecordId,0L)
                prefs.edit().remove(recordedKey).remove("impact_"+impactRecordId)
                    .putLong("carbon_kg_millionths",(prefs.getLong("carbon_kg_millionths",0L)-units).coerceAtLeast(0L)).apply()
                dialog.dismiss(); toast(R.string.impact_removed); if(tab==4)render()
            }); gap(form,10)
        }
        form.add(button(s(R.string.impact_source)) { openUri(Uri.parse(ImpactMaterial.SOURCE)) }); gap(form,10)
        form.add(button(s(R.string.cancel)) { dialog.dismiss() })
        dialog.show()
    }
    private fun choiceDialog(title:Int,options:List<String>,select:(Int)->Unit) {
        val form=col(); pad(form,20); form.add(text(s(title),23,ink,true)); gap(form,18)
        val container=ScrollView(this).apply { addView(form) }
        val dialog=AlertDialog.Builder(this).setView(container).create()
        options.forEachIndexed { index,label ->
            form.add(button(label) { dialog.dismiss(); select(index) }); gap(form,10)
        }
        form.add(button(s(R.string.cancel)) { dialog.dismiss() })
        dialog.show()
    }
    private fun saveCurrentExample() {
        val features=scanFeatures?.copyOf()
        val selectedKind=kind; val selectedIdentity=objectIdentity
        if(features==null || photoUri==null || multipleScanObjects) { toast(R.string.personal_unavailable); return }
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { PersonalMatches(applicationContext).save(features,selectedKind,selectedIdentity) }
                toast(R.string.personal_saved)
            } catch(cancel:CancellationException) { throw cancel } catch(_:Exception) { toast(R.string.personal_unavailable) }
        }
    }
    private fun correctObject() {
        choiceDialog(R.string.correct_item,listOf(s(R.string.computer_keyboard),s(R.string.vape),s(R.string.manual))) { which ->
                if(which==2)chooseMaterial()
                else {
                    objectIdentity=if(which==0)ObjectIdentity.KEYBOARD else ObjectIdentity.VAPE
                    kind=if(which==0)WasteKind.ELECTRONICS else WasteKind.VAPE
                    objectConfirmed=true; materialMarking=null; identityFromText=false; personalMatched=false; saveCurrentExample(); multipleScanObjects=false; go(7)
                }
            }
    }
    private fun chooseMaterial() {
        val kinds=WasteKind.entries.filter { it!=WasteKind.UNKNOWN }
        choiceDialog(R.string.confirm_material,kinds.map { s(it.title) }) { i -> kind=kinds[i]; objectIdentity=null; objectConfirmed=true; personalMatched=false; saveCurrentExample(); go(7) }
    }

    private fun locations() {
        heading(R.string.map_title,R.string.map_sub)
        val search=EditText(this).apply {
            hint=s(R.string.map_city_hint); setText(mapQuery); setSingleLine(); setTextColor(ink); setHintTextColor(secondary)
            background=shape(surface,16); pad(this,14); minimumHeight=dp(52)
            inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        mapSearch=search; body.add(search); gap(body,10)
        body.add(button(s(R.string.map_city_search)) {
            mapQuery=search.text.toString().trim()
            if(mapQuery.length<2)toast(R.string.map_city_required)
            else {
                (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(search.windowToken,0)
                mapJob?.cancel(); mapStatus.text=s(R.string.location_loading)
                mapJob=lifecycleScope.launch {
                    try {
                        val found=dropOffs.searchCity(mapQuery); area=found; mapZoom=12.0
                        map?.controller?.setZoom(mapZoom); map?.controller?.setCenter(GeoPoint(found.latitude,found.longitude))
                        mapStatus.text=getString(R.string.map_center_label,found.label)
                        showPoints(found.latitude,found.longitude)
                    } catch(cancel:CancellationException) { throw cancel }
                    catch(error:Exception) { mapStatus.text=s(if(error is java.net.UnknownHostException)R.string.map_connection_error else R.string.map_search_error) }
                }
            }
        }); gap(body)
        mapStatus=text(s(R.string.location_loading),13,secondary); body.add(mapStatus); gap(body)
        body.add(button("⌖  "+s(R.string.use_location)) { requestLocation() }); gap(body)
        val view=MapView(this).apply {
            setTileSource(org.osmdroid.tileprovider.tilesource.XYTileSource("Binly OpenStreetMap",0,19,256,".png",arrayOf("https://tile.openstreetmap.org/"),"© OpenStreetMap contributors")); setMultiTouchControls(true)
            controller.setZoom(if(area==null)2.0 else mapZoom)
            controller.setCenter(GeoPoint(area?.latitude ?: 0.0,area?.longitude ?: 0.0))
            setOnTouchListener { v,event ->
                v.parent.requestDisallowInterceptTouchEvent(event.action!=MotionEvent.ACTION_UP && event.action!=MotionEvent.ACTION_CANCEL)
                false
            }
        }
        map=view; body.add(view,-1,300); view.onResume(); gap(body,10)
        body.add(text(s(R.string.map_attribution),11,secondary)); gap(body)
        body.add(button(s(R.string.search_here)) {
            val centre=view.mapCenter
            area=Area(centre.latitude,centre.longitude,"",false,"map"); mapZoom=view.zoomLevelDouble
            mapStatus.text=s(R.string.map_selected_area)
            loadPoints(centre.latitude,centre.longitude)
        }); gap(body,24)
        section(R.string.nearby); mapResults=col(); body.add(mapResults); gap(body,16)
        body.add(text(s(R.string.map_privacy_updated),12,secondary))
        val known=area
        if(known!=null) {
            mapStatus.text=when(known.source) { "ip"->getString(R.string.ip_location,known.label); "search"->getString(R.string.map_center_label,known.label); "map"->s(R.string.map_selected_area); else->s(R.string.device_location) }
            loadPoints(known.latitude,known.longitude)
        } else locate(hasLocationPermission())
    }
    private fun hasLocationPermission()=ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED
    private fun requestLocation() {
        when {
            hasLocationPermission()->locate(true)
            shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION)->
                AlertDialog.Builder(this).setMessage(R.string.location_rationale)
                    .setPositiveButton(R.string.continue_label) { _,_ -> locationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION) }
                    .setNegativeButton(R.string.cancel,null).show()
            prefs.getBoolean("locationAsked",false)->
                AlertDialog.Builder(this).setMessage(R.string.permission_settings)
                    .setPositiveButton(R.string.open_settings) { _,_ -> openUri(Uri.parse("package:"+packageName),Settings.ACTION_APPLICATION_DETAILS_SETTINGS) }
                    .setNegativeButton(R.string.cancel,null).show()
            else -> { prefs.edit().putBoolean("locationAsked",true).apply(); locationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION) }
        }
    }
    private fun locate(useDevice:Boolean) {
        mapJob?.cancel(); mapNeedsRetry=false
        mapStatus.text=s(R.string.location_loading)
        mapJob=lifecycleScope.launch {
            try {
                val device=if(useDevice && hasLocationPermission())withTimeoutOrNull(10_000) { deviceLocation() } else null
                val found=if(device!=null)Area(device.latitude,device.longitude,"",false) else dropOffs.approximateArea()
                area=found; mapZoom=if(found.approximate)10.0 else 13.0
                map?.controller?.setZoom(mapZoom); map?.controller?.setCenter(GeoPoint(found.latitude,found.longitude))
                mapStatus.text=if(found.approximate)getString(R.string.ip_location,found.label) else s(R.string.device_location)
                showPoints(found.latitude,found.longitude)
            } catch(e:CancellationException) { throw e } catch(error:Exception) { mapNeedsRetry=true; mapStatus.text=s(if(error is java.net.UnknownHostException)R.string.map_connection_error else R.string.location_error) }
        }
    }
    private suspend fun deviceLocation():Location? {
        if(!hasLocationPermission())return null
        val manager=getSystemService(LocationManager::class.java)
        try {
            val provider=manager.getProviders(true).firstOrNull { it==LocationManager.NETWORK_PROVIDER }
                ?: manager.getProviders(true).firstOrNull { it==LocationManager.GPS_PROVIDER } ?: return null
            val cached=manager.getLastKnownLocation(provider)
            if(cached!=null && (android.os.SystemClock.elapsedRealtimeNanos()-cached.elapsedRealtimeNanos) in 0..600_000_000_000L)return cached
            return suspendCancellableCoroutine { continuation ->
                val signal=CancellationSignal()
                continuation.invokeOnCancellation { signal.cancel() }
                try { LocationManagerCompat.getCurrentLocation(manager,provider,signal,ContextCompat.getMainExecutor(this)) { location ->
                    if(continuation.isActive)continuation.resume(location)
                } } catch(_:SecurityException) { if(continuation.isActive)continuation.resume(null) }
            }
        } catch(_:SecurityException) { return null }
    }
    private fun loadPoints(lat:Double,lon:Double) {
        mapJob?.cancel(); mapJob=lifecycleScope.launch { showPoints(lat,lon) }
    }
    private suspend fun showPoints(lat:Double,lon:Double) {
        mapNeedsRetry=false
        mapResults.removeAllViews(); mapResults.add(text(s(R.string.map_loading),14,secondary))
        map?.overlays?.clear(); map?.invalidate()
        try {
            val found=dropOffs.nearby(lat,lon)
            mapResults.removeAllViews(); map?.overlays?.clear()
            if(found.isEmpty()) mapResults.add(text(s(R.string.map_empty),14,secondary))
            found.forEach { place ->
                val name=place.name.ifBlank { s(R.string.recycling_point) }
                map?.let { view ->
                    val marker=Marker(view).apply {
                        position=GeoPoint(place.latitude,place.longitude); title=name
                        snippet=place.materials.joinToString(", ")
                        setAnchor(Marker.ANCHOR_CENTER,Marker.ANCHOR_BOTTOM)
                        setOnMarkerClickListener { _,_ -> placeDialog(place); true }
                    }
                    view.overlays.add(marker)
                }
                val c=card(); c.add(text(name,17,ink,true)); gap(c,8)
                val materials=place.materials.joinToString(", ").ifBlank { s(R.string.not_listed) }
                c.add(text(getString(R.string.accepted,materials),13,secondary))
                if(place.hours.isNotBlank()) { gap(c,8); c.add(text(place.hours,12,secondary)) }
                gap(c,12); c.add(button(s(R.string.directions)) { directions(place) })
                mapResults.add(c); gap(mapResults,12)
            }
            map?.invalidate()
        } catch(e:CancellationException) { throw e } catch(error:Exception) {
            mapNeedsRetry=true
            mapResults.removeAllViews(); mapResults.add(text(s(if(error is java.net.UnknownHostException)R.string.map_connection_error else R.string.map_error),14)); gap(mapResults)
            mapResults.add(button(s(R.string.retry)) { loadPoints(lat,lon) })
        }
    }
    private fun captureMap() {
        if(area==null)return
        map?.let { v ->
            val centre=v.mapCenter
            val previous=area!!
            area=if(kotlin.math.abs(centre.latitude-previous.latitude)>0.005 || kotlin.math.abs(centre.longitude-previous.longitude)>0.005)
                Area(centre.latitude,centre.longitude,"",false,"map") else previous
            mapZoom=v.zoomLevelDouble
        }
    }
    private fun placeDialog(place:DropOff) {
        AlertDialog.Builder(this).setTitle(place.name.ifBlank { s(R.string.recycling_point) })
            .setMessage(getString(R.string.accepted,place.materials.joinToString(", ").ifBlank { s(R.string.not_listed) })+"\n"+place.hours)
            .setPositiveButton(R.string.directions) { _,_->directions(place) }.setNegativeButton(R.string.cancel,null).show()
    }
    private fun directions(place:DropOff) {
        openUri(Uri.parse("https://www.google.com/maps/dir/?api=1&destination="+place.latitude+","+place.longitude))
    }
    private fun openUri(uri:Uri,action:String=Intent.ACTION_VIEW) {
        try { startActivity(Intent(action,uri)) } catch(_:android.content.ActivityNotFoundException) { toast(R.string.map_error) }
    }

    private fun leaderboard() {
        heading(R.string.league,R.string.community)
        body.add(text(s(R.string.demo),11,accent,true)); gap(body)
        listOf("🥇 EcoMaya" to 1850,"🥈 GreenFox" to 1420,"🥉 Leafy" to 1320,"4  RecyclePro" to 980,s(R.string.you) to xp).forEach { pair ->
            val c=card(); val r=row(); r.add(text(pair.first,17,ink,true),0,-2,1f); r.add(text(pair.second.toString()+" XP",14,accent,true)); c.add(r); body.add(c); gap(body,12)
        }
        body.add(text(s(R.string.rank_note),13,secondary))
    }
    private fun profile() {
        heading(R.string.profile_title)
        body.add(MascotView(this),-1,185); gap(body)
        val c=card(); c.add(text(auth?.currentUser?.email ?: s(R.string.eco_hero),24,ink,true).apply { gravity=Gravity.CENTER })
        gap(c,10); c.add(text(getString(R.string.level,xp/100+1,xp),16,accent,true).apply { gravity=Gravity.CENTER })
        gap(c,20); c.add(progress(xp%100,100),-1,8); body.add(c); gap(body)
        body.add(button("⚙  "+s(R.string.settings)) { go(8) }); gap(body,22)
        val impact=card(soft); impact.add(text(s(R.string.impact_title),19,ink,true)); gap(impact,10); impact.add(text(totalCarbonText(),16,accent,true)); gap(impact,10)
        impact.add(text(s(R.string.impact_method),12,secondary)); body.add(impact); gap(body,22)
        val stats=card(soft); stats.add(text(getString(R.string.mastered,prefs.getInt("quizCount",0)),20,ink,true)); gap(stats,10)
        stats.add(text(s(R.string.saved_local),14,secondary)); body.add(stats)
    }
    private fun settings() {
        heading(R.string.settings)
        body.add(button(s(R.string.recognition_sources)) {
            val credits=text(assets.open("recognition_credits.txt").bufferedReader().use { it.readText() },13,ink)
            pad(credits,20)
            credits.autoLinkMask=android.text.util.Linkify.WEB_URLS
            credits.movementMethod=android.text.method.LinkMovementMethod.getInstance()
            val content=ScrollView(this).apply { addView(credits) }
            AlertDialog.Builder(this).setTitle(R.string.recognition_sources).setView(content)
                .setPositiveButton(R.string.done,null).show()
        }); gap(body,24)
        section(R.string.personal_settings)
        body.add(text(getString(R.string.personal_info,PersonalMatches(this).count()),14,secondary)); gap(body)
        body.add(button(s(R.string.personal_clear)) {
            choiceDialog(R.string.personal_clear_confirm,listOf(s(R.string.personal_clear))) {
                lifecycleScope.launch { withContext(Dispatchers.IO) { PersonalMatches(applicationContext).clear() }; render() }
            }
        }); gap(body,24)
        section(R.string.account)
        val c=card()
        val user=auth?.currentUser
        if(user==null) {
            c.add(button(s(R.string.sign_in)) { authDialog(false) }); gap(c,12)
            c.add(button(s(R.string.create_account)) { authDialog(true) })
        } else {
            c.add(text(user.email ?: s(R.string.account),16,ink,true)); gap(c)
            c.add(button(s(R.string.sign_out)) { auth?.signOut(); render() })
        }
        body.add(c); gap(body,24); section(R.string.language)
        body.add(button(if(prefs.getString("language","en")=="sk")"Slovenčina" else "English") {
            AlertDialog.Builder(this).setTitle(R.string.language)
                .setSingleChoiceItems(arrayOf("English","Slovenčina"),if(prefs.getString("language","en")=="sk")1 else 0) { dialog,i ->
                    prefs.edit().putString("language",if(i==0)"en" else "sk").apply(); dialog.dismiss(); recreate()
                }.setNegativeButton(R.string.cancel,null).show()
        }); gap(body,24); section(R.string.theme)
        val themeKeys=listOf("light","dark","system")
        val themeLabels=arrayOf(s(R.string.light),s(R.string.dark),s(R.string.system))
        val selected=themeKeys.indexOf(prefs.getString("theme","system")).coerceAtLeast(0)
        body.add(button(themeLabels[selected]) {
            AlertDialog.Builder(this).setTitle(R.string.theme)
                .setSingleChoiceItems(themeLabels,selected) { dialog,i ->
                    prefs.edit().putString("theme",themeKeys[i]).apply(); dialog.dismiss()
                    AppCompatDelegate.setDefaultNightMode(when(i) { 0->AppCompatDelegate.MODE_NIGHT_NO;1->AppCompatDelegate.MODE_NIGHT_YES;else->AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM })
                }.setNegativeButton(R.string.cancel,null).show()
        })
    }
    private fun authDialog(create:Boolean) {
        val gateway=auth
        if(gateway==null) {
            AlertDialog.Builder(this).setTitle(R.string.account).setMessage(R.string.auth_config).setPositiveButton(R.string.done,null).show()
            return
        }
        val form=col(); pad(form,22)
        val email=EditText(this).apply { hint=s(R.string.email); inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS; setSingleLine(); setTextColor(ink) }
        val password=EditText(this).apply { hint=s(R.string.password); inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD; setSingleLine(); setTextColor(ink) }
        form.add(email); gap(form); form.add(password)
        val status=text("",13,secondary); gap(form); form.add(status)
        val dialog=AlertDialog.Builder(this).setTitle(if(create)R.string.create_account else R.string.sign_in)
            .setView(form).setPositiveButton(if(create)R.string.create_account else R.string.sign_in,null)
            .setNegativeButton(R.string.cancel,null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val address=email.text.toString().trim(); val secret=password.text.toString()
                if(!android.util.Patterns.EMAIL_ADDRESS.matcher(address).matches() || secret.length < if(create)8 else 6) {
                    status.text=s(R.string.auth_validation); return@setOnClickListener
                }
                status.text=s(R.string.auth_busy)
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=false
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled=false
                dialog.setCancelable(false)
                lifecycleScope.launch {
                    try {
                        if(create)gateway.createUserWithEmailAndPassword(address,secret).await()
                        else gateway.signInWithEmailAndPassword(address,secret).await()
                        password.text.clear(); dialog.dismiss(); toast(if(create)R.string.account_created else R.string.signed_in)
                        if(tab==8 || tab==4)render()
                    } catch(e:CancellationException) { throw e } catch(_:Exception) {
                        status.text=s(R.string.auth_failed); dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=true
                        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled=true; dialog.setCancelable(true)
                    }
                }
            }
        }; dialog.show()
    }
    private fun toast(id:Int) { Toast.makeText(this,id,Toast.LENGTH_LONG).show() }
}

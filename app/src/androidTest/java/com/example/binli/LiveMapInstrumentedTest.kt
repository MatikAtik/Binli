package com.example.binli

import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/** Live smoke check using a public test city, never the device location or IP geolocation. */
class LiveMapInstrumentedTest {
    @Test fun cityLookupCollectionPointsAndHttpsTilesWork() = runBlocking {
        // Android Doze blocks DNS for a sleeping app. Wake the test device without changing power settings.
        val automation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.injectInputEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.KEYCODE_WAKEUP),true)
        automation.injectInputEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP,android.view.KeyEvent.KEYCODE_WAKEUP),true)
        val repository=DropOffRepository()
        val city=repository.searchCity("Bratislava, Slovakia")
        assertTrue(city.latitude in 47.0..49.0)
        assertTrue(city.longitude in 16.0..18.0)
        val points=repository.nearby(city.latitude,city.longitude)
        assertTrue("Public test city should have mapped recycling points",points.isNotEmpty())
        assertTrue(points.all { it.latitude.isFinite() && it.longitude.isFinite() })
        val bitmap=withContext(Dispatchers.IO) {
            val connection=URL("https://tile.openstreetmap.org/0/0/0.png").openConnection() as HttpURLConnection
            try {
                connection.connectTimeout=10000; connection.readTimeout=15000
                connection.setRequestProperty("User-Agent","Binly/1.0 (Android; com.example.binli)")
                assertEquals(200,connection.responseCode)
                connection.inputStream.use { BitmapFactory.decodeStream(it) }
            } finally { connection.disconnect() }
        }
        assertNotNull(bitmap); assertEquals(256,bitmap!!.width); bitmap.recycle()
    }
}

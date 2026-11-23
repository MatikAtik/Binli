package com.example.binli

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QuizInteractionTest {
    @Test fun answerUsesInlineFeedbackSurvivesRotationAndDoesNotAwardTwice() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val preferences=context.getSharedPreferences("binly",0)
        val before=preferences.all
        val keys=listOf("xp","quizCount","answer_0")
        val initialXp=preferences.getInt("xp",0)
        val expectedAward=if(preferences.getBoolean("answer_0",false))0 else 20
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    field("tab").setInt(activity,6); field("quizIndex").setInt(activity,0); field("quizSelection").set(activity,null)
                    MainActivity::class.java.getDeclaredMethod("render").apply { isAccessible=true }.invoke(activity)
                    val root=activity.findViewById<ViewGroup>(android.R.id.content)
                    val answer=views(root).first { it.contentDescription==activity.getString(R.string.a_battery) && it.isClickable }
                    assertTrue(answer is ViewGroup)
                    answer.performClick()
                    assertTrue(views(activity.findViewById(android.R.id.content)).any { it is TextView && it.text.toString()==activity.getString(R.string.correct) })
                    assertEquals(initialXp+expectedAward,preferences.getInt("xp",0))
                }
                scenario.recreate()
                scenario.onActivity { activity ->
                    val root=activity.findViewById<ViewGroup>(android.R.id.content)
                    assertTrue(views(root).any { it is TextView && it.text.toString()==activity.getString(R.string.correct) })
                    val lockedAnswer=views(root).first { it.contentDescription==activity.getString(R.string.a_battery)+" ✓" }
                    assertFalse(lockedAnswer.isClickable)
                    assertEquals(initialXp+expectedAward,preferences.getInt("xp",0))
                }
            }
        } finally {
            val edit=preferences.edit()
            keys.forEach { key -> when(val value=before[key]) { is Int->edit.putInt(key,value); is Boolean->edit.putBoolean(key,value); null->edit.remove(key) } }
            edit.commit()
        }
    }
    private fun field(name:String)=MainActivity::class.java.getDeclaredField(name).apply { isAccessible=true }
    private fun views(view:View):List<View> = listOf(view)+if(view is ViewGroup)(0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
}

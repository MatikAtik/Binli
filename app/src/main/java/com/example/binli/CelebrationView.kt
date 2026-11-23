package com.example.binli

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.view.ViewGroup
import kotlin.math.sin

class CelebrationView(context:Context):View(context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private var fraction=0f
    private val colors=intArrayOf(0xff8fd642.toInt(),0xffffce53.toInt(),0xff4cbb8b.toInt(),0xff69b9ec.toInt())
    init { importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO; isClickable=false }
    fun play() {
        ValueAnimator.ofFloat(0f,1f).apply {
            duration=1000
            addUpdateListener { fraction=it.animatedValue as Float; invalidate() }
            addListener(object:AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation:Animator) { (parent as? ViewGroup)?.removeView(this@CelebrationView) }
            })
            start()
        }
    }
    override fun onDraw(canvas:Canvas) {
        repeat(36) { n ->
            paint.color=colors[n%colors.size]; paint.alpha=((1f-fraction)*255).toInt()
            val x=width*(n%12+0.5f)/12f+sin(fraction*8+n)*22
            val y=height*.22f+fraction*height*.65f-((n%5)*20)
            canvas.save(); canvas.rotate(fraction*540+n*17f,x,y)
            canvas.drawRoundRect(x,y,x+8*resources.displayMetrics.density,y+4*resources.displayMetrics.density,3f,3f,paint)
            canvas.restore()
        }
    }
}

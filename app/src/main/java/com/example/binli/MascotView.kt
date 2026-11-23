package com.example.binli

import android.content.Context
import android.graphics.*
import android.view.View

class MascotView(context:Context):View(context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val leftLeaf=Path().apply { moveTo(0f,-48f); cubicTo(-72f,-65f,-49f,-99f,-59f,-105f); cubicTo(-2f,-108f,5f,-70f,0f,-48f) }
    private val rightLeaf=Path().apply { moveTo(2f,-50f); cubicTo(-1f,-92f,42f,-101f,47f,-126f); cubicTo(64f,-84f,38f,-50f,2f,-50f) }
    override fun onDraw(c:Canvas) {
        super.onDraw(c)
        val scale=minOf(width/170f,height/220f)
        c.save(); c.translate(width/2f,height/2f+24.5f*scale); c.scale(scale,scale)
        oval(c,-54f,65f,56f,77f,0x22000000)
        paint.color=0xff54aa36.toInt(); c.drawPath(leftLeaf,paint)
        paint.color=0xff8fd642.toInt(); c.drawPath(rightLeaf,paint)
        oval(c,-55f,-54f,55f,59f,0xff50a832.toInt()); oval(c,-47f,-51f,48f,50f,0xffc4e76b.toInt())
        oval(c,-42f,-15f,-6f,23f,Color.WHITE); oval(c,9f,-15f,43f,23f,Color.WHITE)
        oval(c,-30f,-8f,-13f,15f,0xff0f3e2a.toInt()); oval(c,16f,-8f,33f,15f,0xff0f3e2a.toInt())
        oval(c,-27f,-7f,-20f,0f,Color.WHITE); oval(c,19f,-7f,26f,0f,Color.WHITE)
        oval(c,-44f,20f,-27f,30f,0xffedbc5a.toInt()); oval(c,28f,20f,45f,30f,0xffedbc5a.toInt())
        paint.color=0xff0f3e2a.toInt(); c.drawArc(-10f,21f,12f,40f,0f,180f,true,paint)
        oval(c,-37f,51f,-9f,68f,0xff4b9d32.toInt()); oval(c,12f,51f,38f,68f,0xff4b9d32.toInt())
        oval(c,-70f,15f,-46f,37f,0xff76bb3b.toInt()); oval(c,46f,8f,68f,31f,0xff76bb3b.toInt())
        c.restore()
    }
    private fun oval(canvas:Canvas,l:Float,t:Float,r:Float,b:Float,color:Int) { paint.color=color; canvas.drawOval(l,t,r,b,paint) }
}

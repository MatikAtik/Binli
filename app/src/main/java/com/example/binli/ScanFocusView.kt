package com.example.binli

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View

/** Displays the actual analyzed area, or lets the user drag a new area over the upright photo. */
class ScanFocusView(context:Context, private val photo:Bitmap, initial:FocusBox?, private val editable:Boolean):View(context) {
    private val imageBounds=RectF()
    private val selection=RectF()
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val photoPaint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var anchorX=0f
    private var anchorY=0f
    var focus:FocusBox = initial ?: if(editable)FocusBox(0.05f,0.05f,0.95f,0.95f) else FocusBox(0f,0f,1f,1f)
        private set
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int) {
        val scale=minOf(w.toFloat()/photo.width,h.toFloat()/photo.height)
        val iw=photo.width*scale; val ih=photo.height*scale
        imageBounds.set((w-iw)/2f,(h-ih)/2f,(w+iw)/2f,(h+ih)/2f)
    }
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        canvas.drawBitmap(photo,null,imageBounds,photoPaint)
        selection.set(imageBounds.left+focus.left*imageBounds.width(),imageBounds.top+focus.top*imageBounds.height(),
            imageBounds.left+focus.right*imageBounds.width(),imageBounds.top+focus.bottom*imageBounds.height())
        paint.style=Paint.Style.FILL; paint.color=0x77000000
        canvas.drawRect(imageBounds.left,imageBounds.top,imageBounds.right,selection.top,paint)
        canvas.drawRect(imageBounds.left,selection.bottom,imageBounds.right,imageBounds.bottom,paint)
        canvas.drawRect(imageBounds.left,selection.top,selection.left,selection.bottom,paint)
        canvas.drawRect(selection.right,selection.top,imageBounds.right,selection.bottom,paint)
        paint.style=Paint.Style.STROKE; paint.color=0xffa3df76.toInt(); paint.strokeWidth=3*resources.displayMetrics.density
        canvas.drawRect(selection,paint)
    }
    override fun onTouchEvent(event:MotionEvent):Boolean {
        if(!editable || imageBounds.isEmpty)return super.onTouchEvent(event)
        val x=((event.x-imageBounds.left)/imageBounds.width()).coerceIn(0f,1f)
        val y=((event.y-imageBounds.top)/imageBounds.height()).coerceIn(0f,1f)
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { anchorX=x; anchorY=y; parent.requestDisallowInterceptTouchEvent(true); return true }
            MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP -> {
                val next=FocusBox(minOf(anchorX,x),minOf(anchorY,y),maxOf(anchorX,x),maxOf(anchorY,y))
                if(next.valid) { focus=next; invalidate() }
                if(event.actionMasked==MotionEvent.ACTION_UP) { parent.requestDisallowInterceptTouchEvent(false); performClick() }
                return true
            }
            MotionEvent.ACTION_CANCEL -> { parent.requestDisallowInterceptTouchEvent(false); return true }
        }
        return super.onTouchEvent(event)
    }
    override fun performClick():Boolean { super.performClick(); return true }
}

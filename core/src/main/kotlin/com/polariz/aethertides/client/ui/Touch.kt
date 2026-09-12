package com.polariz.aethertides.client.ui

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.InputProcessor

/**
 * Multi-touch state.
 *
 * Mobile HUDs live or die on this: a thumb holding the helm must not swallow the other hand's
 * cast, and a cast must not be stolen by a button the finger happened to pass over on the way
 * down. So every pointer is tracked independently and can be *claimed* by exactly one widget
 * at the moment it goes down; nothing else sees it for the rest of its life.
 *
 * Y is flipped into the usual bottom-left origin on the way in, so HUD code never has to think
 * about screen-space handedness.
 */
class Touch : InputProcessor {

    companion object {
        const val MAX_POINTERS = 6
        const val UNCLAIMED = -1
    }

    class Pointer {
        @JvmField var active = false
        @JvmField var x = 0f
        @JvmField var y = 0f
        @JvmField var startX = 0f
        @JvmField var startY = 0f
        @JvmField var prevX = 0f
        @JvmField var prevY = 0f
        @JvmField var justDown = false
        @JvmField var justUp = false
        /** Widget id that owns this pointer, or UNCLAIMED. */
        @JvmField var owner = UNCLAIMED
        @JvmField var age = 0f
        @JvmField var moved = 0f

        val dx: Float get() = x - prevX
        val dy: Float get() = y - prevY
    }

    val pointers = Array(MAX_POINTERS) { Pointer() }

    /** Set true while a modal is up so gameplay widgets stop responding. */
    var blocked = false

    fun beginFrame(dt: Float) {
        for (p in pointers) {
            if (!p.active) continue
            p.age += dt
        }
    }

    /** Called at the very end of a frame, after all widgets have had their look. */
    fun endFrame() {
        for (p in pointers) {
            p.justDown = false
            if (p.justUp) {
                p.justUp = false
                p.active = false
                p.owner = UNCLAIMED
            }
            p.prevX = p.x
            p.prevY = p.y
        }
    }

    /** Claim a pointer for a widget. Returns false if someone else already owns it. */
    fun claim(p: Pointer, id: Int): Boolean {
        if (p.owner != UNCLAIMED && p.owner != id) return false
        p.owner = id
        return true
    }

    fun ownedBy(id: Int): Pointer? {
        for (p in pointers) if (p.active && p.owner == id) return p
        return null
    }

    /** First unclaimed pointer that went down this frame inside the given rectangle. */
    fun pressedIn(x: Float, y: Float, w: Float, h: Float): Pointer? {
        if (blocked) return null
        for (p in pointers) {
            if (!p.active || !p.justDown || p.owner != UNCLAIMED) continue
            if (p.x >= x && p.x <= x + w && p.y >= y && p.y <= y + h) return p
        }
        return null
    }

    fun anyPressed(): Boolean {
        for (p in pointers) if (p.active && p.justDown) return true
        return false
    }

    // --- InputProcessor ----------------------------------------------------

    private fun flipY(screenY: Int): Float = Gdx.graphics.height - screenY.toFloat()

    override fun touchDown(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
        if (pointer >= MAX_POINTERS) return false
        val p = pointers[pointer]
        p.active = true
        p.justDown = true
        p.justUp = false
        p.owner = UNCLAIMED
        p.age = 0f
        p.moved = 0f
        p.x = screenX.toFloat(); p.y = flipY(screenY)
        p.startX = p.x; p.startY = p.y
        p.prevX = p.x; p.prevY = p.y
        return true
    }

    override fun touchDragged(screenX: Int, screenY: Int, pointer: Int): Boolean {
        if (pointer >= MAX_POINTERS) return false
        val p = pointers[pointer]
        if (!p.active) return false
        val nx = screenX.toFloat()
        val ny = flipY(screenY)
        p.moved += kotlin.math.abs(nx - p.x) + kotlin.math.abs(ny - p.y)
        p.x = nx
        p.y = ny
        return true
    }

    override fun touchUp(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
        if (pointer >= MAX_POINTERS) return false
        val p = pointers[pointer]
        if (!p.active) return false
        p.x = screenX.toFloat(); p.y = flipY(screenY)
        p.justUp = true
        return true
    }

    override fun touchCancelled(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean =
        touchUp(screenX, screenY, pointer, button)

    override fun mouseMoved(screenX: Int, screenY: Int): Boolean = false
    override fun scrolled(amountX: Float, amountY: Float): Boolean = false
    override fun keyDown(keycode: Int): Boolean = false
    override fun keyUp(keycode: Int): Boolean = false
    override fun keyTyped(character: Char): Boolean = false
}

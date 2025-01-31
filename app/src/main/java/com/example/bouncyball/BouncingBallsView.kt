package com.example.bouncyball

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

class BouncingBallsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class Ball(
        var x: Float,
        var y: Float,
        var radius: Float,
        var vx: Float,
        var vy: Float,
        val color: Int,
        var isSleeping: Boolean = false,
        var framesBelowSpeedThreshold: Int = 0
    )


    // Paint for drawing
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // Configuration
    private val numberOfBalls = 1
    private val balls = mutableListOf<Ball>()

    // View dimensions
    private var viewWidth = 0
    private var viewHeight = 0

    // Gravity updated from MainActivity
    private var gravityX = 0f
    private var gravityY = 0f

    // Simulation timing
    private val frameRate = 120
    private val frameDelay = (1000L / frameRate)
    private val dt = 1f / frameRate

    // Increase friction so they slow down faster
    private val frictionFactor = 0.99f

    // Slightly inelastic collisions so that they lose energy over time
    // (1.0 = perfectly elastic, 0.0 = perfectly inelastic)
    private val restitution = 0.99f

    // Vibrator to produce custom haptic feedback
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        vm.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    init {
        // Create some random balls
        for (i in 0 until numberOfBalls) {
            val radius = Random.nextInt(70, 100).toFloat()
            val x = Random.nextInt(radius.toInt(), 500).toFloat()
            val y = Random.nextInt(radius.toInt(), 800).toFloat()
            val vx = Random.nextInt(-10, 11).toFloat()
            val vy = Random.nextInt(-10, 11).toFloat()
            val color = Color.rgb(
                Random.nextInt(256),
                Random.nextInt(256),
                Random.nextInt(256)
            )
            balls.add(Ball(x, y, radius, vx, vy, color))
        }
    }

    /**
     * Call from MainActivity to update gravity vector
     */
    fun setGravity(gx: Float, gy: Float) {
        gravityX = gx
        gravityY = gy
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        viewWidth = w
        viewHeight = h
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Draw all balls
        for (ball in balls) {
            paint.color = ball.color
            canvas.drawCircle(ball.x, ball.y, ball.radius, paint)
        }

        // Schedule the next update
        postDelayed({ updatePhysics() }, frameDelay)
    }

    private fun updatePhysics() {
        // 1. Apply gravity
        for (ball in balls) {
            ball.vx += gravityX * dt
            ball.vy += gravityY * dt
        }

        // 2. Apply friction/damping
        for (ball in balls) {
            ball.vx *= frictionFactor
            ball.vy *= frictionFactor
        }

        // 3. Update positions
        for (ball in balls) {
            ball.x += ball.vx
            ball.y += ball.vy
        }

        // 4. Resolve collisions with walls
        checkWallCollisions()

        // 5. Check collisions between balls
        checkBallCollisions()

        // 6. Zero out very small velocities
        killSmallVelocities()

        // 7. Redraw
        invalidate()
    }

    /**
     * If a ball goes out of bounds, bounce off the wall.
     * We measure the velocity change to scale haptic feedback.
     */
    private fun checkWallCollisions() {
        for (ball in balls) {
            var collided = false
            var impact = 0f

            // Left wall
            if (ball.x - ball.radius < 0) {
                val oldVx = ball.vx
                ball.x = ball.radius
                ball.vx = -ball.vx * restitution  // reduce with restitution
                impact = maxOf(impact, abs(ball.vx - oldVx))
                collided = true
            }
            // Right wall
            else if (ball.x + ball.radius > viewWidth) {
                val oldVx = ball.vx
                ball.x = viewWidth - ball.radius
                ball.vx = -ball.vx * restitution
                impact = maxOf(impact, abs(ball.vx - oldVx))
                collided = true
            }

            // Top wall
            if (ball.y - ball.radius < 0) {
                val oldVy = ball.vy
                ball.y = ball.radius
                ball.vy = -ball.vy * restitution
                impact = maxOf(impact, abs(ball.vy - oldVy))
                collided = true
            }
            // Bottom wall
            else if (ball.y + ball.radius > viewHeight) {
                val oldVy = ball.vy
                ball.y = viewHeight - ball.radius
                ball.vy = -ball.vy * restitution
                impact = maxOf(impact, abs(ball.vy - oldVy))
                collided = true
            }

            // Trigger haptic if there was a collision and impact is large
            if (collided && impact > 20f) {
                triggerHapticFeedback(impact)
            }
        }
    }

    /**
     * Check collisions among balls and resolve them with
     * a *partially* elastic collision for equal masses.
     */
    private fun checkBallCollisions() {
        // A small epsilon to prevent constant micro-collisions
        val epsilon = 20f

        for (i in 0 until balls.size) {
            val a = balls[i]
            for (j in i+1 until balls.size) {
                val b = balls[j]

                val dx = b.x - a.x
                val dy = b.y - a.y
                val dist = sqrt(dx*dx + dy*dy)
                val minDist = a.radius + b.radius

                // Only consider real overlap if it's more than epsilon
                if (dist < minDist - epsilon) {
                    // 1. Compute the collision normal
                    val nx = dx / dist
                    val ny = dy / dist

                    // 2. Determine overlap
                    val overlap = (minDist - dist)

                    // 3. Push balls apart (split the overlap)
                    a.x -= 0.5f * overlap * nx
                    a.y -= 0.5f * overlap * ny
                    b.x += 0.5f * overlap * nx
                    b.y += 0.5f * overlap * ny

                    // 4. Compute relative velocity along normal
                    val va = a.vx*nx + a.vy*ny
                    val vb = b.vx*nx + b.vy*ny

                    // 5. Apply 1D inelastic collision in normal direction
                    // Perfectly elastic would be: vaAfter = vb, vbAfter = va
                    val vaAfter = vb * restitution
                    val vbAfter = va * restitution

                    val changeA = vaAfter - va
                    val changeB = vbAfter - vb

                    // Old velocities for measuring impact
                    val oldAx = a.vx
                    val oldAy = a.vy
                    val oldBx = b.vx
                    val oldBy = b.vy

                    // 6. Update velocities
                    a.vx += changeA * nx
                    a.vy += changeA * ny
                    b.vx += changeB * nx
                    b.vy += changeB * ny

                    // 7. Estimate collision impact
                    val dvA = sqrt((a.vx - oldAx)*(a.vx - oldAx) + (a.vy - oldAy)*(a.vy - oldAy))
                    val dvB = sqrt((b.vx - oldBx)*(b.vx - oldBx) + (b.vy - oldBy)*(b.vy - oldBy))
                    val impact = dvA + dvB

                    // Only vibrate if the impact is above a threshold
                    if (impact > 20f) {
                        //triggerHapticFeedback(impact)
                    }
                }
            }
        }
    }

    /**
     * Zero out small velocities so balls don't 'jiggle' indefinitely.
     */
    private fun killSmallVelocities() {
        val threshold = 0.5f // tweak as needed
        for (ball in balls) {
            val speed = sqrt(ball.vx*ball.vx + ball.vy*ball.vy)
            if (speed < threshold) {
                ball.vx = 0f
                ball.vy = 0f
            }
        }
    }

    /**
     * Trigger a vibration whose amplitude or duration scales
     * with the collision "impact" (approx change in velocity).
     */
    private fun triggerHapticFeedback(impact: Float) {
        // You already skip if impact < 25, but you could increase or refine logic.
        vibrator?.let { vib ->
            // We can scale the “impact” to a reasonable range
            val clampedImpact = (impact*impact*impact/100000).coerceIn(1f, 500f)
            val amplitude = (clampedImpact*2).toInt().coerceIn(1, 255)

            // We’ll do a short buzz, e.g., 20 ms
            val durationMs = (255L - (impact*impact*impact/2000).toLong()).coerceIn(0L, 100L)/30 + 1

            // On API 26+, we can set amplitude
            val vibrationEffect = VibrationEffect.createOneShot(durationMs * durationMs, amplitude)
            vib.vibrate(vibrationEffect)
        }
    }
}

package com.scanmap.org

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Sensor fusion engine: rotation-vector heading + adaptive accelerometer
 * step detection → metric dead-reckoning pose (x, y in meters, heading °).
 * Position advances by `stride` meters per detected step while tracking.
 */
class SensorEngine(ctx: Context) : SensorEventListener {

    interface Listener {
        fun onPose(x: Float, y: Float, headingDeg: Float)
        fun onStep()
    }

    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    var x = 0f
    var y = 0f
    var headingDeg = 0f
    var steps = 0L
    var walked = 0f
    var stride = 0.72f
    var northOffset = 0f
    var tracking = false
    var listener: Listener? = null

    val hasRotationVector: Boolean
    val hasMagnetometer: Boolean
    val hasStepDetector: Boolean

    private var haveRotVecData = false
    private var haveMagData = false
    private val rotM = FloatArray(9)
    private val incM = FloatArray(9)
    private val orient = FloatArray(3)
    private val gravVals = FloatArray(3)
    private val magVals = FloatArray(3)

    // adaptive step detector state
    private var gravEst = 9.81f
    private var ampEst = 1.2f
    private var armed = true
    private var lastStepMs = 0L
    private var lastDev = 0f

    // pose emission throttle (~30 Hz)
    private var lastPoseEmit = 0L

    init {
        hasRotationVector = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null
        hasMagnetometer = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) != null
        hasStepDetector = sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) != null
    }

    fun start() {
        val rotVec = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val mag = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        if (rotVec != null) sm.registerListener(this, rotVec, SensorManager.SENSOR_DELAY_GAME)
        if (accel != null) sm.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME)
        if (mag != null) sm.registerListener(this, mag, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        sm.unregisterListener(this)
    }

    fun setPosition(px: Float, py: Float) {
        x = px; y = py
        listener?.onPose(x, y, headingDeg)
    }

    fun resetStats() {
        steps = 0L
        walked = 0f
    }

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                haveRotVecData = true
                SensorManager.getRotationMatrixFromVector(rotM, e.values)
                updateHeading()
            }
            Sensor.TYPE_ACCELEROMETER -> {
                gravVals[0] = e.values[0]; gravVals[1] = e.values[1]; gravVals[2] = e.values[2]
                detectStep(e)
                if (!haveRotVecData && haveMagData) {
                    if (SensorManager.getRotationMatrix(rotM, incM, gravVals, magVals)) updateHeading()
                }
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                haveMagData = true
                magVals[0] = e.values[0]; magVals[1] = e.values[1]; magVals[2] = e.values[2]
                if (!haveRotVecData) {
                    if (SensorManager.getRotationMatrix(rotM, incM, gravVals, magVals)) updateHeading()
                }
            }
        }
    }

    private fun updateHeading() {
        SensorManager.getOrientation(rotM, orient)
        var h = Math.toDegrees(orient[0].toDouble()).toFloat() + northOffset
        h %= 360f
        if (h < 0f) h += 360f
        headingDeg = h
        val now = System.currentTimeMillis()
        if (now - lastPoseEmit > 33) {
            lastPoseEmit = now
            listener?.onPose(x, y, headingDeg)
        }
    }

    private fun detectStep(e: SensorEvent) {
        val now = System.currentTimeMillis()
        val mag = sqrt(
            (e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]).toDouble()
        ).toFloat()
        gravEst += 0.02f * (mag - gravEst)
        val dev = mag - gravEst
        ampEst += 0.05f * (abs(dev) - ampEst)
        val th = (ampEst * 1.4f).coerceIn(0.9f, 3.2f)
        if (armed && dev > th && lastDev <= th && now - lastStepMs > 280) {
            armed = false
            lastStepMs = now
            onStepDetected()
        } else if (!armed && dev < -th * 0.6f) {
            armed = true
        }
        lastDev = dev
    }

    private fun onStepDetected() {
        steps++
        walked += stride
        if (tracking) {
            val rad = Math.toRadians(headingDeg.toDouble())
            x += (stride * sin(rad)).toFloat()
            y += (stride * cos(rad)).toFloat()
            listener?.onStep()
            listener?.onPose(x, y, headingDeg)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

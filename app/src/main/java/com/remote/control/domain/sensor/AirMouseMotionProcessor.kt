package com.remote.control.domain.sensor

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Кроссплатформенный процессор данных гироскопа для аэромыши (Air Mouse / Magic Remote).
 * 
 * Не содержит зависимостей от Android SDK и может использоваться как в Android, так и в KMP / iOS:
 * - Фильтрация низких частот (Low-Pass Filter) против шума датчика
 * - Мёртвая зона (Deadzone) для подавления естественного микротремора руки
 * - Нелинейная баллистика (ускорение при резких движениях, высокая точность при медленных)
 * - Настройка чувствительности пользователя
 */
class AirMouseMotionProcessor(
    var lowPassAlpha: Float = 0.25f,
    var deadzone: Float = 0.025f,
    var baseMultiplier: Float = 18.0f,
    var enableBallistics: Boolean = true
) {
    private var filteredGyroX: Float = 0f
    private var filteredGyroZ: Float = 0f

    data class MotionDelta(
        val deltaX: Float,
        val deltaY: Float,
        val intDeltaX: Int,
        val intDeltaY: Int
    )

    /**
     * Обработка среза данных гироскопа (в радианах в секунду).
     *
     * @param pitchX угловая скорость вокруг оси X (наклон смартфона вверх/вниз)
     * @param yawZ угловая скорость вокруг оси Z (поворот смартфона влево/вправо)
     * @param sensitivity пользовательский коэффициент чувствительности (по умолчанию 1.5f)
     * @return рассчитанные смещения курсора (дробные и целочисленные для HID/WebSocket)
     */
    fun process(pitchX: Float, yawZ: Float, sensitivity: Float = 1.5f): MotionDelta {
        // 1. Фильтр низких частот (EMA) для устранения резких скачков
        filteredGyroZ += lowPassAlpha * (yawZ - filteredGyroZ)
        filteredGyroX += lowPassAlpha * (pitchX - filteredGyroX)

        // 2. Отсечение мёртвой зоны (рука неподвижна)
        val absZ = abs(filteredGyroZ)
        val absX = abs(filteredGyroX)

        val rawDeltaX = if (absZ > deadzone) {
            -filteredGyroZ * baseMultiplier * sensitivity
        } else {
            0f
        }

        val rawDeltaY = if (absX > deadzone) {
            -filteredGyroX * baseMultiplier * sensitivity
        } else {
            0f
        }

        // 3. Динамическая баллистика (нелинейное ускорение курсора)
        val finalDeltaX = if (enableBallistics && rawDeltaX != 0f) applyBallistics(rawDeltaX) else rawDeltaX
        val finalDeltaY = if (enableBallistics && rawDeltaY != 0f) applyBallistics(rawDeltaY) else rawDeltaY

        return MotionDelta(
            deltaX = finalDeltaX,
            deltaY = finalDeltaY,
            intDeltaX = finalDeltaX.roundToInt(),
            intDeltaY = finalDeltaY.roundToInt()
        )
    }

    /**
     * Сброс накопленных значений фильтра (при включении/выключении или паузе аэромыши)
     */
    fun reset() {
        filteredGyroX = 0f
        filteredGyroZ = 0f
    }

    private fun applyBallistics(delta: Float): Float {
        val sign = if (delta >= 0) 1f else -1f
        val magnitude = abs(delta)
        // Плавное ускорение для быстрых взмахов кистью
        val accelerated = if (magnitude > 12f) {
            magnitude.pow(1.18f)
        } else {
            magnitude
        }
        return sign * accelerated
    }
}

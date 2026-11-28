import Foundation
import CoreMotion

/// Модуль управления виртуальной аэромышью (Air Mouse / Magic Remote) на iOS.
///
/// Обрабатывает данные гироскопа CoreMotion с тем же алгоритмом сглаживания и баллистики,
/// что и в кроссплатформенном движке:
/// - Фильтрация шумов (Low-Pass Filter)
/// - Мёртвая зона против естественного микротремора руки
/// - Нелинейное ускорение (баллистика) для точного позиционирования и быстрых взмахов
public final class AirMouseMotionManager: ObservableObject {
    public static let shared = AirMouseMotionManager()

    private let motionManager = CMMotionManager()
    
    // Параметры фильтрации и чувствительности
    public var sensitivity: Float = 1.5
    public var lowPassAlpha: Float = 0.25
    public var deadzone: Float = 0.025
    public var baseMultiplier: Float = 18.0
    public var enableBallistics: BooleanLiteralType = true

    private var filteredGyroX: Float = 0.0
    private var filteredGyroZ: Float = 0.0

    /// Callback при получении смещения курсора (dx, dy)
    public var onMotionDelta: ((Float, Float) -> Void)?

    @Published public private(set) var isRunning: Bool = false

    private init() {}

    public func start() {
        guard motionManager.isDeviceMotionAvailable else {
            print("[AirMouse iOS] Device Motion (гироскоп) недоступен на этом устройстве")
            return
        }

        reset()
        motionManager.deviceMotionUpdateInterval = 1.0 / 60.0 // 60 FPS
        motionManager.startDeviceMotionUpdates(to: .main) { [weak self] motion, error in
            guard let self = self, let motion = motion, error == nil else { return }
            self.processMotion(motion)
        }
        isRunning = true
        print("[AirMouse iOS] Гироскоп запущен (60 Гц, sensitivity=\(sensitivity))")
    }

    public func stop() {
        motionManager.stopDeviceMotionUpdates()
        isRunning = false
        reset()
        print("[AirMouse iOS] Гироскоп остановлен")
    }

    public func reset() {
        filteredGyroX = 0.0
        filteredGyroZ = 0.0
    }

    private func processMotion(_ motion: CMDeviceMotion) {
        // rotationRate: x - тангаж (pitch, наклон вверх/вниз), z - рыскание (yaw, поворот влево/вправо)
        let rawPitchX = Float(motion.rotationRate.x)
        let rawYawZ = Float(motion.rotationRate.z)

        // 1. Фильтр низких частот (EMA)
        filteredGyroZ += lowPassAlpha * (rawYawZ - filteredGyroZ)
        filteredGyroX += lowPassAlpha * (rawPitchX - filteredGyroX)

        // 2. Отсечение мёртвой зоны
        let absZ = abs(filteredGyroZ)
        let absX = abs(filteredGyroX)

        var deltaX: Float = 0.0
        var deltaY: Float = 0.0

        if absZ > deadzone {
            deltaX = -filteredGyroZ * baseMultiplier * sensitivity
        }

        if absX > deadzone {
            deltaY = -filteredGyroX * baseMultiplier * sensitivity
        }

        guard deltaX != 0.0 || deltaY != 0.0 else { return }

        // 3. Динамическая баллистика
        if enableBallistics {
            deltaX = applyBallistics(deltaX)
            deltaY = applyBallistics(deltaY)
        }

        onMotionDelta?(deltaX, deltaY)
    }

    private func applyBallistics(_ delta: Float) -> Float {
        let sign: Float = delta >= 0 ? 1.0 : -1.0
        let magnitude = abs(delta)
        if magnitude > 12.0 {
            return sign * pow(magnitude, 1.18)
        }
        return sign * magnitude
    }
}

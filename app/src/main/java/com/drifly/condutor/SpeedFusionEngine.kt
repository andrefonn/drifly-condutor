package com.drifly.condutor

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Motor puro de fusão GNSS + IMU para o velocímetro do DriFly (sem dependência
 * de Android, 100% testável na JVM).
 *
 * Inspirado na arquitetura de velocidade local usada por apps de navegação:
 * 1. **Arbitragem de fonte:** prioriza velocidade Doppler do chip (`gps`) sobre
 *    a velocidade suavizada/atrasada do Fused Location Provider (`fused`), com
 *    fallback automático quando o sinal GNSS direto cai.
 * 2. **Filtro de Kalman 2 estados `[v, a]`:** rejeita picos isolados de
 *    multipath (gate de Mahalanobis), estima aceleração longitudinal e
 *    compensa a latência real do fix usando `elapsedRealtimeMs` monotônico.
 * 3. **Fusão inercial autocalibrada (IMU sem magnetômetro):** recebe aceleração
 *    linear nivelada no plano horizontal (`GAME_ROTATION_VECTOR` +
 *    `LINEAR_ACCELERATION`), aprende automaticamente o ângulo de montagem no
 *    suporte a partir das primeiras acelerações/frenagens em reta e projeta a
 *    velocidade a 10 Hz entre os fixes de 1 Hz do GPS — eliminando o atraso
 *    típico de ~8–12 km/h em arrancadas e frenagens.
 * 4. **Falha segura:** se o celular for pego na mão (rotação rápida) ou se a
 *    IMU divergir do GNSS, zera o peso da IMU automaticamente e segue com
 *    Kalman GNSS puro. Sem sinal por mais de [staleAfterMs], volta para `--`.
 */
class SpeedFusionEngine(
    private val staleAfterMs: Long = SpeedReadoutEngine.STALE_AFTER_MS
) {

    data class Diagnostics(
        val activeProvider: String?,
        val rawSpeedKmh: Float,
        val fusedSpeedKmh: Float,
        val latencyMs: Long,
        val speedAccuracyMps: Float?,
        val imuConfidence: Float
    )

    private data class ImuSample(
        val elapsedMs: Long,
        val hx: Float,
        val hy: Float
    )

    // Estado do filtro de Kalman ancorado na época do último fix GNSS aceito.
    private var initialized = false
    private var vMps = 0f
    private var aMps2 = 0f
    private var p00 = 4f
    private var p01 = 0f
    private var p11 = 4f
    private var stateElapsedMs = 0L

    // Controle de fonte (gps vs fused) e último fix aceito.
    private var lastAcceptedFix: GpsFix? = null
    private var lastAcceptedArrivalMs = 0L
    private var lastGpsProviderElapsedMs = Long.MIN_VALUE
    private var prevCalibrationFix: GpsFix? = null
    private var consecutiveOutliers = 0
    private var lastDisplayedKmh: Int? = null

    // Estado da IMU horizontal (sem magnetômetro, autocalibrada via GNSS).
    private val imuBuffer = ArrayDeque<ImuSample>(128)
    private var hasYawOffset = false
    private var yawCosSum = 0f
    private var yawSinSum = 0f
    private var imuConfidence = 0f
    private var disturbedUntilMs = Long.MIN_VALUE
    private var recentAccelMagEma = 0f

    fun reset() {
        initialized = false
        vMps = 0f
        aMps2 = 0f
        p00 = 4f
        p01 = 0f
        p11 = 4f
        stateElapsedMs = 0L
        lastAcceptedFix = null
        lastAcceptedArrivalMs = 0L
        lastGpsProviderElapsedMs = Long.MIN_VALUE
        prevCalibrationFix = null
        consecutiveOutliers = 0
        lastDisplayedKmh = null
        imuBuffer.clear()
        hasYawOffset = false
        yawCosSum = 0f
        yawSinSum = 0f
        imuConfidence = 0f
        disturbedUntilMs = Long.MIN_VALUE
        recentAccelMagEma = 0f
    }

    /**
     * Recebe uma amostra de aceleração linear já projetada no plano horizontal
     * do `GAME_ROTATION_VECTOR` (`hx`, `hy` em m/s²), junto com a norma da
     * velocidade angular do giroscópio (`gyroNormRadS`, em rad/s).
     */
    fun onImuSample(
        hx: Float,
        hy: Float,
        gyroNormRadS: Float,
        nowElapsedMs: Long
    ) {
        if (!hx.isFinite() || !hy.isFinite()) return

        val mag = hypot(hx.toDouble(), hy.toDouble()).toFloat()
        recentAccelMagEma = if (recentAccelMagEma == 0f) {
            mag
        } else {
            recentAccelMagEma * (1f - ACCEL_EMA_ALPHA) + mag * ACCEL_EMA_ALPHA
        }

        // Celular manuseado na mão ou solavanco extremo: desliga a IMU por 3 s.
        val gyroUnsafe = gyroNormRadS.isFinite() && gyroNormRadS > HAND_GYRO_RAD_S
        if (gyroUnsafe || mag > EXTREME_ACCEL_MPS2) {
            disturbedUntilMs = nowElapsedMs + DISTURBANCE_COOLDOWN_MS
            imuConfidence = 0f
            imuBuffer.clear()
            return
        }

        if (nowElapsedMs < disturbedUntilMs) return

        // Evita timestamps regressivos no buffer.
        if (imuBuffer.isNotEmpty() && nowElapsedMs <= imuBuffer.last().elapsedMs) return

        imuBuffer.addLast(ImuSample(nowElapsedMs, hx, hy))
        val cutoff = nowElapsedMs - IMU_BUFFER_WINDOW_MS
        while (imuBuffer.isNotEmpty() && imuBuffer.first().elapsedMs < cutoff) {
            imuBuffer.removeFirst()
        }
    }

    /**
     * Incorpora um fix de localização vindo de `gps` (LocationManager) ou `fused` (FLP).
     * Retorna `true` se o fix foi aceito pelo filtro.
     */
    fun onLocationFix(fix: GpsFix, nowElapsedMs: Long): Boolean {
        val fixElapsed = fix.elapsedRealtimeMs
        val isDirectGps = isGpsProvider(fix.provider)

        // Ignora fixes claramente antigos em relação ao último aceito.
        if (initialized && fixElapsed < stateElapsedMs - MAX_BACKWARD_SKEW_MS) {
            return false
        }

        // Enquanto o GNSS direto (Doppler) estiver entregando velocidade fresca,
        // não deixa o Fused Location Provider (atrasado/suavizado) contaminar o filtro.
        if (!isDirectGps &&
            lastGpsProviderElapsedMs != Long.MIN_VALUE &&
            nowElapsedMs - lastGpsProviderElapsedMs <= GPS_PRIORITY_WINDOW_MS
        ) {
            return false
        }

        if (!fix.hasSpeed) {
            // Não sobrescreve um estado ativo com fix sem velocidade; apenas registra se ainda buscando.
            if (lastAcceptedFix == null) {
                lastAcceptedFix = fix
                lastAcceptedArrivalMs = nowElapsedMs
            }
            return false
        }

        val z = fix.speedMps
        if (!z.isFinite() || z < 0f) return false
        if ((z * SpeedReadoutEngine.MPS_TO_KMH).roundToInt() > SpeedReadoutEngine.MAX_PLAUSIBLE_KMH) {
            return false
        }

        if (isDirectGps) {
            lastGpsProviderElapsedMs = nowElapsedMs
        }

        val sigma = measurementSigmaMps(fix, isDirectGps)
        var rVar = sigma * sigma

        val prevCal = prevCalibrationFix
        if (!initialized || fixElapsed - stateElapsedMs > staleAfterMs) {
            // Inicialização ou retomada após perda de sinal.
            initialized = true
            vMps = z
            aMps2 = 0f
            p00 = rVar
            p01 = 0f
            p11 = INITIAL_ACCEL_VAR
            stateElapsedMs = fixElapsed
            consecutiveOutliers = 0
        } else {
            val dt = ((fixElapsed - stateElapsedMs) / 1000f).coerceAtLeast(0f)
            if (dt > 0f) {
                predictKalmanInPlace(dt)
            }

            // Calibração/validação da IMU usando o intervalo real entre dois fixes GNSS.
            if (prevCal != null) {
                updateImuAlignment(prevCal, fix, nowElapsedMs)
            }

            // Parada real confirmada com precisão boa: zera de imediato sem "escorregar".
            if (z <= HARD_ZERO_MPS && sigma <= 1.1f) {
                vMps = 0f
                aMps2 = 0f
                p00 = rVar
                p01 = 0f
                p11 = 0.5f
                consecutiveOutliers = 0
            } else {
                val innovation = z - vMps
                val s = p00 + rVar
                val gate = maxOf(OUTLIER_MIN_JUMP_MPS, OUTLIER_SIGMA_MULT * sqrt(s))
                val poorQuality = sigma > 1.2f || fix.horizontalAccuracyM > 25f

                if (abs(innovation) > gate && poorQuality && consecutiveOutliers == 0) {
                    // Pico isolado de baixa precisão (multipath): atenua forte sem travar.
                    consecutiveOutliers = 1
                    rVar *= OUTLIER_VARIANCE_INFLATE
                } else {
                    consecutiveOutliers = 0
                }

                val sEff = p00 + rVar
                val kv = p00 / sEff
                val ka = p01 / sEff

                vMps = (vMps + kv * innovation).coerceAtLeast(0f)
                aMps2 = (aMps2 + ka * innovation).coerceIn(MIN_CAR_ACCEL_MPS2, MAX_CAR_ACCEL_MPS2)

                val np00 = ((1f - kv) * p00).coerceAtLeast(MIN_V_VAR)
                val np01 = (1f - kv) * p01
                val np11 = (p11 - ka * p01).coerceAtLeast(MIN_A_VAR)
                p00 = np00
                p01 = np01
                p11 = np11
            }

            stateElapsedMs = fixElapsed
        }

        lastAcceptedFix = fix
        lastAcceptedArrivalMs = nowElapsedMs
        prevCalibrationFix = fix
        return true
    }

    /**
     * Avalia o estado pronto para exibição em `nowElapsedMs` (chamado a ~10 Hz).
     * Projeta o estado do filtro da época do último fix até o instante atual
     * usando IMU autocalibrada (quando confiável) + modelo de aceleração amortecida.
     */
    fun evaluate(
        nowElapsedMs: Long,
        permissionGranted: Boolean
    ): GpsSpeedState {
        if (!permissionGranted) {
            lastDisplayedKmh = null
            return GpsSpeedState(GpsSpeedStatus.PERMISSION_DENIED, null)
        }

        val fix = lastAcceptedFix
        if (fix == null || !initialized) {
            lastDisplayedKmh = null
            return GpsSpeedState(GpsSpeedStatus.SEARCHING, null)
        }

        // Usa a idade real desde a época do fix (e também limita atraso extremo de chegada).
        val epochAgeMs = (nowElapsedMs - fix.elapsedRealtimeMs).coerceAtLeast(0L)
        val arrivalAgeMs = (nowElapsedMs - lastAcceptedArrivalMs).coerceAtLeast(0L)
        val effectiveAgeMs = minOf(epochAgeMs, arrivalAgeMs + MAX_PIPELINE_LATENCY_MS)

        if (effectiveAgeMs > staleAfterMs) {
            lastDisplayedKmh = null
            return GpsSpeedState(GpsSpeedStatus.STALE, null, ageMs = effectiveAgeMs)
        }

        val projectedMps = projectSpeedMps(nowElapsedMs)
        val kmhFloat = projectedMps * SpeedReadoutEngine.MPS_TO_KMH

        val snappedZero = projectedMps < ZUPT_SPEED_MPS &&
            (fix.speedMps < ZUPT_GPS_MPS || (imuBuffer.isNotEmpty() && recentAccelMagEma < ZUPT_ACCEL_MPS2))

        val kmh = if (snappedZero) {
            0
        } else {
            applyDisplayHysteresis(kmhFloat)
        }.coerceIn(0, SpeedReadoutEngine.MAX_PLAUSIBLE_KMH)

        lastDisplayedKmh = kmh

        val accuracy = fix.speedAccuracyMps
        val speedAccuracyPoor = accuracy != null && accuracy.isFinite() &&
            accuracy > SpeedReadoutEngine.LOW_SPEED_ACCURACY_MPS
        val horizontalPoor = fix.horizontalAccuracyM >= 0f &&
            fix.horizontalAccuracyM > SpeedReadoutEngine.LOW_HORIZONTAL_ACCURACY_M

        return GpsSpeedState(
            status = GpsSpeedStatus.ACTIVE,
            kmh = kmh,
            lowConfidence = speedAccuracyPoor || horizontalPoor,
            ageMs = effectiveAgeMs
        )
    }

    /** Retrato compacto para diagnóstico em campo (log com throttle). */
    fun diagnostics(nowElapsedMs: Long): Diagnostics? {
        val fix = lastAcceptedFix ?: return null
        if (!initialized) return null
        val latencyMs = (lastAcceptedArrivalMs - fix.elapsedRealtimeMs).coerceAtLeast(0L)
        return Diagnostics(
            activeProvider = fix.provider,
            rawSpeedKmh = fix.speedMps * SpeedReadoutEngine.MPS_TO_KMH,
            fusedSpeedKmh = projectSpeedMps(nowElapsedMs) * SpeedReadoutEngine.MPS_TO_KMH,
            latencyMs = latencyMs,
            speedAccuracyMps = fix.speedAccuracyMps,
            imuConfidence = imuConfidence
        )
    }

    /** Confiança atual do alinhamento da IMU (0..0.85), útil para testes e diagnóstico. */
    fun currentImuConfidence(): Float = imuConfidence

    // -----------------------------------------------------------------------

    private fun predictKalmanInPlace(dt: Float) {
        val phi = exp((-dt / ACCEL_DECAY_TAU_S).toDouble()).toFloat()
        vMps = (vMps + aMps2 * dt).coerceAtLeast(0f)
        aMps2 *= phi

        val np00 = p00 + 2f * dt * p01 + dt * dt * p11 + PROCESS_Q_V * dt
        val np01 = phi * (p01 + dt * p11)
        val np11 = phi * phi * p11 + PROCESS_Q_A * dt
        p00 = np00.coerceAtLeast(MIN_V_VAR)
        p01 = np01
        p11 = np11.coerceAtLeast(MIN_A_VAR)
    }

    private fun projectSpeedMps(nowElapsedMs: Long): Float {
        val dtForward = ((nowElapsedMs - stateElapsedMs) / 1000f)
            .coerceIn(0f, MAX_FORWARD_PREDICT_S)
        if (dtForward <= 0f) return vMps

        // Integral analítica de a * exp(-t / tau_proj) de 0 até dtForward:
        // compensa a latência do GPS e evita overshoot quando o motorista tira o pé.
        val decay = 1f - exp((-dtForward / PROJ_DECAY_TAU_S).toDouble()).toFloat()
        val dvKalman = aMps2 * PROJ_DECAY_TAU_S * decay

        val fix = lastAcceptedFix
        val canUseImu = imuConfidence >= IMU_MIN_CONFIDENCE &&
            hasYawOffset &&
            nowElapsedMs >= disturbedUntilMs &&
            fix != null &&
            fix.hasBearing &&
            vMps >= IMU_MIN_SPEED_MPS

        if (!canUseImu) {
            return (vMps + dvKalman).coerceAtLeast(0f)
        }

        val dvImu = integrateImuForwardDeltaV(
            fromElapsedMs = stateElapsedMs,
            toElapsedMs = nowElapsedMs,
            bearingDeg = fix!!.bearingDeg
        ) ?: return (vMps + dvKalman).coerceAtLeast(0f)

        val w = imuConfidence.coerceIn(0f, IMU_MAX_BLEND)
        val dvBlended = w * dvImu + (1f - w) * dvKalman
        return (vMps + dvBlended).coerceAtLeast(0f)
    }

    /**
     * Integra a aceleração longitudinal da IMU no intervalo `(fromElapsedMs, toElapsedMs]`,
     * projetando `(hx, hy)` sobre o eixo frontal do carro dado pelo rumo GPS + offset aprendido.
     */
    private fun integrateImuForwardDeltaV(
        fromElapsedMs: Long,
        toElapsedMs: Long,
        bearingDeg: Float
    ): Float? {
        if (!hasYawOffset || imuBuffer.isEmpty()) return null

        val psi0 = atan2(yawSinSum.toDouble(), yawCosSum.toDouble())
        val alpha = Math.toRadians(bearingDeg.toDouble()) + psi0
        val fx = sin(alpha).toFloat()
        val fy = cos(alpha).toFloat()

        var dv = 0f
        var prevTime = fromElapsedMs
        var count = 0

        for (sample in imuBuffer) {
            if (sample.elapsedMs <= fromElapsedMs) continue
            if (sample.elapsedMs > toElapsedMs) break
            val dt = ((sample.elapsedMs - prevTime) / 1000f).coerceIn(0f, 0.2f)
            if (dt > 0f) {
                val aFwd = (sample.hx * fx + sample.hy * fy)
                    .coerceIn(MIN_CAR_ACCEL_MPS2, MAX_CAR_ACCEL_MPS2)
                dv += aFwd * dt
                count++
            }
            prevTime = sample.elapsedMs
        }

        if (count == 0) return null
        return dv.coerceIn(-MAX_IMU_DV_MPS, MAX_IMU_DV_MPS)
    }

    /**
     * Aprende e valida continuamente o ângulo `psi0` entre o plano horizontal
     * do `GAME_ROTATION_VECTOR` e o norte geográfico usando trechos em linha reta
     * com aceleração ou frenagem real observada no GNSS.
     */
    private fun updateImuAlignment(prevFix: GpsFix, currFix: GpsFix, nowElapsedMs: Long) {
        if (nowElapsedMs < disturbedUntilMs || imuBuffer.isEmpty()) return

        val dt = (currFix.elapsedRealtimeMs - prevFix.elapsedRealtimeMs) / 1000f
        if (dt < 0.45f || dt > 1.8f) return
        if (!prevFix.hasBearing || !currFix.hasBearing) return

        val minSpeed = minOf(prevFix.speedMps, currFix.speedMps)
        if (minSpeed < CALIBRATION_MIN_SPEED_MPS) return

        // Validação contínua: se a IMU já está calibrada mas divergiu muito do GNSS,
        // reduz a confiança imediatamente (proteção contra deriva ou suporte mexido).
        val dvGnss = currFix.speedMps - prevFix.speedMps
        if (hasYawOffset && imuConfidence >= IMU_MIN_CONFIDENCE) {
            val dvImu = integrateImuForwardDeltaV(
                fromElapsedMs = prevFix.elapsedRealtimeMs,
                toElapsedMs = currFix.elapsedRealtimeMs,
                bearingDeg = prevFix.bearingDeg
            )
            if (dvImu != null && abs(dvImu - dvGnss) > IMU_DISAGREE_MPS) {
                imuConfidence *= 0.35f
            }
        }

        // Só calibra direção em linha reta (evita confundir aceleração lateral em curva).
        val headingChangeDeg = abs(angleDiffDeg(prevFix.bearingDeg, currFix.bearingDeg))
        if (headingChangeDeg > MAX_STRAIGHT_HEADING_DELTA_DEG) return

        val aGnss = dvGnss / dt
        if (abs(aGnss) < CALIBRATION_MIN_ACCEL_MPS2) return

        // Média do vetor horizontal da IMU na mesma janela [prevFix, currFix].
        var sumHx = 0f
        var sumHy = 0f
        var count = 0
        for (sample in imuBuffer) {
            if (sample.elapsedMs in prevFix.elapsedRealtimeMs..currFix.elapsedRealtimeMs) {
                sumHx += sample.hx
                sumHy += sample.hy
                count++
            }
        }
        if (count < 3) return

        val avgHx = sumHx / count
        val avgHy = sumHy / count
        val hNorm = hypot(avgHx.toDouble(), avgHy.toDouble()).toFloat()
        if (hNorm < CALIBRATION_MIN_IMU_MPS2) return

        val ratio = hNorm / abs(aGnss)
        if (ratio < 0.45f || ratio > 2.2f) return

        // Direção frontal observada no plano horizontal da IMU.
        val sign = if (aGnss >= 0f) 1f else -1f
        val ux = (sign * avgHx) / hNorm
        val uy = (sign * avgHy) / hNorm
        val alphaObs = atan2(ux.toDouble(), uy.toDouble())
        val meanBearingRad = circularMeanRad(
            Math.toRadians(prevFix.bearingDeg.toDouble()),
            Math.toRadians(currFix.bearingDeg.toDouble())
        )
        val psiObs = wrapPi(alphaObs - meanBearingRad)
        val obsCos = cos(psiObs).toFloat()
        val obsSin = sin(psiObs).toFloat()

        if (!hasYawOffset) {
            yawCosSum = obsCos
            yawSinSum = obsSin
            hasYawOffset = true
            imuConfidence = 0.55f
            return
        }

        val currentPsi = atan2(yawSinSum.toDouble(), yawCosSum.toDouble())
        val agreement = cos(wrapPi(psiObs - currentPsi)).toFloat()

        if (agreement >= 0.70f) {
            // Alinhamento consistente: acompanha deriva lenta do giroscópio.
            yawCosSum = yawCosSum * 0.75f + obsCos * 0.25f
            yawSinSum = yawSinSum * 0.75f + obsSin * 0.25f
            imuConfidence = (imuConfidence + 0.15f).coerceAtMost(IMU_MAX_BLEND)
        } else if (agreement < 0.20f) {
            // Celular mudou de ângulo no suporte: reinicia estimativa e exige nova confirmação.
            yawCosSum = obsCos
            yawSinSum = obsSin
            imuConfidence = 0.25f
        }
    }

    private fun applyDisplayHysteresis(kmhFloat: Float): Int {
        val prev = lastDisplayedKmh
        if (prev == null || kmhFloat < 0.5f) {
            return kmhFloat.roundToInt()
        }
        return if (abs(kmhFloat - prev) < DISPLAY_HYSTERESIS_KMH) {
            prev
        } else {
            kmhFloat.roundToInt()
        }
    }

    private fun measurementSigmaMps(fix: GpsFix, isDirectGps: Boolean): Float {
        val reported = fix.speedAccuracyMps?.takeIf { it.isFinite() && it > 0f }
        var sigma = reported ?: if (isDirectGps) DEFAULT_GPS_SIGMA_MPS else DEFAULT_FUSED_SIGMA_MPS
        // Fused costuma vir com suavização extra do Play Services; dá peso maior ao Doppler direto.
        if (!isDirectGps) sigma = maxOf(sigma * 1.25f, 0.9f)
        if (fix.horizontalAccuracyM > 25f) {
            sigma *= (fix.horizontalAccuracyM / 25f).coerceAtMost(2.5f)
        }
        return sigma.coerceIn(MIN_SIGMA_MPS, MAX_SIGMA_MPS)
    }

    private fun isGpsProvider(provider: String?): Boolean =
        provider.equals("gps", ignoreCase = true)

    private fun angleDiffDeg(a: Float, b: Float): Float {
        var diff = (b - a) % 360f
        if (diff > 180f) diff -= 360f
        if (diff < -180f) diff += 360f
        return diff
    }

    private fun circularMeanRad(a: Double, b: Double): Double =
        atan2(sin(a) + sin(b), cos(a) + cos(b))

    private fun wrapPi(rad: Double): Double {
        var x = (rad + PI) % (2.0 * PI)
        if (x < 0.0) x += 2.0 * PI
        return x - PI
    }

    companion object {
        private const val GPS_PRIORITY_WINDOW_MS = 1_800L
        private const val MAX_BACKWARD_SKEW_MS = 50L
        private const val MAX_PIPELINE_LATENCY_MS = 1_500L
        private const val MAX_FORWARD_PREDICT_S = 1.5f

        // Parâmetros do Filtro de Kalman 2 estados [v, a] (modelo Singer para carro).
        private const val ACCEL_DECAY_TAU_S = 3.5f
        private const val PROJ_DECAY_TAU_S = 2.2f
        private const val PROCESS_Q_V = 0.25f
        private const val PROCESS_Q_A = 4.5f
        private const val INITIAL_ACCEL_VAR = 4.0f
        private const val MIN_V_VAR = 0.01f
        private const val MIN_A_VAR = 0.05f
        private const val MIN_CAR_ACCEL_MPS2 = -10.0f
        private const val MAX_CAR_ACCEL_MPS2 = 7.0f

        // Ruído de medição e gate de multipath.
        private const val DEFAULT_GPS_SIGMA_MPS = 0.6f
        private const val DEFAULT_FUSED_SIGMA_MPS = 1.4f
        private const val MIN_SIGMA_MPS = 0.15f
        private const val MAX_SIGMA_MPS = 6.0f
        private const val OUTLIER_MIN_JUMP_MPS = 4.5f
        private const val OUTLIER_SIGMA_MULT = 3.5f
        private const val OUTLIER_VARIANCE_INFLATE = 16f

        // Parâmetros da IMU horizontal autocalibrada.
        private const val IMU_BUFFER_WINDOW_MS = 4_000L
        private const val IMU_MIN_CONFIDENCE = 0.45f
        private const val IMU_MAX_BLEND = 0.85f
        private const val IMU_MIN_SPEED_MPS = 2.0f
        private const val MAX_IMU_DV_MPS = 12.0f
        private const val IMU_DISAGREE_MPS = 2.5f
        private const val CALIBRATION_MIN_SPEED_MPS = 2.8f
        private const val CALIBRATION_MIN_ACCEL_MPS2 = 0.75f
        private const val CALIBRATION_MIN_IMU_MPS2 = 0.55f
        private const val MAX_STRAIGHT_HEADING_DELTA_DEG = 12f
        private const val HAND_GYRO_RAD_S = 1.5f
        private const val EXTREME_ACCEL_MPS2 = 12.0f
        private const val DISTURBANCE_COOLDOWN_MS = 3_000L
        private const val ACCEL_EMA_ALPHA = 0.15f

        // ZUPT (parada no semáforo) e histerese visual.
        private const val HARD_ZERO_MPS = 0.35f
        private const val ZUPT_SPEED_MPS = 0.75f
        private const val ZUPT_GPS_MPS = 0.60f
        private const val ZUPT_ACCEL_MPS2 = 0.30f
        private const val DISPLAY_HYSTERESIS_KMH = 0.68f
    }
}

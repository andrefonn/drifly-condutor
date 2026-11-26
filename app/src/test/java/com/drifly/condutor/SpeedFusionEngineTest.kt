package com.drifly.condutor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * Testes unitários do motor de fusão GNSS + IMU ([SpeedFusionEngine]):
 * - prioridade do GPS_PROVIDER (Doppler) sobre Fused + fallback automático;
 * - rejeição de picos de multipath vs aceitação de frenagem real;
 * - compensação de latência do fix em aceleração e frenagem;
 * - autocalibração do ângulo de montagem da IMU (sem magnetômetro);
 * - proteção contra celular na mão (giroscópio alto) e ZUPT parado no semáforo;
 * - vencimento após 3 s sem sinal (`--`) e histerese visual anti-tremulação.
 */
class SpeedFusionEngineTest {

    private fun fix(
        elapsedMs: Long,
        speedKmh: Float,
        provider: String = "gps",
        speedAccMps: Float? = 0.35f,
        horizAccM: Float = 5f,
        bearingDeg: Float? = 90f,
        hasSpeed: Boolean = true
    ) = GpsFix(
        timeMs = elapsedMs,
        hasSpeed = hasSpeed,
        speedMps = speedKmh / SpeedReadoutEngine.MPS_TO_KMH,
        speedAccuracyMps = speedAccMps,
        horizontalAccuracyM = horizAccM,
        provider = provider,
        elapsedRealtimeMs = elapsedMs,
        hasBearing = bearingDeg != null,
        bearingDeg = bearingDeg ?: 0f
    )

    @Test
    fun `prioriza gps direto sobre fused atrasado e faz fallback quando gps cai`() {
        val engine = SpeedFusionEngine()

        assertTrue(engine.onLocationFix(fix(1_000L, 60f, provider = "gps"), nowElapsedMs = 1_100L))
        // Fused atrasado marcando 50 km/h enquanto GPS direto está fresco deve ser ignorado.
        assertFalse(engine.onLocationFix(fix(1_200L, 50f, provider = "fused"), nowElapsedMs = 1_300L))
        val ativo = engine.evaluate(nowElapsedMs = 1_300L, permissionGranted = true)
        assertEquals(60, ativo.kmh)

        // Depois de 2 s sem GPS direto, o fallback para Fused assume normalmente.
        assertTrue(engine.onLocationFix(fix(3_000L, 52f, provider = "fused"), nowElapsedMs = 3_100L))
        val fallback = engine.evaluate(nowElapsedMs = 3_100L, permissionGranted = true)
        assertEquals(GpsSpeedStatus.ACTIVE, fallback.status)
        assertNotNull(fallback.kmh)
        assertTrue("esperado perto de 52 km/h, veio ${fallback.kmh}", fallback.kmh!! in 51..55)
    }

    @Test
    fun `ignora fix antigo fora de ordem`() {
        val engine = SpeedFusionEngine()
        assertTrue(engine.onLocationFix(fix(2_000L, 50f), nowElapsedMs = 2_100L))
        assertFalse(engine.onLocationFix(fix(1_500L, 80f), nowElapsedMs = 2_150L))
        assertEquals(50, engine.evaluate(nowElapsedMs = 2_150L, permissionGranted = true).kmh)
    }

    @Test
    fun `rejeita pico isolado de multipath mas aceita frenagem real confirmada`() {
        val engine = SpeedFusionEngine()
        engine.onLocationFix(fix(1_000L, 50f), nowElapsedMs = 1_050L)
        engine.onLocationFix(fix(2_000L, 50f), nowElapsedMs = 2_050L)

        // Pico isolado de baixa precisão (multipath de +22 km/h): não pode pular para 72.
        engine.onLocationFix(
            fix(3_000L, 72f, speedAccMps = 3.0f, horizAccM = 40f),
            nowElapsedMs = 3_050L
        )
        val aposPico = engine.evaluate(nowElapsedMs = 3_050L, permissionGranted = true).kmh!!
        assertTrue("pico isolado devia ser contido (<55), veio $aposPico", aposPico < 55)

        // Volta ao normal em 50 km/h.
        engine.onLocationFix(fix(4_000L, 50f), nowElapsedMs = 4_050L)

        // Frenagem forte real confirmada em 2 fixes consecutivos (50 -> 30 -> 15 km/h):
        // acompanha rapidamente.
        engine.onLocationFix(fix(5_000L, 30f, speedAccMps = 0.4f), nowElapsedMs = 5_050L)
        engine.onLocationFix(fix(6_000L, 15f, speedAccMps = 0.4f), nowElapsedMs = 6_050L)
        val aposFrenagem = engine.evaluate(nowElapsedMs = 6_050L, permissionGranted = true).kmh!!
        assertTrue("frenagem real devia cair abaixo de 20 km/h, veio $aposFrenagem", aposFrenagem <= 19)
    }

    @Test
    fun `compensa latencia do gps em aceleracao mesmo sem imu`() {
        val engine = SpeedFusionEngine()
        // Carro acelerando ~3 m/s² (~10.8 km/h por segundo): 20 -> 31 -> 42 km/h.
        engine.onLocationFix(fix(1_000L, 20f), nowElapsedMs = 1_600L)
        engine.onLocationFix(fix(2_000L, 31f), nowElapsedMs = 2_600L)
        engine.onLocationFix(fix(3_000L, 42f), nowElapsedMs = 3_600L)

        // Em t=3800ms (800 ms depois da época do fix de 42 km/h), o carro real já está perto de 50 km/h.
        val exibido = engine.evaluate(nowElapsedMs = 3_800L, permissionGranted = true).kmh!!
        assertTrue("esperado compensacao de latencia (>=47 km/h), veio $exibido", exibido >= 47)
    }

    @Test
    fun `imu autocalibra angulo arbitrario no suporte e responde instantaneamente a frenagem`() {
        val engine = SpeedFusionEngine()

        // Simula celular montado com offset de yaw arbitrário de +137° no GameRotationVector
        // e carro andando para rumo 60° (NE).
        val yawOffsetRad = Math.toRadians(137.0)
        val bearingDeg = 60f
        val forwardAngleRad = Math.toRadians(bearingDeg.toDouble()) + yawOffsetRad
        val fx = sin(forwardAngleRad).toFloat()
        val fy = cos(forwardAngleRad).toFloat()

        fun alimentarImu(deMs: Long, ateMs: Long, accelFwdMps2: Float) {
            var t = deMs
            while (t <= ateMs) {
                engine.onImuSample(
                    hx = accelFwdMps2 * fx,
                    hy = accelFwdMps2 * fy,
                    gyroNormRadS = 0.02f,
                    nowElapsedMs = t
                )
                t += 50L
            }
        }

        // Arrancada de 20 -> 30.8 km/h (+3.0 m/s²) durante 1 s: calibra automaticamente o ângulo!
        engine.onLocationFix(fix(1_000L, 20f, bearingDeg = bearingDeg), nowElapsedMs = 1_100L)
        alimentarImu(1_050L, 2_000L, accelFwdMps2 = 3.0f)
        engine.onLocationFix(fix(2_000L, 30.8f, bearingDeg = bearingDeg), nowElapsedMs = 2_100L)

        assertTrue(
            "IMU devia ter se autocalibrado (conf >= 0.45), veio ${engine.currentImuConfidence()}",
            engine.currentImuConfidence() >= 0.45f
        )

        // Continua acelerando de 30.8 -> 41.6 km/h (+3.0 m/s²) e depois estabiliza em 41.6 km/h
        // (dados fisicamente consistentes entre GPS e IMU).
        alimentarImu(2_050L, 3_000L, accelFwdMps2 = 3.0f)
        engine.onLocationFix(fix(3_000L, 41.6f, bearingDeg = bearingDeg), nowElapsedMs = 3_100L)
        alimentarImu(3_050L, 4_000L, accelFwdMps2 = 0f)
        engine.onLocationFix(fix(4_000L, 41.6f, bearingDeg = bearingDeg), nowElapsedMs = 4_100L)
        alimentarImu(4_050L, 5_000L, accelFwdMps2 = 0f)
        engine.onLocationFix(fix(5_000L, 41.6f, bearingDeg = bearingDeg), nowElapsedMs = 5_100L)

        // Agora o motorista pisa forte no freio (-5 m/s² = -18 km/h por segundo) entre 5000 e 5800 ms,
        // ANTES do próximo fix de GPS chegar!
        alimentarImu(5_050L, 5_800L, accelFwdMps2 = -5.0f)

        val exibidoDuranteFrenagem = engine.evaluate(nowElapsedMs = 5_800L, permissionGranted = true).kmh!!
        // Sem IMU continuaria em 42 km/h; com IMU cai junto com o freio (~30–36 km/h).
        assertTrue(
            "velocimetro com IMU devia cair ja durante a frenagem (<=36), veio $exibidoDuranteFrenagem",
            exibidoDuranteFrenagem <= 36
        )
    }

    @Test
    fun `imu divergindo do gnss reduz confianca automaticamente`() {
        val engine = SpeedFusionEngine()
        engine.onLocationFix(fix(1_000L, 20f, bearingDeg = 0f), nowElapsedMs = 1_100L)
        for (t in 1_050L..2_000L step 50L) {
            engine.onImuSample(hx = 0f, hy = 3.0f, gyroNormRadS = 0.02f, nowElapsedMs = t)
        }
        engine.onLocationFix(fix(2_000L, 30.8f, bearingDeg = 0f), nowElapsedMs = 2_100L)
        val confAntes = engine.currentImuConfidence()
        assertTrue(confAntes >= 0.45f)

        // GPS mostra +18 km/h (+5 m/s) enquanto IMU marca 0: falha segura deve derrubar confiança.
        for (t in 2_050L..3_000L step 50L) {
            engine.onImuSample(hx = 0f, hy = 0f, gyroNormRadS = 0.01f, nowElapsedMs = t)
        }
        engine.onLocationFix(fix(3_000L, 48.8f, bearingDeg = 0f), nowElapsedMs = 3_100L)
        assertTrue(
            "confianca da IMU devia cair apos divergencia, antes=$confAntes depois=${engine.currentImuConfidence()}",
            engine.currentImuConfidence() < 0.45f
        )
    }

    @Test
    fun `pegar celular na mao desativa imu com seguranca sem gerar pico`() {
        val engine = SpeedFusionEngine()
        val bearingDeg = 0f

        // Calibra IMU normalmente.
        engine.onLocationFix(fix(1_000L, 20f, bearingDeg = bearingDeg), nowElapsedMs = 1_100L)
        for (t in 1_050L..2_000L step 50L) {
            engine.onImuSample(hx = 0f, hy = 3.0f, gyroNormRadS = 0.02f, nowElapsedMs = t)
        }
        engine.onLocationFix(fix(2_000L, 30.8f, bearingDeg = bearingDeg), nowElapsedMs = 2_100L)
        assertTrue(engine.currentImuConfidence() >= 0.45f)

        // Usuário pega o celular na mão (rotação rápida > 1.5 rad/s e aceleração absurda).
        engine.onImuSample(hx = 8f, hy = 8f, gyroNormRadS = 3.2f, nowElapsedMs = 2_300L)
        assertEquals(0f, engine.currentImuConfidence(), 0.001f)

        // Velocímetro continua estável via GNSS sem inventar salto.
        val exibido = engine.evaluate(nowElapsedMs = 2_400L, permissionGranted = true).kmh!!
        assertTrue("velocidade nao deve explodir ao pegar celular, veio $exibido", exibido in 28..38)
    }

    @Test
    fun `carro parado no semaforo trava em zero sem oscilar`() {
        val engine = SpeedFusionEngine()
        engine.onLocationFix(fix(1_000L, 1.5f, speedAccMps = 0.5f), nowElapsedMs = 1_100L)
        for (t in 1_050L..1_500L step 50L) {
            engine.onImuSample(hx = 0.04f, hy = 0.05f, gyroNormRadS = 0.01f, nowElapsedMs = t)
        }
        val state = engine.evaluate(nowElapsedMs = 1_500L, permissionGranted = true)
        assertEquals(GpsSpeedStatus.ACTIVE, state.status)
        assertEquals(0, state.kmh)
    }

    @Test
    fun `histerese evita oscilacao entre dois inteiros em velocidade constante`() {
        val engine = SpeedFusionEngine()
        engine.onLocationFix(fix(1_000L, 50.0f), nowElapsedMs = 1_050L)
        assertEquals(50, engine.evaluate(nowElapsedMs = 1_050L, permissionGranted = true).kmh)

        // Pequena flutuação de +0.55 km/h (50.55) não fica piscando para 51.
        engine.onLocationFix(fix(2_000L, 50.55f), nowElapsedMs = 2_050L)
        assertEquals(50, engine.evaluate(nowElapsedMs = 2_050L, permissionGranted = true).kmh)
    }

    @Test
    fun `sem sinal por mais de tres segundos volta para stale e dois tracos`() {
        val engine = SpeedFusionEngine(staleAfterMs = 3_000L)
        engine.onLocationFix(fix(1_000L, 60f), nowElapsedMs = 1_050L)

        val vencido = engine.evaluate(nowElapsedMs = 4_500L, permissionGranted = true)
        assertEquals(GpsSpeedStatus.STALE, vencido.status)
        assertNull(vencido.kmh)
        assertEquals("--", vencido.displayValue)
    }

    @Test
    fun `sem permissao retorna permission denied`() {
        val engine = SpeedFusionEngine()
        engine.onLocationFix(fix(1_000L, 60f), nowElapsedMs = 1_050L)
        val state = engine.evaluate(nowElapsedMs = 1_100L, permissionGranted = false)
        assertEquals(GpsSpeedStatus.PERMISSION_DENIED, state.status)
        assertNull(state.kmh)
    }
}

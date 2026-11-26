package com.drifly.condutor

data class RideOffer(
    val platform: String,
    val price: Double,
    val distanceKm: Double,
    val durationMinutes: Int,
    val ratePerKm: Double,
    val ratePerHour: Double = 0.0,
    val estimatedFuelCost: Double = 0.0,
    val netProfit: Double = 0.0,
    val passengerRating: Float? = null,
    val isLowRating: Boolean = false,
    val destinationAddress: String? = null,
    val isHeadingHome: Boolean = false,
    val homeDistanceDeltaKm: Double = 0.0,
    val pickupAddress: String? = null,
    val isBlacklistBlocked: Boolean = false,
    val blacklistAlert: String? = null,
    // Trechos detalhados (quando o card informa embarque e viagem separadamente).
    // A distância/tempo totais continuam em distanceKm/durationMinutes.
    val pickupDistanceKm: Double? = null,
    val tripDistanceKm: Double? = null,
    val pickupDurationMinutes: Int? = null,
    val tripDurationMinutes: Int? = null,
    // O card tinha mais de um trecho mas não foi possível ler o km de todos: a distância total
    // é DUVIDOSA (o fallback subestima o percurso). Não pode ser aceita automaticamente.
    val isDistanceUncertain: Boolean = false,
    // Modo Casa não conseguiu medir o deslocamento para casa (GPS velho, sem destino ou geocode
    // falhou). Também não pode ser aceita automaticamente.
    val isModoCasaUncertain: Boolean = false
)

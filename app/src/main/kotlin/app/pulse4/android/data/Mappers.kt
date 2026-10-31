package app.pulse4.android.data

import app.pulse4.domain.MetricObservation
import app.pulse4.domain.MetricType
import app.pulse4.domain.Provider
import app.pulse4.domain.Quality

fun MetricObservation.toRow() = ObservationRow(
    id, provider.name, accountId, externalId, type.name, startUtcMs, endUtcMs, value, zoneId, localDate, quality.name, sourceVersion, deleted, origin,
)

fun ObservationRow.toDomain() = MetricObservation(
    id, Provider.valueOf(provider), accountId, externalId, MetricType.valueOf(type), startUtcMs, endUtcMs, value, zoneId, localDate,
    Quality.valueOf(quality), sourceVersion, deleted, origin,
)

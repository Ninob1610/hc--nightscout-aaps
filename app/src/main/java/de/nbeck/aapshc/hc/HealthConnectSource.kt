package de.nbeck.aapshc.hc

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BodyTemperatureMeasurementLocation
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.MenstruationFlowRecord
import androidx.health.connect.client.records.MenstruationPeriodRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import de.nbeck.aapshc.sync.DailySteps
import de.nbeck.aapshc.sync.ExerciseInfo
import de.nbeck.aapshc.sync.Health
import de.nbeck.aapshc.sync.PeriodInfo
import de.nbeck.aapshc.sync.TemperatureInfo
import de.nbeck.aapshc.sync.SleepInfo
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.reflect.KClass

class HealthConnectSource(private val context: Context) {

    enum class Availability { AVAILABLE, UPDATE_REQUIRED, NOT_INSTALLED }

    fun availability(): Availability = when (HealthConnectClient.getSdkStatus(context)) {
        HealthConnectClient.SDK_AVAILABLE -> Availability.AVAILABLE
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> Availability.UPDATE_REQUIRED
        else -> Availability.NOT_INSTALLED
    }

    private val client by lazy { HealthConnectClient.getOrCreate(context) }

    suspend fun grantedPermissions(): Set<String> = client.permissionController.getGrantedPermissions()

    fun isBackgroundReadAvailable(): Boolean =
        client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND) ==
            HealthConnectFeatures.FEATURE_STATUS_AVAILABLE

    suspend fun readExercises(from: Instant, to: Instant): List<ExerciseSessionRecord> =
        readAll(ExerciseSessionRecord::class, from, to)

    /** Ergänzt eine Trainingseinheit um Distanz, Kalorien, Puls und Schritte – soweit freigegeben. */
    suspend fun enrich(session: ExerciseSessionRecord, granted: Set<String>, maxHeartRate: Int): ExerciseInfo {
        val metrics = buildSet<AggregateMetric<*>> {
            if (read(DistanceRecord::class) in granted) add(DistanceRecord.DISTANCE_TOTAL)
            if (read(TotalCaloriesBurnedRecord::class) in granted) add(TotalCaloriesBurnedRecord.ENERGY_TOTAL)
            if (read(HeartRateRecord::class) in granted) {
                add(HeartRateRecord.BPM_AVG)
                add(HeartRateRecord.BPM_MAX)
            }
            if (read(StepsRecord::class) in granted) add(StepsRecord.COUNT_TOTAL)
        }
        val result = if (metrics.isEmpty()) null else client.aggregate(
            AggregateRequest(metrics, TimeRangeFilter.between(session.startTime, session.endTime))
        )
        val zones = if (maxHeartRate > 0 && read(HeartRateRecord::class) in granted) {
            val samples = readAll(HeartRateRecord::class, session.startTime, session.endTime)
                .flatMap { r -> r.samples.map { it.time to it.beatsPerMinute } }
                .filter { (t, _) -> !t.isBefore(session.startTime) && !t.isAfter(session.endTime) }
            if (samples.isEmpty()) null else Health.zoneMinutes(samples, session.endTime, maxHeartRate)
        } else null
        return ExerciseInfo(
            recordId = session.metadata.id,
            start = session.startTime,
            end = session.endTime,
            offset = session.startZoneOffset ?: currentOffset(session.startTime),
            typeName = ExerciseTypes.name(session.exerciseType),
            title = session.title,
            source = sourceName(session.metadata.dataOrigin.packageName),
            distanceKm = result?.get(DistanceRecord.DISTANCE_TOTAL)?.inKilometers,
            kcal = result?.get(TotalCaloriesBurnedRecord.ENERGY_TOTAL)?.inKilocalories,
            avgHeartRate = result?.get(HeartRateRecord.BPM_AVG),
            maxHeartRate = result?.get(HeartRateRecord.BPM_MAX),
            steps = result?.get(StepsRecord.COUNT_TOTAL),
            zoneMinutes = zones,
        )
    }

    suspend fun readSleep(from: Instant, to: Instant): List<SleepInfo> =
        readAll(SleepSessionRecord::class, from, to).map { s ->
            fun sum(type: Int) = s.stages.filter { it.stage == type }
                .fold(Duration.ZERO) { acc, st -> acc + Duration.between(st.startTime, st.endTime) }
            SleepInfo(
                recordId = s.metadata.id,
                start = s.startTime,
                end = s.endTime,
                offset = s.startZoneOffset ?: currentOffset(s.startTime),
                source = sourceName(s.metadata.dataOrigin.packageName),
                sourcePackage = s.metadata.dataOrigin.packageName,
                deep = sum(SleepSessionRecord.STAGE_TYPE_DEEP),
                rem = sum(SleepSessionRecord.STAGE_TYPE_REM),
                awake = sum(SleepSessionRecord.STAGE_TYPE_AWAKE) + sum(SleepSessionRecord.STAGE_TYPE_OUT_OF_BED),
            )
        }

    /** Nur Messungen ab [Health.FEVER_CELSIUS]; normale Werte sind für AAPS uninteressant. */
    suspend fun readFever(from: Instant, to: Instant): List<TemperatureInfo> =
        readAll(BodyTemperatureRecord::class, from, to)
            .filter { it.temperature.inCelsius >= Health.FEVER_CELSIUS }
            .map { r ->
                TemperatureInfo(
                    recordId = r.metadata.id,
                    time = r.time,
                    offset = r.zoneOffset ?: currentOffset(r.time),
                    celsius = r.temperature.inCelsius,
                    location = TEMPERATURE_LOCATIONS[r.measurementLocation],
                    source = sourceName(r.metadata.dataOrigin.packageName),
                )
            }

    /** Perioden aus Blutungs-Tagesmeldungen und Periodenzeiträumen, zu Zeiträumen zusammengefasst. */
    suspend fun readPeriods(from: Instant, to: Instant): List<PeriodInfo> {
        val zone = ZoneId.systemDefault()
        val days = mutableSetOf<LocalDate>()
        readAll(MenstruationFlowRecord::class, from, to).forEach { days += it.time.atZone(zone).toLocalDate() }
        readAll(MenstruationPeriodRecord::class, from, to).forEach { p ->
            var day = p.startTime.atZone(zone).toLocalDate()
            val last = p.endTime.minusSeconds(1).atZone(zone).toLocalDate()
            while (!day.isAfter(last)) { days += day; day = day.plusDays(1) }
        }
        return Health.mergePeriodDays(days).map { range ->
            val start = range.start.atStartOfDay(zone).toInstant()
            PeriodInfo(range.start, range.endInclusive, start, zone.rules.getOffset(start))
        }
    }

    /** Schrittsumme je Kalendertag (lokale Zeitzone), von [fromDay] bis heute. */
    suspend fun dailySteps(fromDay: LocalDate, now: Instant): List<DailySteps> {
        val zone = ZoneId.systemDefault()
        val today = now.atZone(zone).toLocalDate()
        val out = mutableListOf<DailySteps>()
        var day = fromDay
        while (!day.isAfter(today)) {
            val start = day.atStartOfDay(zone).toInstant()
            val end = if (day == today) now else day.plusDays(1).atStartOfDay(zone).toInstant().minusSeconds(60)
            val total = client.aggregate(
                AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), TimeRangeFilter.between(start, end))
            )[StepsRecord.COUNT_TOTAL] ?: 0L
            out += DailySteps(day, end, zone.rules.getOffset(end), total)
            day = day.plusDays(1)
        }
        return out
    }

    private suspend fun <T : Record> readAll(type: KClass<T>, from: Instant, to: Instant): List<T> {
        val out = mutableListOf<T>()
        var pageToken: String? = null
        do {
            val response = client.readRecords(
                ReadRecordsRequest(type, TimeRangeFilter.between(from, to), pageToken = pageToken)
            )
            out += response.records
            pageToken = response.pageToken
        } while (!pageToken.isNullOrEmpty())
        return out
    }

    private fun currentOffset(at: Instant): ZoneOffset = ZoneId.systemDefault().rules.getOffset(at)

    fun sourceName(pkg: String): String = KNOWN_SOURCES[pkg] ?: runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    companion object {
        fun read(type: KClass<out Record>) = HealthPermission.getReadPermission(type)

        const val BACKGROUND = HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND

        /** Alle Rechte, die die App anfragt. Pflicht sind nur die für die aktivierten Kategorien. */
        val ALL_PERMISSIONS = setOf(
            read(ExerciseSessionRecord::class),
            read(SleepSessionRecord::class),
            read(StepsRecord::class),
            read(HeartRateRecord::class),
            read(DistanceRecord::class),
            read(TotalCaloriesBurnedRecord::class),
            read(BodyTemperatureRecord::class),
            read(MenstruationFlowRecord::class),
            BACKGROUND,
        )

        private val TEMPERATURE_LOCATIONS = mapOf(
            BodyTemperatureMeasurementLocation.MEASUREMENT_LOCATION_ARMPIT to "Achsel",
            BodyTemperatureMeasurementLocation.MEASUREMENT_LOCATION_FOREHEAD to "Stirn",
            BodyTemperatureMeasurementLocation.MEASUREMENT_LOCATION_MOUTH to "Mund",
            BodyTemperatureMeasurementLocation.MEASUREMENT_LOCATION_EAR to "Ohr",
            BodyTemperatureMeasurementLocation.MEASUREMENT_LOCATION_RECTUM to "rektal",
            BodyTemperatureMeasurementLocation.MEASUREMENT_LOCATION_WRIST to "Handgelenk",
            BodyTemperatureMeasurementLocation.MEASUREMENT_LOCATION_FINGER to "Finger",
            BodyTemperatureMeasurementLocation.MEASUREMENT_LOCATION_TEMPORAL_ARTERY to "Schläfe",
        )

        private val KNOWN_SOURCES = mapOf(
            "com.sec.android.app.shealth" to "Samsung Health",
            "com.garmin.android.apps.connectmobile" to "Garmin Connect",
            "com.google.android.apps.fitness" to "Google Fit",
            "com.fitbit.FitbitMobile" to "Fitbit",
            "com.google.android.apps.healthdata" to "Health Connect",
            "com.withings.wiscale2" to "Withings",
            "com.xiaomi.wearable" to "Mi Fitness",
            "com.huawei.health" to "Huawei Health",
            "com.strava" to "Strava",
            "com.ouraring.oura" to "Oura",
            "com.polar.polarflow" to "Polar Flow",
            "com.suunto.china" to "Suunto",
        )
    }
}

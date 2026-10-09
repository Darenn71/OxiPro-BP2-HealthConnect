package com.oxipro.bridge.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.units.Pressure
import com.oxipro.bridge.ble.BloodPressureParser
import java.time.Instant
import java.time.ZoneOffset

class HealthConnectManager(private val context: Context) {

    val permissions = setOf(
        HealthPermission.getWritePermission(BloodPressureRecord::class),
        HealthPermission.getWritePermission(HeartRateRecord::class)
    )

    fun isAvailable(): Boolean =
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    fun client(): HealthConnectClient = HealthConnectClient.getOrCreate(context)

    /** Standard permission-request contract to launch from an Activity/Fragment. */
    fun requestPermissionsContract() = PermissionController.createRequestPermissionResultContract()

    suspend fun hasAllPermissions(): Boolean {
        val granted = client().permissionController.getGrantedPermissions()
        return granted.containsAll(permissions)
    }

    /** Converts a parsed final-result packet into Health Connect records and writes them. */
    suspend fun writeReading(result: BloodPressureParser.ParsedPacket.FinalResult) {
        val time = result.receivedAt
        val zoneOffset = ZoneOffset.systemDefault().rules.getOffset(time)

        val bpRecord = BloodPressureRecord(
            time = time,
            zoneOffset = zoneOffset,
            systolic = Pressure.millimetersOfMercury(result.systolicMmHg.toDouble()),
            diastolic = Pressure.millimetersOfMercury(result.diastolicMmHg.toDouble()),
            bodyPosition = BloodPressureRecord.BODY_POSITION_UNKNOWN,
            measurementLocation = BloodPressureRecord.MEASUREMENT_LOCATION_LEFT_UPPER_ARM,
            metadata = Metadata.manualEntry()
        )

        val heartRateRecord = HeartRateRecord(
            startTime = time,
            startZoneOffset = zoneOffset,
            endTime = time,
            endZoneOffset = zoneOffset,
            samples = listOf(
                HeartRateRecord.Sample(time = time, beatsPerMinute = result.pulseBpm.toLong())
            ),
            metadata = Metadata.manualEntry()
        )

        client().insertRecords(listOf<Record>(bpRecord, heartRateRecord))
    }
}

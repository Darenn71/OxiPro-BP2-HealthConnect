package com.oxipro.bridge.ble

import java.time.Instant

/**
 * Parses notifications from the OxiPro BP2's vendor-specific protocol,
 * reverse-engineered from an HCI snoop capture of the official MedM app.
 *
 * This is NOT the standard Bluetooth SIG Blood Pressure Service — the device
 * uses a proprietary service (0xffe0) with a custom framing:
 *
 *   d0 c2 [cmd] [payload...] [trailer]      -- device -> phone (handle 0xffe1)
 *   be b0 [len] [payload...] [trailer]      -- phone -> device (handle 0xffe2)
 *
 * Two notification types matter for our purposes:
 *  - cmd 0x04, subtype 0xcb: live cuff pressure while deflating (payload:
 *    subtype, then pressure as a 2-byte big-endian mmHg value). Useful for
 *    an optional "measuring... 142 mmHg" progress indicator; not required.
 *  - cmd 0x0c, subtype 0xcc: final result. Payload bytes observed:
 *      [subtype=cc] [systolic] [diastolic] [pulse] [00] [year:2][month][day][hour][min][sec] [trailer]
 *    Confirmed against a real reading (device screen showed 120/69, pulse 76):
 *    systolic=0x78=120, diastolic=0x45=69, pulse=0x4c=76 — byte order is
 *    systolic, diastolic, pulse. The embedded timestamp also matched the
 *    capture time exactly.
 *
 * The device's embedded timestamp is intentionally NOT used (its checksum
 * algorithm is unconfirmed, so we can't reliably time-sync the device, and
 * an un-synced device clock could be stale). We stamp readings with the
 * phone's own clock at the moment the notification arrives instead.
 */
object BloodPressureParser {

    /**
     * If false, swaps which of the two middle bytes is treated as pulse vs.
     * diastolic. Confirmed against a real reading (120/69, pulse 76): the
     * second payload byte is diastolic, the third is pulse — so this is false.
     */
    private const val SECOND_VALUE_IS_PULSE = false

    sealed class ParsedPacket {
        data class LivePressure(val cuffPressureMmHg: Int) : ParsedPacket()
        data class FinalResult(
            val systolicMmHg: Int,
            val diastolicMmHg: Int,
            val pulseBpm: Int,
            val receivedAt: Instant = Instant.now()
        ) : ParsedPacket()
        object Unknown : ParsedPacket()
    }

    fun parse(bytes: ByteArray): ParsedPacket {
        if (bytes.size < 4) return ParsedPacket.Unknown
        if ((bytes[0].toInt() and 0xFF) != 0xD0 || (bytes[1].toInt() and 0xFF) != 0xC2) {
            return ParsedPacket.Unknown
        }

        val cmd = bytes[2].toInt() and 0xFF
        val subtype = bytes[3].toInt() and 0xFF

        return when {
            cmd == 0x04 && subtype == 0xCB && bytes.size >= 6 -> {
                val pressure = ((bytes[4].toInt() and 0xFF) shl 8) or (bytes[5].toInt() and 0xFF)
                ParsedPacket.LivePressure(pressure)
            }

            cmd == 0x0C && subtype == 0xCC && bytes.size >= 7 -> {
                val systolic = bytes[4].toInt() and 0xFF
                val second = bytes[5].toInt() and 0xFF
                val third = bytes[6].toInt() and 0xFF

                val (pulse, diastolic) = if (SECOND_VALUE_IS_PULSE) {
                    second to third
                } else {
                    third to second
                }

                ParsedPacket.FinalResult(
                    systolicMmHg = systolic,
                    diastolicMmHg = diastolic,
                    pulseBpm = pulse
                )
            }

            else -> ParsedPacket.Unknown
        }
    }
}

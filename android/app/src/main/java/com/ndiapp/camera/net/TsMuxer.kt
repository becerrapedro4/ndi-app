package com.ndiapp.camera.net

import java.io.ByteArrayOutputStream

/**
 * Muxer MPEG-TS mínimo pero conforme para un único programa de vídeo H.264.
 *
 * Genera PAT, PMT y PES con PTS/DTS en base 90 kHz, e inserta PCR en el primer
 * paquete de cada access unit (sin PCR los receptores cortan el stream).
 */
class TsMuxer {

    private val continuityCounters = HashMap<Int, Int>()

    /**
     * Empaqueta un access unit H.264 en Annex-B.
     * @param annexB access unit completo (puede contener SPS/PPS/IDR)
     * @param ptsUs timestamp de presentación en microsegundos
     * @param keyFrame true si es un punto de entrada (aleatorio)
     * @return bytes TS listos para enviar por SRT
     */
    fun writeVideoFrame(annexB: ByteArray, ptsUs: Long, keyFrame: Boolean): ByteArray {
        val pts90k = (ptsUs * 90L / 1000L) and PTS_MASK
        val out = ByteArrayOutputStream(annexB.size + PACKET_SIZE * 4)
        if (keyFrame) {
            out.write(psiPacket(PID_PAT, buildPat()))
            out.write(psiPacket(PID_PMT, buildPmt()))
        }
        out.write(pesPackets(buildPes(annexB, pts90k), pts90k, keyFrame))
        return out.toByteArray()
    }

    // ------------------------------------------------------------------ PSI ----

    private fun buildPat(): ByteArray {
        val section = ByteArray(16)
        section[0] = 0x00                     // table_id: PAT
        section[1] = 0xB0.toByte()            // section_syntax_indicator + longitud alta
        section[2] = 0x0D                     // section_length = 13
        section[3] = 0x00                     // transport_stream_id
        section[4] = 0x01
        section[5] = 0xC1.toByte()            // version 0, current_next 1
        section[6] = 0x00                     // section_number
        section[7] = 0x00                     // last_section_number
        section[8] = 0x00                     // program_number
        section[9] = 0x01
        section[10] = (0xE0 or ((PID_PMT shr 8) and 0x1F)).toByte()
        section[11] = (PID_PMT and 0xFF).toByte()
        writeCrc(section, 12)
        return section
    }

    private fun buildPmt(): ByteArray {
        val streams = 1
        val sectionLength = 9 + streams * 5 + 4
        val section = ByteArray(3 + sectionLength)
        section[0] = 0x02                     // table_id: PMT
        section[1] = (0xB0 or ((sectionLength shr 8) and 0x0F)).toByte()
        section[2] = (sectionLength and 0xFF).toByte()
        section[3] = 0x00                     // program_number
        section[4] = 0x01
        section[5] = 0xC1.toByte()            // version 0, current_next 1
        section[6] = 0x00                     // section_number
        section[7] = 0x00                     // last_section_number
        section[8] = (0xE0 or ((PID_VIDEO shr 8) and 0x1F)).toByte()  // PCR_PID
        section[9] = (PID_VIDEO and 0xFF).toByte()
        section[10] = 0xF0.toByte()           // program_info_length = 0
        section[11] = 0x00
        section[12] = STREAM_TYPE_H264        // stream_type: H.264
        section[13] = (0xE0 or ((PID_VIDEO shr 8) and 0x1F)).toByte()
        section[14] = (PID_VIDEO and 0xFF).toByte()
        section[15] = 0xF0.toByte()           // ES_info_length = 0
        section[16] = 0x00
        writeCrc(section, 3 + sectionLength - 4)
        return section
    }

    /** Envuelve una sección PSI en un paquete TS con pointer_field. */
    private fun psiPacket(pid: Int, section: ByteArray): ByteArray {
        val packet = ByteArray(PACKET_SIZE) { 0xFF.toByte() }
        packet[0] = 0x47
        packet[1] = (0x40 or ((pid shr 8) and 0x1F)).toByte()
        packet[2] = (pid and 0xFF).toByte()
        packet[3] = 0x10
        packet[4] = 0x00                       // pointer_field
        System.arraycopy(section, 0, packet, 5, section.size)
        bumpContinuity(packet, pid)
        return packet
    }

    // ------------------------------------------------------------------ PES ----

    private fun buildPes(annexB: ByteArray, pts90k: Long): ByteArray {
        val header = ByteArray(19)
        header[0] = 0x00
        header[1] = 0x00
        header[2] = 0x01
        header[3] = 0xE0.toByte()              // stream_id: video
        header[4] = 0x00                       // PES_packet_length = 0 (vídeo, ilimitado)
        header[5] = 0x00
        header[6] = 0x84.toByte()              // '10', sin scrambling, data_alignment
        header[7] = 0xC0.toByte()              // PTS + DTS presentes
        header[8] = 0x0A                       // PES_header_data_length = 10
        writeTimestamp(header, 9, 0x3, pts90k) // PTS con prefijo '0011'
        writeTimestamp(header, 14, 0x1, pts90k) // DTS con prefijo '0001' (sin B-frames: igual a PTS)
        return header + annexB
    }

    private fun writeTimestamp(target: ByteArray, offset: Int, prefix: Int, value: Long) {
        target[offset] = ((prefix shl 4) or (((value shr 29) and 0x0E).toInt()) or 1).toByte()
        target[offset + 1] = ((value shr 22) and 0xFF).toByte()
        target[offset + 2] = ((((value shr 14) and 0xFE) or 1)).toByte()
        target[offset + 3] = ((value shr 7) and 0xFF).toByte()
        target[offset + 4] = ((((value shl 1) and 0xFE) or 1)).toByte()
    }

    private fun pesPackets(pes: ByteArray, pcr90k: Long, randomAccess: Boolean): ByteArray {
        val out = ByteArrayOutputStream(pes.size + PACKET_SIZE)
        var offset = 0
        var first = true
        while (offset < pes.size) {
            val remaining = pes.size - offset
            val withPcr = first
            val minAdaptation = if (withPcr) 8 else 0
            val usable = PACKET_SIZE - 4 - minAdaptation
            val payloadLength = minOf(remaining, usable)
            val leftover = PACKET_SIZE - 4 - payloadLength

            val packet = ByteArray(PACKET_SIZE) { 0xFF.toByte() }
            packet[0] = 0x47
            packet[1] = (((if (first) 0x40 else 0x00) or ((PID_VIDEO shr 8) and 0x1F))).toByte()
            packet[2] = (PID_VIDEO and 0xFF).toByte()

            val hasAdaptation = withPcr || leftover > 0
            var payloadStart = 4
            if (hasAdaptation) {
                val adaptationContent = leftover - 1
                packet[3] = 0x30.toByte()      // adaptation + payload
                packet[4] = adaptationContent.toByte()
                var cursor = 5
                packet[cursor] = (if (randomAccess && withPcr) 0x40 else 0x00).toByte()
                cursor++
                if (withPcr) {
                    writePcr(packet, cursor, pcr90k)
                    cursor += 6
                }
                while (cursor < 5 + adaptationContent) {
                    packet[cursor] = 0xFF.toByte()
                    cursor++
                }
                payloadStart = 5 + adaptationContent
            } else {
                packet[3] = 0x10
            }

            System.arraycopy(pes, offset, packet, payloadStart, payloadLength)
            bumpContinuity(packet, PID_VIDEO)
            out.write(packet)

            offset += payloadLength
            first = false
        }
        return out.toByteArray()
    }

    private fun writePcr(target: ByteArray, offset: Int, base90k: Long) {
        val base = base90k and PTS_MASK
        target[offset] = ((base shr 25) and 0xFF).toByte()
        target[offset + 1] = ((base shr 17) and 0xFF).toByte()
        target[offset + 2] = ((base shr 9) and 0xFF).toByte()
        target[offset + 3] = ((base shr 1) and 0xFF).toByte()
        target[offset + 4] = ((((base and 1L) shl 7) or 0x7E or 0x01).toByte())
        target[offset + 5] = 0x00
    }

    private fun bumpContinuity(packet: ByteArray, pid: Int) {
        val counter = continuityCounters.getOrDefault(pid, 0) and 0x0F
        packet[3] = ((packet[3].toInt() and 0xF0) or counter).toByte()
        continuityCounters[pid] = (counter + 1) and 0x0F
    }

    private fun writeCrc(target: ByteArray, crcOffset: Int) {
        val crc = crc32Mpeg(target, 0, crcOffset)
        target[crcOffset] = ((crc shr 24) and 0xFF).toByte()
        target[crcOffset + 1] = ((crc shr 16) and 0xFF).toByte()
        target[crcOffset + 2] = ((crc shr 8) and 0xFF).toByte()
        target[crcOffset + 3] = (crc and 0xFF).toByte()
    }

    private fun crc32Mpeg(data: ByteArray, offset: Int, length: Int): Long {
        var crc = 0xFFFFFFFFL
        for (i in offset until offset + length) {
            crc = crc xor ((data[i].toLong() and 0xFF) shl 24)
            repeat(8) {
                crc = if (crc and 0x80000000L != 0L) {
                    ((crc shl 1) xor 0x04C11DB7L) and 0xFFFFFFFFL
                } else {
                    (crc shl 1) and 0xFFFFFFFFL
                }
            }
        }
        return crc and 0xFFFFFFFFL
    }

    companion object {
        private const val PACKET_SIZE = 188
        private const val PID_PAT = 0x0000
        private const val PID_PMT = 0x1000
        private const val PID_VIDEO = 0x0100
        private const val STREAM_TYPE_H264 = 0x1B
        private const val PTS_MASK = 0x1FFFFFFFFL
    }
}

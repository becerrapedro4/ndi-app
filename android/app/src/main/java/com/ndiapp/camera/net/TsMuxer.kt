package com.ndiapp.camera.net

import java.io.ByteArrayOutputStream

/**
 * Empaquetador MPEG-TS mínimo para un solo flujo de vídeo H.264.
 *
 * SRT transporta bytes: lo que se manda tiene que ser un transporte real, así
 * que acá se arman PAT, PMT y los PES del vídeo en paquetes de 188 bytes. Es
 * lo que espera cualquier receptor (VLC, ffplay, un decodificador de hardware)
 * para abrir un `srt://`.
 */
class TsMuxer {

    private companion object {
        const val PACKET = 188
        const val PAT_PID = 0x0000
        const val PMT_PID = 0x1000
        const val VIDEO_PID = 0x0100
        const val PMT_EVERY = 20
    }

    private val bytes = ByteArrayOutputStream(96 * 1024)
    private var ccPat = 0
    private var ccPmt = 0
    private var ccVideo = 0
    private var psiCountdown = 0

    /** Devuelve los paquetes TS de una unidad de acceso completa. */
    fun encode(accessUnit: ByteArray, pts90k: Long, keyFrame: Boolean): ByteArray {
        bytes.reset()
        if (psiCountdown <= 0) {
            writeSection(PAT_PID, patSection(), ccPat).also { ccPat = it }
            writeSection(PMT_PID, pmtSection(), ccPmt).also { ccPmt = it }
            psiCountdown = PMT_EVERY
        } else {
            psiCountdown--
        }
        writeVideo(accessUnit, pts90k.coerceAtLeast(0), keyFrame)
        return bytes.toByteArray()
    }

    private fun patSection(): ByteArray {
        val section = ByteArray(16)
        var i = 0
        section[i++] = 0x00                     // table_id
        section[i++] = 0xB0.toByte()            // syntax + section_length alto
        section[i++] = 0x0D                     // section_length = 13
        section[i++] = 0x00
        section[i++] = 0x01                     // transport_stream_id
        section[i++] = 0xC1.toByte()            // versión 0, current_next 1
        section[i++] = 0x00                     // section_number
        section[i++] = 0x00                     // last_section_number
        section[i++] = 0x00
        section[i++] = 0x01                     // program_number 1
        section[i++] = (0xE0 or (PMT_PID shr 8)).toByte()
        section[i++] = (PMT_PID and 0xFF).toByte()
        appendCrc(section, i)
        return section
    }

    private fun pmtSection(): ByteArray {
        val section = ByteArray(21)
        var i = 0
        section[i++] = 0x02                     // table_id PMT
        section[i++] = 0xB0.toByte()
        section[i++] = 0x12                     // section_length = 18
        section[i++] = 0x00
        section[i++] = 0x01                     // program_number
        section[i++] = 0xC1.toByte()            // versión 0, current_next 1
        section[i++] = 0x00                     // section_number
        section[i++] = 0x00                     // last_section_number
        section[i++] = (0xE0 or (VIDEO_PID shr 8)).toByte()
        section[i++] = (VIDEO_PID and 0xFF).toByte()   // PCR_PID
        section[i++] = 0xF0.toByte()            // program_info_length = 0
        section[i++] = 0x00
        section[i++] = 0x1B                     // H.264
        section[i++] = (0xE0 or (VIDEO_PID shr 8)).toByte()
        section[i++] = (VIDEO_PID and 0xFF).toByte()
        section[i++] = 0xF0.toByte()            // ES_info_length = 0
        section[i++] = 0x00
        appendCrc(section, i)
        return section
    }

    private fun appendCrc(section: ByteArray, length: Int) {
        val crc = crc32(section, length)
        section[length] = ((crc shr 24) and 0xFF).toByte()
        section[length + 1] = ((crc shr 16) and 0xFF).toByte()
        section[length + 2] = ((crc shr 8) and 0xFF).toByte()
        section[length + 3] = (crc and 0xFF).toByte()
    }

    private fun crc32(data: ByteArray, length: Int): Int {
        var crc = 0xFFFFFFFF.toInt()
        for (index in 0 until length) {
            crc = crc xor ((data[index].toInt() and 0xFF) shl 24)
            repeat(8) {
                crc = if (crc and 0x80000000.toInt() != 0) {
                    (crc shl 1) xor 0x04C11DB7
                } else {
                    crc shl 1
                }
            }
        }
        return crc
    }

    /** PAT o PMT: puntero a cero, sección completa y relleno hasta 188. */
    private fun writeSection(pid: Int, section: ByteArray, counter: Int): Int {
        val packet = ByteArray(PACKET) { 0xFF.toByte() }
        packet[0] = 0x47
        packet[1] = (0x40 or (pid shr 8)).toByte()
        packet[2] = (pid and 0xFF).toByte()
        packet[3] = (0x10 or (counter and 0x0F)).toByte()
        packet[4] = 0x00
        System.arraycopy(section, 0, packet, 5, section.size)
        bytes.write(packet)
        return (counter + 1) and 0x0F
    }

    private fun writeVideo(data: ByteArray, pts: Long, keyFrame: Boolean) {
        // Cabecera PES de vídeo con PTS y DTS (la longitud se deja en cero).
        val header = 19
        val pes = ByteArray(header + data.size)
        pes[0] = 0x00
        pes[1] = 0x00
        pes[2] = 0x01
        pes[3] = 0xE0.toByte()
        pes[4] = 0x00
        pes[5] = 0x00
        pes[6] = 0x80.toByte()
        pes[7] = 0xC0.toByte()
        pes[8] = 0x0A
        writeTimestamp(pes, 9, 0x3, pts)
        writeTimestamp(pes, 14, 0x1, (pts - 3600).coerceAtLeast(0))
        System.arraycopy(data, 0, pes, header, data.size)

        var position = 0
        var first = true
        while (position < pes.size) {
            val packet = ByteArray(PACKET) { 0xFF.toByte() }
            packet[0] = 0x47
            packet[1] = ((if (first) 0x40 else 0x00) or (VIDEO_PID shr 8)).toByte()
            packet[2] = (VIDEO_PID and 0xFF).toByte()
            packet[3] = (0x30 or ccVideo).toByte()

            var offset = 4
            var payload: Int
            if (first && keyFrame) {
                // PCR en el primer paquete de cada cuadro clave.
                packet[4] = 7
                packet[5] = 0x10
                writePcr(packet, 6, (pts - 3600).coerceAtLeast(0))
                offset = 12
                payload = minOf(PACKET - offset, pes.size - position)
            } else {
                val rest = pes.size - position
                if (rest >= PACKET - 4) {
                    payload = PACKET - 4
                } else {
                    val pad = (PACKET - 4) - rest
                    packet[4] = (pad - 1).toByte()
                    if (pad > 1) packet[5] = 0x00
                    payload = rest
                }
            }
            System.arraycopy(pes, position, packet, offset, payload)
            position += payload
            first = false
            ccVideo = (ccVideo + 1) and 0x0F
            bytes.write(packet)
        }
    }

    private fun writeTimestamp(target: ByteArray, offset: Int, prefix: Int, value: Long) {
        val ts = value and 0x1FFFFFFFFL
        target[offset] = ((prefix shl 4) or (((ts shr 30) and 0x07).toInt() shl 1) or 1).toByte()
        target[offset + 1] = ((ts shr 22) and 0xFF).toByte()
        target[offset + 2] = ((((ts shr 15) and 0x7F).toInt() shl 1) or 1).toByte()
        target[offset + 3] = ((ts shr 7) and 0xFF).toByte()
        target[offset + 4] = (((ts and 0x7F).toInt() shl 1) or 1).toByte()
    }

    private fun writePcr(target: ByteArray, offset: Int, base90k: Long) {
        val base = base90k and 0x1FFFFFFFFL
        target[offset] = ((base shr 25) and 0xFF).toByte()
        target[offset + 1] = ((base shr 17) and 0xFF).toByte()
        target[offset + 2] = ((base shr 9) and 0xFF).toByte()
        target[offset + 3] = ((base shr 1) and 0xFF).toByte()
        target[offset + 4] = ((((base and 1).toInt() shl 7) or 0x7E)).toByte()
        target[offset + 5] = 0x00
    }
}

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
        const val AUDIO_PID = 0x0101
        const val PMT_EVERY = 20
    }

    private val bytes = ByteArrayOutputStream(96 * 1024)
    private var ccPat = 0
    private var ccPmt = 0
    private var ccVideo = 0
    private var ccAudio = 0
    private var psiCountdown = 0
    private var audio = false

    /** Versión del PMT: cambia cuando aparece o desaparece una pista. */
    private var version = 0

    // La norma pide un PCR al menos cada 100 ms. Antes sólo iba en los cuadros
    // clave (uno por segundo) y muchos receptores no enganchaban el programa.
    private var lastPcrPts = -1L

    /** Devuelve los paquetes TS de una unidad de acceso completa. */
    @Synchronized
    fun encode(accessUnit: ByteArray, pts90k: Long, keyFrame: Boolean): ByteArray {
        bytes.reset()
        if (psiCountdown <= 0) writePsi() else psiCountdown--
        writeVideo(accessUnit, pts90k.coerceAtLeast(0), keyFrame)
        return bytes.toByteArray()
    }

    /** Paquetes TS de una trama AAC con cabecera ADTS. */
    @Synchronized
    fun encodeAudio(frame: ByteArray, pts90k: Long): ByteArray {
        bytes.reset()
        writeAudio(frame, pts90k.coerceAtLeast(0))
        return bytes.toByteArray()
    }

    /** PAT y PMT sueltos: se envían al aparecer una pista nueva. */
    @Synchronized
    fun psi(): ByteArray {
        bytes.reset()
        writePsi()
        return bytes.toByteArray()
    }

    /** Anuncia (o deja de anunciar) la pista de audio en el PMT. */
    @Synchronized
    fun setAudio(enabled: Boolean) {
        if (audio == enabled) return
        audio = enabled
        // Un PMT con la misma versión y otro contenido se descarta en el
        // receptor: hay que subir la versión para que lo vuelva a leer.
        version = (version + 1) and 0x1F
        psiCountdown = 0
    }

    private fun writePsi() {
        ccPat = writeSection(PAT_PID, patSection(), ccPat)
        ccPmt = writeSection(PMT_PID, pmtSection(), ccPmt)
        psiCountdown = PMT_EVERY
    }

    private fun patSection(): ByteArray {
        val section = ByteArray(16)
        var i = 0
        section[i++] = 0x00                     // table_id
        section[i++] = 0xB0.toByte()            // syntax + section_length alto
        section[i++] = 0x0D                     // section_length = 13
        section[i++] = 0x00
        section[i++] = 0x01                     // transport_stream_id
        section[i++] = (0xC0 or (version shl 1) or 0x01).toByte()
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
        // 9 bytes fijos + 5 por flujo + 4 de CRC.
        val sectionLength = 9 + 5 + (if (audio) 5 else 0) + 4
        val section = ByteArray(3 + sectionLength)
        var i = 0
        section[i++] = 0x02                     // table_id PMT
        section[i++] = (0xB0 or ((sectionLength shr 8) and 0x0F)).toByte()
        section[i++] = (sectionLength and 0xFF).toByte()
        section[i++] = 0x00
        section[i++] = 0x01                     // program_number
        section[i++] = (0xC0 or (version shl 1) or 0x01).toByte()
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
        if (audio) {
            section[i++] = 0x0F                 // AAC/ADTS
            section[i++] = (0xE0 or (AUDIO_PID shr 8)).toByte()
            section[i++] = (AUDIO_PID and 0xFF).toByte()
            section[i++] = 0xF0.toByte()        // ES_info_length = 0
            section[i++] = 0x00
        }
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
        // PCR en el primer paquete de este cuadro si toca: cuadro clave, primer
        // cuadro o pasaron 100 ms desde el anterior.
        val withPcr = keyFrame || lastPcrPts < 0L || pts - lastPcrPts >= 9_000L
        if (withPcr) lastPcrPts = pts
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

            val rest = pes.size - position
            var offset = 4
            val payload: Int
            when {
                // PCR cuando toca: cuadro clave o cada 100 ms.
                first && withPcr -> {
                    val room = PACKET - 12
                    payload = minOf(room, rest)
                    packet[3] = (0x30 or ccVideo).toByte()
                    // Si el cuadro termina dentro de este paquete, el resto se
                    // declara como relleno del campo de adaptación: sin eso los
                    // bytes 0xFF se leerían como parte del PES y rompen el flujo.
                    packet[4] = (7 + (room - payload)).toByte()
                    packet[5] = 0x10
                    writePcr(packet, 6, (pts - 3600).coerceAtLeast(0))
                    // Con relleno el campo de adaptación crece y el payload
                    // arranca más tarde; sin relleno cae en 12.
                    offset = PACKET - payload
                }

                rest >= PACKET - 4 -> {
                    // Paquete lleno y sin adaptación: sólo payload.
                    payload = PACKET - 4
                    packet[3] = (0x10 or ccVideo).toByte()
                }

                else -> {
                    val pad = (PACKET - 4) - rest
                    payload = rest
                    packet[3] = (0x30 or ccVideo).toByte()
                    packet[4] = (pad - 1).toByte()
                    if (pad > 1) packet[5] = 0x00
                    // El relleno ocupa bytes reales: el payload empieza después
                    // del campo de adaptación, no en el 4.
                    offset = 4 + pad
                }
            }
            System.arraycopy(pes, position, packet, offset, payload)
            position += payload
            first = false
            ccVideo = (ccVideo + 1) and 0x0F
            bytes.write(packet)
        }
    }

    /**
     * PES de audio con PTS y su troceado en paquetes de 188 bytes. A
     * diferencia del vídeo, el PES de audio declara su longitud.
     */
    private fun writeAudio(frame: ByteArray, pts: Long) {
        val header = 14
        val body = 8 + frame.size
        val pes = ByteArray(header + frame.size)
        pes[0] = 0x00
        pes[1] = 0x00
        pes[2] = 0x01
        pes[3] = 0xC0.toByte()                  // stream_id: audio
        pes[4] = ((body shr 8) and 0xFF).toByte()
        pes[5] = (body and 0xFF).toByte()
        pes[6] = 0x80.toByte()
        pes[7] = 0x80.toByte()                  // sólo PTS
        pes[8] = 0x05
        writeTimestamp(pes, 9, 0x2, pts)
        System.arraycopy(frame, 0, pes, header, frame.size)

        var position = 0
        var first = true
        while (position < pes.size) {
            val packet = ByteArray(PACKET) { 0xFF.toByte() }
            packet[0] = 0x47
            packet[1] = ((if (first) 0x40 else 0x00) or (AUDIO_PID shr 8)).toByte()
            packet[2] = (AUDIO_PID and 0xFF).toByte()
            val rest = pes.size - position
            var offset = 4
            val payload: Int
            if (rest >= PACKET - 4) {
                packet[3] = (0x10 or ccAudio).toByte()
                payload = PACKET - 4
            } else {
                val pad = (PACKET - 4) - rest
                packet[3] = (0x30 or ccAudio).toByte()
                packet[4] = (pad - 1).toByte()
                if (pad > 1) packet[5] = 0x00
                offset = 4 + pad
                payload = rest
            }
            System.arraycopy(pes, position, packet, offset, payload)
            position += payload
            first = false
            ccAudio = (ccAudio + 1) and 0x0F
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

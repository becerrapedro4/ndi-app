package com.ndiapp.camera.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Log
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import java.util.Locale

/**
 * Lente física que el sistema no expone como cámara propia: vive dentro de una
 * cámara lógica y solo se puede usar pidiendo una salida enlazada a su id, como
 * hace PhotonCamera. Es la vía para el ultra gran angular en equipos donde
 * CameraX solo ve la cámara lógica.
 */
data class HiddenLens(
    /** Cámara lógica que se abre. */
    val logicalId: String,
    /** Cámara física dentro de la lógica. */
    val physicalId: String,
    /** Aumento respecto de la lente principal («0,6×»). */
    val label: String,
    val equivMm: Float,
    val focalMm: Float
) {
    /** Identificador que circula por la app. */
    val key: String get() = "$logicalId:$physicalId"
}

object HiddenLenses {

    private const val TAG = "HiddenLenses"

    /**
     * Lentes físicas traseras más angulares que la principal que no aparecen en
     * [exposedIds] (las que CameraX ya ofrece y se abren por su cuenta).
     */
    fun back(context: Context, exposedIds: Set<String>): List<HiddenLens> {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return emptyList()
        val all = runCatching { manager.cameraIdList.toSet() }.getOrElse { return emptyList() }

        // Referencia: la cámara trasera expuesta más cercana a 26 mm equivalentes.
        var reference = 0f
        for (id in all) {
            val characteristics = runCatching { manager.getCameraCharacteristics(id) }.getOrNull()
                ?: continue
            if (characteristics.get(CameraCharacteristics.LENS_FACING) !=
                CameraCharacteristics.LENS_FACING_BACK
            ) {
                continue
            }
            val equiv = equivalentMm(characteristics) ?: continue
            if (reference == 0f || abs(equiv - 26f) < abs(reference - 26f)) reference = equiv
        }
        if (reference <= 0f) return emptyList()

        val result = mutableListOf<HiddenLens>()
        for (id in all) {
            if (id in exposedIds) continue
            val characteristics = runCatching { manager.getCameraCharacteristics(id) }.getOrNull()
                ?: continue
            if (characteristics.get(CameraCharacteristics.LENS_FACING) !=
                CameraCharacteristics.LENS_FACING_BACK
            ) {
                continue
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) continue
            val physicalIds = runCatching {
                characteristics.physicalCameraIds
            }.getOrNull().orEmpty()
            for (physicalId in physicalIds) {
                if (physicalId in all) continue // se abre sola, la maneja CameraX
                val physical = runCatching {
                    manager.getCameraCharacteristics(physicalId)
                }.getOrNull() ?: continue
                val equiv = equivalentMm(physical) ?: continue
                // Solo interesan las más angulares que la principal.
                if (equiv > reference * 0.85f) continue
                val ratio = (equiv / reference * 10f).roundToInt() / 10f
                if (ratio <= 0.2f) continue
                val label = if (abs(ratio - ratio.roundToInt()) < 0.05f) {
                    "${ratio.roundToInt()}×"
                } else {
                    String.format(Locale.US, "%.1f×", ratio)
                }
                if (result.any { it.logicalId == id && it.label == label }) continue
                val focal = physical.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    ?.minOrNull() ?: 0f
                result += HiddenLens(id, physicalId, label, equiv, focal)
                Log.i(TAG, "Lente oculta $label (lógica $id, física $physicalId)")
            }
        }
        return result.sortedBy { it.equivMm }
    }

    /** Distancia focal equivalente en 35 mm, a partir del tamaño físico del sensor. */
    fun equivalentMm(characteristics: CameraCharacteristics): Float? {
        val focal = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.minOrNull() ?: return null
        val physical = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            ?: return null
        val diagonal = hypot(physical.width.toDouble(), physical.height.toDouble())
        if (diagonal <= 0.1) return null
        return (focal * (43.2666 / diagonal)).toFloat()
    }

    /** Rango de zoom del equipo para una cámara concreta. */
    fun zoomRange(characteristics: CameraCharacteristics): ClosedFloatingPointRange<Float> {
        val max = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
            ?: 1f
        return 1f..max.coerceAtLeast(1f)
    }
}

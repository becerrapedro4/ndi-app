package com.ndiapp.camera.usb

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build

/** Cámara UVC conectada por USB (clase de interfaz USB_CLASS_VIDEO). */
data class UvcCamera(
    val deviceName: String,
    val product: String,
    val vendorId: Int,
    val productId: Int,
    val hasPermission: Boolean
) {
    val label: String
        get() = "$product (${vendorId.toString(16)}:${productId.toString(16)})"
}

/** Detección de cámaras UVC conectadas al puerto USB del dispositivo. */
object UvcCameras {

    private fun isVideoDevice(device: UsbDevice): Boolean =
        (0 until device.interfaceCount).any { index ->
            device.getInterface(index).interfaceClass == UsbConstants.USB_CLASS_VIDEO
        }

    fun list(context: Context): List<UvcCamera> {
        val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            ?: return emptyList()
        return manager.deviceList.values.filter(::isVideoDevice).map { device ->
            UvcCamera(
                deviceName = device.deviceName,
                product = device.productName ?: device.manufacturerName ?: "Cámara USB",
                vendorId = device.vendorId,
                productId = device.productId,
                hasPermission = manager.hasPermission(device)
            )
        }
    }

    /** Pide permiso de acceso al dispositivo; el resultado llega por broadcast. */
    fun requestPermission(context: Context, camera: UvcCamera, action: String): Boolean {
        val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            ?: return false
        val device = manager.deviceList.values
            .firstOrNull { it.deviceName == camera.deviceName } ?: return false
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        val intent = Intent(action).setPackage(context.packageName)
        val pending = PendingIntent.getBroadcast(context, 0, intent, flags)
        manager.requestPermission(device, pending)
        return true
    }
}

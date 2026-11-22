package com.ndiapp.camera

import android.Manifest
import android.app.Activity
import android.app.PictureInPictureParams
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Rect
import android.hardware.camera2.CaptureRequest
import android.hardware.usb.UsbManager
import android.media.projection.MediaProjectionManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.content.res.Configuration
import android.os.Build
import android.util.Rational
import android.view.SurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ndiapp.camera.camera.CameraLens
import com.ndiapp.camera.camera.CameraRequest
import com.ndiapp.camera.camera.CameraSession
import com.ndiapp.camera.media.AudioNdiSource
import com.ndiapp.camera.media.MediaItem
import com.ndiapp.camera.media.MediaKind
import com.ndiapp.camera.media.MediaPlaylistPlayer
import com.ndiapp.camera.ndi.NdiFinder
import com.ndiapp.camera.ndi.NdiNative
import com.ndiapp.camera.ndi.NdiReceiverController
import com.ndiapp.camera.ndi.StreamStats
import com.ndiapp.camera.net.SrtDataSource
import com.ndiapp.camera.net.SrtNative
import com.ndiapp.camera.net.SrtTransmitter
import com.ndiapp.camera.ndi.NdiSource
import com.ndiapp.camera.usb.UvcCamera
import com.ndiapp.camera.usb.UvcCameras
import com.ndiapp.camera.usb.UvcNdiSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pipActive.value = isInPictureInPictureMode
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Cualquier fallo queda anotado y se muestra al abrir la app otra vez.
        CrashLog.install(this)
        lastCrashText = CrashLog.take(this)?.let { CrashLog.summary(it) }
        enableMulticast()
        // Los datos móviles no pueden llevarse el multicast de NDI.
        bindToWifi(this)
        setContent {
            NdiAppTheme {
                NdiBackdrop { AppRoot() }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Con la app abierta, conectar una cámara lleva a su pestaña.
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) usbAttachSignal.intValue++
    }

    /** NDI usa mDNS/multicast para descubrir fuentes: sin este lock el descubrimiento falla. */
    private fun enableMulticast() {
        MulticastLock.ensure(applicationContext)
    }
}

/**
 * Lock de multicast de Android. Sin él el sistema filtra el multicast entrante y
 * el anuncio mDNS de la fuente NDI deja de llegar a los receptores (vMix), aunque
 * el monitor de la propia app la siga viendo por bucle local.
 *
 * Se guarda en un objeto: si el lock vive en una variable local, el recolector lo
 * libera al primer GC —por ejemplo cuando el codificador de SRT empieza a pedir
 * memoria— y el envío NDI desaparece de la red sin ningún aviso.
 */
private object MulticastLock {

    private var lock: WifiManager.MulticastLock? = null

    /** El sistema esta entregando multicast ahora mismo. */
    val held: Boolean
        get() = lock?.isHeld == true

    /** Pide el lock una sola vez y lo conserva mientras viva la app. */
    fun ensure(context: Context) {
        if (lock?.isHeld == true) return
        runCatching {
            val wifi = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
            lock = wifi.createMulticastLock("ndi-app").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }
}

/**
 * Fija los sockets del proceso a la Wi-Fi. Con datos móviles activos, Android
 * manda el multicast por la interfaz por defecto (la celular): el anuncio NDI
 * no llega a la LAN y el monitor solo encuentra las fuentes del propio telefono,
 * aunque la IP manual funcione por unicast.
 */
private var boundNetwork: Network? = null

private fun bindToWifi(context: Context): Boolean = runCatching {
    val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
        as? ConnectivityManager
    val wifi = manager?.allNetworks?.firstOrNull { network ->
        manager.getNetworkCapabilities(network)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    } ?: return@runCatching false
    // Rebindeár cada dos segundos movía las tomas de libndi bajo sus pies y la
    // hacía caer al volver a buscar fuentes: sólo se cambia si cambió la red.
    if (boundNetwork == wifi) return@runCatching false
    manager.bindProcessToNetwork(wifi)
    boundNetwork = wifi
    true
}.getOrDefault(false)

/** Fija los sockets a la Wi-Fi y rehace el finder si cambió la red. */
private fun ensureWifiBinding(context: Context) {
    if (bindToWifi(context)) sharedNdiFinder.restart()
}

/**
 * Red por la que sale la emisión: sin Wi-Fi no hay NDI ni SRT en la LAN. El
 * estado de multicast dice si Android esta entregando el anuncio mDNS de las
 * demas fuentes.
 */
private data class LocalNetwork(
    val wifi: Boolean,
    val address: String,
    val multicast: Boolean
) {
    val label: String
        get() = when {
            !wifi -> "datos móviles (sin Wi-Fi: no hay red local)"
            address.isBlank() -> "Wi-Fi sin dirección"
            else -> "Wi-Fi $address"
        }
}

private fun localNetwork(context: Context): LocalNetwork {
    var wifi = false
    var address = ""
    runCatching {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager
        val active = manager?.activeNetwork
        val caps = active?.let { manager.getNetworkCapabilities(it) }
        wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }
    runCatching {
        val interfaces = NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
        for (nic in interfaces) {
            for (entry in nic.inetAddresses) {
                val ip = entry as? Inet4Address ?: continue
                if (ip.isLoopbackAddress || ip.isLinkLocalAddress) continue
                address = ip.hostAddress.orEmpty()
                break
            }
            if (address.isNotBlank()) break
        }
    }
    return LocalNetwork(wifi, address, MulticastLock.held)
}


/** Error de SRT con la pista de qué espera del otro lado. */
private fun srtErrorMessage(message: String, url: String, context: Context): String {
    if (!message.startsWith("SRT:")) return message
    val listener = url.contains("mode=listener", ignoreCase = true)
    val port = url.substringAfterLast(':', "").substringBefore('?').trim()
    val net = localNetwork(context)
    return if (listener) {
        "$message · en vMix poné el input SRT en modo Caller hacia " +
            "${net.address.ifBlank { "la IP del teléfono" }}:${port.ifBlank { "puerto" }}"
    } else {
        "$message · revisá que en vMix el input SRT esté en modo Listener " +
            "en el puerto ${port.ifBlank { "de la URL" }}"
    }
}

/** Muestra u oculta las barras del sistema para el visor a pantalla completa. */
private fun setSystemBarsVisible(activity: Activity?, visible: Boolean) {
    val window = activity?.window ?: return
    val controller = WindowCompat.getInsetsController(window, window.decorView)
    controller.systemBarsBehavior =
        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    if (visible) {
        controller.show(WindowInsetsCompat.Type.systemBars())
    } else {
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }
}

private enum class Screen { CAMERA, SCREEN, MULTIMEDIA, UVC, AUDIO, MONITOR, SRT }

/** Cámara USB conectada con la app ya abierta: lleva al visor de esa fuente. */
private val usbAttachSignal = mutableIntStateOf(0)

// ------------------------------------------------------------------- raíz ---



@Composable
private fun AppRoot() {
    val context = LocalContext.current
    val activity = context as? Activity
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    // Si abrieron la app al conectar una cámara USB, arranca en esa pestaña.
    val openedFromUsb = activity?.intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED
    var screen by rememberSaveable {
        mutableStateOf(if (openedFromUsb) Screen.UVC else Screen.CAMERA)
    }
    val camera = remember { CameraUi(context.applicationContext) }

    // Permiso USB: apenas se conecta la cámara la app pide el acceso, aunque no
    // se esté en la pestaña Cámara USB. Si no se pide, el sistema no muestra el
    // aviso y el dispositivo queda sin autorizar (sólo lo abre otra app).
    val usbPermissionAction = remember { "${context.packageName}.USB_PERMISSION" }
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
                UvcCameras.list(context)
                    .filter { !it.hasPermission }
                    .forEach { UvcCameras.requestPermission(context, it, usbPermissionAction) }
            }
        }
        val filter = IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    LaunchedEffect(usbAttachSignal.intValue) {
        if (usbAttachSignal.intValue > 0) screen = Screen.UVC
    }

    // El visor de cámara ocupa toda la pantalla: en él se ocultan las barras del
    // sistema en portrait y en landscape. En el resto de pantallas se muestran.
    LaunchedEffect(screen, drawerState.isOpen) {
        setSystemBarsVisible(activity, screen != Screen.CAMERA || drawerState.isOpen)
    }
    DisposableEffect(Unit) {
        onDispose {
            setSystemBarsVisible(activity, true)
            camera.srtTx.stop()
            camera.session.stop()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            MenuSheet(
                screen = screen,
                version = camera.version,
                onSelect = { target ->
                    screen = target
                    if (target == Screen.CAMERA) {
                        camera.session.previewVisible = true
                        camera.previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
                    } else {
                        // La cámara sigue abierta mientras haya una salida en vivo:
                        // así NDI y SRT no se cortan al abrir el monitor ni otra pestaña.
                        camera.session.previewVisible = false
                        if (!camera.running && !camera.srtTxRunning) camera.stopPreview()
                    }
                    scope.launch { drawerState.close() }
                }
            )
        }
    ) {
        Box(Modifier.fillMaxSize()) {
            when (screen) {
                Screen.CAMERA -> CameraScreen(
                    camera = camera,
                    lifecycleOwner = lifecycleOwner,
                    onMenu = { scope.launch { drawerState.open() } }
                )
                Screen.SCREEN -> ScreenTab(onMenu = { scope.launch { drawerState.open() } })
                Screen.MULTIMEDIA -> MediaTab(onMenu = { scope.launch { drawerState.open() } })
                Screen.UVC -> UvcTab(onMenu = { scope.launch { drawerState.open() } })
                Screen.AUDIO -> AudioTab(onMenu = { scope.launch { drawerState.open() } })
                Screen.MONITOR -> MonitorTab(onMenu = { scope.launch { drawerState.open() } })
                Screen.SRT -> SrtTab(
                    onMenu = { scope.launch { drawerState.open() } },
                    srtUrl = camera.srtTxUrl,
                    onSrtUrl = { url -> camera.setSrtTxUrl(url) },
                    srtHeight = camera.srtHeight,
                    srtBitrate = camera.srtBitrate,
                    onSrtVideo = { height, bitrate -> camera.setSrtVideo(height, bitrate) }
                )
            }

            // Con la cámara al aire el resto de la app sigue usable: este aviso
            // recuerda qué se está emitiendo y vuelve al visor con un toque.
            if (screen != Screen.CAMERA && !pipActive.value && !monitorClean.value &&
                (camera.running || camera.srtTxRunning)
            ) {
                LivePill(
                    ndi = camera.running,
                    srt = camera.srtTxRunning,
                    detail = camera.srtTxDetail,
                    onOpen = {
                        camera.session.previewVisible = true
                        screen = Screen.CAMERA
                    },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(top = 8.dp)
                )
            }
        }
    }
}

// ------------------------------------------------------ estado de la cámara --

/** La app está en ventana flotante (picture-in-picture) mostrando el monitor. */
private val pipActive = mutableStateOf(false)

/** El monitor está en vista limpia: no se superpone ningún aviso de emisión. */
private val monitorClean = mutableStateOf(false)

/**
 * Aviso flotante de emisión: se muestra sobre las demás pestañas mientras la
 * cámara sigue transmitiendo, con el protocolo activo y el caudal de SRT.
 */
@Composable
private fun LivePill(
    ndi: Boolean,
    srt: Boolean,
    detail: String,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0xE60A1018))
            .border(1.dp, Color(0xFFFF3B30).copy(alpha = 0.65f), RoundedCornerShape(50))
            .clickable { onOpen() }
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(Color(0xFFFF3B30)))
        Text(
            buildString {
                append("EN VIVO")
                if (ndi) append(" · NDI")
                if (srt) append(" · SRT")
                if (detail.isNotBlank()) append(" · ").append(detail)
            },
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            maxLines = 1
        )
    }
}

/**
 * Silencio compartido: el chip de audio de la cámara también enmudece lo que se
 * está reproduciendo en el monitor (NDI, RTSP o SRT).
 */
private val monitorMuted = mutableStateOf(false)

/** Naranja para SRT y verde para RTSP, como se pidió en la interfaz. */
private val SrtOrange = Color(0xFFFF8A3D)
private val RtspGreen = Color(0xFF35D07F)

/** Pone la app en ventana flotante con la forma del vídeo del monitor. */
private fun enterPip(activity: Activity?, aspect: Float = 16f / 9f) {
    if (activity == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    if (activity.isInPictureInPictureMode) return
    runCatching {
        val ratio = if (aspect >= 1f) Rational(16, 9) else Rational(9, 16)
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(ratio)
            .build()
        activity.enterPictureInPictureMode(params)
    }
}

/** Último fallo no controlado, para mostrarlo en el estado de la cámara. */
private var lastCrashText: String? = null

/** Finder de NDI compartido por toda la app: no se destruye nunca. */
private val sharedNdiFinder: NdiFinder by lazy { NdiFinder() }

/** Estado compartido entre el visor y la pantalla de ajustes. */
private class CameraUi(val appContext: Context) {
    val session = CameraSession(appContext)
    val previewView = PreviewView(appContext).apply {
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        // FIT_CENTER: el visor enseña el cuadro completo que viaja por NDI, sin
        // recortarlo para llenar la pantalla alta (eso cerraba el plano).
        scaleType = PreviewView.ScaleType.FIT_CENTER
    }

    /** Superficie del pipeline Camera2 (lentes físicas que CameraX no expone). */
    val surfaceView = SurfaceView(appContext)

    /** Visor propio: dibuja los cuadros de la cámara sin recomponer la pantalla. */
    val cameraView = CameraPreviewView(appContext)

    /** Preferencias de las salidas en vivo (se configuran desde el menú). */
    val prefs = appContext.getSharedPreferences("ndi_app", Context.MODE_PRIVATE)

    /** Transmisión SRT: codifica los cuadros de la cámara y los empuja por SRT. */
    val srtTx = SrtTransmitter()
    var srtTxRunning by mutableStateOf(false)
    var srtTxUrl by mutableStateOf(prefs.getString("srt_tx", "").orEmpty())
    var srtTxStatus by mutableStateOf("")
    // Caudal real y errores de la salida SRT, para no emitir a ciegas.
    var srtTxDetail by mutableStateOf("")
    // Estado del socket y contadores de envío: dice en qué etapa se corta.
    var srtTxDiag by mutableStateOf("")
    var srtTxNotice by mutableStateOf<String?>(null)
    // Calidad del H.264 de SRT: 0 = tamaño nativo y tasa automática.
    var srtHeight by mutableIntStateOf(prefs.getInt("srt_height", 0))
    var srtBitrate by mutableIntStateOf(prefs.getInt("srt_bitrate", 0))

    init {
        session.setSurfaceView(surfaceView)
        srtTx.setVideoLimit(srtHeight, srtBitrate)
    }

    var running by mutableStateOf(false)
    var previewing by mutableStateOf(false)
    // Lente física oculta abierta con Camera2: su vista va en un SurfaceView propio.
    var camera2Active by mutableStateOf(false)
    var status by mutableStateOf("Vista previa")

    init {
        lastCrashText?.let { status = "Último fallo: $it" }
    }
    var facing by mutableIntStateOf(CameraSelector.LENS_FACING_BACK)
    var height by mutableIntStateOf(1080)
    var fps by mutableIntStateOf(30)
    // Encuadre de la fuente NDI: 16:9 recorta el sensor, 4:3 usa el sensor completo.
    var fullSensor by mutableStateOf(false)
    // La barra de zoom arranca cerrada: el visor queda despejado y la barra
    // sólo aparece cuando se pide desde el dock.
    var zoomShown by mutableStateOf(false)
    var withAudio by mutableStateOf(true)
    var muted by mutableStateOf(false)
    var grid by mutableStateOf(true)
    var lenses by mutableStateOf(emptyList<CameraLens>())
    var lensIndex by mutableIntStateOf(0)
    var recording by mutableStateOf(false)
    var recordSeconds by mutableIntStateOf(0)
    var recordNotice by mutableStateOf<String?>(null)
    var connections by mutableIntStateOf(0)
    // Relación real del cuadro que entrega la lente (alto / ancho en vertical).
    var previewAspect by mutableFloatStateOf(0f)
    var zoom by mutableFloatStateOf(1f)
    var minZoom by mutableFloatStateOf(1f)
    var maxZoom by mutableFloatStateOf(1f)
    var exposure by mutableIntStateOf(0)
    var exposureRange by mutableStateOf(0..0)
    var version by mutableStateOf("")

    // Controles pro: arrancan cerrados para no tapar el visor.
    var proOpen by mutableStateOf(false)
    var proParam by mutableStateOf(ProParam.EV)

    // ISO y obturación se manejan por separado, como en la cámara del sistema:
    // al tocar uno queda manual y el otro sigue en automático compensando el brillo.
    var isoManual by mutableStateOf(false)
    var shutterManual by mutableStateOf(false)
    var autoIso by mutableIntStateOf(400)
    var autoShutterDen by mutableIntStateOf(60)
    var isoIndex by mutableIntStateOf(0)
    var shutterIndex by mutableIntStateOf(0)
    var evIndex by mutableIntStateOf(0)
    var wbIndex by mutableIntStateOf(0)
    var focusIndex by mutableIntStateOf(0)
    var exposureStep by mutableFloatStateOf(1f / 3f)
    var isoRange by mutableStateOf(100..6400)
    var shutterDen by mutableIntStateOf(30)
    var focusManual by mutableStateOf(false)
    var focusDistance by mutableFloatStateOf(0f)
    var minFocusDistance by mutableFloatStateOf(0f)
    var wbMode by mutableIntStateOf(CaptureRequest.CONTROL_AWB_MODE_AUTO)
    var torch by mutableStateOf(false)
    var torchAvailable by mutableStateOf(false)

    /** Hay algún parámetro en manual: la exposición automática está apagada. */
    val aeManual: Boolean get() = isoManual || shutterManual
}

private fun CameraUi.refreshLens() {
    minZoom = session.minZoom.coerceIn(0.1f, 1f)
    maxZoom = session.maxZoom.coerceAtLeast(minZoom)
    exposureRange = session.exposureRange
    zoom = 1f.coerceIn(minZoom, maxZoom)
    isoRange = session.isoRange
    exposureStep = session.exposureStep
    minFocusDistance = session.minFocusDistance
    focusDistance = 0f
    isoManual = false
    shutterManual = false
    torch = false
    torchAvailable = session.hasFlash
    focusManual = false
    // En CameraX 0 es el índice neutro (0 EV), no el extremo del rango.
    evIndex = 0.coerceIn(exposureRange.first, exposureRange.last)
    exposure = evIndex
    wbIndex = 0
    focusIndex = 0
    isoIndex = isoValues().indexOf(400).takeIf { it >= 0 } ?: 0
    // Arranca en el obturador de 180°: la mitad del periodo de cuadro.
    val speeds = shutterValues()
    val preferred = speeds.minByOrNull { abs(it - fps * 2) } ?: speeds.first()
    shutterIndex = speeds.indexOf(preferred).coerceAtLeast(0)
    shutterDen = speeds[shutterIndex]
    autoShutterDen = shutterDen
    autoIso = isoValues().getOrElse(isoIndex) { 400 }
    lenses = session.rearLenses
    // La primera apertura usa la cámara trasera por defecto: el índice se ajusta
    // a la que el sensor tiene abierta de verdad, no a la primera de la lista.
    val active = lenses.indexOfFirst { it.id == session.cameraId }
    camera2Active = session.isCamera2Source
    if (session.previewAspect > 0f) previewAspect = session.previewAspect
    lensIndex = if (active >= 0) active
    else lensIndex.coerceIn(0, (lenses.size - 1).coerceAtLeast(0))
    session.setMuted(muted)
    applyPro()
}

/** Pasos fotográficos: la regla se mueve de marca en marca, sin saltos raros. */
private val isoStops = listOf(
    50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800,
    1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400
)
private fun CameraUi.isoValues(): List<Int> = isoStops.filter { it in isoRange }.ifEmpty { isoStops }

private fun CameraUi.shutterValues(): List<Int> =
    session.shutterValues(fps).ifEmpty { listOf(fps) }

private fun CameraUi.evCount(): Int =
    (exposureRange.last - exposureRange.first + 1).coerceAtLeast(1)

private fun CameraUi.wbLabel(): String =
    wbPresets[wbIndex.coerceIn(0, wbPresets.lastIndex)].first

/** ISO que se está usando: el elegido a mano o el que compensa la exposición. */
private fun CameraUi.effectiveIso(): Int {
    val stops = isoValues()
    return if (isoManual) stops[isoIndex.coerceIn(0, stops.lastIndex)]
    else autoIso.coerceIn(stops.first(), stops.last())
}

/** Denominador de obturación en uso (1/den segundos). */
private fun CameraUi.effectiveShutterDen(): Int {
    val speeds = shutterValues()
    return if (shutterManual) speeds[shutterIndex.coerceIn(0, speeds.lastIndex)]
    else autoShutterDen.coerceIn(speeds.min(), speeds.max())
}

/** Etiqueta de EV: «0» en el centro y con signo a los lados. */
private fun evText(index: Int, step: Float): String {
    val ev = index * step
    return if (abs(ev) < 0.01f) "0" else String.format("%+.1f", ev)
}

/** Rótulo de una marca de la regla: solo EV lleva números, cada 2 pasos. */
private fun CameraUi.proTickLabel(index: Int): String? {
    if (proParam != ProParam.EV) return null
    val ev = (exposureRange.first + index) * exposureStep
    val rounded = ev.roundToInt()
    if (abs(ev - rounded) > 0.02f) return null
    if (rounded % 2 != 0) return null
    return if (rounded == 0) "0" else String.format("%+d", rounded)
}

/** Cada parámetro indica si está en automático (como el icono «A» de MIUI). */
private fun CameraUi.proAuto(param: ProParam): Boolean = when (param) {
    ProParam.EV -> !aeManual
    ProParam.SHUTTER -> !shutterManual
    ProParam.ISO -> !isoManual
    ProParam.WB -> wbMode == CaptureRequest.CONTROL_AWB_MODE_AUTO
    ProParam.FOCUS -> !focusManual
}

/** Valor que muestra cada celda de la tira. */
private fun CameraUi.proValue(param: ProParam): String = when (param) {
    ProParam.EV -> if (aeManual) "—" else evText(evIndex, exposureStep)
    ProParam.SHUTTER -> "1/${effectiveShutterDen()}"
    ProParam.ISO -> effectiveIso().toString()
    ProParam.WB -> wbLabel()
    ProParam.FOCUS -> when {
        !focusManual -> "AF"
        focusDistance <= 0.01f -> "MF ∞"
        else -> String.format("MF %.2fm", 1f / focusDistance)
    }
}

private fun CameraUi.proCount(): Int = when (proParam) {
    ProParam.EV -> evCount()
    ProParam.SHUTTER -> shutterValues().size
    ProParam.ISO -> isoValues().size
    ProParam.WB -> wbPresets.size
    ProParam.FOCUS -> if (minFocusDistance > 0f) focusSteps else 1
}

private fun CameraUi.proIndex(): Int = when (proParam) {
    // La regla empieza en el extremo negativo del rango de compensación.
    ProParam.EV -> evIndex - exposureRange.first
    ProParam.SHUTTER -> shutterIndex
    ProParam.ISO -> isoIndex
    ProParam.WB -> wbIndex
    ProParam.FOCUS -> focusIndex
}

private fun CameraUi.proMin(): String = when (proParam) {
    ProParam.EV -> String.format("%+.1f", exposureRange.first * exposureStep)
    ProParam.SHUTTER -> "1/${shutterValues().first()}"
    ProParam.ISO -> isoValues().first().toString()
    ProParam.WB -> wbPresets.first().first
    ProParam.FOCUS -> "∞"
}

private fun CameraUi.proMax(): String = when (proParam) {
    ProParam.EV -> String.format("%+.1f", exposureRange.last * exposureStep)
    ProParam.SHUTTER -> "1/${shutterValues().last()}"
    ProParam.ISO -> isoValues().last().toString()
    ProParam.WB -> wbPresets.last().first
    ProParam.FOCUS ->
        if (minFocusDistance > 0f) String.format("%.1fm", 1f / minFocusDistance) else "∞"
}

/** Aplica el valor elegido en la regla al parámetro seleccionado. */
private fun CameraUi.setProIndex(value: Int) {
    when (proParam) {
        ProParam.EV -> {
            evIndex = (exposureRange.first + value)
                .coerceIn(exposureRange.first, exposureRange.last)
            applyEvComp()
        }
        ProParam.SHUTTER -> {
            shutterIndex = value
            shutterManual = true
            applyManualExposure()
        }
        ProParam.ISO -> {
            isoIndex = value
            isoManual = true
            applyManualExposure()
        }
        ProParam.WB -> {
            wbIndex = value
            applyWhiteBalance()
        }
        ProParam.FOCUS -> {
            focusIndex = value
            applyManualFocus()
        }
    }
}

private fun CameraUi.applyManualExposure() {
    val stops = isoValues()
    val speeds = shutterValues()
    isoIndex = isoIndex.coerceIn(0, stops.lastIndex)
    shutterIndex = shutterIndex.coerceIn(0, speeds.lastIndex)
    shutterDen = speeds[shutterIndex]
    autoIso = autoIso.coerceIn(stops.first(), stops.last())
    autoShutterDen = autoShutterDen.coerceIn(speeds.min(), speeds.max())
    if (!isoManual && !shutterManual) {
        // Todo automático: manda la cámara y solo se aplica la compensación EV.
        session.setManualExposure(false)
        session.setExposure(evIndex.coerceIn(exposureRange.first, exposureRange.last))
        return
    }
    session.setManualExposureValues(effectiveIso(), 1_000_000_000L / effectiveShutterDen())
}

/**
 * Cuando solo uno de los dos está en manual, el otro se ajusta solo para que el
 * cuadro no salte a negro ni a blanco (ISO u obturación automáticas).
 */
private fun CameraUi.autoExpose() {
    if (isoManual == shutterManual) return
    val luma = session.averageLuma
    if (luma <= 0.002f) return
    // 0,45 de luma equivale a un gris medio correctamente expuesto en vídeo.
    val stops = (ln(0.45f / luma) / ln(2f)).coerceIn(-0.4f, 0.4f)
    if (abs(stops) < 0.05f) return
    val factor = 2.0.pow(stops.toDouble())
    if (!isoManual) {
        val stopsList = isoValues()
        val next = (autoIso * factor).roundToInt()
            .coerceIn(stopsList.first(), stopsList.last())
        if (next == autoIso) return
        autoIso = next
    } else {
        val speeds = shutterValues()
        val ns = (1_000_000_000.0 / autoShutterDen * factor).toLong()
        val den = (1_000_000_000.0 / ns.coerceAtLeast(1L)).roundToInt()
            .coerceIn(speeds.min(), speeds.max())
        if (den == autoShutterDen) return
        autoShutterDen = den
    }
    applyManualExposure()
}

private fun CameraUi.applyEvComp() {
    isoManual = false
    shutterManual = false
    session.setManualExposure(false)
    exposure = evIndex.coerceIn(exposureRange.first, exposureRange.last)
    session.setExposure(exposure)
}

private fun CameraUi.applyWhiteBalance() {
    wbMode = wbPresets[wbIndex.coerceIn(0, wbPresets.lastIndex)].second
    session.setWhiteBalance(wbMode)
}

private fun CameraUi.applyManualFocus() {
    focusManual = true
    focusDistance = minFocusDistance * focusIndex / (focusSteps - 1).toFloat()
    session.setManualFocus(true, focusDistance)
}

/** Botón «A»: devuelve el parámetro seleccionado a automático. */
private fun CameraUi.resetProParam() {
    when (proParam) {
        ProParam.EV -> {
            evIndex = 0.coerceIn(exposureRange.first, exposureRange.last)
            exposure = evIndex
            applyEvComp()
        }
        ProParam.ISO -> {
            isoManual = false
            applyManualExposure()
        }
        ProParam.SHUTTER -> {
            shutterManual = false
            applyManualExposure()
        }
        ProParam.WB -> {
            wbIndex = 0
            applyWhiteBalance()
        }
        ProParam.FOCUS -> {
            focusManual = false
            focusDistance = 0f
            session.setManualFocus(false, 0f)
        }
    }
}

/** Vuelve a aplicar los ajustes pro tras reiniciar la sesión. */
private fun CameraUi.applyPro() {
    applyManualExposure()
    session.setManualFocus(focusManual, focusDistance)
    session.setWhiteBalance(wbMode)
}

private fun CameraUi.cameraRequest(): CameraRequest = CameraRequest(
    lensFacing = facing,
    cameraId = if (facing == CameraSelector.LENS_FACING_BACK) {
        lenses.getOrNull(lensIndex)?.id
    } else {
        null
    },
    height = height,
    fps = fps,
    withAudio = withAudio,
    fullSensor = fullSensor,
    ndiName = sessionNdiName()
)

/** Deja la cámara encendida en el visor aunque no se esté emitiendo. */
private fun CameraUi.openPreview(lifecycleOwner: LifecycleOwner) {
    if (previewing) return
    // Antes de crear los sockets de NDI, todo el proceso va por la Wi-Fi.
    ensureWifiBinding(appContext)
    // Si el lock se perdió (Wi-Fi reiniciado o un GC), se vuelve a pedir antes
    // de abrir la cámara: sin él la fuente NDI no se anuncia en la red.
    MulticastLock.ensure(appContext)
    session.onStatus = { status = it }
    session.onRecordingStarted = {
        recording = true
        recordSeconds = 0
    }
    session.onRecordingFinished = { message ->
        recording = false
        recordNotice = message
    }
    // Los rangos reales (EV, ISO, obturación) solo valen cuando la cámara ya abrió.
    session.onCameraReady = {
        Handler(Looper.getMainLooper()).post { refreshLens() }
    }
    session.onPreviewFrame = { bitmap -> cameraView.setFrame(bitmap) }
    session.onYuvFrame = { frame -> if (srtTxRunning) srtTx.send(frame) }
    // El micrófono alimenta NDI y la salida SRT a la vez.
    session.onAudioFrame = { pcm, samples, channels, rate ->
        if (srtTxRunning) srtTx.sendAudio(pcm, samples, channels, rate)
    }
    session.start(previewView, lifecycleOwner, cameraRequest())
    previewing = true
    status = "Vista previa"
    previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
    Handler(Looper.getMainLooper()).postDelayed({ refreshLens() }, 1200)
}

/** Inicia la emisión NDI reutilizando la vista previa ya abierta. */
private fun CameraUi.start(lifecycleOwner: LifecycleOwner) {
    openPreview(lifecycleOwner)
    session.onStatus = { status = it }
    running = session.startStreaming()
    if (!running) {
        status = "No se pudo iniciar la emisión"
    } else {
        // Si la salida no va por Wi-Fi, vMix no puede ver la fuente por más que
        // la app diga que está emitiendo.
        val net = localNetwork(appContext)
        status = "Enviando «${sessionNdiName()}» · ${net.label}"
    }
}

/** Corta la emisión; la cámara sigue visible en el visor. */
private fun CameraUi.stop() {
    session.stopStreaming()
    running = false
    connections = 0
    if (previewing) status = "Vista previa"
}

/** Inicia o detiene la grabación local de la vista previa. */
/** Guarda la URL de transmisión SRT: sin URL el botón no aparece. */
private fun CameraUi.setSrtTxUrl(url: String) {
    srtTxUrl = url.trim()
    prefs.edit().putString("srt_tx", srtTxUrl).apply()
}

/** Resolución y tasa de bits del H.264 de SRT; se aplican al momento. */
private fun CameraUi.setSrtVideo(height: Int, bitrate: Int) {
    srtHeight = height
    srtBitrate = bitrate
    prefs.edit().putInt("srt_height", height).putInt("srt_bitrate", bitrate).apply()
    srtTx.setVideoLimit(height, bitrate)
}

/** Arranca o corta la transmisión SRT hacia la URL configurada. */
private fun CameraUi.toggleSrtTx(lifecycleOwner: LifecycleOwner) {
    if (srtTxRunning) {
        srtTx.stop()
        srtTxRunning = false
        srtTxStatus = ""
        srtTxDetail = ""
        srtTxDiag = ""
        session.keepAudioForSrt = false
        session.releaseAudioCaptureIfIdle()
        return
    }
    if (srtTxUrl.isBlank()) return
    if (!SrtNative.available) {
        srtTxNotice = "SRT no está en este APK"
        return
    }
    openPreview(lifecycleOwner)
    // El micrófono se enciende aunque NDI esté apagado: la salida SRT lleva
    // imagen y sonido, igual que la fuente NDI.
    session.keepAudioForSrt = true
    session.ensureAudioCapture()
    srtTxDetail = ""
    srtTxDiag = ""
    srtTxNotice = null
    srtTx.onStatus = { message ->
        Handler(Looper.getMainLooper()).post { srtTxStatus = message }
    }
    srtTx.onWarning = { message ->
        Handler(Looper.getMainLooper()).post { srtTxNotice = message }
    }
    srtTx.onError = { message ->
        Handler(Looper.getMainLooper()).post {
            srtTxStatus = ""
            srtTxDetail = ""
            srtTxRunning = false
            // El aviso dice qué mirar del otro lado: casi siempre el modo del
            // input SRT de vMix está al revés del que pide la URL.
            srtTxNotice = srtErrorMessage(message, srtTxUrl, appContext)
            session.keepAudioForSrt = false
            session.releaseAudioCaptureIfIdle()
        }
    }
    srtTx.onProgress = { mbps, rate ->
        Handler(Looper.getMainLooper()).post {
            // Los MB son los bytes que el socket ya confirmó: si suben, el
            // receptor está recibiendo; si no, el corte es de red o de libsrt.
            val mb = srtTx.sentBytes / 1_000_000f
            srtTxDetail = String.format(
                java.util.Locale.US, "%.1f Mb/s · %d fps · %.1f MB", mbps, rate, mb
            )
        }
    }
    srtTx.onDiagnostics = { message ->
        Handler(Looper.getMainLooper()).post { srtTxDiag = message }
    }
    srtTx.onStopped = {
        Handler(Looper.getMainLooper()).post {
            srtTxRunning = false
            session.keepAudioForSrt = false
            session.releaseAudioCaptureIfIdle()
        }
    }
    // El tamaño del codificador sale del primer cuadro y de la calidad elegida:
    // si cambiás de lente, encuadre o calidad, se rehace solo sin cortar el socket.
    srtTx.setVideoLimit(srtHeight, srtBitrate)
    srtTx.start(srtTxUrl, fps)
    srtTxRunning = true
}

private fun CameraUi.toggleRecording() {
    if (recording) {
        session.stopRecording()
    } else if (!session.startRecording()) {
        status = "Grabación no disponible"
    }
}

/** Abre una lente trasera concreta (gran angular, principal, tele). */
private fun CameraUi.selectLens(index: Int, lifecycleOwner: LifecycleOwner) {
    if (facing != CameraSelector.LENS_FACING_BACK) return
    if (index !in lenses.indices || index == lensIndex) return
    lensIndex = index
    restart(lifecycleOwner)
}

/** Rota entre las lentes traseras (ultra gran angular, principal, tele). */
private fun CameraUi.cycleLens(lifecycleOwner: LifecycleOwner) {
    if (facing != CameraSelector.LENS_FACING_BACK || lenses.size < 2) return
    selectLens((lensIndex + 1) % lenses.size, lifecycleOwner)
}

/** Cambia el encuadre de la fuente: 16:9 recortado o 4:3 con el sensor completo. */
private fun CameraUi.setFrame(fullSensor: Boolean, lifecycleOwner: LifecycleOwner) {
    if (this.fullSensor == fullSensor) return
    this.fullSensor = fullSensor
    restart(lifecycleOwner)
}

/** Fija el aumento sobre la lente abierta (0,5× … máximo del sensor). */
private fun CameraUi.setZoomTo(ratio: Float) {
    zoom = ratio.coerceIn(minZoom, maxZoom)
    session.setZoom(zoom)
}

/**
 * Saltos de aumento del visor. Incluye el mínimo real del sensor, que en los
 * teléfonos con cámara lógica es el ultra gran angular (0,5×/0,6×).
 */
private fun CameraUi.zoomPresets(): List<Float> {
    if (facing != CameraSelector.LENS_FACING_BACK) return emptyList()
    val options = mutableListOf<Float>()
    if (minZoom < 0.95f) options += minZoom
    options += 1f
    listOf(2f, 3f, 5f).filter { it <= maxZoom + 0.02f }.forEach { options += it }
    return options.distinct().sorted()
}

/** Rótulo de aumento con coma decimal: «0,6×», «1×», «2,5×». */
private fun zoomLabel(ratio: Float): String {
    val tenths = (ratio * 10f).roundToInt()
    return if (tenths % 10 == 0) "${tenths / 10}×" else "${tenths / 10},${tenths % 10}×"
}

private fun CameraUi.stopPreview() {
    session.stop()
    previewing = false
    running = false
    status = "Fuente detenida"
}

/** Reinicia el pipeline con la nueva configuración (lente, resolución o audio). */
private fun CameraUi.restart(lifecycleOwner: LifecycleOwner) {
    if (!previewing) return
    val wasStreaming = running
    session.stop()
    previewing = false
    running = false
    status = "Reiniciando…"
    Handler(Looper.getMainLooper()).postDelayed({
        openPreview(lifecycleOwner)
        if (wasStreaming) running = session.startStreaming()
        Handler(Looper.getMainLooper()).postDelayed({ refreshLens() }, 1200)
    }, 280)
}

private fun sessionNdiName(): String = "NDI App"

// ---------------------------------------------------------------- cámara ----

private val requiredPermissions = arrayOf(
    Manifest.permission.CAMERA,
    Manifest.permission.RECORD_AUDIO
)

private fun hasCameraPermissions(context: Context): Boolean = requiredPermissions.all {
    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
}

@Composable
private fun CameraScreen(
    camera: CameraUi,
    lifecycleOwner: LifecycleOwner,
    onMenu: () -> Unit
) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasCameraPermissions(context)) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted = hasCameraPermissions(context) }
    var chrome by remember { mutableStateOf(true) }
    var focusRing by remember { mutableStateOf<Offset?>(null) }
    var netWarning by remember { mutableStateOf<String?>(null) }
    // El visor se encaja por encima del dock: se mide la distancia desde el
    // borde inferior de la pantalla hasta el dock, no el alto de las barras, así
    // el cuadro conserva su tamaño cuando se abren o cierran.
    var dockTop by remember { mutableFloatStateOf(0f) }
    var columnBottom by remember { mutableFloatStateOf(0f) }
    val bottomBars = (columnBottom - dockTop).roundToInt().coerceAtLeast(0)
    val density = LocalDensity.current

    LaunchedEffect(focusRing) {
        if (focusRing != null) {
            delay(1100)
            focusRing = null
        }
    }

    // Una fuente NDI al aire sin Wi-Fi o con el multicast apagado no llega a
    // ningún receptor: se avisa de forma fija para que no parezca que sale.
    LaunchedEffect(camera.running) {
        while (camera.running) {
            // Si el lock se perdió, se recupera antes de avisar: el aviso es
            // para cuando de verdad no hay red o no hay multicast.
            MulticastLock.ensure(context)
            val net = withContext(Dispatchers.IO) { localNetwork(context) }
            netWarning = when {
                !net.wifi -> "NDI al aire sin Wi-Fi: la fuente no llega a la red"
                !net.multicast -> "NDI al aire sin multicast: nadie puede descubrir la fuente"
                net.address.isBlank() -> "NDI al aire sin dirección de red: no puede anunciarse"
                else -> null
            }
            delay(2000)
        }
        netWarning = null
    }

    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(requiredPermissions)
        camera.version = runCatching { NdiNative.sdkVersion() }.getOrDefault("")
    }

    // La actividad no se recrea al girar (configChanges): se avisa a la cámara
    // para que los cuadros salgan derechos con la orientación nueva.
    val configuration = LocalConfiguration.current
    LaunchedEffect(configuration.orientation) {
        camera.session.onDisplayRotated()
    }

    // La cámara se abre al entrar en el visor y sigue viéndose sin emitir.
    LaunchedEffect(granted) {
        if (granted) camera.openPreview(lifecycleOwner)
    }

    // Tally: la fuente está en uso si otro equipo se conecta a ella.
    LaunchedEffect(camera.running) {
        while (camera.running) {
            camera.connections = withContext(Dispatchers.IO) { camera.session.connections() }
            delay(700)
        }
        camera.connections = 0
    }

    // ISO u obturación automáticas: el parámetro libre se ajusta solo para que
    // el cuadro no se vaya a negro ni a blanco al mover el otro.
    LaunchedEffect(camera.isoManual, camera.shutterManual, camera.previewing) {
        while (camera.isoManual != camera.shutterManual && camera.previewing) {
            delay(350)
            camera.autoExpose()
        }
    }

    // Contador de grabación en la cabecera.
    LaunchedEffect(camera.recording) {
        if (!camera.recording) return@LaunchedEffect
        camera.recordSeconds = 0
        while (true) {
            delay(1000)
            camera.recordSeconds++
        }
    }

    LaunchedEffect(camera.recordNotice) {
        if (camera.recordNotice != null) {
            delay(4200)
            camera.recordNotice = null
        }
    }

    LaunchedEffect(camera.srtTxNotice) {
        if (camera.srtTxNotice != null) {
            delay(7000)
            camera.srtTxNotice = null
        }
    }

    if (!granted) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text("Permisos necesarios", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                "NDI App necesita la cámara y el micrófono para crear la fuente NDI.",
                color = NdiGlass.Muted
            )
            Spacer(Modifier.height(16.dp))
            PrimaryAction("Conceder permisos") { launcher.launch(requiredPermissions) }
        }
        return
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        val landscape = maxWidth > maxHeight

        // El visor dibuja los mismos cuadros que van a NDI con su proporción:
        // franjas negras si sobra espacio, nunca deformación. Abajo se reserva
        // siempre el alto del dock y no el de las barras: el cuadro no cambia de
        // tamaño al abrir o cerrar la barra de zoom o la tira pro.
        AndroidView(
            factory = { camera.cameraView },
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    bottom = if (chrome && bottomBars > 0) {
                        with(density) { bottomBars.toDp() }
                    } else {
                        0.dp
                    }
                )
        )
        if (camera.grid) FramingGrid()

        // Al emitir queda una línea roja fija en el borde superior y, si otro
        // equipo está recibiendo la fuente, un tally estable debajo de la cabecera.
        if (camera.running) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(Color(0xFFFF3B30))
            )
            TallyBadge(
                active = camera.connections > 0,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(top = 54.dp)
            )
        }

        // Aviso de red, fijo sobre el visor: mientras esté visible la fuente
        // NDI no puede llegar a la LAN aunque la app diga que está emitiendo.
        netWarning?.let { message ->
            StatusPill(
                running = false,
                warning = true,
                text = message,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(top = 88.dp, start = 12.dp, end = 12.dp)
                    .widthIn(max = 360.dp)
            )
        }

        // Salida SRT: dice si conectó, el caudal real o el motivo del fallo, y
        // debajo el estado del socket con los contadores del envío.
        if (camera.srtTxRunning || camera.srtTxStatus.isNotBlank() ||
            camera.srtTxDiag.isNotBlank()
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(start = 12.dp, top = 54.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                StatusPill(
                    running = camera.srtTxRunning,
                    text = buildString {
                        append(camera.srtTxStatus.ifBlank { "SRT" })
                        if (camera.srtTxDetail.isNotBlank()) {
                            append(" · ").append(camera.srtTxDetail)
                        }
                    },
                    modifier = Modifier.widthIn(max = 340.dp)
                )
                if (camera.srtTxDiag.isNotBlank()) {
                    StatusPill(
                        running = camera.srtTxRunning,
                        text = camera.srtTxDiag,
                        modifier = Modifier.widthIn(max = 340.dp)
                    )
                }
            }
        }

        // Un toque enfoca ese punto; doble toque muestra u oculta los controles.
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { point ->
                            focusRing = point
                            camera.session.focusAt(
                                point.x / size.width.coerceAtLeast(1),
                                point.y / size.height.coerceAtLeast(1)
                            )
                        },
                        onDoubleTap = { chrome = !chrome }
                    )
                }
                .pointerInput(camera.maxZoom) {
                    // El zoom se hace con el gesto de dos dedos.
                    detectTransformGestures(panZoomLock = true) { _, _, zoomChange, _ ->
                        if (zoomChange == 1f) return@detectTransformGestures
                        val next = (camera.zoom * zoomChange)
                            .coerceIn(camera.minZoom, camera.maxZoom)
                        camera.zoom = next
                        camera.session.setZoom(next)
                    }
                }
        )

        val ring = focusRing
        if (ring != null) {
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(
                    color = NdiGlass.BlueBright,
                    radius = 30.dp.toPx(),
                    center = ring,
                    style = Stroke(width = 2.dp.toPx())
                )
                drawCircle(
                    color = Color.White.copy(alpha = 0.85f),
                    radius = 3.dp.toPx(),
                    center = ring
                )
            }
        }

        if (chrome) {
            TopBar(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing),
                recording = camera.recording,
                recordSeconds = camera.recordSeconds,
                trailing = {
                    StatusPill(
                        running = camera.running,
                        text = camera.status,
                        modifier = Modifier.widthIn(max = if (landscape) 260.dp else 170.dp)
                    )
                },
                onMenu = onMenu
            )

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 10.dp, vertical = 10.dp)
                    .onGloballyPositioned { position ->
                        columnBottom = position.boundsInRoot().bottom
                    },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                camera.recordNotice?.let { message ->
                    StatusPill(
                        running = false,
                        text = message,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                camera.srtTxNotice?.let { message ->
                    StatusPill(
                        running = false,
                        text = message,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                if (camera.zoomShown) {
                    ZoomBar(
                        camera = camera,
                        lifecycleOwner = lifecycleOwner,
                        modifier = Modifier.padding(bottom = 5.dp)
                    )
                }
                if (camera.proOpen) {
                    ProStrip(
                        camera = camera,
                        modifier = Modifier.padding(bottom = 5.dp)
                    )
                }
            ControlDock(
                modifier = Modifier.onGloballyPositioned { position ->
                    dockTop = position.boundsInRoot().top
                },
                streaming = camera.running,
                recording = camera.recording,
                grid = camera.grid,
                audio = camera.withAudio && !camera.muted,
                frontLens = camera.facing == CameraSelector.LENS_FACING_FRONT,
                quality = "${camera.height}p${camera.fps}",
                frameLabel = if (camera.fullSensor) "4:3" else "16:9",
                zoomShown = camera.zoomShown,
                lensLabel = camera.lenses.getOrNull(camera.lensIndex)?.label.orEmpty(),
                showLens = camera.facing == CameraSelector.LENS_FACING_BACK &&
                    camera.lenses.size > 1,
                torch = camera.torch,
                showTorch = camera.torchAvailable &&
                    camera.facing == CameraSelector.LENS_FACING_BACK,
                onTorch = {
                    camera.torch = !camera.torch
                    camera.session.setTorch(camera.torch)
                },
                onRecord = { camera.toggleRecording() },
                onLens = { camera.cycleLens(lifecycleOwner) },
                onGrid = { camera.grid = !camera.grid },
                onStream = {
                    if (camera.running) camera.stop() else camera.start(lifecycleOwner)
                },
                // El mismo chip silencia NDI y lo que suene en el monitor.
                onAudio = {
                    if (camera.running) {
                        camera.muted = !camera.muted
                        camera.session.setMuted(camera.muted)
                        monitorMuted.value = camera.muted
                    } else {
                        camera.withAudio = !camera.withAudio
                        monitorMuted.value = !camera.withAudio
                        // Con la cámara en vista previa el chip apaga también el
                        // micrófono de la salida SRT.
                        camera.session.setAudioEnabled(camera.withAudio)
                    }
                },
                onFlip = {
                    camera.facing = if (camera.facing == CameraSelector.LENS_FACING_BACK) {
                        CameraSelector.LENS_FACING_FRONT
                    } else {
                        CameraSelector.LENS_FACING_BACK
                    }
                    camera.restart(lifecycleOwner)
                },
                onFrame = { camera.setFrame(!camera.fullSensor, lifecycleOwner) },
                onZoom = { camera.zoomShown = !camera.zoomShown },
                onQuality = {
                    val next = when {
                        camera.height == 1080 && camera.fps == 30 -> 1080 to 60
                        camera.height == 1080 && camera.fps == 60 -> 720 to 30
                        camera.height == 720 && camera.fps == 30 -> 720 to 60
                        else -> 1080 to 30
                    }
                    camera.height = next.first
                    camera.fps = next.second
                    camera.restart(lifecycleOwner)
                },
                // Solo oculta la interfaz: la emisión sigue activa.
                onHide = { chrome = false },
                showSrt = camera.srtTxUrl.isNotBlank(),
                srtOn = camera.srtTxRunning,
                onSrt = { camera.toggleSrtTx(lifecycleOwner) },
                proOpen = camera.proOpen,
                onPro = { camera.proOpen = !camera.proOpen }
            )
            }
        }
    }
}

/** Cabecera: marca NDI a la izquierda (o el contador de grabación) y menú a la derecha. */
@Composable
private fun TopBar(
    modifier: Modifier = Modifier,
    recording: Boolean = false,
    recordSeconds: Int = 0,
    trailing: @Composable () -> Unit = {},
    onMenu: () -> Unit
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (recording) {
            RecordPill(seconds = recordSeconds)
        } else {
            NdiWordmark()
        }
        Spacer(Modifier.weight(1f))
        trailing()
        Spacer(Modifier.width(10.dp))
        MenuButton(onMenu)
    }
}

/** Contador de grabación anclado en la esquina superior izquierda. */
@Composable
private fun RecordPill(seconds: Int) {
    val blink by rememberInfiniteTransition(label = "rec").animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "recAlpha"
    )
    Row(
        Modifier
            .glassSurface(
                shape = RoundedCornerShape(50),
                tint = Color.Black,
                fillAlpha = 0.58f,
                strokeAlpha = 0.34f,
                elevation = 8
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(Color(0xFFFF3B30).copy(alpha = blink))
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "REC",
            fontSize = 11.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 0.6.sp,
            color = Color(0xFFFF6B60)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            clockText(seconds),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White
        )
    }
}

private fun clockText(seconds: Int): String {
    val safe = seconds.coerceAtLeast(0)
    return String.format("%02d:%02d", safe / 60, safe % 60)
}

/**
 * Tally fijo bajo la cabecera. Aparece solo cuando otro equipo está recibiendo
 * esta fuente NDI y se queda encendido sin parpadeo ni halo animado.
 */
@Composable
private fun TallyBadge(active: Boolean, modifier: Modifier = Modifier) {
    if (!active) return
    val red = Color(0xFFFF3B30)
    Row(
        modifier
            .glassSurface(
                shape = RoundedCornerShape(50),
                tint = red,
                fillAlpha = 0.94f,
                strokeAlpha = 0.9f,
                elevation = 8
            )
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(8.dp)
                .background(Color.White, CircleShape)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "TALLY · fuente en uso",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
    }
}

@Composable
private fun NdiWordmark() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "NDI",
            fontSize = 22.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = (-0.5).sp,
            color = Color.White
        )
        Box(
            Modifier
                .padding(start = 1.dp, bottom = 12.dp)
                .size(4.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.85f))
        )
    }
}

@Composable
private fun MenuButton(onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .glassSurface(
                shape = CircleShape,
                tint = Color.Black,
                fillAlpha = 0.45f,
                strokeAlpha = 0.32f,
                elevation = 10
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(22.dp)) {
            val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
            val lineColor = Color.White.copy(alpha = 0.92f)
            for (i in 0..2) {
                val y = size.height * (0.22f + i * 0.28f)
                drawLine(lineColor, Offset(0f, y), Offset(size.width, y), strokeWidth = stroke.width, cap = StrokeCap.Round)
            }
        }
    }
}

/**
 * Barra de zoom del visor: las lentes del equipo si las expone por separado y,
 * si no, los saltos de aumento del sensor. El pellizco sigue funcionando.
 */
@Composable
private fun ZoomBar(
    camera: CameraUi,
    lifecycleOwner: LifecycleOwner,
    modifier: Modifier = Modifier
) {
    if (camera.facing != CameraSelector.LENS_FACING_BACK) return
    val lenses = camera.lenses
    val presets = camera.zoomPresets()
    val lensMode = lenses.size > 1
    if (!lensMode && presets.size < 2 && abs(camera.maxZoom - camera.minZoom) < 0.05f) return
    Column(
        modifier
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .glassSurface(
                shape = RoundedCornerShape(18.dp),
                tint = Color.Black,
                fillAlpha = 0.62f,
                strokeAlpha = 0.32f,
                elevation = 16
            )
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("ZOOM", fontSize = 8.sp, color = NdiGlass.Muted)
            Spacer(Modifier.width(7.dp))
            Row(
                Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (lensMode) {
                    lenses.forEachIndexed { index, lens ->
                        ZoomChip(lens.label, camera.lensIndex == index) {
                            camera.selectLens(index, lifecycleOwner)
                        }
                    }
                } else {
                    presets.forEach { preset ->
                        ZoomChip(zoomLabel(preset), abs(camera.zoom - preset) < 0.06f) {
                            camera.setZoomTo(preset)
                        }
                    }
                }
            }
            Spacer(Modifier.width(6.dp))
            Text(
                zoomLabel(camera.zoom),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = NdiGlass.BlueBright
            )
        }
        // Regla continua, como el dial de la cámara del sistema: permite
        // cualquier aumento entre el mínimo (gran angular) y el máximo.
        if (camera.maxZoom - camera.minZoom >= 0.05f) {
            Slider(
                value = camera.zoom.coerceIn(camera.minZoom, camera.maxZoom),
                onValueChange = { camera.setZoomTo(it) },
                valueRange = camera.minZoom..camera.maxZoom,
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = NdiGlass.BlueBright,
                    inactiveTrackColor = Color.White.copy(alpha = 0.22f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp)
            )
        }
    }
}

/** Pastilla de una lente o de un aumento del visor. */
@Composable
private fun ZoomChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(CircleShape)
            .background(
                if (selected) NdiGlass.BlueBright.copy(alpha = 0.20f)
                else Color.White.copy(alpha = 0.10f)
            )
            .border(
                1.dp,
                if (selected) NdiGlass.BlueBright.copy(alpha = 0.85f)
                else Color.White.copy(alpha = 0.22f),
                CircleShape
            )
            .clickable { onClick() }
            .padding(horizontal = 9.dp, vertical = 3.dp)
    ) {
        Text(
            label,
            fontSize = 10.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) NdiGlass.BlueBright else Color.White.copy(alpha = 0.85f),
            maxLines = 1
        )
    }
}

/** Dock inferior con los controles de la emisión. */
@Composable
private fun ControlDock(
    modifier: Modifier = Modifier,
    streaming: Boolean,
    recording: Boolean,
    grid: Boolean,
    audio: Boolean,
    frontLens: Boolean,
    quality: String,
    frameLabel: String,
    zoomShown: Boolean,
    lensLabel: String,
    showLens: Boolean,
    torch: Boolean,
    showTorch: Boolean,
    onRecord: () -> Unit,
    onLens: () -> Unit,
    onTorch: () -> Unit,
    onGrid: () -> Unit,
    onStream: () -> Unit,
    onAudio: () -> Unit,
    onFlip: () -> Unit,
    onQuality: () -> Unit,
    onFrame: () -> Unit,
    onZoom: () -> Unit,
    onHide: () -> Unit,
    proOpen: Boolean,
    onPro: () -> Unit,
    showSrt: Boolean,
    srtOn: Boolean,
    onSrt: () -> Unit
) {
    // En landscape hay sitio de sobra: el dock se limita a un ancho cómodo y
    // reparte las acciones a partes iguales para que no queden apretadas.
    // En portrait no cabe todo, así que se mantiene desplazable en horizontal.
    BoxWithConstraints(
        modifier
            .widthIn(max = 600.dp)
            .fillMaxWidth()
            .glassSurface(
                shape = RoundedCornerShape(24.dp),
                tint = Color.Black,
                fillAlpha = 0.5f,
                strokeAlpha = 0.3f,
                elevation = 16
            )
            .padding(horizontal = 6.dp, vertical = 6.dp)
    ) {
        val wide = maxWidth >= 500.dp
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (wide) Modifier else Modifier.horizontalScroll(rememberScrollState())),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DockItems(
                wide = wide,
                streaming = streaming,
                recording = recording,
                grid = grid,
                audio = audio,
                frontLens = frontLens,
                quality = quality,
                frameLabel = frameLabel,
                zoomShown = zoomShown,
                lensLabel = lensLabel,
                showLens = showLens,
                torch = torch,
                showTorch = showTorch,
                onRecord = onRecord,
                onLens = onLens,
                onTorch = onTorch,
                onGrid = onGrid,
                onStream = onStream,
                onAudio = onAudio,
                onFlip = onFlip,
                onQuality = onQuality,
                onFrame = onFrame,
                onZoom = onZoom,
                onHide = onHide,
                proOpen = proOpen,
                onPro = onPro,
                showSrt = showSrt,
                srtOn = srtOn,
                onSrt = onSrt
            )
        }
    }
}

/** Fila de acciones del dock; `wide` reparte el ancho disponible a partes iguales. */
@Composable
private fun RowScope.DockItems(
    wide: Boolean,
    streaming: Boolean,
    recording: Boolean,
    grid: Boolean,
    audio: Boolean,
    frontLens: Boolean,
    quality: String,
    frameLabel: String,
    zoomShown: Boolean,
    lensLabel: String,
    showLens: Boolean,
    torch: Boolean,
    showTorch: Boolean,
    onRecord: () -> Unit,
    onLens: () -> Unit,
    onTorch: () -> Unit,
    onGrid: () -> Unit,
    onStream: () -> Unit,
    onAudio: () -> Unit,
    onFlip: () -> Unit,
    onQuality: () -> Unit,
    onFrame: () -> Unit,
    onZoom: () -> Unit,
    onHide: () -> Unit,
    proOpen: Boolean,
    onPro: () -> Unit,
    showSrt: Boolean,
    srtOn: Boolean,
    onSrt: () -> Unit
) {
    val slot = if (wide) Modifier.weight(1f) else Modifier
    DockAction(DockIcon.GRID, "Grilla", grid, wide, slot, onGrid)
    StreamButton(streaming, wide, slot, onStream)
    // Solo aparece cuando hay una salida SRT configurada.
    if (showSrt) TxButton("SRT", srtOn, SrtOrange, wide, slot, onSrt)
    RecordButton(recording, wide, slot, onRecord)
    DockAction(DockIcon.MIC, "Mic", audio, wide, slot, onAudio)
    if (showTorch) {
        DockAction(DockIcon.TORCH, "Linterna", torch, wide, slot, onTorch)
    }
    if (showLens) {
        DockAction(DockIcon.LENS, lensLabel, true, wide, slot, onLens)
    }
    DockAction(DockIcon.FLIP, if (frontLens) "Frontal" else "Girar", frontLens, wide, slot, onFlip)
    DockAction(DockIcon.QUALITY, quality, false, wide, slot, onQuality)
    DockAction(DockIcon.ASPECT, frameLabel, false, wide, slot, onFrame)
    DockAction(DockIcon.ZOOM, "Zoom", zoomShown, wide, slot, onZoom)
    DockAction(DockIcon.HIDE, "Ocultar", false, wide, slot, onHide)
    DockAction(DockIcon.SETTINGS, "Pro", proOpen, wide, slot, onPro)
}

/** Grabación local: punto rojo en reposo, cuadrado rojo mientras graba. */
@Composable
private fun RecordButton(
    recording: Boolean,
    wide: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier
            .then(if (wide) Modifier else Modifier.width(60.dp))
            .clip(RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.size(if (wide) 24.dp else 22.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(if (wide) 22.dp else 20.dp)) {
                val red = Color(0xFFFF3B30)
                if (recording) {
                    val side = size.minDimension * 0.62f
                    drawRoundRect(
                        color = red,
                        topLeft = Offset((size.width - side) / 2f, (size.height - side) / 2f),
                        size = Size(side, side),
                        cornerRadius = CornerRadius(3.dp.toPx())
                    )
                } else {
                    drawCircle(color = red, radius = size.minDimension / 2f)
                }
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(
            if (recording) "Parar" else "Grabar",
            fontSize = if (wide) 10.sp else 9.sp,
            maxLines = 1,
            color = if (recording) Color(0xFFFF6B60) else Color.White.copy(alpha = 0.72f)
        )
    }
}

private enum class DockIcon { GRID, MIC, TORCH, LENS, FLIP, QUALITY, ASPECT, ZOOM, HIDE, SETTINGS, MEDIA }

@Composable
private fun DockAction(
    icon: DockIcon,
    label: String,
    active: Boolean,
    wide: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val tint = if (active) NdiGlass.BlueBright else Color.White.copy(alpha = 0.86f)
    val iconSize = if (wide) 24.dp else 22.dp
    Column(
        modifier
            .then(if (wide) Modifier else Modifier.width(if (icon == DockIcon.QUALITY) 64.dp else 60.dp))
            .clip(RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (icon == DockIcon.QUALITY) {
            Box(Modifier.size(iconSize), contentAlignment = Alignment.Center) {
                Text(
                    "FPS",
                    fontSize = if (wide) 8.sp else 7.sp,
                    fontWeight = FontWeight.Bold,
                    color = tint.copy(alpha = 0.9f)
                )
            }
        } else {
            Canvas(Modifier.size(iconSize)) { drawDockIcon(icon, tint) }
        }
        Spacer(Modifier.height(3.dp))
        Text(
            label,
            fontSize = if (wide) 10.sp else 9.sp,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            color = if (active) NdiGlass.BlueBright else Color.White.copy(alpha = 0.72f)
        )
    }
}

/** Botón central NDI que inicia o detiene la emisión. */
@Composable
private fun StreamButton(
    streaming: Boolean,
    wide: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier
            .then(if (wide) Modifier else Modifier.width(78.dp))
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick() }
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .width(if (wide) 72.dp else 60.dp)
                .height(if (wide) 38.dp else 34.dp)
                .then(
                    if (streaming) Modifier.shadowGlassBlue(RoundedCornerShape(12.dp))
                    else Modifier
                )
                .clip(RoundedCornerShape(12.dp))
                .background(
                    if (streaming) {
                        Brush.verticalGradient(listOf(Color(0xFF79C8FF), Color(0xFF1D78DE)))
                    } else {
                        Brush.verticalGradient(
                            listOf(
                                Color.White.copy(alpha = 0.18f),
                                Color.White.copy(alpha = 0.06f)
                            )
                        )
                    }
                )
                .border(
                    1.dp,
                    if (streaming) NdiGlass.BlueBright.copy(alpha = 0.9f)
                    else Color.White.copy(alpha = 0.28f),
                    RoundedCornerShape(12.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "NDI",
                fontSize = 15.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = (-0.5).sp,
                color = if (streaming) Color(0xFF03121F) else Color.White.copy(alpha = 0.9f)
            )
        }
        Spacer(Modifier.height(3.dp))
        Text(
            if (streaming) "Detener" else "Emitir",
            fontSize = if (wide) 10.sp else 9.sp,
            fontWeight = if (streaming) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            softWrap = false,
            color = if (streaming) NdiGlass.BlueBright else Color.White.copy(alpha = 0.72f)
        )
    }
}

/** Botón de salida en vivo: SRT en naranja, RTSP en verde. */
@Composable
private fun TxButton(
    label: String,
    active: Boolean,
    accent: Color,
    wide: Boolean,
    slot: Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier = slot
            .then(if (wide) Modifier else Modifier.width(62.dp))
            .clip(RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(if (wide) 42.dp else 38.dp)
                .background(
                    if (active) accent else accent.copy(alpha = 0.16f),
                    RoundedCornerShape(12.dp)
                )
                .border(
                    1.dp,
                    accent.copy(alpha = if (active) 0.95f else 0.5f),
                    RoundedCornerShape(12.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                label,
                fontSize = 12.sp,
                fontWeight = FontWeight.Black,
                color = if (active) Color(0xFF10161C) else accent
            )
        }
        Spacer(Modifier.height(3.dp))
        Text(
            if (active) "Detener" else "Enviar",
            fontSize = if (wide) 10.sp else 9.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            softWrap = false,
            color = if (active) accent else Color.White.copy(alpha = 0.72f)
        )
    }
}

private fun DrawScope.drawDockIcon(icon: DockIcon, color: Color) {
    val w = size.width
    val h = size.height
    val stroke = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round)
    when (icon) {
        // Rayo: linterna del teléfono.
        DockIcon.TORCH -> {
            val bolt = Path().apply {
                moveTo(w * 0.60f, h * 0.06f)
                lineTo(w * 0.24f, h * 0.56f)
                lineTo(w * 0.46f, h * 0.56f)
                lineTo(w * 0.38f, h * 0.94f)
                lineTo(w * 0.78f, h * 0.42f)
                lineTo(w * 0.55f, h * 0.42f)
                close()
            }
            drawPath(bolt, color)
        }

        DockIcon.LENS -> {
            drawCircle(
                color = color,
                radius = w * 0.36f,
                center = Offset(w * 0.5f, h * 0.5f),
                style = stroke
            )
            drawCircle(
                color = color,
                radius = w * 0.15f,
                center = Offset(w * 0.5f, h * 0.5f),
                style = stroke
            )
            drawCircle(color, radius = 1.6.dp.toPx(), center = Offset(w * 0.5f, h * 0.5f))
        }

        DockIcon.GRID -> {
            drawRoundRect(
                color = color,
                topLeft = Offset(w * 0.16f, h * 0.16f),
                size = Size(w * 0.68f, h * 0.68f),
                cornerRadius = CornerRadius(w * 0.08f),
                style = stroke
            )
            for (i in 1..2) {
                val x = w * (0.16f + 0.68f * i / 3f)
                val y = h * (0.16f + 0.68f * i / 3f)
                drawLine(color, Offset(x, h * 0.16f), Offset(x, h * 0.84f), strokeWidth = stroke.width)
                drawLine(color, Offset(w * 0.16f, y), Offset(w * 0.84f, y), strokeWidth = stroke.width)
            }
        }

        DockIcon.MIC -> {
            drawRoundRect(
                color = color,
                topLeft = Offset(w * 0.38f, h * 0.1f),
                size = Size(w * 0.24f, h * 0.42f),
                cornerRadius = CornerRadius(w * 0.12f),
                style = stroke
            )
            drawArc(
                color = color,
                startAngle = 0f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(w * 0.26f, h * 0.3f),
                size = Size(w * 0.48f, h * 0.4f),
                style = stroke
            )
            drawLine(color, Offset(w * 0.5f, h * 0.7f), Offset(w * 0.5f, h * 0.88f), strokeWidth = stroke.width)
            drawLine(color, Offset(w * 0.36f, h * 0.88f), Offset(w * 0.64f, h * 0.88f), strokeWidth = stroke.width)
        }

        // Dos flechas en sentidos opuestos: cambiar de cámara.
        DockIcon.FLIP -> {
            drawLine(color, Offset(w * 0.84f, h * 0.3f), Offset(w * 0.24f, h * 0.3f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            drawLine(color, Offset(w * 0.24f, h * 0.3f), Offset(w * 0.42f, h * 0.14f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            drawLine(color, Offset(w * 0.24f, h * 0.3f), Offset(w * 0.42f, h * 0.46f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            drawLine(color, Offset(w * 0.16f, h * 0.7f), Offset(w * 0.76f, h * 0.7f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            drawLine(color, Offset(w * 0.76f, h * 0.7f), Offset(w * 0.58f, h * 0.54f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            drawLine(color, Offset(w * 0.76f, h * 0.7f), Offset(w * 0.58f, h * 0.86f), strokeWidth = stroke.width, cap = StrokeCap.Round)
        }

        DockIcon.QUALITY -> Unit

        // Marco 16:9 y, dentro, la zona 4:3: encuadre de la fuente.
        DockIcon.ASPECT -> {
            drawRect(
                color.copy(alpha = 0.5f),
                topLeft = Offset(w * 0.05f, h * 0.24f),
                size = Size(w * 0.9f, h * 0.52f),
                style = Stroke(width = stroke.width)
            )
            drawRect(
                color,
                topLeft = Offset(w * 0.22f, h * 0.24f),
                size = Size(w * 0.56f, h * 0.52f),
                style = Stroke(width = stroke.width)
            )
        }

        // Lupa con el signo +: barra de zoom del visor.
        DockIcon.ZOOM -> {
            drawCircle(
                color,
                radius = w * 0.28f,
                center = Offset(w * 0.42f, h * 0.42f),
                style = Stroke(width = stroke.width)
            )
            drawLine(
                color,
                Offset(w * 0.63f, h * 0.63f),
                Offset(w * 0.9f, h * 0.9f),
                strokeWidth = stroke.width,
                cap = StrokeCap.Round
            )
            drawLine(
                color,
                Offset(w * 0.26f, h * 0.42f),
                Offset(w * 0.58f, h * 0.42f),
                strokeWidth = stroke.width,
                cap = StrokeCap.Round
            )
            drawLine(
                color,
                Offset(w * 0.42f, h * 0.26f),
                Offset(w * 0.42f, h * 0.58f),
                strokeWidth = stroke.width,
                cap = StrokeCap.Round
            )
        }

        DockIcon.SETTINGS -> {
            for (i in 0..2) {
                val y = h * (0.25f + i * 0.25f)
                drawLine(color, Offset(w * 0.12f, y), Offset(w * 0.88f, y), strokeWidth = stroke.width)
                val knobX = w * (if (i == 0) 0.35f else if (i == 1) 0.68f else 0.46f)
                drawCircle(color, radius = 2.6.dp.toPx(), center = Offset(knobX, y))
            }
        }

        // Triángulo de reproducción: menú multimedia.
        DockIcon.MEDIA -> {
            val play = Path().apply {
                moveTo(w * 0.32f, h * 0.16f)
                lineTo(w * 0.82f, h * 0.5f)
                lineTo(w * 0.32f, h * 0.84f)
                close()
            }
            drawPath(play, color)
        }

        DockIcon.HIDE -> {
            drawOval(
                color = color,
                topLeft = Offset(w * 0.1f, h * 0.3f),
                size = Size(w * 0.8f, h * 0.4f),
                style = stroke
            )
            drawCircle(color, radius = w * 0.11f, center = Offset(w * 0.5f, h * 0.5f))
            drawLine(color, Offset(w * 0.18f, h * 0.84f), Offset(w * 0.82f, h * 0.16f), strokeWidth = stroke.width)
        }
    }
}

// ------------------------------------------------------------ controles pro --

/**
 * Presets de balance de blancos, de cálido a frío. Son los modos que ofrece
 * Camera2: no hay valores intermedios reales, así que se usan todos los que
 * tienen efecto para que la regla tenga el máximo recorrido posible.
 * El Kelvin es la temperatura aproximada de cada preset.
 */
private val wbPresets = listOf(
    "Auto" to CaptureRequest.CONTROL_AWB_MODE_AUTO,
    "2850K" to CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT,
    "2950K" to CaptureRequest.CONTROL_AWB_MODE_WARM_FLUORESCENT,
    "3800K" to CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT,
    "5500K" to CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT,
    "6500K" to CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT,
    "7000K" to CaptureRequest.CONTROL_AWB_MODE_TWILIGHT,
    "7500K" to CaptureRequest.CONTROL_AWB_MODE_SHADE
)

/** Marcas de la regla de foco manual: 0 = infinito, el resto hacia el mínimo. */
private const val focusSteps = 41

/** Parámetros que se eligen desde la tira y se ajustan con la regla. */
private enum class ProParam(val label: String) {
    EV("EV"),
    SHUTTER("s"),
    ISO("ISO"),
    WB("WB"),
    FOCUS("F")
}

private val proParams =
    listOf(ProParam.EV, ProParam.SHUTTER, ProParam.ISO, ProParam.WB, ProParam.FOCUS)

/**
 * Tira pro del visor: los cinco parámetros siempre a la vista y, debajo, una
 * regla con marcas para ajustarlos con precisión mientras se emite.
 */
@Composable
private fun ProStrip(camera: CameraUi, modifier: Modifier = Modifier) {
    Column(
        modifier
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .glassSurface(
                shape = RoundedCornerShape(20.dp),
                tint = Color.Black,
                fillAlpha = 0.66f,
                strokeAlpha = 0.34f,
                elevation = 18
            )
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            proParams.forEach { param ->
                ProCell(
                    label = param.label,
                    value = camera.proValue(param),
                    auto = camera.proAuto(param),
                    selected = camera.proParam == param,
                    modifier = Modifier.weight(1f)
                ) { camera.proParam = param }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            AutoButton { camera.resetProParam() }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                ProRuler(
                    index = camera.proIndex().coerceIn(0, (camera.proCount() - 1).coerceAtLeast(0)),
                    count = camera.proCount(),
                    labelFor = { camera.proTickLabel(it) },
                    onChange = { camera.setProIndex(it) },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(camera.proMin(), fontSize = 8.sp, color = NdiGlass.Muted)
                    Text(camera.proMax(), fontSize = 8.sp, color = NdiGlass.Muted)
                }
            }
        }
    }
}

@Composable
private fun ProCell(
    label: String,
    value: String,
    auto: Boolean,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(vertical = 1.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, fontSize = 8.sp, color = NdiGlass.Muted)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                value,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) NdiGlass.BlueBright else Color.White.copy(alpha = 0.9f),
                maxLines = 1
            )
            // Insignia de automático, como el «A» de la cámara del sistema.
            if (auto) {
                Spacer(Modifier.width(2.dp))
                Text(
                    "A",
                    fontSize = 7.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.55f)
                )
            }
        }
    }
}

/** Botón «A» que devuelve el parámetro elegido a automático. */
@Composable
private fun AutoButton(onClick: () -> Unit) {
    Box(
        Modifier
            .size(26.dp)
            .glassSurface(
                shape = CircleShape,
                tint = Color.White,
                fillAlpha = 0.12f,
                strokeAlpha = 0.24f,
                elevation = 0
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            "A",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = NdiGlass.BlueBright
        )
    }
}

/** Regla de precisión: la marca seleccionada queda centrada bajo la aguja. */
@Composable
private fun ProRuler(
    index: Int,
    count: Int,
    labelFor: (Int) -> String?,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val latestIndex by rememberUpdatedState(index)
    val latestOnChange by rememberUpdatedState(onChange)
    var dragAccum by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val tickDp = 16.dp

    BoxWithConstraints(modifier.height(46.dp)) {
        val tickPx = with(density) { tickDp.toPx() }
        val widthPx = constraints.maxWidth.toFloat()
        val cx = widthPx / 2f
        val slots = (widthPx / tickPx / 2f).toInt() + 1

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(count) {
                    detectTapGestures { position ->
                        val tick = tickDp.toPx()
                        val delta = ((position.x - size.width / 2f) / tick).roundToInt()
                        latestOnChange((latestIndex + delta).coerceIn(0, count - 1))
                    }
                }
                .pointerInput(count) {
                    detectHorizontalDragGestures { change, dragAmount ->
                        change.consume()
                        val tick = tickDp.toPx()
                        dragAccum += dragAmount
                        val steps = (dragAccum / tick).toInt()
                        if (steps != 0) {
                            dragAccum -= steps * tick
                            latestOnChange((latestIndex - steps).coerceIn(0, count - 1))
                        }
                    }
                }
        ) {
            val top = 13.dp.toPx()
            for (i in (index - slots)..(index + slots)) {
                if (i < 0 || i >= count) continue
                val x = cx + (i - index) * tickPx
                if (x < 0f || x > size.width) continue
                val major = i % 5 == 0
                val lineHeight = if (major) 11.dp.toPx() else 6.dp.toPx()
                drawLine(
                    color = when {
                        i == index -> NdiGlass.BlueBright
                        major -> Color.White.copy(alpha = 0.55f)
                        else -> Color.White.copy(alpha = 0.28f)
                    },
                    start = Offset(x, size.height - lineHeight),
                    end = Offset(x, size.height),
                    strokeWidth = if (i == index) 2.dp.toPx() else 1.dp.toPx()
                )
            }
            drawLine(
                color = NdiGlass.Blue.copy(alpha = 0.9f),
                start = Offset(cx, top),
                end = Offset(cx, size.height - 15.dp.toPx()),
                strokeWidth = 2.dp.toPx()
            )
        }

        // Rótulos de la regla (en EV: -4, -2, 0, +2, +4).
        val labelWidth = 40.dp
        for (i in (index - slots)..(index + slots)) {
            if (i < 0 || i >= count) continue
            val text = labelFor(i) ?: continue
            val x = cx + (i - index) * tickPx
            val half = with(density) { labelWidth.toPx() } / 2f
            if (x - half < 0f || x + half > widthPx) continue
            Text(
                text,
                fontSize = 8.sp,
                maxLines = 1,
                textAlign = TextAlign.Center,
                color = if (i == index) NdiGlass.BlueBright else NdiGlass.Muted,
                modifier = Modifier
                    .width(labelWidth)
                    .offset { IntOffset((x - half).roundToInt(), 0) }
            )
        }
    }
}

/** Zoom vertical de la izquierda, como el control de la app oficial. */
@Composable
private fun VerticalZoom(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    onChange: (Float) -> Unit
) {
    val span = (range.endInclusive - range.start).takeIf { it > 0f } ?: 1f
    val progress = ((value - range.start) / span).coerceIn(0f, 1f)

    Column(
        modifier
            .width(44.dp)
            .glassSurface(
                shape = RoundedCornerShape(50),
                tint = Color.Black,
                fillAlpha = 0.42f,
                strokeAlpha = 0.3f,
                elevation = 12
            )
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "+",
            fontSize = 16.sp,
            color = Color.White.copy(alpha = 0.85f)
        )
        Box(
            Modifier
                .weight(1f)
                .width(26.dp)
                .padding(vertical = 8.dp)
                .pointerInput(range) {
                    fun apply(y: Float) {
                        val p = (1f - (y / size.height)).coerceIn(0f, 1f)
                        onChange(range.start + p * span)
                    }
                    detectDragGestures(
                        onDragStart = { apply(it.y) },
                        onDrag = { change, _ -> apply(change.position.y) }
                    )
                }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val cx = size.width / 2f
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.16f),
                    topLeft = Offset(cx - 2.dp.toPx(), 0f),
                    size = Size(4.dp.toPx(), size.height),
                    cornerRadius = CornerRadius(2.dp.toPx())
                )
                val thumbY = (1f - progress) * size.height
                drawRoundRect(
                    color = NdiGlass.Blue,
                    topLeft = Offset(cx - 2.dp.toPx(), thumbY),
                    size = Size(4.dp.toPx(), (size.height - thumbY).coerceAtLeast(0f)),
                    cornerRadius = CornerRadius(2.dp.toPx())
                )
                drawCircle(NdiGlass.BlueBright, radius = 7.dp.toPx(), center = Offset(cx, thumbY))
                drawCircle(Color.White, radius = 3.dp.toPx(), center = Offset(cx, thumbY))
            }
        }
        Text(
            "−",
            fontSize = 16.sp,
            color = Color.White.copy(alpha = 0.85f)
        )
    }
}

// ------------------------------------------------------------------ menú ----

@Composable
private fun MenuSheet(
    screen: Screen,
    version: String,
    onSelect: (Screen) -> Unit
) {
    ModalDrawerSheet(
        modifier = Modifier.width(300.dp),
        drawerShape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp),
        drawerContainerColor = Color(0xFF080D14).copy(alpha = 0.98f),
        drawerContentColor = NdiGlass.Text
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 16.dp, vertical = 20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                NdiWordmark()
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        "NDI App",
                        style = MaterialTheme.typography.titleMedium,
                        color = NdiGlass.Text
                    )
                    Text(
                        if (version.isBlank()) "Cámara y pantalla NDI" else "SDK $version",
                        style = MaterialTheme.typography.labelSmall,
                        color = NdiGlass.Muted
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
            Spacer(Modifier.height(12.dp))

            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                MenuEntry(
                    icon = DockIcon.HIDE,
                    title = "Monitor NDI",
                    subtitle = "Ver las fuentes NDI de la red",
                    selected = screen == Screen.MONITOR
                ) { onSelect(Screen.MONITOR) }
                MenuEntry(
                    icon = DockIcon.MIC,
                    title = "Cámara",
                    subtitle = "Transmitir la cámara como fuente NDI",
                    selected = screen == Screen.CAMERA
                ) { onSelect(Screen.CAMERA) }
                MenuEntry(
                    icon = DockIcon.GRID,
                    title = "Pantalla",
                    subtitle = "Compartir la pantalla con audio del sistema",
                    selected = screen == Screen.SCREEN
                ) { onSelect(Screen.SCREEN) }
                MenuEntry(
                    icon = DockIcon.MEDIA,
                    title = "Multimedia",
                    subtitle = "Archivos por NDI y cámaras USB",
                    selected = screen == Screen.MULTIMEDIA
                ) { onSelect(Screen.MULTIMEDIA) }
                MenuEntry(
                    icon = DockIcon.MIC,
                    title = "Audio",
                    subtitle = "Micrófono o archivo como fuente de audio",
                    selected = screen == Screen.AUDIO
                ) { onSelect(Screen.AUDIO) }
                MenuEntry(
                    icon = DockIcon.MEDIA,
                    title = "Captura UVC",
                    subtitle = "Cámara USB como fuente NDI",
                    selected = screen == Screen.UVC
                ) { onSelect(Screen.UVC) }
                MenuEntry(
                    icon = DockIcon.SETTINGS,
                    title = "SRT",
                    subtitle = "URL de la salida SRT de la cámara",
                    selected = screen == Screen.SRT
                ) { onSelect(Screen.SRT) }
            }
            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
            Spacer(Modifier.height(10.dp))
            Text(
                "NDI® es una marca registrada de Vizrt NDI AB.",
                style = MaterialTheme.typography.labelSmall,
                color = NdiGlass.Muted.copy(alpha = 0.8f)
            )
        }
    }
}

/**
 * Configuración de la salida SRT desde el menú. La URL se guarda y con eso
 * aparece el botón naranja en la cámara; sin URL no hay botón.
 */
@Composable
private fun OutboundPanel(srtUrl: String, onSrtUrl: (String) -> Unit) {
    var draft by remember(srtUrl) { mutableStateOf(srtUrl) }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Salida SRT",
                style = MaterialTheme.typography.labelLarge,
                color = SrtOrange,
                modifier = Modifier.weight(1f)
            )
            GlassChip("Guardar", draft.trim() == srtUrl, accent = SrtOrange) {
                onSrtUrl(draft)
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .glassSurface(
                    shape = RoundedCornerShape(14.dp),
                    fillAlpha = 0.08f,
                    elevation = 3
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.weight(1f)) {
                if (draft.isEmpty()) {
                    Text(
                        "srt://host:9000?mode=caller",
                        color = NdiGlass.Muted,
                        fontSize = 13.sp
                    )
                }
                androidx.compose.foundation.text.BasicTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = NdiGlass.Text,
                        fontSize = 13.sp
                    ),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(SrtOrange)
                )
            }
        }
        Text(
            "H.264 y AAC (micrófono) en MPEG-TS. Con la URL guardada aparece el botón "
                + "naranja en la cámara: ?mode=caller cuando el receptor escucha (vMix) "
                + "y ?mode=listener cuando el receptor se conecta al teléfono.",
            style = MaterialTheme.typography.labelSmall,
            color = NdiGlass.Muted
        )
    }
}

@Composable
private fun MenuEntry(
    icon: DockIcon,
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(shape)
            .background(
                if (selected) NdiGlass.Blue.copy(alpha = 0.16f)
                else Color.Transparent
            )
            .border(
                1.dp,
                if (selected) NdiGlass.Blue.copy(alpha = 0.45f) else Color.Transparent,
                shape
            )
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(38.dp)
                .glassSurface(
                    shape = RoundedCornerShape(12.dp),
                    tint = Color.White,
                    fillAlpha = if (selected) 0.2f else 0.1f,
                    strokeAlpha = 0.16f,
                    elevation = 0
                ),
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.size(20.dp)) {
                drawDockIcon(icon, if (selected) NdiGlass.BlueBright else Color.White.copy(alpha = 0.8f))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = if (selected) NdiGlass.BlueBright else NdiGlass.Text
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = NdiGlass.Muted,
                maxLines = 2
            )
        }
    }
}

// --------------------------------------------------------------- componentes --

@Composable
private fun GlassPanel(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier
            .fillMaxWidth()
            .glassSurface()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (title != null) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = NdiGlass.BlueBright
            )
        }
        content()
    }
}

@Composable
private fun GlassChip(
    label: String,
    active: Boolean,
    enabled: Boolean = true,
    accent: Color? = null,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(50)
    val highlight = accent ?: NdiGlass.BlueBright
    Row(
        Modifier
            .clip(shape)
            .background(
                if (active) {
                    Brush.verticalGradient(
                        if (accent != null) {
                            listOf(accent.copy(alpha = 0.95f), accent.copy(alpha = 0.72f))
                        } else {
                            listOf(Color(0xFF66BAFF), NdiGlass.Blue)
                        }
                    )
                } else {
                    Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.16f),
                            Color.White.copy(alpha = 0.05f)
                        )
                    )
                }
            )
            .border(
                1.dp,
                if (active) highlight.copy(alpha = 0.9f)
                else Color.White.copy(alpha = 0.22f),
                shape
            )
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            color = if (active) Color(0xFF02121F)
            else Color.White.copy(alpha = if (enabled) 0.86f else 0.35f)
        )
    }
}

@Composable
private fun PrimaryAction(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier
            .fillMaxWidth()
            .height(52.dp)
            .shadowGlassBlue(shape)
            .clip(shape)
            .background(
                Brush.verticalGradient(listOf(Color(0xFF79C8FF), Color(0xFF1D78DE)))
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF03121F)
        )
    }
}

private fun Modifier.shadowGlassBlue(shape: androidx.compose.ui.graphics.Shape): Modifier =
    this.shadow(
        elevation = 20.dp,
        shape = shape,
        clip = false,
        ambientColor = NdiGlass.Blue.copy(alpha = 0.55f),
        spotColor = NdiGlass.Blue.copy(alpha = 0.4f)
    )

@Composable
private fun FramingGrid() {
    val lineColor = Color.White.copy(alpha = 0.3f)
    Canvas(Modifier.fillMaxSize()) {
        val dx = size.width / 3f
        val dy = size.height / 3f
        for (i in 1..2) {
            drawLine(lineColor, Offset(dx * i, 0f), Offset(dx * i, size.height), strokeWidth = 2f)
            drawLine(lineColor, Offset(0f, dy * i), Offset(size.width, dy * i), strokeWidth = 2f)
        }
    }
}

@Composable
private fun StatusPill(
    running: Boolean,
    text: String,
    modifier: Modifier = Modifier,
    warning: Boolean = false
) {
    Row(
        modifier
            .glassSurface(
                shape = RoundedCornerShape(50),
                tint = Color.Black,
                fillAlpha = 0.46f,
                strokeAlpha = 0.32f,
                elevation = 8
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(
                    when {
                        warning -> Color(0xFFFFB020)
                        running -> Color(0xFF4ADE80)
                        else -> NdiGlass.Muted
                    }
                )
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = NdiGlass.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueText: String,
    onChange: (Float) -> Unit
) {
    Column {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = NdiGlass.Text)
            Text(
                valueText,
                style = MaterialTheme.typography.labelLarge,
                color = NdiGlass.BlueBright
            )
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range
        )
    }
}

// ---------------------------------------------------------------- pantalla --

@Composable
private fun ScreenTab(onMenu: () -> Unit) {
    val context = LocalContext.current
    // El servicio de captura es la fuente de verdad: si sigue emitiendo, la pestaña
    // lo muestra aunque la actividad se haya recreado o se haya cambiado de sección.
    val streaming = ScreenCaptureService.running.value
    var height by remember { mutableIntStateOf(1080) }
    var audioMode by remember { mutableIntStateOf(ScreenCaptureService.AUDIO_SYSTEM) }
    val systemAudioSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    val manager = remember {
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    ScreenCaptureService.startIntent(
                        context,
                        result.resultCode,
                        data,
                        height,
                        audioMode
                    )
                )
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(
            modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
            trailing = {
                Text(
                    "Pantalla",
                    style = MaterialTheme.typography.labelLarge,
                    color = NdiGlass.Muted
                )
            },
            onMenu = onMenu
        )

        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            GlassPanel {
                Text(
                    "Pantalla como fuente NDI",
                    style = MaterialTheme.typography.titleMedium,
                    color = NdiGlass.Text
                )
                Text(
                    "Se transmite la pantalla completa del dispositivo, incluida cualquier " +
                        "aplicación abierta. Al iniciar, Android pide confirmación de captura.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NdiGlass.Muted
                )
                Spacer(Modifier.height(4.dp))
                PrimaryAction(
                    text = if (streaming) "Detener captura" else "Iniciar captura de pantalla"
                ) {
                    if (streaming) {
                        context.startService(ScreenCaptureService.stopIntent(context))
                    } else {
                        launcher.launch(manager.createScreenCaptureIntent())
                    }
                }
                StatusPill(
                    running = streaming,
                    text = if (streaming) "Transmitiendo la pantalla" else "Detenido"
                )
                if (streaming && !Settings.canDrawOverlays(context)) {
                    Text(
                        "Concede «Mostrar sobre otras apps» para ver el punto rojo de emisión. " +
                            "Ese punto usa una capa segura, así que no aparece en la fuente NDI.",
                        style = MaterialTheme.typography.bodySmall,
                        color = NdiGlass.Muted
                    )
                    PrimaryAction("Permitir superposición") {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    android.net.Uri.parse("package:${context.packageName}")
                                )
                            )
                        }
                    }
                }
            }

            GlassPanel(title = "Resolución de salida") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassChip("1080p", height == 1080) { height = 1080 }
                    GlassChip("720p", height == 720) { height = 720 }
                }
            }

            GlassPanel(title = "Audio de la transmisión") {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    GlassChip(
                        label = "Audio del sistema",
                        active = audioMode == ScreenCaptureService.AUDIO_SYSTEM,
                        enabled = systemAudioSupported && !streaming
                    ) { audioMode = ScreenCaptureService.AUDIO_SYSTEM }
                    GlassChip(
                        label = "Micrófono",
                        active = audioMode == ScreenCaptureService.AUDIO_MIC,
                        enabled = !streaming
                    ) { audioMode = ScreenCaptureService.AUDIO_MIC }
                    GlassChip(
                        label = "Sin audio",
                        active = audioMode == ScreenCaptureService.AUDIO_NONE,
                        enabled = !streaming
                    ) { audioMode = ScreenCaptureService.AUDIO_NONE }
                }
                Text(
                    if (systemAudioSupported) {
                        "El audio del sistema captura lo que reproducen otras apps (vídeo, " +
                            "música, navegador) mientras compartes la pantalla."
                    } else {
                        "El audio del sistema necesita Android 10 o superior; en este " +
                            "dispositivo puedes usar el micrófono."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = NdiGlass.Muted
                )
                Text(
                    "La fuente aparece como «NDI App Pantalla».",
                    style = MaterialTheme.typography.bodySmall,
                    color = NdiGlass.Muted
                )
            }
        }
    }
}

// ----------------------------------------------------------------- monitor --

@Composable
private fun MonitorTab(onMenu: () -> Unit) {
    val scope = rememberCoroutineScope()
    val finder = remember { sharedNdiFinder }
    val receiver = remember { NdiReceiverController() }
    var sources by remember { mutableStateOf(emptyList<NdiSource>()) }
    var playing by remember { mutableStateOf<NdiSource?>(null) }
    var frame by remember { mutableStateOf<Bitmap?>(null) }
    var audio by remember { mutableStateOf(true) }
    var frameSeq by remember { mutableIntStateOf(0) }
    var fullscreen by remember { mutableStateOf(false) }
    var clean by rememberSaveable { mutableStateOf(false) }
    var cleanHint by remember { mutableStateOf(false) }
    var stats by remember { mutableStateOf(StreamStats()) }
    var proxy by remember { mutableStateOf(false) }
    // Caudal medido con el flujo completo, para decir en pantalla cuánto baja el proxy.
    var nativeMbps by remember { mutableStateOf(0f) }
    var net by remember { mutableStateOf(LocalNetwork(false, "", false)) }
    var guides by remember { mutableIntStateOf(0) }
    val activity = LocalContext.current as? Activity
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("ndi_monitor", Context.MODE_PRIVATE) }
    // Resolución de recepción NDI: 0 = la de la fuente, o el alto máximo pedido.
    // El hilo de recepción lee el valor atómico, sin depender de la instantánea de Compose.
    val rxHeightLimit = remember {
        java.util.concurrent.atomic.AtomicInteger(prefs.getInt("ndi_rx_height", 0))
    }
    var rxHeight by remember { mutableIntStateOf(rxHeightLimit.get()) }
    var manualIps by remember {
        mutableStateOf(prefs.getStringSet("ips", emptySet())?.sorted().orEmpty())
    }
    var newIp by remember { mutableStateOf("") }
    // Recepción de red: RTSP (verde) o SRT (naranja), con la URL de cada uno.
    var rxMode by rememberSaveable {
        mutableStateOf(prefs.getString("rxmode", "rtsp").orEmpty())
    }
    var rtspUrl by rememberSaveable {
        mutableStateOf(prefs.getString("rtsp", "").orEmpty())
    }
    var srtUrl by rememberSaveable {
        mutableStateOf(prefs.getString("srt", "").orEmpty())
    }
    val rxUrl = if (rxMode == "srt") srtUrl else rtspUrl
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    var rtspError by remember { mutableStateOf<String?>(null) }
    var streamReady by remember { mutableStateOf(false) }
    val openSource: (NdiSource) -> Unit = { chosen ->
        // Si quedaba una escucha SRT esperando al emisor, se corta.
        SrtNative.cancel()
        player?.release()
        player = null
        rtspError = null
        playing = chosen
        // 720p/540p piden además el flujo proxy: sin eso sólo bajarían los píxeles
        // del teléfono y el caudal de red seguiría siendo el de la fuente.
        val reduced = proxy || rxHeightLimit.get() != 0
        receiver.start(
            scope = scope,
            source = chosen,
            audioEnabled = { audio },
            lowBandwidth = reduced,
            maxHeight = { rxHeightLimit.get() },
            onFrame = { bitmap ->
                frame = bitmap
                frameSeq++
            },
            onStats = { values ->
                stats = values
                if (!reduced && values.fps > 1f) nativeMbps = values.mbps
            }
        )
    }

    /** Reabre la fuente para que el cambio de resolución y de proxy valga ya. */
    val reconnectSource: () -> Unit = {
        playing?.let { current ->
            receiver.stop()
            openSource(current)
        }
    }

    /** Reproduce una URL rtsp:// o srt:// en el monitor. */
    val playStream: (String) -> Unit = { raw ->
        val address = raw.trim()
        if (address.isNotEmpty()) {
            // La escucha SRT anterior puede estar bloqueada esperando al emisor.
            SrtNative.cancel()
            player?.release()
            rtspError = null
            streamReady = false
            playing = null
            frame = null
            receiver.stop()
            val isSrt = address.startsWith("srt://", ignoreCase = true)
            val built = ExoPlayer.Builder(context).build()
            if (isSrt) {
                // SRT sólo transporta bytes: se lee el MPEG-TS con ExoPlayer.
                built.setMediaSource(
                    ProgressiveMediaSource.Factory(SrtDataSource.Factory())
                        .createMediaSource(ExoMediaItem.fromUri(address))
                )
            } else {
                built.setMediaItem(ExoMediaItem.fromUri(address))
            }
            built.volume = if (monitorMuted.value) 0f else 1f
            built.playWhenReady = true
            built.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    // El código de ExoPlayer no dice nada: el motivo real de SRT
                    // (puerto ocupado, emisor ausente, flujo inválido) va en la causa.
                    val detail = error.cause?.message?.trim()?.take(140)
                    rtspError = if (detail.isNullOrEmpty()) {
                        error.errorCodeName
                    } else {
                        "${error.errorCodeName}: $detail"
                    }
                }

                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) streamReady = true
                }
            })
            built.prepare()
            player = built
            prefs.edit().putString(if (isSrt) "srt" else "rtsp", address).apply()
        }
    }

    // El chip de audio de la cámara también enmudece lo que suena acá.
    LaunchedEffect(monitorMuted.value, player) {
        player?.volume = if (monitorMuted.value) 0f else 1f
    }
    val stopRtsp: () -> Unit = {
        SrtNative.cancel()
        player?.release()
        player = null
        rtspError = null
        streamReady = false
    }

    // Pantalla completa y vista limpia ocultan cabecera, lista y barras del sistema.
    LaunchedEffect(fullscreen, clean) {
        setSystemBarsVisible(activity, !(fullscreen || clean))
    }
    // La vista limpia deja solo la imagen: un aviso breve recuerda cómo salir.
    LaunchedEffect(clean) {
        monitorClean.value = clean
        if (!clean) {
            cleanHint = false
            return@LaunchedEffect
        }
        cleanHint = true
        delay(2200)
        cleanHint = false
    }
    // Al dejar de recibir una fuente se sale de pantalla completa y de la vista limpia.
    LaunchedEffect(playing) {
        if (playing == null) {
            fullscreen = false
            clean = false
        }
    }
    DisposableEffect(Unit) {
        onDispose { setSystemBarsVisible(activity, true) }
    }

    LaunchedEffect(Unit) {
        while (true) {
            // Con datos móviles activos los sockets del proceso tienen que
            // quedar en la Wi-Fi o el anuncio de las fuentes no llega.
            withContext(Dispatchers.IO) { ensureWifiBinding(context) }
            val found = withContext(Dispatchers.IO) { finder.sources() }
            sources = found
            MulticastLock.ensure(context)
            net = withContext(Dispatchers.IO) { localNetwork(context) }
            delay(2500)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            monitorClean.value = false
            receiver.stop()
            SrtNative.cancel()
            player?.release()
            // El finder es compartido y no se destruye: cerrarlo con una
            // búsqueda en vuelo hacía caer libndi en su propio hilo.
        }
    }

    Column(Modifier.fillMaxSize()) {
        if (!fullscreen && !clean && !pipActive.value) TopBar(
            modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
            trailing = {
                Text(
                    "Monitor",
                    style = MaterialTheme.typography.labelLarge,
                    color = NdiGlass.Muted
                )
            },
            onMenu = onMenu
        )

        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val landscape = maxWidth > maxHeight
            val viewer: @Composable (Modifier) -> Unit = { modifier ->
                Box(
                    modifier
                        .background(Color.Black)
                        .then(
                            // En vista limpia toda la imagen es el botón de salida.
                            if (clean) {
                                Modifier.pointerInput(Unit) {
                                    detectTapGestures { clean = false }
                                }
                            } else {
                                Modifier
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    val current = frame
                    val tick = frameSeq
                    val rtspPlayer = player
                    if (rtspPlayer != null) {
                        AndroidView(
                            factory = { ctx ->
                                PlayerView(ctx).apply {
                                    useController = false
                                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                                }
                            },
                            update = { view -> view.player = rtspPlayer },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else if (current != null) {
                        AndroidView(
                            factory = { ctx -> NdiVideoView(ctx) },
                            update = { view -> if (tick >= 0) view.setFrame(current) },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Text("Sin señal", color = Color(0xFF6B7785))
                    }
                    if (!clean && !pipActive.value) {
                        Row(
                            Modifier
                                .align(Alignment.TopStart)
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            GlassChip("Menú", false) { onMenu() }
                            StatusPill(
                                running = playing != null || player != null,
                                text = playing?.name ?: when {
                                    player != null && rxMode == "srt" -> "SRT"
                                    player != null -> "RTSP"
                                    else -> "Monitor NDI"
                                }
                            )
                        }
                        Row(
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (playing != null || player != null) {
                                GlassChip("PiP", pipActive.value) { enterPip(activity) }
                                GlassChip("Vista limpia", false) { clean = true }
                                GlassChip(
                                    if (fullscreen) "Salir" else "Pantalla completa",
                                    fullscreen
                                ) { fullscreen = !fullscreen }
                            }
                        }
                        GuidesOverlay(guides)
                        if (playing != null) {
                            LevelMeter(
                                left = stats.levelLeft,
                                right = stats.levelRight,
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(14.dp)
                            )
                            Text(
                                (if (stats.width > 0) {
                                    "${stats.width}×${stats.height} · "
                                } else {
                                    ""
                                }) +
                                    "${String.format("%.1f", stats.fps)} fps · " +
                                    "${String.format("%.1f", stats.mbps)} Mb/s",
                                fontSize = 10.sp,
                                color = NdiGlass.Muted,
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(14.dp)
                            )
                        }
                    } else if (cleanHint) {
                        Text(
                            "Vista limpia · toca la imagen para salir",
                            fontSize = 11.sp,
                            color = Color.White,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(18.dp)
                                .clip(RoundedCornerShape(50))
                                .background(Color(0x66000000))
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }
            val list: @Composable (Modifier) -> Unit = { modifier ->
                Column(
                    modifier
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    GlassPanel {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Recepción",
                                style = MaterialTheme.typography.titleMedium,
                                color = NdiGlass.Text,
                                modifier = Modifier.weight(1f)
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                GlassChip("RTSP", rxMode == "rtsp", accent = RtspGreen) {
                                    if (player == null) rxMode = "rtsp"
                                }
                                GlassChip("SRT", rxMode == "srt", accent = SrtOrange) {
                                    if (player == null) rxMode = "srt"
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            GlassChip(if (player != null) "Parar" else "Ver", player != null) {
                                if (player != null) {
                                    stopRtsp()
                                } else {
                                    prefs.edit().putString("rxmode", rxMode).apply()
                                    playStream(rxUrl)
                                }
                            }
                        }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp)
                                .glassSurface(
                                    shape = RoundedCornerShape(14.dp),
                                    fillAlpha = 0.08f,
                                    elevation = 3
                                )
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(Modifier.weight(1f)) {
                                if (rxUrl.isEmpty()) {
                                    Text(
                                        if (rxMode == "srt") "srt://host:9000"
                                        else "rtsp://usuario:clave@host:554/stream",
                                        color = NdiGlass.Muted,
                                        fontSize = 13.sp
                                    )
                                }
                                androidx.compose.foundation.text.BasicTextField(
                                    value = rxUrl,
                                    onValueChange = { value ->
                                        if (rxMode == "srt") srtUrl = value else rtspUrl = value
                                    },
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                                        color = NdiGlass.Text,
                                        fontSize = 13.sp
                                    ),
                                    cursorBrush = androidx.compose.ui.graphics.SolidColor(
                                        NdiGlass.BlueBright
                                    )
                                )
                            }
                        }
                        Text(
                            rtspError?.let { if (rxMode == "srt") "SRT: $it" else "RTSP: $it" }
                                ?: if (rxMode == "srt" && player != null && !streamReady) {
                                    "Esperando al emisor SRT en ese puerto…"
                                } else if (rxMode == "srt") {
                                    "Pegá una URL srt:// (MPEG-TS) y tocá Ver."
                                } else {
                                    "Pegá una URL rtsp:// y tocá Ver."
                                },
                            style = MaterialTheme.typography.bodySmall,
                            color = NdiGlass.Muted
                        )
                    }

                    GlassPanel {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Fuentes en la red",
                                style = MaterialTheme.typography.titleMedium,
                                color = NdiGlass.Text,
                                modifier = Modifier.weight(1f)
                            )
                            GlassChip("Audio", audio) { audio = !audio }
                            GlassChip("Proxy", proxy || rxHeight != 0) {
                                if (proxy || rxHeight != 0) {
                                    // Apagar el proxy devuelve la fuente a su flujo completo.
                                    proxy = false
                                    rxHeight = 0
                                    rxHeightLimit.set(0)
                                    prefs.edit().putInt("ndi_rx_height", 0).apply()
                                } else {
                                    proxy = true
                                }
                                reconnectSource()
                            }
                            GlassChip(
                                when (guides) {
                                    0 -> "Guías"
                                    1 -> "Tercios"
                                    2 -> "2.39"
                                    3 -> "4:3"
                                    else -> "9:16"
                                },
                                guides > 0
                            ) { guides = (guides + 1) % 5 }
                        }

                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                                .horizontalScroll(rememberScrollState()),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                "Recepción",
                                style = MaterialTheme.typography.labelSmall,
                                color = NdiGlass.Muted
                            )
                            listOf(0 to "Nativa", 720 to "720p", 540 to "540p")
                                .forEach { option ->
                                    GlassChip(option.second, rxHeight == option.first) {
                                        rxHeight = option.first
                                        rxHeightLimit.set(option.first)
                                        // Bajar resolución también pide el flujo proxy al
                                        // emisor, así que el caudal de red baja de verdad.
                                        proxy = option.first != 0
                                        prefs.edit()
                                            .putInt("ndi_rx_height", option.first)
                                            .apply()
                                        reconnectSource()
                                    }
                                }
                        }
                        val reducedRx = proxy || rxHeight != 0
                        Text(
                            when (rxHeight) {
                                720 -> "720p: el visor mueve el 44 % de los píxeles de un " +
                                    "cuadro 1080p."
                                540 -> "540p: el visor mueve el 25 % de los píxeles de un " +
                                    "cuadro 1080p."
                                else -> "Nativa: el cuadro llega al tamaño de la fuente."
                            } + " " + when {
                                !reducedRx ->
                                    "Sin proxy llega el flujo completo de la fuente, con todo " +
                                        "su caudal de red."
                                nativeMbps > 0.2f && stats.mbps > 0.05f ->
                                    "Con proxy el emisor manda su flujo reducido: el caudal " +
                                        "real bajó de " + String.format("%.1f", nativeMbps) +
                                        " Mb/s a " + String.format("%.1f", stats.mbps) + " Mb/s (" +
                                        "−" + ((1f - stats.mbps / nativeMbps) * 100f)
                                            .coerceIn(0f, 100f).toInt() + " %)."
                                else ->
                                    "Con proxy el emisor manda su flujo reducido, normalmente " +
                                        "un tercio del caudal o menos; ahora llegan " +
                                        String.format("%.1f", stats.mbps) + " Mb/s. Si la " +
                                        "fuente no publica proxy, NDI entrega el flujo completo."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = NdiGlass.Muted
                        )

                        if (sources.isEmpty()) {
                            Text(
                                if (!net.wifi) {
                                    "Estás en datos móviles: conectate a la misma Wi-Fi que " +
                                        "la PC para ver sus fuentes NDI."
                                } else {
                                    "Buscando fuentes NDI en " +
                                        net.address.ifBlank { "la red local" } +
                                        "… revisá que la PC esté en esa misma red."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = NdiGlass.Muted
                            )
                        }
                        Text(
                            net.label + " · multicast " +
                                if (net.multicast) "activo" else "apagado",
                            style = MaterialTheme.typography.labelSmall,
                            color = NdiGlass.Muted
                        )

                        sources.forEach { source ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .glassSurface(
                                        shape = RoundedCornerShape(16.dp),
                                        fillAlpha = 0.08f,
                                        elevation = 4
                                    )
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        source.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = NdiGlass.Text,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        source.url,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = NdiGlass.Muted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (playing?.fullName == source.fullName) {
                                    TextButton(onClick = {
                                        receiver.stop()
                                        playing = null
                                        frame = null
                                    }) {
                                        Text("Parar", color = NdiGlass.BlueBright)
                                    }
                                } else {
                                    TextButton(onClick = { openSource(source) }) {
                                        Text("Ver", color = NdiGlass.BlueBright)
                                    }
                                }
                            }
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0x33FFFFFF))
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            if (newIp.isEmpty()) {
                                Text(
                                    "IP de la fuente, p. ej. 192.168.1.20:5961",
                                    fontSize = 11.sp,
                                    color = NdiGlass.Muted
                                )
                            }
                            androidx.compose.foundation.text.BasicTextField(
                                value = newIp,
                                onValueChange = { newIp = it },
                                singleLine = true,
                                textStyle = androidx.compose.ui.text.TextStyle(
                                    color = NdiGlass.Text,
                                    fontSize = 13.sp
                                ),
                                cursorBrush = androidx.compose.ui.graphics.SolidColor(
                                    NdiGlass.BlueBright
                                )
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        GlassChip("Añadir IP", false, newIp.isNotBlank()) {
                            val address = newIp.trim().let {
                                if (it.contains(':')) it else "$it:5961"
                            }
                            val updated = (manualIps + address).distinct().sorted()
                            manualIps = updated
                            prefs.edit().putStringSet("ips", updated.toSet()).apply()
                            newIp = ""
                        }
                    }

                    manualIps.forEach { address ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .glassSurface(
                                    shape = RoundedCornerShape(16.dp),
                                    fillAlpha = 0.08f,
                                    elevation = 4
                                )
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "IP $address",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = NdiGlass.Text,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    "Fuente manual",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = NdiGlass.Muted
                                )
                            }
                            if (playing?.url == address) {
                                TextButton(onClick = {
                                    receiver.stop()
                                    playing = null
                                    frame = null
                                }) {
                                    Text("Parar", color = NdiGlass.BlueBright)
                                }
                            } else {
                                TextButton(onClick = {
                                    openSource(NdiSource("IP $address", "", address))
                                }) {
                                    Text("Ver", color = NdiGlass.BlueBright)
                                }
                            }
                            TextButton(onClick = {
                                val updated = manualIps - address
                                manualIps = updated
                                prefs.edit().putStringSet("ips", updated.toSet()).apply()
                            }) {
                                Text("Quitar", color = NdiGlass.Muted)
                            }
                        }
                    }

                    if (playing != null) {
                        val audioLine = when {
                            stats.audioRate == 0 && stats.audioPackets == 0 ->
                                "Audio: sin paquetes recibidos"
                            stats.audioProblem.isNotEmpty() -> "Audio: ${stats.audioProblem}"
                            else -> "Audio: ${stats.audioRate / 1000} kHz · " +
                                "${stats.audioChannels} canales · ${stats.audioPackets} pkt"
                        }
                        Text(
                            audioLine,
                            style = MaterialTheme.typography.labelSmall,
                            color = NdiGlass.Muted
                        )
                    }
                }
            }

            if (fullscreen || clean || pipActive.value) {
                viewer(Modifier.fillMaxSize())
            } else if (landscape) {
                Row(Modifier.fillMaxSize()) {
                    viewer(Modifier.weight(1f).fillMaxHeight())
                    list(Modifier.width(380.dp).fillMaxHeight())
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    viewer(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                    list(Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
    }
}

// -------------------------------------------------------------- multimedia --

/** Encaja la vista en el espacio disponible con [aspect], sin recortar ni deformar. */
private fun Modifier.fitViewport(aspect: Float): Modifier = layout { measurable, constraints ->
    val maxWidth = constraints.maxWidth
    val maxHeight = constraints.maxHeight
    if (aspect <= 0f || maxWidth <= 0 || maxHeight <= 0) {
        val placeable = measurable.measure(constraints)
        return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
    var fitWidth = maxWidth.toFloat()
    var fitHeight = fitWidth / aspect
    if (fitHeight > maxHeight) {
        fitHeight = maxHeight.toFloat()
        fitWidth = fitHeight * aspect
    }
    val placeable = measurable.measure(
        Constraints.fixed(fitWidth.roundToInt(), fitHeight.roundToInt())
    )
    layout(maxWidth, maxHeight) {
        placeable.place(
            ((maxWidth - placeable.width) / 2f).roundToInt(),
            ((maxHeight - placeable.height) / 2f).roundToInt()
        )
    }
}

/**
 * Dibuja un cuadro de cámara en RGBA encajado en el espacio disponible.
 *
 * El búfer llega en orden RGBA, que es como Android guarda ARGB_8888 en memoria,
 * así que se pinta tal cual: sin invertir canales, con los mismos colores que
 * viajan por NDI. El escalado es uniforme (min) para que la imagen no se deforme.
 */
private class CameraPreviewView(context: Context) : android.view.View(context) {

    private var bitmap: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val target = Rect()

    fun setFrame(frame: Bitmap) {
        bitmap = frame
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(AndroidColor.BLACK)
        val bmp = bitmap ?: return
        val scale = minOf(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
        val drawW = (bmp.width * scale).toInt()
        val drawH = (bmp.height * scale).toInt()
        val left = (width - drawW) / 2
        val top = (height - drawH) / 2
        target.set(left, top, left + drawW, top + drawH)
        canvas.drawBitmap(bmp, null, target, paint)
    }
}

/** Vista simple que dibuja el frame NDI escalado y centrado. */
private class NdiVideoView(context: Context) : android.view.View(context) {

    private var bitmap: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val target = Rect()

    fun setFrame(frame: Bitmap) {
        bitmap = frame
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(AndroidColor.BLACK)
        val bmp = bitmap ?: return
        val scale = minOf(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
        val drawW = (bmp.width * scale).toInt()
        val drawH = (bmp.height * scale).toInt()
        val left = (width - drawW) / 2
        val top = (height - drawH) / 2
        target.set(left, top, left + drawW, top + drawH)
        canvas.drawBitmap(bmp, null, target, paint)
    }
}

// -------------------------------------------------------------- multimedia --

private fun queryDisplayName(context: Context, uri: android.net.Uri): String {
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) return cursor.getString(0)
            }
    }
    return uri.lastPathSegment?.substringAfterLast('/') ?: "Archivo"
}


// ------------------------------------------------------------------- audio --

/** Fuente NDI de solo audio: micrófono, archivo o audio del sistema. */
@Composable
private fun AudioTab(onMenu: () -> Unit) {
    val context = LocalContext.current
    val source = remember { AudioNdiSource(context.applicationContext) }
    val projectionManager = remember {
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? android.media.projection.MediaProjectionManager
    }
    var status by remember { mutableStateOf("Elige micrófono, archivo o audio del sistema") }
    var running by remember { mutableStateOf(false) }
    var mode by remember { mutableIntStateOf(0) }
    var fileName by remember { mutableStateOf<String?>(null) }
    var picked by remember { mutableStateOf<android.net.Uri?>(null) }
    val systemRunning = SystemAudioService.running.value
    val systemStatus = SystemAudioService.status.value
    val systemDetail = SystemAudioService.detail.value
    val canRecord = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        picked = uri
        fileName = queryDisplayName(context, uri)
        mode = 1
    }

    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            ContextCompat.startForegroundService(
                context,
                SystemAudioService.startIntent(context, result.resultCode, data)
            )
            mode = 2
            status = "Capturando el audio del sistema…"
        } else {
            status = "Permiso de captura de audio denegado"
        }
    }

    var pendingMode by remember { mutableIntStateOf(-1) }
    val audioPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val target = pendingMode
        pendingMode = -1
        if (!granted) {
            status = "Sin permiso de micrófono no se puede capturar audio"
        } else if (target == 2) {
            val intent = projectionManager?.createScreenCaptureIntent()
            if (intent != null) {
                projectionLauncher.launch(intent)
            } else {
                status = "Este dispositivo no permite capturar audio del sistema"
            }
        } else if (target == 0) {
            running = source.startMicrophone("NDI App Audio")
            if (!running) status = "No se pudo iniciar la fuente de audio"
        }
    }

    DisposableEffect(Unit) {
        source.onStatus = { status = it }
        onDispose { source.stop() }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(
            modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
            trailing = {
                Text(
                    "Audio",
                    style = MaterialTheme.typography.labelLarge,
                    color = NdiGlass.Muted
                )
            },
            onMenu = onMenu
        )

        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            GlassPanel {
                Text(
                    "Fuente de solo audio",
                    style = MaterialTheme.typography.titleMedium,
                    color = NdiGlass.Text
                )
                Text(
                    "Publica el micrófono, un archivo o el sonido de otras apps como fuente " +
                        "NDI. No se envía vídeo: la fuente lleva un cuadro negro para que los " +
                        "receptores la listen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NdiGlass.Muted
                )
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    GlassChip("Micrófono", mode == 0, enabled = !running && !systemRunning) {
                        mode = 0
                    }
                    GlassChip(
                        "Archivo",
                        mode == 1,
                        enabled = !running && !systemRunning && fileName != null
                    ) { mode = 1 }
                    GlassChip("Sistema", mode == 2, enabled = !running) { mode = 2 }
                }
                if (mode == 1) {
                    fileName?.let { name ->
                        Text(
                            name,
                            style = MaterialTheme.typography.bodySmall,
                            color = NdiGlass.Text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (mode == 2) {
                    Text(
                        "Se envía el audio que reproducen otras apps (Android 10 o superior). " +
                            "Al iniciar, el sistema pide permiso de captura; no se comparte la " +
                            "pantalla. Hace falta también el permiso de micrófono, y las apps " +
                            "pueden bloquear su propio audio.",
                        style = MaterialTheme.typography.bodySmall,
                        color = NdiGlass.Muted
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryAction(
                        if (mode == 2) {
                            if (systemRunning) "Detener" else "Iniciar"
                        } else if (running) {
                            "Detener"
                        } else {
                            "Iniciar"
                        }
                    ) {
                        when (mode) {
                            2 -> {
                                if (systemRunning) {
                                    context.startService(SystemAudioService.stopIntent(context))
                                } else if (!canRecord) {
                                    pendingMode = 2
                                    status = "Concede el micrófono para capturar audio"
                                    audioPermission.launch(Manifest.permission.RECORD_AUDIO)
                                } else {
                                    val intent = projectionManager?.createScreenCaptureIntent()
                                    if (intent != null) {
                                        projectionLauncher.launch(intent)
                                    } else {
                                        status = "Este dispositivo no permite capturar audio"
                                    }
                                }
                            }

                            else -> {
                                if (running) {
                                    source.stop()
                                    running = false
                                    status = "Detenido"
                                } else if (mode == 0 && !canRecord) {
                                    pendingMode = 0
                                    status = "Concede el micrófono para enviar audio"
                                    audioPermission.launch(Manifest.permission.RECORD_AUDIO)
                                } else {
                                    val name = "NDI App Audio"
                                    running = if (mode == 0) {
                                        source.startMicrophone(name)
                                    } else {
                                        picked?.let { source.startFile(it, name) } ?: false
                                    }
                                    if (!running) {
                                        status = "No se pudo iniciar la fuente de audio"
                                    }
                                }
                            }
                        }
                    }
                    if (mode != 2) {
                        PrimaryAction("Elegir audio") { picker.launch(arrayOf("audio/*")) }
                    }
                }
                StatusPill(
                    running = running || systemRunning,
                    text = if (mode == 2 && systemStatus.isNotEmpty()) systemStatus else status
                )
                if (mode == 2) {
                    Text(
                        "Estado de la captura de audio del sistema",
                        style = MaterialTheme.typography.labelLarge,
                        color = NdiGlass.Text
                    )
                    Text(
                        when {
                            systemRunning && systemStatus.isNotEmpty() -> systemStatus
                            systemRunning -> "Captura activa, sin datos todavía"
                            systemStatus.isNotEmpty() -> systemStatus
                            else -> "Sin captura en marcha"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = NdiGlass.Text
                    )
                    if (systemRunning && systemDetail.isNotEmpty()) {
                        Text(
                            systemDetail,
                            style = MaterialTheme.typography.bodySmall,
                            color = NdiGlass.Muted
                        )
                    }
                    Text(
                        if (canRecord) {
                            "Permiso de micrófono (RECORD_AUDIO): concedido"
                        } else {
                            "Permiso de micrófono (RECORD_AUDIO): falta; pulsa Iniciar para concederlo"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = NdiGlass.Muted
                    )
                    Text(
                        "Motivos posibles si no llega sonido: permiso denegado, proyección detenida " +
                            "por el sistema (bloqueo de pantalla u otra captura) o una app que excluye " +
                            "su audio de la captura. Si llega silencio, prueba con YouTube o un " +
                            "reproductor del sistema.",
                        style = MaterialTheme.typography.bodySmall,
                        color = NdiGlass.Muted
                    )
                }
            }
        }
    }
}

// -------------------------------------------------------------- multimedia --

/** Lista de reproducción de vídeo, fotos y audio publicada como fuente NDI. */
@Composable
private fun MediaTab(onMenu: () -> Unit) {
    val context = LocalContext.current
    val player = remember { MediaPlaylistPlayer(context.applicationContext) }
    var playlist by remember { mutableStateOf(emptyList<MediaItem>()) }
    var current by remember { mutableIntStateOf(-1) }
    var status by remember { mutableStateOf("Añade archivos para empezar") }
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableIntStateOf(0) }
    var duration by remember { mutableIntStateOf(0) }
    var loop by remember { mutableStateOf(true) }
    var sending by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var previewTick by remember { mutableIntStateOf(0) }
    var rotation by remember { mutableIntStateOf(0) }

    fun bindPlayer() {
        player.onStatus = { status = it }
        player.onItem = { index, item ->
            current = index
            status = "Reproduciendo «${item.name}»"
        }
        player.onProgress = { pos, total, isPlaying ->
            position = pos
            duration = total
            playing = isPlaying
        }
        player.onEnded = {
            playing = false
            status = "Fin de la lista"
        }
        player.onPreview = { bitmap ->
            preview = bitmap
            previewTick++
        }
        player.setLooping(loop)
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val added = uris.mapNotNull { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            val mime = context.contentResolver.getType(uri).orEmpty()
            val kind = when {
                mime.startsWith("video/") -> MediaKind.VIDEO
                mime.startsWith("image/") -> MediaKind.IMAGE
                mime.startsWith("audio/") -> MediaKind.AUDIO
                else -> null
            } ?: return@mapNotNull null
            MediaItem(uri, queryDisplayName(context, uri), kind)
        }
        if (added.isEmpty()) {
            status = "No se reconocieron esos archivos"
            return@rememberLauncherForActivityResult
        }
        playlist = playlist + added
        bindPlayer()
        val from = if (current >= 0) current else 0
        sending = player.start(playlist, "NDI App Multimedia", from)
        player.setExtraRotation(rotation)
        if (!sending) status = "No se pudo iniciar la fuente NDI"
    }

    DisposableEffect(Unit) {
        onDispose { player.close() }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(
            modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
            trailing = {
                Text(
                    "Multimedia",
                    style = MaterialTheme.typography.labelLarge,
                    color = NdiGlass.Muted
                )
            },
            onMenu = onMenu
        )

        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            GlassPanel(title = "Emisión") {
                val shot = preview
                val tick = previewTick
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    if (shot != null) {
                        AndroidView(
                            factory = { ctx -> NdiVideoView(ctx) },
                            update = { view -> if (tick >= 0) view.setFrame(shot) },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Text(
                            "Sin vista previa",
                            style = MaterialTheme.typography.bodySmall,
                            color = NdiGlass.Muted
                        )
                    }
                }
                StatusPill(running = sending, text = status)
                if (duration > 0) {
                    Slider(
                        value = position.coerceIn(0, duration).toFloat(),
                        onValueChange = { position = it.roundToInt() },
                        onValueChangeFinished = { player.seekTo(position) },
                        valueRange = 0f..duration.toFloat().coerceAtLeast(1f)
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(clockText(position / 1000), fontSize = 10.sp, color = NdiGlass.Muted)
                        Text(clockText(duration / 1000), fontSize = 10.sp, color = NdiGlass.Muted)
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    GlassChip("Anterior", false, sending) { player.previous() }
                    GlassChip(
                        if (playing) "Pausa" else "Reproducir",
                        playing,
                        playlist.isNotEmpty()
                    ) {
                        if (!sending) {
                            // Tras detener, la fuente se vuelve a crear desde el elemento actual.
                            bindPlayer()
                            sending = player.start(
                                playlist,
                                "NDI App Multimedia",
                                if (current >= 0) current else 0
                            )
                            player.setExtraRotation(rotation)
                            if (!sending) status = "No se pudo iniciar la fuente NDI"
                        } else {
                            player.toggle()
                        }
                    }
                    GlassChip("Siguiente", false, sending) { player.next() }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    GlassChip("Bucle", loop) {
                        loop = !loop
                        player.setLooping(loop)
                    }
                    GlassChip("Girar $rotation°", rotation != 0) {
                        rotation = (rotation + 90) % 360
                        player.setExtraRotation(rotation)
                    }
                    GlassChip("Detener", false, sending) {
                        player.close()
                        sending = false
                        playing = false
                        position = 0
                        duration = 0
                        preview = null
                        status = "Detenido · pulsa Reproducir para reanudar"
                    }
                }
            }

            GlassPanel {
                Text(
                    "Lista de reproducción",
                    style = MaterialTheme.typography.titleMedium,
                    color = NdiGlass.Text
                )
                Text(
                    "Añade vídeos, fotos y audios: se emiten en orden por la misma fuente NDI. " +
                        "El vídeo y el audio viajan sincronizados; las fotos duran 8 s.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NdiGlass.Muted
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryAction("Añadir archivos") {
                        picker.launch(arrayOf("video/*", "image/*", "audio/*"))
                    }
                    if (playlist.isNotEmpty()) {
                        PrimaryAction("Limpiar") {
                            player.close()
                            playlist = emptyList()
                            current = -1
                            sending = false
                            playing = false
                            position = 0
                            duration = 0
                            preview = null
                            status = "Lista vacía"
                        }
                    }
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    playlist.forEachIndexed { index, item ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .glassSurface(
                                    shape = RoundedCornerShape(14.dp),
                                    fillAlpha = if (index == current) 0.18f else 0.06f,
                                    elevation = 2
                                )
                                .clickable {
                                    if (sending) {
                                        player.select(index)
                                    } else {
                                        bindPlayer()
                                        sending = player.start(
                                            playlist,
                                            "NDI App Multimedia",
                                            index
                                        )
                                        player.setExtraRotation(rotation)
                                    }
                                    current = index
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "${index + 1}.",
                                fontSize = 11.sp,
                                color = NdiGlass.Muted,
                                modifier = Modifier.width(26.dp)
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    item.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = NdiGlass.Text,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    when (item.kind) {
                                        MediaKind.VIDEO -> "Vídeo"
                                        MediaKind.IMAGE -> "Foto"
                                        MediaKind.AUDIO -> "Audio"
                                    },
                                    fontSize = 10.sp,
                                    color = NdiGlass.Muted
                                )
                            }
                        }
                    }
                    if (playlist.isEmpty()) {
                        Text(
                            "La lista está vacía.",
                            style = MaterialTheme.typography.bodySmall,
                            color = NdiGlass.Muted
                        )
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------------- SRT ---

/** Configuración de la salida SRT, en su propia pestaña del menú. */
@Composable
private fun SrtTab(
    onMenu: () -> Unit,
    srtUrl: String,
    onSrtUrl: (String) -> Unit,
    srtHeight: Int,
    srtBitrate: Int,
    onSrtVideo: (Int, Int) -> Unit
) {
    val context = LocalContext.current
    val net = remember { localNetwork(context) }
    Column(Modifier.fillMaxSize()) {
        TopBar(
            modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
            trailing = {
                Text(
                    "SRT",
                    style = MaterialTheme.typography.labelLarge,
                    color = NdiGlass.Muted
                )
            },
            onMenu = onMenu
        )
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            GlassPanel {
                OutboundPanel(srtUrl = srtUrl, onSrtUrl = onSrtUrl)
                Text(
                    net.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = NdiGlass.Text
                )
                Text(
                    "El botón naranja del visor de la cámara arranca y corta la salida. " +
                        "El estado del socket y los MB enviados se muestran sobre el visor.",
                    style = MaterialTheme.typography.labelSmall,
                    color = NdiGlass.Muted
                )
            }
            GlassPanel {
                Text(
                    "Calidad del envío",
                    style = MaterialTheme.typography.titleMedium,
                    color = NdiGlass.Text
                )
                Text(
                    "Resolución y tasa de bits del H.264 que sale por SRT. Se aplican al " +
                        "instante, también con la emisión en marcha. Solo afectan a SRT: NDI " +
                        "sale con la resolución del visor.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NdiGlass.Muted
                )
                Text(
                    "Resolución",
                    style = MaterialTheme.typography.labelMedium,
                    color = NdiGlass.Text
                )
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        0 to "Nativa",
                        1080 to "1080p",
                        720 to "720p",
                        540 to "540p",
                        480 to "480p"
                    ).forEach { (value, label) ->
                        GlassChip(label, srtHeight == value, accent = SrtOrange) {
                            onSrtVideo(value, srtBitrate)
                        }
                    }
                }
                Text(
                    "Tasa de bits de vídeo",
                    style = MaterialTheme.typography.labelMedium,
                    color = NdiGlass.Text
                )
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        0 to "Auto",
                        1_500_000 to "1,5 Mb/s",
                        2_500_000 to "2,5 Mb/s",
                        4_000_000 to "4 Mb/s",
                        6_000_000 to "6 Mb/s",
                        8_000_000 to "8 Mb/s"
                    ).forEach { (value, label) ->
                        GlassChip(label, srtBitrate == value, accent = SrtOrange) {
                            onSrtVideo(srtHeight, value)
                        }
                    }
                }
                Text(
                    if (srtBitrate > 0) {
                        String.format(
                            java.util.Locale.US,
                            "Sale a %.1f Mb/s de vídeo (más 128 kb/s de audio).",
                            srtBitrate / 1_000_000f
                        )
                    } else {
                        "Automática: 6 Mb/s a 1080p, 4 Mb/s a 720p y 2,5 Mb/s por debajo."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = NdiGlass.Muted
                )
            }
        }
    }
}

// -------------------------------------------------------------- cámara USB --

/** Cámaras USB (UVC) capturadas y emitidas como fuente NDI. */
@Composable
private fun UvcTab(onMenu: () -> Unit) {
    val context = LocalContext.current
    val usbAction = remember { "${context.packageName}.USB_PERMISSION" }
    val source = remember { UvcNdiSource(context.applicationContext) }
    var cameras by remember { mutableStateOf(emptyList<UvcCamera>()) }
    var active by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("Conecta una cámara USB al teléfono") }
    val preview = remember { UvcPreview() }

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                cameras = UvcCameras.list(context)
                val running = active
                if (running != null && cameras.none { it.deviceName == running }) {
                    source.stop()
                    active = null
                    preview.clear()
                    status = "La cámara se desconectó"
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(usbAction)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        source.onPreview = { rgba, width, height -> preview.submit(rgba, width, height) }
        cameras = UvcCameras.list(context)
        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
            source.onPreview = null
            preview.clear()
            source.stop()
        }
    }

    // Con una sola cámara ya autorizada, la sección la abre sola: el monitor muestra
    // imagen y la fuente NDI queda al aire sin pasos extra.
    LaunchedEffect(cameras) {
        val only = cameras.singleOrNull() ?: return@LaunchedEffect
        if (active != null || !only.hasPermission) return@LaunchedEffect
        source.onStatus = { status = it }
        val started = source.start(only, "NDI App UVC (${only.product})")
        active = if (started) only.deviceName else null
        if (!started) preview.clear()
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(
            modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
            trailing = {
                Text(
                    "Cámara USB",
                    style = MaterialTheme.typography.labelLarge,
                    color = NdiGlass.Muted
                )
            },
            onMenu = onMenu
        )

        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            GlassPanel {
                Text(
                    "Monitor de vista previa",
                    style = MaterialTheme.typography.titleMedium,
                    color = NdiGlass.Text
                )
                Text(
                    "El cuadro completo de la cámara USB, con su proporción y franjas negras " +
                        "si sobra espacio. Se activa al pulsar Emitir.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NdiGlass.Muted
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(preview.aspect.coerceIn(0.4f, 3f))
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    val frame = preview.image
                    if (frame == null) {
                        Text(
                            if (active != null) {
                                "Esperando imagen de la cámara…"
                            } else {
                                "Sin vista previa"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = NdiGlass.Muted
                        )
                    } else {
                        Image(
                            bitmap = frame,
                            contentDescription = "Vista previa de la cámara USB",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    }
                }
            }
            GlassPanel {
                Text(
                    "Cámaras USB (UVC)",
                    style = MaterialTheme.typography.titleMedium,
                    color = NdiGlass.Text
                )
                Text(
                    "Se capturan las cámaras que exponen la clase de vídeo USB: se negocia " +
                        "MJPEG (o YUY2), se leen por bulk o isocrono y se emiten por NDI.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NdiGlass.Muted
                )
                if (cameras.isEmpty()) {
                    Text(
                        "No hay cámaras UVC conectadas.",
                        style = MaterialTheme.typography.bodySmall,
                        color = NdiGlass.Muted
                    )
                }
                cameras.forEach { camera ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .glassSurface(
                                shape = RoundedCornerShape(16.dp),
                                fillAlpha = 0.08f,
                                elevation = 4
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                camera.product,
                                style = MaterialTheme.typography.bodyMedium,
                                color = NdiGlass.Text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                camera.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = NdiGlass.Muted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        GlassChip("Permiso", camera.hasPermission, true) {
                            if (!camera.hasPermission) {
                                status = if (
                                    UvcCameras.requestPermission(context, camera, usbAction)
                                ) {
                                    "Acepta el permiso USB para la cámara"
                                } else {
                                    "No se pudo pedir el permiso USB"
                                }
                            }
                        }
                        GlassChip(
                            if (active == camera.deviceName) "Detener" else "Emitir",
                            active == camera.deviceName,
                            true
                        ) {
                            if (active == camera.deviceName) {
                                source.stop()
                                active = null
                                preview.clear()
                                status = "Cámara detenida"
                            } else if (!camera.hasPermission) {
                                UvcCameras.requestPermission(context, camera, usbAction)
                                status = "Concede el permiso USB y vuelve a pulsar Emitir"
                            } else {
                                source.onStatus = { status = it }
                                val started = source.start(
                                    camera,
                                    "NDI App UVC (${camera.product})"
                                )
                                active = if (started) camera.deviceName else null
                                if (!started) preview.clear()
                                if (!started && status.isEmpty()) {
                                    status = "No se pudo emitir la cámara"
                                }
                            }
                        }
                    }
                }
                StatusPill(running = active != null, text = status)
            }
        }
    }
}

/**
 * Monitor de vista previa de la cámara USB. Reduce cada cuadro a 640 px de ancho y
 * lo publica a 12 fps, así el hilo de captura no espera a la interfaz ni al dibujo.
 */
private class UvcPreview {

    var image by mutableStateOf<ImageBitmap?>(null)
        private set
    var aspect by mutableFloatStateOf(16f / 9f)
        private set

    private val main = Handler(Looper.getMainLooper())
    private var lastAt = 0L
    private var busy = false

    fun submit(rgba: ByteArray, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val now = SystemClock.uptimeMillis()
        if (busy || now - lastAt < PREVIEW_GAP_MS) return
        lastAt = now
        busy = true

        val scale = (PREVIEW_WIDTH / width.toFloat()).coerceAtMost(1f)
        val targetWidth = (width * scale).toInt().coerceAtLeast(2)
        val targetHeight = (height * scale).toInt().coerceAtLeast(2)
        val pixels = IntArray(targetWidth * targetHeight)
        for (y in 0 until targetHeight) {
            val sourceRow = (y * height / targetHeight) * width
            for (x in 0 until targetWidth) {
                val index = (sourceRow + x * width / targetWidth) * 4
                if (index + 3 >= rgba.size) continue
                val r = rgba[index].toInt() and 0xFF
                val g = rgba[index + 1].toInt() and 0xFF
                val b = rgba[index + 2].toInt() and 0xFF
                pixels[y * targetWidth + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        val bitmap = runCatching {
            Bitmap.createBitmap(pixels, targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        }.getOrNull()
        val frameAspect = width.toFloat() / height

        main.post {
            busy = false
            if (bitmap != null) {
                image = bitmap.asImageBitmap()
                aspect = frameAspect
            }
        }
    }

    fun clear() {
        image = null
        busy = false
        lastAt = 0L
    }

    private companion object {
        const val PREVIEW_WIDTH = 640f
        const val PREVIEW_GAP_MS = 80L
    }
}

// ------------------------------------------------------- herramientas de monitor --

/** Guías de encuadre sobre la imagen recibida. */
@Composable
private fun GuidesOverlay(mode: Int) {
    if (mode == 0) return
    Canvas(Modifier.fillMaxSize()) {
        val lineColor = Color(0xB3FFFFFF)
        if (mode == 1) {
            for (index in 1..2) {
                drawLine(
                    lineColor,
                    Offset(size.width * index / 3f, 0f),
                    Offset(size.width * index / 3f, size.height),
                    strokeWidth = 2f
                )
                drawLine(
                    lineColor,
                    Offset(0f, size.height * index / 3f),
                    Offset(size.width, size.height * index / 3f),
                    strokeWidth = 2f
                )
            }
            drawLine(
                lineColor,
                Offset(size.width / 2 - 18f, size.height / 2),
                Offset(size.width / 2 + 18f, size.height / 2),
                strokeWidth = 2f
            )
            drawLine(
                lineColor,
                Offset(size.width / 2, size.height / 2 - 18f),
                Offset(size.width / 2, size.height / 2 + 18f),
                strokeWidth = 2f
            )
            val marginX = size.width * 0.05f
            val marginY = size.height * 0.05f
            drawRect(
                color = lineColor.copy(alpha = 0.6f),
                topLeft = Offset(marginX, marginY),
                size = Size(size.width - marginX * 2, size.height - marginY * 2),
                style = Stroke(width = 2f)
            )
        } else {
            val ratio = when (mode) {
                2 -> 2.39f
                3 -> 4f / 3f
                else -> 9f / 16f
            }
            val boxWidth: Float
            val boxHeight: Float
            if (size.width / size.height > ratio) {
                boxHeight = size.height
                boxWidth = size.height * ratio
            } else {
                boxWidth = size.width
                boxHeight = size.width / ratio
            }
            drawRect(
                color = lineColor,
                topLeft = Offset((size.width - boxWidth) / 2, (size.height - boxHeight) / 2),
                size = Size(boxWidth, boxHeight),
                style = Stroke(width = 2f)
            )
        }
    }
}

/** Medidor de nivel estéreo del audio recibido. */
@Composable
private fun LevelMeter(left: Float, right: Float, modifier: Modifier = Modifier) {
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        listOf(left, right).forEach { level ->
            val db = if (level <= 0.0005f) -60f else
                (20.0 * kotlin.math.log10(level.toDouble())).toFloat().coerceIn(-60f, 0f)
            val fraction = ((db + 60f) / 60f).coerceIn(0f, 1f)
            Box(
                Modifier
                    .width(6.dp)
                    .height(56.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color(0x66000000))
            ) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .fillMaxHeight(fraction.coerceAtLeast(0.02f))
                        .background(if (db > -1f) Color(0xFFFF3B30) else Color(0xFF37D67A))
                )
            }
        }
    }
}

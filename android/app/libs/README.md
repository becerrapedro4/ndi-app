# UVCAndroid con los parches de captura bulk y UVC 1.5

`UVCAndroid-1.0.13-feedwatch.aar` es [UVCAndroid](https://github.com/shiyinghan/UVCAndroid)
1.0.13 con dos correcciones en libuvc, publicada por el proyecto
[FeedWatch](https://github.com/daubli/FeedWatch). Se usa como archivo local hasta que
una versión de Maven Central incluya los parches; después alcanza con
`implementation("com.herohan:UVCAndroid:1.0.13")` y borrar esta carpeta.

## Qué corrige

- **Reensamblado de payloads bulk**: UVCAndroid limita las transferencias bulk a 16 KB
  en Android, pero libuvc trataba cada transferencia como un payload UVC completo. Las
  tarjetas que mandan payloads más grandes (por ejemplo la UGREEN 25854, ~900 KB por
  cuadro MJPEG) perdían todos los cuadros.
- **UVC 1.5**: libuvc rechazaba con -12 las cámaras que declaran `bcdUVC` 0x0150.

Las cámaras que emiten por endpoints isocronos, como las MS2109, no se ven afectadas
por el primero de los parches.

## Procedencia

- Fork: <https://github.com/daubli/UVCAndroid>, rama `fix/bulk-payload-reassembly`
- Compilado desde el commit `0b3b124`
- SHA-256 del AAR: `9d782f95016fc12dd0f193e7fac04aec9536361587aaa946b1fb47f76551ed1f`
- Licencias: UVCAndroid Apache 2.0; libuvc BSD; libusb LGPL 2.1 (como `libusb1.0.so`
  separada, por lo que se puede reemplazar); libjpeg-turbo y libyuv BSD.

## Qué incluye

Sólo las clases `com.serenegiant.usb` hacen falta; el AAR trae además las librerías
nativas `libUVCCamera.so`, `libuvc.so`, `libusb1.0.so`, `libjpeg-turbo212.so` y
`libyuv.so` para arm64-v8a, armeabi-v7a, x86 y x86_64. El APK sólo empaqueta las dos
primeras ABIs, que son las que declara `abiFilters`.

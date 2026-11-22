# NDI App (Android)

Fuente NDI para Android: cámara, pantalla, archivos multimedia, cámara USB (UVC), micrófono y
audio del sistema. Incluye un monitor NDI con herramientas de campo (guías, medidor de nivel,
estadísticas de flujo, modo proxy y vista limpia a pantalla completa) y una página de descarga
en `web/`.

## Contenido del repositorio

- `android/` — app Android (Kotlin + Compose, puente JNI con el SDK de NDI).
- `web/` — página de descarga y APK publicado.
- `deploy/` — Worker de Cloudflare que sirve la página y el APK.
- `dist/` — copia local del APK (no se versiona).

## Requisitos para compilar

- JDK 17.
- Android SDK (compileSdk/targetSdk según `android/app/build.gradle.kts`) y NDK con CMake.
- **NDI SDK para Android**, descargado desde <https://ndi.video/for-developers/ndi-sdk/download/>
  aceptando su licencia. Sus archivos **no** están en el repositorio porque la licencia no permite
  redistribuirlos. Cópialos antes de compilar:

  ```bash
  cp -R "<NDI_SDK>/NDI SDK for Android/include"/* android/app/src/main/cpp/ndi-sdk/include/
  cp -R "<NDI_SDK>/NDI SDK for Android/lib"/* android/app/src/main/jniLibs/
  ```

## Compilar

```bash
cd android
./gradlew assembleRelease      # APK
./gradlew bundleRelease        # AAB para Google Play
```

El APK queda en `android/app/build/outputs/apk/release/app-release.apk`.

La versión publicada se firma con una clave de depuración; para publicar en Google Play hay que
crear un keystore de release y configurar la firma (ver más abajo).

## Licencia

Código propio bajo licencia MIT (ver [LICENSE](LICENSE)). Las dependencias mantienen sus licencias:

- **NDI SDK**: propietario de Vizrt NDI AB, no incluido en el repositorio.
- AndroidX, CameraX y Compose: Apache License 2.0.
- **UVCAndroid 1.0.13** (`android/app/libs/UVCAndroid-1.0.13-feedwatch.aar`), la
  captura UVC a través de libuvc: Apache License 2.0. Incluye **libuvc** (BSD),
  **libjpeg-turbo** y **libyuv** (BSD) y **libusb 1.0** bajo LGPL 2.1, compilada
  como `libusb1.0.so` aparte para que se pueda reemplazar. El AAR es la variante
  con los parches de payloads bulk y UVC 1.5 de
  [daubli/UVCAndroid](https://github.com/daubli/UVCAndroid), rama
  `fix/bulk-payload-reassembly`, SHA-256
  `9d782f95016fc12dd0f193e7fac04aec9536361587aaa946b1fb47f76551ed1f`.

NDI® es una marca registrada de Vizrt NDI AB. Este proyecto no está afiliado ni respaldado por
Vizrt NDI AB.

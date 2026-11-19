# NDI App (Android)

Fuente NDI para Android: cámara, pantalla, archivos multimedia, cámara USB (UVC), micrófono y
audio del sistema. Incluye un monitor NDI con herramientas de campo (guías, medidor de nivel,
estadísticas de flujo y modo proxy) y una página de descarga en `web/`.

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

## Publicar en Google Play

1. Crear un keystore propio y **no** versionarlo:

   ```bash
   keytool -genkeypair -v -keystore release.jks -keyalg RSA -keysize 4096 \
           -validity 10000 -alias ndi-app
   ```

2. Configurar la firma en `android/app/build.gradle.kts` leyendo las credenciales de
   `keystore.properties` (ese archivo está en `.gitignore`).
3. Generar el AAB con `./gradlew bundleRelease` y subirlo a Play Console.
4. Completar ficha, política de privacidad, seguridad de datos, clasificación de contenido y las
   declaraciones de permisos sensibles (captura de pantalla, audio y superposición).

## Licencia

Código propio bajo licencia MIT (ver [LICENSE](LICENSE)). Las dependencias mantienen sus licencias:

- **NDI SDK**: propietario de Vizrt NDI AB, no incluido en el repositorio.
- AndroidX, CameraX y Compose: Apache License 2.0.

NDI® es una marca registrada de Vizrt NDI AB. Este proyecto no está afiliado ni respaldado por
Vizrt NDI AB.

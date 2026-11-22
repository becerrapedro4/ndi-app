# Cabeceras del SDK de NDI

Solo están las **cabeceras C** (`Processing.NDI.*.h`), que el SDK publica bajo
licencia MIT. La biblioteca `libndi.so` es propietaria y **no** se versiona aquí:
la descarga el flujo de trabajo desde el APK base v1.9.2 ya publicado.

- Origen: repositorio público `DistroAV/DistroAV`, carpeta `lib/ndi`, en el
  commit `fa41b38f18f07acc3c041e382e091a196fc12db0`.
- Versión: NDI SDK 6.3.0.3 (`Version.txt` de esa carpeta).
- Licencia: MIT, con el aviso de copyright de Vizrt NDI AB en cada cabecera.

Con estas cabeceras, `android/app/src/main/cpp/ndi_bridge.cpp` se compila en cada
compilación de CI (`Compilar el puente JNI de NDI`), así que los cambios en el
puente llegan al APK. El CMakeLists ya las espera en
`android/app/src/main/cpp/ndi-sdk/include`.

NDI® es una marca registrada de Vizrt NDI AB.

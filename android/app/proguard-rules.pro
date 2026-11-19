# Puente JNI: libndi_app.so resuelve los métodos por nombre de símbolo
# (Java_com_ndiapp_camera_ndi_NdiNative_*), así que la clase y sus métodos no
# pueden renombrarse.
-keep class com.ndiapp.camera.ndi.NdiNative { *; }
-keepclassmembers class * {
    native <methods>;
}

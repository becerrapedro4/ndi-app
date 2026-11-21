cmake_minimum_required(VERSION 3.22.1)
project(ndi_app CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_library(log_lib log)

# ------------------------------------------------------------------ NDI ----
# El SDK de NDI es propietario y no se redistribuye: las cabeceras se colocan en
# ndi-sdk/include y las librerías en jniLibs/<abi>/libndi.so (ver README).
# Si no están, se compila un stub para que el proyecto siga siendo compilable.
set(NDI_INCLUDE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/ndi-sdk/include")
set(NDI_LIB "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libndi.so")

if(EXISTS "${NDI_INCLUDE_DIR}/Processing.NDI.Lib.h" AND EXISTS "${NDI_LIB}")
    add_library(ndi_app SHARED ndi_bridge.cpp)
    target_include_directories(ndi_app PRIVATE "${NDI_INCLUDE_DIR}")
    add_library(ndi_sdk SHARED IMPORTED)
    set_target_properties(ndi_sdk PROPERTIES IMPORTED_LOCATION "${NDI_LIB}")
    target_link_libraries(ndi_app ndi_sdk ${log_lib})
    message(STATUS "NDI: usando el SDK de ${NDI_INCLUDE_DIR}")
else()
    add_library(ndi_app SHARED ndi_stub.cpp)
    target_link_libraries(ndi_app ${log_lib})
    message(WARNING "NDI: SDK ausente; se compila ndi_stub (la app arranca sin runtime NDI)")
endif()

# ------------------------------------------------------------------ SRT ----
# libsrt se compila desde el código oficial de Haivision con el NDK y se enlaza
# estáticamente dentro de libsrt_app.so. El workflow de CI deja las cabeceras en
# cpp/srt/include y la librería en cpp/srt/lib/<abi>/libsrt.a.
set(SRT_INCLUDE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/srt/include")
set(SRT_STATIC_LIB "${CMAKE_CURRENT_SOURCE_DIR}/srt/lib/${ANDROID_ABI}/libsrt.a")

if(EXISTS "${SRT_INCLUDE_DIR}/srt.h" AND EXISTS "${SRT_STATIC_LIB}")
    add_library(srt_app SHARED srt_bridge.cpp)
    target_include_directories(srt_app PRIVATE "${SRT_INCLUDE_DIR}")
    add_library(srt_core STATIC IMPORTED)
    set_target_properties(srt_core PROPERTIES IMPORTED_LOCATION "${SRT_STATIC_LIB}")
    target_link_libraries(srt_app srt_core ${log_lib})
    message(STATUS "SRT: enlazando libsrt.a oficial (Haivision)")
else()
    add_library(srt_app SHARED srt_stub.cpp)
    target_link_libraries(srt_app ${log_lib})
    message(WARNING "SRT: libsrt.a o cabeceras ausentes; se compila srt_stub")
endif()

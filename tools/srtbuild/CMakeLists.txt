cmake_minimum_required(VERSION 3.22)
project(srt_app CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Todas las bibliotecas quedan en <build>/out para que el flujo de compilación
# las copie de una ruta fija.
set(CMAKE_LIBRARY_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/out)
set(CMAKE_ARCHIVE_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/out)

# SRT_ROOT apunta al prefijo donde se instaló libsrt (include/srt + lib/libsrt.a)
if(NOT DEFINED SRT_ROOT)
    message(FATAL_ERROR "Falta -DSRT_ROOT=<prefijo de libsrt>")
endif()

add_library(srt_app SHARED
    ${CMAKE_CURRENT_SOURCE_DIR}/../../android/app/src/main/cpp/srt/srt_jni.cpp)

target_include_directories(srt_app PRIVATE ${SRT_ROOT}/include)

find_library(LOG_LIBRARY log)
find_library(ANDROID_LIBRARY android)
target_link_libraries(srt_app PRIVATE ${SRT_ROOT}/lib/libsrt.a ${LOG_LIBRARY} ${ANDROID_LIBRARY})

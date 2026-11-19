cmake_minimum_required(VERSION 3.22.1)
project(ndi_app CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(ndi_app SHARED ndi_bridge.cpp)

# Cabeceras del SDK de NDI (MIT), incluidas en ndi-sdk/include.
target_include_directories(ndi_app PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/ndi-sdk/include)

# libndi.so por ABI. Las librerias viven en app/src/main/jniLibs/<abi>/ y se
# empaquetan en el APK automaticamente.
add_library(ndi_sdk SHARED IMPORTED)
set_target_properties(ndi_sdk PROPERTIES
        IMPORTED_LOCATION "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libndi.so")

find_library(log_lib log)

target_link_libraries(ndi_app ndi_sdk ${log_lib})

# libndi.so debe viajar con el APK: se empaqueta desde jniLibs, no se enlaza estaticamente.
set_target_properties(ndi_app PROPERTIES
        LIBRARY_OUTPUT_DIRECTORY "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}")

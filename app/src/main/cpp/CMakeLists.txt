cmake_minimum_required(VERSION 3.22)
project(audioengine LANGUAGES CXX)

set(CMAKE_ANDROID_STL_TYPE c++_shared)
set(CMAKE_CXX_STANDARD 17)
add_compile_options(-O2 -Wall)

# Fuentes (lista explícita para evitar que se "olvide" WavReader.cpp)
set(ENGINE_SRC
        engine/AudioEngine.cpp
        engine/Track.cpp
        engine/WavReader.cpp
        engine/HrtfBank.cpp
        jni/AudioEngine_JNI.cpp)

add_library(audioengine SHARED ${ENGINE_SRC})

# Oboe via Prefab
find_package(oboe REQUIRED CONFIG)

# Android / Log
find_library(android-lib android)
find_library(log-lib log)

# SoundTouch (compilada desde subdirectorio)
add_subdirectory(vendor/soundtouch/lib-soundtouch/src/main/cpp)
target_include_directories(audioengine PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/vendor/soundtouch/lib-soundtouch/src/main/cpp/soundtouch/include)

# *** Importante: SoundTouch en float (evita glitches por tipo de muestra) ***
target_compile_definitions(soundtouch PRIVATE SOUNDTOUCH_FLOAT_SAMPLES=1)

# Optimizaciones por arquitectura
# ARM/ARM64: habilita NEON
if (CMAKE_SYSTEM_PROCESSOR MATCHES "arm" OR CMAKE_SYSTEM_PROCESSOR MATCHES "aarch64" OR ANDROID_ABI MATCHES "arm64-v8a|armeabi-v7a")
    target_compile_definitions(soundtouch PRIVATE SOUNDTOUCH_USE_NEON)
endif()
# x86/x86_64: desactiva SSE de SoundTouch para evitar símbolos faltantes (TDStretchSSE/FIRFilterSSE)
if (CMAKE_SYSTEM_PROCESSOR MATCHES "x86" OR CMAKE_SYSTEM_PROCESSOR MATCHES "i686" OR CMAKE_SYSTEM_PROCESSOR MATCHES "x86_64" OR ANDROID_ABI MATCHES "x86|x86_64")
    target_compile_definitions(soundtouch PRIVATE SOUNDTOUCH_DISABLE_X86_OPTIMIZATIONS)
endif()

# Enlazado
target_link_libraries(audioengine
        PRIVATE
        oboe::oboe
        soundtouch
        ${android-lib}
        ${log-lib})

# Includes internos + vendor (dr_flac single header)
target_include_directories(audioengine PRIVATE engine vendor)
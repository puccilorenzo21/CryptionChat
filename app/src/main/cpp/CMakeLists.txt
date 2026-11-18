cmake_minimum_required(VERSION 3.18.1)

project("cryptionchat_native")

# OTTIMIZZAZIONE HARDAWARE ARM64 (Per Snapdragon 8 Gen 3 / Exynos dell'S24)
if(ANDROID_ABI STREQUAL "arm64-v8a")
    add_compile_options(-O3 -mcpu=generic -march=armv8.2-a+fp16+dotprod -ffast-math)
endif()

# Disabilita OpenMP che su Android causa rallentamenti e thread-locking
set(GGML_DISABLE_OPENMP ON CACHE BOOL "" FORCE)

# Includi tutti i file sorgente necessari (.c e .cpp)
add_library(
        cryptionchat_native
        SHARED
        native-lib.cpp
        whisper.cpp
        ggml.c
        ggml-alloc.c
        ggml-backend.c
        ggml-quants.c
)

find_library(
        log-lib
        log
)

target_link_libraries(
        cryptionchat_native
        ${log-lib}
)
# language: CMake, file: CMakeLists.txt, target: NDK r28+, API 30
cmake_minimum_required(VERSION 3.22.1)
project(vcam_hook CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

add_library(vcam_hook SHARED
    NativeBridge.cpp
)

target_compile_options(vcam_hook PRIVATE
    -O2
    -fvisibility=hidden
    -fvisibility-inlines-hidden
    -fno-rtti
    -Wall
    -Wextra
    -Wno-unused-parameter
)

target_link_options(vcam_hook PRIVATE
    -Wl,--no-undefined
    -Wl,-z,max-page-size=16384
    -Wl,-z,common-page-size=16384
)

target_link_libraries(vcam_hook PRIVATE
    android
    log
    EGL
    GLESv2
)

# The native half of on-device transcription: whisper.cpp, plus the thin bridge in whisper_jni.cpp.
#
# whisper.cpp is a pinned submodule rather than vendored sources, so what this builds is exactly an
# upstream release and updating it is a submodule bump instead of a merge. Its own CMake is used as
# it stands — hand-listing its sources would have to be redone every time ggml is reorganised, which
# upstream does often.

cmake_minimum_required(VERSION 3.22.1)
project(whisper_jni CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Every one of these has to be a FORCEd cache entry rather than a plain `set`. whisper.cpp declares
# `cmake_minimum_required(VERSION 3.5)`, which leaves CMP0077 at OLD inside its scope — and under
# OLD, `option()` *clears* a normal variable of the same name instead of honouring it. Set the
# ordinary way, all of this is silently discarded and the defaults win.

# Everything links into one libwhisper_jni.so. ggml otherwise splits itself across several shared
# libraries (ggml-base, ggml-cpu, ggml) which would all have to be packaged and kept in step, and
# `--gc-sections` could not drop what nothing calls — parakeet, most of ggml's operator set.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

# Nothing here needs the command line tools, the test suite, or the HTTP server.
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_IS_DEV OFF CACHE BOOL "" FORCE)

# The NDK ships no OpenMP runtime that can be linked this simply; ggml falls back to its own thread
# pool, which is what the official Android build uses too.
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
# "Optimize for the current system" means the build host, which is not the phone.
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE OFF CACHE BOOL "" FORCE)

# ggml and whisper are compiled by whisper.cpp's own CMake, which honours the build type AGP passes
# down — so a debug build of the app would compile the model runtime at -O0 and spend minutes on a
# clip that should take seconds, which reads as a broken feature rather than a debug build. Nobody
# steps through ggml's kernels, so they are optimized whatever the variant. Appended rather than
# replacing the flags, because -g is still worth having and the last -O wins.
set(CMAKE_C_FLAGS_DEBUG "${CMAKE_C_FLAGS_DEBUG} -O3")
set(CMAKE_CXX_FLAGS_DEBUG "${CMAKE_CXX_FLAGS_DEBUG} -O3")

add_subdirectory(whisper.cpp EXCLUDE_FROM_ALL)

add_library(whisper_jni SHARED whisper_jni.cpp)

find_library(log-lib log)
target_link_libraries(whisper_jni PRIVATE whisper ${log-lib} android)

# Release builds only: a debug build of ggml is slow enough to make a voice note feel broken, but
# forcing -O3 into a debug build would make the native half unsteppable.
if (NOT CMAKE_BUILD_TYPE STREQUAL "Debug")
    target_compile_options(whisper_jni PRIVATE -O3)
    target_compile_options(whisper_jni PRIVATE -fvisibility=hidden -fvisibility-inlines-hidden)
    target_compile_options(whisper_jni PRIVATE -ffunction-sections -fdata-sections)
    target_link_options(whisper_jni PRIVATE -Wl,--gc-sections)
    target_link_options(whisper_jni PRIVATE -Wl,--exclude-libs,ALL)
endif ()

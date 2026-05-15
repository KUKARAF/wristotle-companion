cmake_minimum_required(VERSION 3.22)
project(wristotle_speech LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Whisper inference is unusably slow without optimization (~100× realtime in
# debug builds), well past the typical companion-app dictation timeout. Force
# -O3 + NDEBUG for all native code in this module regardless of gradle's
# debug/release variant. Whisper is heavily matmul-bound; loop optimisations
# and NEON SIMD auto-vectorisation only kick in at -O2 or higher.
set(CMAKE_C_FLAGS_DEBUG     "-O3 -DNDEBUG")
set(CMAKE_CXX_FLAGS_DEBUG   "-O3 -DNDEBUG")
set(CMAKE_C_FLAGS_RELEASE   "-O3 -DNDEBUG")
set(CMAKE_CXX_FLAGS_RELEASE "-O3 -DNDEBUG")

# Pull in whisper.cpp (+ ggml) from the submodule. WHISPER_STANDALONE is unset
# when used via add_subdirectory, which keeps BUILD_TESTS / EXAMPLES / SERVER
# off by default.
add_subdirectory(whisper.cpp)

add_library(wristotle_speech SHARED
    wristotle_speech.cpp
)

find_library(log-lib log)
target_link_libraries(wristotle_speech
    PRIVATE
        whisper
        ${log-lib}
)

# whisper.h lives at whisper.cpp/include/.
target_include_directories(wristotle_speech
    PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp/include
)

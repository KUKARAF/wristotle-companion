cmake_minimum_required(VERSION 3.22)
project(wristotle_speech LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

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

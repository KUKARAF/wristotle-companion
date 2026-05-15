cmake_minimum_required(VERSION 3.22)
project(wristotle_speech LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Phase 2a: hello-world JNI library only. Phase 2b will add whisper.cpp as a
# git submodule under cpp/whisper.cpp/ and link it in here.
add_library(wristotle_speech SHARED
    wristotle_speech.cpp
)

# Standard Android logging.
find_library(log-lib log)
target_link_libraries(wristotle_speech ${log-lib})

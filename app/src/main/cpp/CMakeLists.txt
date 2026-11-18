cmake_minimum_required(VERSION 3.22)
project(yinlang_airplay LANGUAGES C CXX)

include(FetchContent)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(AIRPLAY_BUILD_EXAMPLE OFF CACHE BOOL "" FORCE)
set(AIRPLAY_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(AIRPLAY_BUILD_QT_HOST OFF CACHE BOOL "" FORCE)
FetchContent_Declare(airplay_sender
    GIT_REPOSITORY https://github.com/akustikrausch/airplay2-sender-cpp.git
    GIT_TAG fc913509039412790aa2427558d35178aa7842ea)
FetchContent_GetProperties(airplay_sender)
if(NOT airplay_sender_POPULATED)
    FetchContent_Populate(airplay_sender)
    # The pinned library's size_t shift must be guarded on 32-bit Android.
    set(ring_header "${airplay_sender_SOURCE_DIR}/src/ring_buffer.h")
    file(READ "${ring_header}" ring_source)
    set(ring_fix "if constexpr (sizeof(size_t) > 4) v |= v >> 32;")
    if(NOT ring_source MATCHES "if constexpr")
        string(REPLACE "v |= v >> 32;" "${ring_fix}" ring_source "${ring_source}")
        file(WRITE "${ring_header}" "${ring_source}")
    endif()
    add_subdirectory("${airplay_sender_SOURCE_DIR}" "${airplay_sender_BINARY_DIR}")
endif()

add_library(yinlang_airplay SHARED airplay_jni.cpp)
target_compile_features(yinlang_airplay PRIVATE cxx_std_20)
target_link_libraries(yinlang_airplay PRIVATE raop_sender log)

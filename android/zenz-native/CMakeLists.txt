# zenz candidate scorer on top of a pinned llama.cpp source tree (LLAMA_DIR, not vendored in Git).
# Host builds produce the zenz-score evaluation CLI; Android builds produce the JNI library.
cmake_minimum_required(VERSION 3.22)
project(astra_zenz CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT LLAMA_DIR)
    message(FATAL_ERROR "Set -DLLAMA_DIR=<llama.cpp source root>")
endif()

set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
if(ANDROID)
    set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
endif()
add_subdirectory(${LLAMA_DIR} llama EXCLUDE_FROM_ALL)

add_library(zenzscore STATIC src/zenz_scorer.cpp)
target_include_directories(zenzscore PUBLIC src)
target_link_libraries(zenzscore PUBLIC llama)

# JNI library: packaged for Android; on the host it lets the JVM evaluation tools call the same code.
add_library(astra_zenz SHARED src/zenz_jni.cpp)
target_link_libraries(astra_zenz PRIVATE zenzscore)
if(ANDROID)
    target_link_options(astra_zenz PRIVATE "-Wl,-z,max-page-size=16384")
else()
    # Host only: headers from the JDK given as -DJAVA_HOME (FindJNI is not needed for two include paths).
    if(NOT JAVA_HOME)
        message(FATAL_ERROR "Set -DJAVA_HOME=<jdk> for the host JNI library")
    endif()
    target_include_directories(astra_zenz PRIVATE "${JAVA_HOME}/include" "${JAVA_HOME}/include/win32")
    target_link_options(astra_zenz PRIVATE -static-libgcc -static-libstdc++ -Wl,--add-stdcall-alias)
    add_executable(zenz-score src/zenz_score_cli.cpp)
    target_link_libraries(zenz-score PRIVATE zenzscore)
    add_executable(zenz-cache-check src/zenz_cache_check.cpp)
    target_link_libraries(zenz-cache-check PRIVATE zenzscore)
endif()

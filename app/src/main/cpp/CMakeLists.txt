cmake_minimum_required(VERSION 3.22.1)

project("myapplication" CXX)

# --- Create the Library ---
# We create the shared library and will add all other necessary files to it.
add_library(myapplication SHARED
    native-lib.cpp
)

# --- Add Subdirectories ---
# Add the vendors as subdirectories to inherit their CMake targets instead of linking source files manually.
# Since we only want the static libraries, we disable building their tests, examples, and server.
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "Disable Llama tests" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "Disable Llama examples" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "Disable Llama server" FORCE)
set(LLAMA_BUILD_COMMON ON CACHE BOOL "Build Llama common library" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "Disable Whisper tests")
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "Disable Whisper examples")

# Disable shared libs for the dependencies
set(BUILD_SHARED_LIBS OFF CACHE BOOL "Build static dependencies")

add_subdirectory(vendor/llama.cpp)
add_subdirectory(vendor/whisper.cpp)

# --- Add Include Directories ---
target_include_directories(myapplication PRIVATE
    vendor/whisper.cpp/include
    vendor/llama.cpp/include
    vendor/llama.cpp/common
    vendor/llama.cpp/ggml/include
)

# --- Add Compiler Definitions ---
# Required by the libraries to configure themselves correctly during compilation.
target_compile_definitions(myapplication PRIVATE
    GGML_SILENT
    WHISPER_SILENT
    # K-quants are specialized quantization methods used by the models
    GGML_USE_K_QUANTS
    WHISPER_USE_K_QUANTS
    WHISPER_VERSION="1.7.0"
)

# --- Compiler Optimisation Flags ---
target_compile_options(myapplication PRIVATE
    -O3
    -ffast-math
)

# --- Link To Android Libraries ---
# Link against the native Android logging library for log output.
find_library(log-lib log)
target_link_libraries(myapplication PRIVATE 
    ${log-lib}
    llama
    ggml
    common
    whisper
)

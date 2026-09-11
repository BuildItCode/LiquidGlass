cmake_minimum_required(VERSION 3.22.1)
project(glass_blur LANGUAGES CXX)

add_library(glass_blur SHARED stack_blur.cpp)
target_compile_features(glass_blur PRIVATE cxx_std_17)
target_link_libraries(glass_blur PRIVATE jnigraphics)
# This pixel loop must be optimized in debug/demo builds too. No fast-math or
# approximation: integer normalization stays identical to the Kotlin fallback.
target_compile_options(glass_blur PRIVATE -O3 -fno-exceptions -fno-rtti -Wall -Wextra)
target_link_options(glass_blur PRIVATE "-Wl,-z,max-page-size=16384")

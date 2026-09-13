cmake_minimum_required(VERSION 4.1)

# 跳过所有 install (含 EXPORT) 生成，
set(CMAKE_SKIP_INSTALL_RULES ON CACHE BOOL "" FORCE)

project("lpty")

set(CMAKE_C_STANDARD 23)
set(CMAKE_CXX_STANDARD 23)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(LPTY_COMPILE_OPTIONS
    -Wall -Wextra -Werror
    -O3
    -fno-stack-protector
    -flto
)

set(LPTY_LINK_OPTIONS
    -Wl,--gc-sections
    -Wl,-s
    -flto
)

include_directories(
    ${CMAKE_CURRENT_SOURCE_DIR}/include
)

add_library(pty SHARED
    pty.cpp
)

target_compile_options(pty PRIVATE
    ${LPTY_COMPILE_OPTIONS}
)

target_link_options(pty PRIVATE
    ${LPTY_LINK_OPTIONS}
)
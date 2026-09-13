cmake_minimum_required(VERSION 4.1)

# 跳过所有 install (含 EXPORT) 生成，
set(CMAKE_SKIP_INSTALL_RULES ON CACHE BOOL "" FORCE)

project("terminal")

set(CMAKE_C_STANDARD 23)
set(CMAKE_CXX_STANDARD 23)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(TERMINAL_COMPILE_OPTIONS
    -Wall -Wextra -Werror
    -O3
    -fno-stack-protector
    -flto
)

set(TERMINAL_LINK_OPTIONS
    -Wl,--gc-sections
    -Wl,-s
    -flto
)

include_directories(
    ${CMAKE_CURRENT_SOURCE_DIR}/include
)

set(THIRD_PARTY_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party)

set(MBEDTLS_USER_CONFIG_FILE
    "${THIRD_PARTY_DIR}/mbedtls_user_config.h"
    CACHE FILEPATH "" FORCE)
set(USE_SHARED_MBEDTLS_LIBRARY OFF CACHE BOOL "" FORCE)
set(USE_STATIC_MBEDTLS_LIBRARY  ON  CACHE BOOL "" FORCE)
set(ENABLE_PROGRAMS OFF CACHE BOOL "" FORCE)
set(ENABLE_TESTING OFF CACHE BOOL "" FORCE)
set(GEN_FILES       OFF CACHE BOOL "" FORCE)
set(CMAKE_POSITION_INDEPENDENT_CODE ON CACHE BOOL "" FORCE)

add_subdirectory(${THIRD_PARTY_DIR}/mbedtls mbedtls)

set(MBEDTLS_INCLUDE_DIR
    "${THIRD_PARTY_DIR}/mbedtls/include"
    CACHE PATH "" FORCE)
set(MBEDTLS_SSL_LIBRARY
    "${THIRD_PARTY_DIR}/mbedtls/library/libmbedtls.a"
    CACHE FILEPATH "" FORCE)
set(MBEDTLS_CRYPTO_LIBRARY
    "${THIRD_PARTY_DIR}/mbedtls/library/libmbedcrypto.a"
    CACHE FILEPATH "" FORCE)
set(MBEDTLS_X509_LIBRARY
    "${THIRD_PARTY_DIR}/mbedtls/library/libmbedx509.a"
    CACHE FILEPATH "" FORCE)

set(WITH_MBEDTLS   ON  CACHE BOOL "" FORCE)
set(WITH_SERVER    OFF CACHE BOOL "" FORCE)
set(WITH_ZLIB      OFF CACHE BOOL "" FORCE)
set(WITH_EXAMPLES  OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(BUILD_STATIC_LIB  OFF CACHE BOOL "" FORCE)

add_subdirectory(${THIRD_PARTY_DIR}/libssh libssh)

# libssh 的部分平台代码依赖 glibc/bionic 的 BSD 扩展宏
target_compile_definitions(ssh PRIVATE _BSD_SOURCE=1)

add_subdirectory(pty)
add_subdirectory(ssh)
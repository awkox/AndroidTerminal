add_library(sshterm SHARED
    sshterm.cpp
)

target_compile_options(sshterm PRIVATE
    ${TERMINAL_COMPILE_OPTIONS}
)

target_include_directories(sshterm PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/../include
)

target_link_libraries(sshterm PRIVATE
    ssh::ssh
)

target_link_options(sshterm PRIVATE
    -Wl,--exclude-libs,ALL
    ${TERMINAL_LINK_OPTIONS}
)
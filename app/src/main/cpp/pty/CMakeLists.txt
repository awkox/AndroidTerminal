add_library(pty SHARED
    pty.cpp
)

target_compile_options(pty PRIVATE
    ${TERMINAL_COMPILE_OPTIONS}
)

target_link_options(pty PRIVATE
    ${TERMINAL_LINK_OPTIONS}
)
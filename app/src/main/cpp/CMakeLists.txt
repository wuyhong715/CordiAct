cmake_minimum_required(VERSION 3.22.1)
project(cordiact_s3_quickjs LANGUAGES C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_C_EXTENSIONS ON)

# Keep this source list aligned with QuickJS-NG v0.17.0's upstream `qjs` core target.
# Deliberately do not compile quickjs-libc.c: it supplies std/os modules and file helpers.
set(QUICKJS_NG_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/quickjs-ng")
foreach(required_file quickjs.c quickjs.h dtoa.c libregexp.c libunicode.c)
    if(NOT EXISTS "${QUICKJS_NG_DIR}/${required_file}")
        message(FATAL_ERROR "Pinned QuickJS-NG source is missing: ${required_file}")
    endif()
endforeach()

add_library(quickjs_ng STATIC
    "${QUICKJS_NG_DIR}/dtoa.c"
    "${QUICKJS_NG_DIR}/libregexp.c"
    "${QUICKJS_NG_DIR}/libunicode.c"
    "${QUICKJS_NG_DIR}/quickjs.c"
)
target_include_directories(quickjs_ng PUBLIC "${QUICKJS_NG_DIR}")
target_compile_definitions(quickjs_ng PRIVATE _GNU_SOURCE)
find_package(Threads REQUIRED)
target_link_libraries(quickjs_ng PUBLIC m ${CMAKE_DL_LIBS} Threads::Threads)

add_library(cordiact_qjs_poc SHARED quickjs_poc_jni.c)
target_compile_definitions(cordiact_qjs_poc PRIVATE _GNU_SOURCE)
target_compile_options(cordiact_qjs_poc PRIVATE -Wall -Wextra -Werror)
target_link_libraries(cordiact_qjs_poc PRIVATE quickjs_ng m ${CMAKE_DL_LIBS} log)

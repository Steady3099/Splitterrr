cmake_minimum_required(VERSION 3.10.2)
project("splitterrr")

add_library(
        native_lib
        SHARED
        native_lib.cpp
)

find_library(
        log-lib
        log
)

target_link_libraries(
        native_lib
        ${log-lib}
)

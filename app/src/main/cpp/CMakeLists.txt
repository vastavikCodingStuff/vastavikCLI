cmake_minimum_required(VERSION 3.22.1)
project("shellzero-pty")

add_library(shellzero-pty SHARED pty.cpp)

find_library(log-lib log)

target_link_libraries(shellzero-pty ${log-lib})

cmake_minimum_required(VERSION 3.28)
project(lsplant)

find_program(CCACHE ccache)

set(CMAKE_CXX_STANDARD 23)

if (CCACHE)
    set(CMAKE_CXX_COMPILER_LAUNCHER ${CCACHE})
    set(CMAKE_C_COMPILER_LAUNCHER ${CCACHE})
endif ()

if (LSPLANT_STANDALONE)
    find_package(cxx REQUIRED CONFIG)
    link_libraries(cxx::cxx)
endif()

set(SOURCES lsplant.cc)
file(GLOB_RECURSE MODULE_SOURCES CONFIGURE_DEPENDS "*.cxx")
file(GLOB_RECURSE MODULE_INTERFACES CONFIGURE_DEPENDS "*.ixx")
list(FILTER MODULE_SOURCES EXCLUDE REGEX "${PROJECT_SOURCE_DIR}.external.+")
list(FILTER MODULE_INTERFACES EXCLUDE REGEX "${PROJECT_SOURCE_DIR}.external.+")

set(DEX_BUILDER_BUILD_SHARED OFF CACHE INTERNAL "" FORCE)
add_subdirectory(external/dex_builder)

option(LSPLANT_BUILD_SHARED "If ON, lsplant will also build shared library" ON)

if (NOT DEFINED DEBUG_SYMBOLS_PATH)
    set(DEBUG_SYMBOLS_PATH ${CMAKE_BINARY_DIR}/symbols)
endif()

if (LSPLANT_BUILD_SHARED)
    message(STATUS "Building lsplant as a shared library")
    add_library(${PROJECT_NAME} SHARED ${SOURCES})
    set_target_properties(${PROJECT_NAME} PROPERTIES CXX_SCAN_FOR_MODULES ON)
    target_sources(${PROJECT_NAME} PRIVATE FILE_SET ms TYPE CXX_MODULES FILES ${MODULE_SOURCES})
    target_sources(${PROJECT_NAME} PUBLIC FILE_SET CXX_MODULES FILES ${MODULE_INTERFACES})
    target_include_directories(${PROJECT_NAME} PUBLIC include)
    target_include_directories(${PROJECT_NAME} PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
    target_compile_options(${PROJECT_NAME} PRIVATE -flto)
    target_link_options(${PROJECT_NAME} PRIVATE -flto)
    add_custom_command(TARGET ${PROJECT_NAME} POST_BUILD
            COMMAND ${CMAKE_COMMAND} -E make_directory ${DEBUG_SYMBOLS_PATH}/${ANDROID_ABI}
            COMMAND ${CMAKE_OBJCOPY} --only-keep-debug $<TARGET_FILE:${PROJECT_NAME}>
            ${DEBUG_SYMBOLS_PATH}/${ANDROID_ABI}/${PROJECT_NAME}
            COMMAND ${CMAKE_STRIP} --strip-all $<TARGET_FILE:${PROJECT_NAME}>)

    target_link_libraries(${PROJECT_NAME} PRIVATE dex_builder_static)
    target_link_libraries(${PROJECT_NAME} PUBLIC log)
endif()

# ─────────────────────────────────────────────────────────────────────────────
# LSPlantPro prefab 发布开关。
# ─────────────────────────────────────────────────────────────────────────────
#   ON（:lsplant 模块 prefab 发布，见 build.gradle.kts -DLSPLANT_PREFAB_MERGE=ON）：
#     ① 静态目标直接命名为 lsplant（artifactName=lsplant），使 AGP prefab 按模块名 lsplant 匹配到本目标
#        （AGP 报 [CXX1404] 时即因找不到名为 lsplant 的目标；LSPLANT_BUILD_SHARED=OFF 时无 shared 同名冲突）。
#     ② POST_BUILD 把 dex_builder_static 合并进产物，产出自包含 liblsplant.a。
#   OFF（默认，:lib 源码构建路径 add_subdirectory）：保持 lsplant_static 原名与原行为，回退零风险。
option(LSPLANT_PREFAB_MERGE "Merge dex_builder into lsplant static lib for self-contained prefab" OFF)
if (LSPLANT_PREFAB_MERGE)
    set(LSPLANT_STATIC_TARGET ${PROJECT_NAME})          # 目标名=lsplant → AGP prefab 模块名匹配
else ()
    set(LSPLANT_STATIC_TARGET ${PROJECT_NAME}_static)   # 目标名=lsplant_static → :lib 源码构建原样链接
endif ()

add_library(${LSPLANT_STATIC_TARGET} STATIC ${SOURCES})
set_target_properties(${LSPLANT_STATIC_TARGET} PROPERTIES CXX_SCAN_FOR_MODULES ON)
target_sources(${LSPLANT_STATIC_TARGET} PRIVATE FILE_SET ms TYPE CXX_MODULES FILES ${MODULE_SOURCES})
target_sources(${LSPLANT_STATIC_TARGET} PUBLIC FILE_SET CXX_MODULES FILES ${MODULE_INTERFACES})
target_include_directories(${LSPLANT_STATIC_TARGET} PUBLIC include)
target_include_directories(${LSPLANT_STATIC_TARGET} PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

target_link_libraries(${LSPLANT_STATIC_TARGET} PRIVATE dex_builder_static)
target_link_libraries(${LSPLANT_STATIC_TARGET} PUBLIC log)

# ─────────────────────────────────────────────────────────────────────────────
# 自包含合并（仅 prefab 发布）：把 dex_builder_static 合并进 liblsplant.a。
# ─────────────────────────────────────────────────────────────────────────────
# 背景：liblsplant.a 仅含 lsplant 自身目标文件，对 startop::dex::DexBuilder 等符号为 UNDEFINED
#   （定义在 external/dex_builder 的 libdex_builder_static.a）。源码构建路径靠 CMake 传递链接
#   自动带上 dex_builder_static，故无需合并；但预编译 prefab 消费端拿到的只有单个 .a，必须自包含，
#   否则 libvabe.so 链接期报 undefined symbol。用 llvm-ar 的 MRI 脚本展开合并两档并重建符号索引。
if (LSPLANT_PREFAB_MERGE)
    message(STATUS "LSPlantPro: merging dex_builder_static into liblsplant.a (self-contained prefab)")

    # 配置期生成 cmake -P 合并脚本（写入 build 目录，不污染上游源码树）。
    set(_lsplant_merge_script ${CMAKE_CURRENT_BINARY_DIR}/lsplant_merge_static.cmake)
    file(WRITE ${_lsplant_merge_script} [=[
# 用 AR 的 MRI 脚本把多个 .a 的成员对象展开合并为单个自包含 .a，再重建符号索引。
execute_process(COMMAND "${AR}" -M INPUT_FILE "${MRI}" RESULT_VARIABLE _rc)
if (NOT _rc EQUAL 0)
    message(FATAL_ERROR "LSPlantPro: llvm-ar MRI merge failed rc=${_rc}")
endif ()
file(RENAME "${OUT_TMP}" "${FINAL}")
execute_process(COMMAND "${AR}" s "${FINAL}" RESULT_VARIABLE _rc2)
if (NOT _rc2 EQUAL 0)
    message(FATAL_ERROR "LSPlantPro: llvm-ar index (ranlib) failed rc=${_rc2}")
endif ()
]=])

    # 生成期落 MRI 脚本（含 $<TARGET_FILE:>，必须用 file(GENERATE)）：先 create 到 .combined 临时档，
    # 再 addlib 两个源档的成员，避免 create 直接覆盖 liblsplant.a 导致自身对象丢失。
    set(_lsplant_mri ${CMAKE_CURRENT_BINARY_DIR}/lsplant_merge.mri)
    file(GENERATE OUTPUT ${_lsplant_mri} CONTENT
"create $<TARGET_FILE:${LSPLANT_STATIC_TARGET}>.combined
addlib $<TARGET_FILE:${LSPLANT_STATIC_TARGET}>
addlib $<TARGET_FILE:dex_builder_static>
save
end
")

    add_custom_command(TARGET ${LSPLANT_STATIC_TARGET} POST_BUILD
            COMMAND ${CMAKE_COMMAND}
            -DAR=${CMAKE_AR}
            -DMRI=${_lsplant_mri}
            -DOUT_TMP=$<TARGET_FILE:${LSPLANT_STATIC_TARGET}>.combined
            -DFINAL=$<TARGET_FILE:${LSPLANT_STATIC_TARGET}>
            -P ${_lsplant_merge_script}
            COMMENT "LSPlantPro: merge dex_builder_static -> liblsplant.a (self-contained)"
            VERBATIM)
endif ()

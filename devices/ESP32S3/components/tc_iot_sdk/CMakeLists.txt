# 闭源 tc_iot_sdk 组件：include/ 公开 API 头 + lib/ 闭源静态库。
#
# 链接方式：configure 期用工具链 ar 把静态库解包成目标文件，以 SRCS 注入本
# 组件。这样 libtc_iot_sdk.a 以普通 ESP-IDF 组件库身份参与链接，与 bt、
# mbedtls 等依赖组件保持正常解析顺序（外部 .a 直接挂链接行会游离在组件序列
# 之外，SDK 引用的 NimBLE/mbedtls 符号无法回填）。
#
# 注意：不能用 CMAKE_AR——组件需求收集阶段 toolchain 尚未加载，该变量未定义；
# 直接 find_program 定位（客户 shell 已激活 IDF，PATH 含工具链 bin）。
set(_sdk_prebuilt "${CMAKE_CURRENT_LIST_DIR}/lib/libtc_iot_sdk.a")
set(_sdk_obj_dir "${CMAKE_CURRENT_BINARY_DIR}/sdk_objects")

# .a 更新（换版本）时触发重新 configure/解包
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${_sdk_prebuilt}")

find_program(_sdk_ar_tool NAMES xtensa-esp-elf-ar)
if(NOT _sdk_ar_tool)
    message(FATAL_ERROR
        "tc_iot_sdk: 未找到 xtensa-esp-elf-ar。请在已激活 ESP-IDF 的终端中执行 idf.py"
        "（export.sh/激活脚本会把工具链加入 PATH）。")
endif()

file(MAKE_DIRECTORY "${_sdk_obj_dir}")
execute_process(
    COMMAND "${_sdk_ar_tool}" x "${_sdk_prebuilt}"
    WORKING_DIRECTORY "${_sdk_obj_dir}"
    RESULT_VARIABLE _sdk_ar_result
)
if(NOT _sdk_ar_result EQUAL 0)
    message(FATAL_ERROR "tc_iot_sdk: 解包 ${_sdk_prebuilt} 失败 (ar exit ${_sdk_ar_result})")
endif()

file(GLOB _sdk_objects "${_sdk_obj_dir}/*.obj" "${_sdk_obj_dir}/*.o")
list(LENGTH _sdk_objects _sdk_obj_count)
if(_sdk_obj_count EQUAL 0)
    message(FATAL_ERROR "tc_iot_sdk: ${_sdk_prebuilt} 解包后未得到目标文件")
endif()

idf_component_register(
    SRCS ${_sdk_objects}
    INCLUDE_DIRS "include"
    REQUIRES bt nvs_flash esp_timer esp_http_client esp_netif mbedtls fatfs
)

cmake_minimum_required(VERSION 3.16)
project(fcitx5-predict-ja-notifier VERSION 0.1.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(Fcitx5Core REQUIRED)
find_package(CURL REQUIRED)

include(GNUInstallDirs)
set(FCITX_INSTALL_PKGDATADIR "${CMAKE_INSTALL_PREFIX}/share/fcitx5")
set(FCITX_INSTALL_ADDONDIR "${FCITX_INSTALL_PKGDATADIR}/addon")
set(FCITX_INSTALL_LIBDIR "${CMAKE_INSTALL_PREFIX}/lib/fcitx5")

add_library(predict-ja-notifier MODULE
    src/predict-ja-notifier.cpp
)

target_link_libraries(predict-ja-notifier
    Fcitx5::Core
    CURL::libcurl
)

set_target_properties(predict-ja-notifier PROPERTIES
    PREFIX ""
    LIBRARY_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/lib"
)

install(TARGETS predict-ja-notifier DESTINATION "${FCITX_INSTALL_LIBDIR}")
install(FILES src/predict-ja-notifier-addon.conf
        DESTINATION "${FCITX_INSTALL_ADDONDIR}"
        RENAME predict-ja-notifier.conf)

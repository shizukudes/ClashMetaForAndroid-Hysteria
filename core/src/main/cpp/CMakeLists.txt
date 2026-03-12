cmake_minimum_required(VERSION 3.0)

# 获取git hash

message(STATUS "CMAKE_CURRENT_SOURCE_DIR= ${CMAKE_CURRENT_SOURCE_DIR}")
execute_process(
  COMMAND git rev-parse --short HEAD
  WORKING_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR} 
  OUTPUT_VARIABLE COMMIT_HASH
)
string(STRIP "${COMMIT_HASH}" COMMIT_HASH)
message(STATUS "git hash= ${COMMIT_HASH}")

# 获取分支名称
execute_process(
  COMMAND git rev-parse --abbrev-ref HEAD
  WORKING_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR} 
  OUTPUT_VARIABLE CURRENT_BRANCH
)
string(STRIP "${CURRENT_BRANCH}" CURRENT_BRANCH)
message(STATUS "git current branch = ${CURRENT_BRANCH}")

# 获取生成时间
string(TIMESTAMP COMPILE_TIME "%y%m%d")
string (REGEX REPLACE "[\n\t\r]" "" COMPILE_TIME ${COMPILE_TIME})
string(REGEX REPLACE "\"" "" COMPILE_TIME ${COMPILE_TIME})

# 生成版本信息
set(GIT_VERSION "${CURRENT_BRANCH}_${COMMIT_HASH}_${COMPILE_TIME}")
message(STATUS "version info = ${GIT_VERSION}")

# 去除空格
string(REGEX REPLACE "[ ]+" "" GIT_VERSION "${GIT_VERSION}")

# 保存变量 do 文件
configure_file(${CMAKE_CURRENT_SOURCE_DIR}/version.h.in ${CMAKE_CURRENT_SOURCE_DIR}/version.h @ONLY)

project(clash-bridge C)


set(CMAKE_POSITION_INDEPENDENT_CODE on)
set(CMAKE_C_FLAGS_RELEASE "${CMAKE_C_FLAGS_RELEASE} -O3")

set(GO_OUTPUT_BASE ${GO_OUTPUT}/${FLAVOR_NAME})

if ("${CMAKE_BUILD_TYPE}" STREQUAL "Debug")
    set(GO_OUTPUT_BASE "${GO_OUTPUT_BASE}Debug")
elseif ("${CMAKE_BUILD_TYPE}" STREQUAL "Release")
    set(GO_OUTPUT_BASE "${GO_OUTPUT_BASE}Release")
elseif ("${CMAKE_BUILD_TYPE}" STREQUAL "RelWithDebInfo")
    set(GO_OUTPUT_BASE "${GO_OUTPUT_BASE}Release")
else ()
    message(FATAL_ERROR "Unknown build type ${CMAKE_BUILD_TYPE}")
endif ()

include_directories("${GO_OUTPUT_BASE}/${CMAKE_ANDROID_ARCH_ABI}")
include_directories("${GO_SOURCE}")

link_directories("${GO_OUTPUT_BASE}/${CMAKE_ANDROID_ARCH_ABI}")

# --- badvpn tun2socks configuration ---
set(BADVPN_DIR ${CMAKE_CURRENT_SOURCE_DIR}/badvpn)

add_definitions(
    -DBADVPN_THREADWORK_USE_PTHREAD -DBADVPN_LINUX -DBADVPN_BREACTOR_BADVPN -D_GNU_SOURCE
    -DBADVPN_USE_SELFPIPE -DBADVPN_USE_EPOLL
    -DBADVPN_LITTLE_ENDIAN -DBADVPN_THREAD_SAFE
    -DNDEBUG
)

include_directories(
    ${BADVPN_DIR}
    ${BADVPN_DIR}/libancillary
    ${BADVPN_DIR}/lwip/src/include/ipv4
    ${BADVPN_DIR}/lwip/src/include/ipv6
    ${BADVPN_DIR}/lwip/src/include
    ${BADVPN_DIR}/lwip/custom
)

set(TUN2SOCKS_SOURCES
    ${BADVPN_DIR}/libancillary/fd_recv.c
    ${BADVPN_DIR}/libancillary/fd_send.c
    ${BADVPN_DIR}/base/BLog_syslog.c
    ${BADVPN_DIR}/system/BReactor_badvpn.c
    ${BADVPN_DIR}/system/BSignal.c
    ${BADVPN_DIR}/system/BConnection_unix.c
    ${BADVPN_DIR}/system/BTime.c
    ${BADVPN_DIR}/system/BUnixSignal.c
    ${BADVPN_DIR}/system/BNetwork.c
    ${BADVPN_DIR}/flow/StreamRecvInterface.c
    ${BADVPN_DIR}/flow/PacketRecvInterface.c
    ${BADVPN_DIR}/flow/PacketPassInterface.c
    ${BADVPN_DIR}/flow/StreamPassInterface.c
    ${BADVPN_DIR}/flow/SinglePacketBuffer.c
    ${BADVPN_DIR}/flow/BufferWriter.c
    ${BADVPN_DIR}/flow/PacketBuffer.c
    ${BADVPN_DIR}/flow/PacketStreamSender.c
    ${BADVPN_DIR}/flow/PacketPassConnector.c
    ${BADVPN_DIR}/flow/PacketProtoFlow.c
    ${BADVPN_DIR}/flow/PacketPassFairQueue.c
    ${BADVPN_DIR}/flow/PacketProtoEncoder.c
    ${BADVPN_DIR}/flow/PacketProtoDecoder.c
    ${BADVPN_DIR}/socksclient/BSocksClient.c
    ${BADVPN_DIR}/tuntap/BTap.c
    ${BADVPN_DIR}/lwip/src/core/timers.c
    ${BADVPN_DIR}/lwip/src/core/udp.c
    ${BADVPN_DIR}/lwip/src/core/memp.c
    ${BADVPN_DIR}/lwip/src/core/init.c
    ${BADVPN_DIR}/lwip/src/core/pbuf.c
    ${BADVPN_DIR}/lwip/src/core/tcp.c
    ${BADVPN_DIR}/lwip/src/core/tcp_out.c
    ${BADVPN_DIR}/lwip/src/core/netif.c
    ${BADVPN_DIR}/lwip/src/core/def.c
    ${BADVPN_DIR}/lwip/src/core/mem.c
    ${BADVPN_DIR}/lwip/src/core/tcp_in.c
    ${BADVPN_DIR}/lwip/src/core/stats.c
    ${BADVPN_DIR}/lwip/src/core/inet_chksum.c
    ${BADVPN_DIR}/lwip/src/core/ipv4/icmp.c
    ${BADVPN_DIR}/lwip/src/core/ipv4/igmp.c
    ${BADVPN_DIR}/lwip/src/core/ipv4/ip4_addr.c
    ${BADVPN_DIR}/lwip/src/core/ipv4/ip_frag.c
    ${BADVPN_DIR}/lwip/src/core/ipv4/ip4.c
    ${BADVPN_DIR}/lwip/src/core/ipv4/autoip.c
    ${BADVPN_DIR}/lwip/src/core/ipv6/ethip6.c
    ${BADVPN_DIR}/lwip/src/core/ipv6/inet6.c
    ${BADVPN_DIR}/lwip/src/core/ipv6/ip6_addr.c
    ${BADVPN_DIR}/lwip/src/core/ipv6/mld6.c
    ${BADVPN_DIR}/lwip/src/core/ipv6/dhcp6.c
    ${BADVPN_DIR}/lwip/src/core/ipv6/icmp6.c
    ${BADVPN_DIR}/lwip/src/core/ipv6/ip6.c
    ${BADVPN_DIR}/lwip/src/core/ipv6/ip6_frag.c
    ${BADVPN_DIR}/lwip/src/core/ipv6/nd6.c
    ${BADVPN_DIR}/lwip/custom/sys.c
    ${BADVPN_DIR}/tun2socks/tun2socks.c
    ${BADVPN_DIR}/base/DebugObject.c
    ${BADVPN_DIR}/base/BLog.c
    ${BADVPN_DIR}/base/BPending.c
    ${BADVPN_DIR}/system/BDatagram_unix.c
    ${BADVPN_DIR}/flowextra/PacketPassInactivityMonitor.c
    ${BADVPN_DIR}/tun2socks/SocksUdpGwClient.c
    ${BADVPN_DIR}/udpgw_client/UdpGwClient.c
)

add_library(bridge SHARED main.c jni_helper.c bridge_helper.c ${TUN2SOCKS_SOURCES})
target_link_libraries(bridge log clash)

# --- pdnsd integration ---
set(PDNSD_DIR ${CMAKE_CURRENT_SOURCE_DIR}/pdnsd)
include_directories(${PDNSD_DIR} ${PDNSD_DIR}/src)

add_executable(pdnsd
    ${PDNSD_DIR}/src/cache.c
    ${PDNSD_DIR}/src/conf-parser.c
    ${PDNSD_DIR}/src/conff.c
    ${PDNSD_DIR}/src/consts.c
    ${PDNSD_DIR}/src/debug.c
    ${PDNSD_DIR}/src/dns.c
    ${PDNSD_DIR}/src/dns_answer.c
    ${PDNSD_DIR}/src/dns_query.c
    ${PDNSD_DIR}/src/error.c
    ${PDNSD_DIR}/src/hash.c
    ${PDNSD_DIR}/src/helpers.c
    ${PDNSD_DIR}/src/icmp.c
    ${PDNSD_DIR}/src/list.c
    ${PDNSD_DIR}/src/main.c
    ${PDNSD_DIR}/src/netdev.c
    ${PDNSD_DIR}/src/rr_types.c
    ${PDNSD_DIR}/src/servers.c
    ${PDNSD_DIR}/src/status.c
    ${PDNSD_DIR}/src/thread.c
)

target_compile_definitions(pdnsd PRIVATE 
    _GNU_SOURCE __ANDROID__ PDNSD_CONFIG_FILE="/data/local/tmp/pdnsd.conf"
)
target_link_libraries(pdnsd log)
# --- end pdnsd ---

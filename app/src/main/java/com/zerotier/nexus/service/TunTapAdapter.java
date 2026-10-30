package com.zerotier.nexus.service;

import android.os.ParcelFileDescriptor;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import com.zerotier.sdk.Node;
import com.zerotier.sdk.ResultCode;
import com.zerotier.sdk.VirtualNetworkConfig;
import com.zerotier.sdk.VirtualNetworkFrameListener;
import com.zerotier.sdk.util.StringUtils;

import com.zerotier.nexus.util.DebugLog;
import com.zerotier.nexus.util.IPPacketUtils;
import com.zerotier.nexus.util.InetAddressUtils;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import com.zerotier.nexus.ZerotierFixApplication;
import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

// TODO: clear up
public class TunTapAdapter implements VirtualNetworkFrameListener {
    public static final String TAG = "TunTapAdapter";
    private static final int ARP_PACKET = 2054;
    private static final int IPV4_PACKET = 2048;
    private static final int IPV6_PACKET = 34525;
    private final HashMap<Route, Long> routeMap = new HashMap<>();
    private final long networkId;
    private final ZeroTierOneService ztService;
    private ARPTable arpTable = new ARPTable();
    private FileInputStream in;
    private NDPTable ndpTable = new NDPTable();
    private Node node;
    private FileOutputStream out;
    private Thread receiveThread;
    private ParcelFileDescriptor vpnSocket;
    private com.zerotier.nexus.util.Socks5UdpRelay socks5Relay;
    private com.zerotier.nexus.util.TcpSocksRelay tcpSocksRelay;

    // --- 代理增强状态缓存 ---
    private volatile boolean isTuicEnabled = false;
    private volatile boolean isRoamingEnabled = false; 
    private volatile String roamingGatewayV4 = null;
    private volatile String roamingGatewayV6 = null;
    private volatile String proxyHost = "";
    private volatile int proxyPort = 10004;
    private volatile long roamingNetworkId = 0;


    public TunTapAdapter(ZeroTierOneService zeroTierOneService, long j) {
        this.ztService = zeroTierOneService;
        this.networkId = j;
    }

    public static long multicastAddressToMAC(InetAddress inetAddress) {
        if (inetAddress instanceof Inet4Address) {
            byte[] address = inetAddress.getAddress();
            return ByteBuffer.wrap(new byte[]{0, 0, 1, 0, 94, (byte) (address[1] & Byte.MAX_VALUE), address[2], address[3]}).getLong();
        } else if (!(inetAddress instanceof Inet6Address)) {
            return 0;
        } else {
            byte[] address2 = inetAddress.getAddress();
            return ByteBuffer.wrap(new byte[]{0, 0, 51, 51, address2[12], address2[13], address2[14], address2[15]}).getLong();
        }
    }

    private void addMulticastRoutes() {
    }

    public void setNode(Node node) {
        if (node == null) return;
        this.node = node;
        try {
            InetAddress multicastAddress = InetAddress.getByName("224.224.224.224");
            ResultCode result = node
                    .multicastSubscribe(this.networkId, multicastAddressToMAC(multicastAddress));
            if (result != ResultCode.RESULT_OK) {
                Log.e(TAG, "Error when calling multicastSubscribe: " + result);
            }
        } catch (UnknownHostException e) {
            Log.e(TAG, e.toString(), e);
        }
    }

    public void setVpnSocket(ParcelFileDescriptor vpnSocket) {
        this.vpnSocket = vpnSocket;
    }

    public void setFileStreams(FileInputStream fileInputStream, FileOutputStream fileOutputStream) {

        this.in = fileInputStream;
        this.out = fileOutputStream;
    }

    /**
     * 更新代理补强状态 (由 Service 调用)
     */
    public synchronized void updateProxyConfig(boolean enabled, boolean roaming, String host, int port) {
        // 如果端口或主机发生变化，且 relay 已存在，则需要重置 relay
        if (enabled && (port != this.proxyPort || !Objects.equals(host, this.proxyHost))) {
            if (this.socks5Relay != null) {
                Log.i(TAG, "Proxy port/host changed, stopping relay to re-initialize on port " + port);
                this.socks5Relay.stop();
                this.socks5Relay = null;
            }
        }
        
        this.isTuicEnabled = enabled;
        this.isRoamingEnabled = roaming;
        if (host != null) this.proxyHost = host;
        if (port != -1) this.proxyPort = port;
        
        Log.i(TAG, "Syncing Mode: Roaming=" + roaming + ", TUIC=" + enabled);
        Log.i(TAG, "Proxy Config Updated: enabled=" + enabled + ", roaming=" + roaming + ", host=" + this.proxyHost + ":" + this.proxyPort);
        
        // 如果补强开启，确保 TCP Relay 也启动
        if (enabled) {
            if (this.tcpSocksRelay == null) {
                int localPort = this.proxyPort;
                if (localPort <= 0) localPort = 1081;
                this.tcpSocksRelay = new com.zerotier.nexus.util.TcpSocksRelay("127.0.0.1", localPort, data -> {
                    try {
                        if (this.out != null) this.out.write(data);
                    } catch (IOException e) {
                        Log.e(TAG, "Error writing TCP response to TUN: " + e.getMessage());
                    }
                });
            }
        }
        
        // 如果补强关闭，停止 relay 线程
        if (!enabled) {
            if (this.socks5Relay != null) {
                this.socks5Relay.stop();
                this.socks5Relay = null;
            }
            if (this.tcpSocksRelay != null) {
                this.tcpSocksRelay.stop();
                this.tcpSocksRelay = null;
            }
        }
    }

    public synchronized void updateRoamingGateways(String v4, String v6) {
        this.roamingGatewayV4 = v4;
        this.roamingGatewayV6 = v6;
        Log.i(TAG, "Syncing Gateways: V4=" + v4 + ", V6=" + v6);
        writeAuditLog("GATEWAY_UPDATE", "V4: " + v4 + " | V6: " + v6);
        
        // --- ARP 预热 ---
        if (v4 != null && !v4.isEmpty() && this.node != null) {
            try {
                InetAddress gwAddr = InetAddress.getByName(v4);
                if (!this.arpTable.hasMacForAddress(gwAddr)) {
                    Log.i(TAG, "Gateway ARP Warmup: Sending request for " + v4);
                    VirtualNetworkConfig vpc = this.ztService.getVirtualNetworkConfig(this.roamingNetworkId != 0 ? this.roamingNetworkId : this.networkId);
                    if (vpc != null) {
                        InetAddress localV4 = null;
                        for (InetSocketAddress addr : vpc.getAssignedAddresses()) {
                            if (addr.getAddress() instanceof Inet4Address) {
                                localV4 = addr.getAddress();
                                break;
                            }
                        }
                        if (localV4 != null) {
                            byte[] arpReq = this.arpTable.getRequestPacket(vpc.getMac(), localV4, gwAddr);
                            long[] nextDeadline = new long[1];
                            this.node.processVirtualNetworkFrame(System.currentTimeMillis(), 
                                    this.roamingNetworkId != 0 ? this.roamingNetworkId : this.networkId, 
                                    vpc.getMac(), InetAddressUtils.BROADCAST_MAC_ADDRESS, ARP_PACKET, 0, arpReq, nextDeadline);
                            this.ztService.setNextBackgroundTaskDeadline(nextDeadline[0]);
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "ARP Warmup failed: " + e.getMessage());
            }
        }
    }

    public synchronized void updateRoamingNetwork(long nwid) {
        this.roamingNetworkId = nwid;
        Log.i(TAG, "Syncing Roaming Network ID: " + Long.toHexString(nwid));
    }

    public void addRouteAndNetwork(Route route, long networkId) {
        synchronized (this.routeMap) {
            this.routeMap.put(route, networkId);
        }
    }

    public void clearRouteMap() {
        synchronized (this.routeMap) {
            this.routeMap.clear();
            addMulticastRoutes();
        }
    }

    private boolean isIPv4Multicast(InetAddress inetAddress) {
        return (inetAddress.getAddress()[0] & 0xF0) == 224;
    }

    private boolean isIPv6Multicast(InetAddress inetAddress) {
        return (inetAddress.getAddress()[0] & 0xFF) == 0xFF;
    }

    public void startThreads() {
        this.receiveThread = new Thread("Tunnel Receive Thread") {

            @Override
            public void run() {
                // 创建 ARP、NDP 表
                if (TunTapAdapter.this.ndpTable == null) {
                    TunTapAdapter.this.ndpTable = new NDPTable();
                }
                if (TunTapAdapter.this.arpTable == null) {
                    TunTapAdapter.this.arpTable = new ARPTable();
                }
                // 转发 TUN 消息至 Zerotier
                try {
                    Log.i(TunTapAdapter.TAG, "TUN Receive Thread Started");
                    ByteBuffer buffer = ByteBuffer.allocate(32767);
                    buffer.order(ByteOrder.LITTLE_ENDIAN);
                    long packetCount = 0;
                    
                    if (TunTapAdapter.this.in == null) {
                        Log.e(TAG, "FATAL_ERROR: TunTapAdapter 'in' stream is NULL during start!");
                        return;
                    }

                    while (!isInterrupted()) {
                        try {
                            boolean noDataBeenRead = true;
                            int readCount = TunTapAdapter.this.in.read(buffer.array());
                            if (readCount > 0) {
                                packetCount++;
                                // 极大降低心跳日志频率 (每 5000 包一次)
                                if (packetCount % 5000 == 0) {
                                    Log.d(TAG, "TUN_STATUS: Processed " + packetCount + " packets.");
                                }
                                
                                byte[] readData = new byte[readCount];
                                System.arraycopy(buffer.array(), 0, readData, 0, readCount);
                                if (readData.length == 0) continue;
                                byte iPVersion = IPPacketUtils.getIPVersion(readData);
                                if (TunTapAdapter.this.node == null) {
                                    // 仅在关键状态丢失时打印一次日志
                                    if (packetCount % 100 == 0) Log.w(TAG, "NODE_ERROR: Node is null, skipping packet.");
                                } else if (iPVersion == 4) {
                                    TunTapAdapter.this.handleIPv4Packet(readData);
                                } else if (iPVersion == 6) {
                                    TunTapAdapter.this.handleIPv6Packet(readData);
                                }
                                buffer.clear();
                                noDataBeenRead = false;
                            }
                            if (noDataBeenRead) {
                                Thread.sleep(10);
                            }
                        } catch (IOException e) {
                            Log.e(TunTapAdapter.TAG, "Error in TUN Receive: " + e.getMessage(), e);
                        }
                    }
                } catch (InterruptedException ignored) {
                }
                Log.d(TunTapAdapter.TAG, "TUN Receive Thread ended");
                // 关闭 ARP、NDP 表
                TunTapAdapter.this.ndpTable.stop();
                TunTapAdapter.this.ndpTable = null;
                TunTapAdapter.this.arpTable.stop();
                TunTapAdapter.this.arpTable = null;
            }
        };
        this.receiveThread.start();
    }

    private void handleIPv4Packet(byte[] packetData) {
        InetAddress destIP = IPPacketUtils.getDestIP(packetData);
        InetAddress sourceIP = IPPacketUtils.getSourceIP(packetData);
        
        // --- [模式一：极速路径 (Fast Path)] ---
        if (!this.isRoamingEnabled) {
            long targetNetworkId = networkIdForDestination(destIP);
            if (targetNetworkId == 0) targetNetworkId = this.networkId;
            processNormalIPv4(packetData, destIP, sourceIP, targetNetworkId, routeForDestination(destIP));
            return;
        }

        // [精准分流逻辑] 动态获取数据包所属的网络 ID
        long targetNetworkId = networkIdForDestination(destIP);
        if (targetNetworkId == 0) targetNetworkId = this.networkId;

        // 智能分流决策矩阵
        boolean isExitTraffic = false;
        
        // [逻辑恢复] 寻找匹配的路由条目
        Route route = routeForDestination(destIP);
        
        try {
            // [逻辑重构] 判断目的地是否为 ZT 内网成员 (排除默认路由)
            long ztInternalNetworkId = 0;
            try {
                ztInternalNetworkId = networkIdForDestination(destIP);
            } catch (Exception ignored) {}

            isExitTraffic = (ztInternalNetworkId == 0);
            
            // 使用同步后的状态
            boolean currentRoaming = this.isRoamingEnabled; 
            boolean currentTuic = this.isTuicEnabled;      

            // --- 全协议审计日志 (包含 TCP/UDP/ICMP) ---
            if (isExitTraffic && currentRoaming) {
                // IPv4 Roaming Detected
            }

            if (isExitTraffic && currentRoaming && currentTuic) {
                // 如果是 UDP 包，检查是否需要分流
                if (com.zerotier.nexus.util.IPPacketUtils.getProtocol(packetData) == 17) { // UDP
                    // Redirect
                    
                    if (this.socks5Relay == null) {
                        int localPort = this.proxyPort;
                        if (localPort <= 0) localPort = 1081; // 兜底
                        Log.i(TAG, "Initializing Socks5UdpRelay on port: " + localPort);
                        this.socks5Relay = new com.zerotier.nexus.util.Socks5UdpRelay("127.0.0.1", localPort);
                        this.socks5Relay.start();
                    }

                    // 使用缓存的代理服务器信息进行白名单过滤
                    String currentProxyHost = this.proxyHost;
                    int currentProxyPort = this.proxyPort;

                    int destPort = com.zerotier.nexus.util.IPPacketUtils.getDestPort(packetData);
                    
                    // 命中白名单：目的地是补强服务器，强制直连 (防止死循环)
                    if (destPort == currentProxyPort && destIP.getHostAddress().equals(currentProxyHost)) {
                        // Bypass
                    } else if (this.socks5Relay.isReady()) {
                        this.socks5Relay.forward(packetData, destIP, destPort);
                        return;
                    }
                } else if (com.zerotier.nexus.util.IPPacketUtils.getProtocol(packetData) == 6) { // TCP
                    // Redirect TCP
                    if (this.tcpSocksRelay != null) {
                        String currentProxyHost = this.proxyHost;
                        int currentProxyPort = this.proxyPort;
                        int destPort = com.zerotier.nexus.util.IPPacketUtils.getDestPort(packetData);
                        
                        // 命中白名单：目的地是补强服务器，强制直连 (防止死循环)
                        if (destPort == currentProxyPort && destIP.getHostAddress().equals(currentProxyHost)) {
                            // Bypass
                        } else if (this.tcpSocksRelay.handlePacket(packetData)) {
                            return; // 报文已被拦截处理
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Error
        }
        
        // 兜底：直接交给 ZT 内核，移除所有热点路径 Log.e

        long activeNetworkId = targetNetworkId;
        if (activeNetworkId == 0) {
            activeNetworkId = (this.roamingNetworkId != 0) ? this.roamingNetworkId : this.networkId;
        }

        boolean isMulticast;
        long destMac;
        VirtualNetworkConfig virtualNetworkConfig = this.ztService.getVirtualNetworkConfig(activeNetworkId);

        if (virtualNetworkConfig == null) {
            Log.e(TAG, "TunTapAdapter has no network config yet");
            return;
        } else if (this.node == null) {
            Log.e(TAG, "Node is null in handleIPv4Packet");
            return;
        } else if (destIP == null) {
            Log.e(TAG, "destAddress is null");
            return;
        } else if (sourceIP == null) {
            Log.e(TAG, "sourceAddress is null");
            return;
        }
        if (isIPv4Multicast(destIP)) {
            ResultCode result = this.node.multicastSubscribe(targetNetworkId, multicastAddressToMAC(destIP));
            if (result != ResultCode.RESULT_OK) {
                android.util.Log.e(TAG, "Error when calling multicastSubscribe: " + result);
            }
            isMulticast = true;
        } else {
            isMulticast = false;
        }
        
        // 使用头部已经定义好的 route 变量
        InetAddress gateway = route != null ? route.getGateway() : null;

        // 查找当前节点的 v4 地址
        InetSocketAddress[] ztAddresses = virtualNetworkConfig.getAssignedAddresses();
        InetAddress localV4Address = null;
        int cidr = 0;

        for (InetSocketAddress address : ztAddresses) {
            if (address.getAddress() instanceof Inet4Address) {
                localV4Address = address.getAddress();
                cidr = address.getPort();
                break;
            }
        }

        java.net.InetAddress destRoute = InetAddressUtils.addressToRouteNo0Route(destIP, cidr);
        java.net.InetAddress sourceRoute = InetAddressUtils.addressToRouteNo0Route(sourceIP, cidr);
        
        // --- 核心路由重定向：针对漫游出口逻辑 ---
        if (isExitTraffic && this.isRoamingEnabled) {
             // 无论是否开启 TUIC，非拦截流量均需定向至虚拟网关 (确保 TCP 连通性)
             try {
                if (this.roamingGatewayV4 != null) {
                    destIP = InetAddress.getByName(this.roamingGatewayV4);
                } else if (localV4Address != null) {
                    // 降级：默认使用该网段的 .1
                    String hostAddr = localV4Address.getHostAddress();
                    destIP = InetAddress.getByName(hostAddr.substring(0, hostAddr.lastIndexOf('.') + 1) + "1");
                }
             } catch (Exception ignored) {}
        } else if (gateway != null && !Objects.equals(destRoute, sourceRoute)) {
            destIP = gateway;
        }
        if (localV4Address == null) {
            Log.e(TAG, "Couldn't determine local address");
            return;
        }

        long localMac = virtualNetworkConfig.getMac();
        long[] nextDeadline = new long[1];
        
        // --- [模式二：Kill Switch 判定] ---
        boolean isGatewayIP = (this.isRoamingEnabled && isExitTraffic); 
        boolean hasMac = this.arpTable.hasMacForAddress(destIP);

        if (isMulticast || hasMac) {
            // 已确定目标 MAC，直接发送
            if (isIPv4Multicast(destIP)) {
                destMac = multicastAddressToMAC(destIP);
            } else {
                destMac = this.arpTable.getMacForAddress(destIP);
            }
            
            ResultCode result = this.node.processVirtualNetworkFrame(System.currentTimeMillis(), activeNetworkId, localMac, destMac, IPV4_PACKET, 0, packetData, nextDeadline);
            if (result != ResultCode.RESULT_OK) {
                return;
            }
            this.ztService.setNextBackgroundTaskDeadline(nextDeadline[0]);
        } else if (isGatewayIP) {
            // [固化：模式二 Kill Switch] 目标是出口网关但 MAC 未知，禁止泄露，丢弃包
            writeAuditLog("MODE2_BLOCK", "Gateway unreachable: " + destIP.getHostAddress());
            return;
        } else {
            // 目标 MAC 未知，进行 ARP 查询 (仅限非出口流量)
            destMac = InetAddressUtils.BROADCAST_MAC_ADDRESS;
            packetData = this.arpTable.getRequestPacket(localMac, localV4Address, destIP);
            ResultCode result = this.node.processVirtualNetworkFrame(System.currentTimeMillis(), activeNetworkId, localMac, destMac, ARP_PACKET, 0, packetData, nextDeadline);
            if (result != ResultCode.RESULT_OK) {
                // Error
            }
            this.ztService.setNextBackgroundTaskDeadline(nextDeadline[0]);
        }
    }

    /**
     * [固化：模式一极速路径] 剥离冗余匹配逻辑的标准 P2P 转发
     */
    private void processNormalIPv4(byte[] packetData, InetAddress destIP, InetAddress sourceIP, long targetNetworkId, Route route) {
        if (destIP == null || sourceIP == null) return;
        
        VirtualNetworkConfig virtualNetworkConfig = this.ztService.getVirtualNetworkConfig(targetNetworkId);
        if (virtualNetworkConfig == null || this.node == null) return;

        long activeNetworkId = targetNetworkId;
        boolean isMulticast = isIPv4Multicast(destIP);
        
        if (isMulticast) {
            this.node.multicastSubscribe(targetNetworkId, multicastAddressToMAC(destIP));
        }

        InetAddress gateway = route != null ? route.getGateway() : null;
        InetSocketAddress[] ztAddresses = virtualNetworkConfig.getAssignedAddresses();
        InetAddress localV4Address = null;
        int cidr = 0;
        for (InetSocketAddress address : ztAddresses) {
            if (address.getAddress() instanceof Inet4Address) {
                localV4Address = address.getAddress();
                cidr = address.getPort();
                break;
            }
        }
        if (localV4Address == null) return;

        java.net.InetAddress destRoute = InetAddressUtils.addressToRouteNo0Route(destIP, cidr);
        java.net.InetAddress sourceRoute = InetAddressUtils.addressToRouteNo0Route(sourceIP, cidr);
        
        if (gateway != null && !Objects.equals(destRoute, sourceRoute)) {
            destIP = gateway;
        }

        long localMac = virtualNetworkConfig.getMac();
        long[] nextDeadline = new long[1];
        long destMac;

        if (isMulticast || this.arpTable.hasMacForAddress(destIP)) {
            destMac = isMulticast ? multicastAddressToMAC(destIP) : this.arpTable.getMacForAddress(destIP);
            this.node.processVirtualNetworkFrame(System.currentTimeMillis(), activeNetworkId, localMac, destMac, IPV4_PACKET, 0, packetData, nextDeadline);
            this.ztService.setNextBackgroundTaskDeadline(nextDeadline[0]);
        } else {
            destMac = InetAddressUtils.BROADCAST_MAC_ADDRESS;
            byte[] arpReq = this.arpTable.getRequestPacket(localMac, localV4Address, destIP);
            this.node.processVirtualNetworkFrame(System.currentTimeMillis(), activeNetworkId, localMac, destMac, ARP_PACKET, 0, arpReq, nextDeadline);
            this.ztService.setNextBackgroundTaskDeadline(nextDeadline[0]);
        }
    }

    private void handleIPv6Packet(byte[] packetData) {
        InetAddress destIP = IPPacketUtils.getDestIP(packetData);
        InetAddress sourceIP = IPPacketUtils.getSourceIP(packetData);
        
        // --- [模式一：极速路径 (Fast Path)] ---
        if (!this.isRoamingEnabled) {
            long targetNetworkId = networkIdForDestination(destIP);
            if (targetNetworkId == 0) targetNetworkId = this.networkId;
            processNormalIPv6(packetData, destIP, sourceIP, targetNetworkId, routeForDestination(destIP));
            return;
        }

        long targetNetworkId = networkIdForDestination(destIP);
        long activeNetworkId = targetNetworkId;
        if (activeNetworkId == 0) {
            activeNetworkId = (this.roamingNetworkId != 0) ? this.roamingNetworkId : this.networkId;
        }

        VirtualNetworkConfig virtualNetworkConfig = this.ztService.getVirtualNetworkConfig(activeNetworkId);

        if (virtualNetworkConfig == null) {
            Log.e(TAG, "TunTapAdapter has no network config yet");
            return;
        } else if (this.node == null) {
            Log.e(TAG, "Node is null in handleIPv6Packet");
            return;
        } else if (destIP == null) {
            Log.e(TAG, "destAddress is null");
            return;
        } else if (sourceIP == null) {
            Log.e(TAG, "sourceAddress is null");
            return;
        }
        if (this.isIPv6Multicast(destIP)) {
            ResultCode result = this.node.multicastSubscribe(activeNetworkId, multicastAddressToMAC(destIP));
            if (result != ResultCode.RESULT_OK) {
                Log.e(TAG, "Error when calling multicastSubscribe: " + result);
            }
        }

        // --- IPv6 审计日志 ---
        long ztInternalNetworkId = networkIdForDestination(destIP);
        boolean isExitTraffic = (ztInternalNetworkId == 0);
        boolean currentRoaming = this.isRoamingEnabled;
        boolean currentTuic = this.isTuicEnabled;

        if (isExitTraffic && currentRoaming) {
            // IPv6 Roaming Detected
        }
        Route route = routeForDestination(destIP);
        InetAddress gateway = route != null ? route.getGateway() : null;

        // 查找当前节点的 v6 地址
        InetSocketAddress[] ztAddresses = virtualNetworkConfig.getAssignedAddresses();
        InetAddress localV4Address = null;
        int cidr = 0;

        for (InetSocketAddress address : ztAddresses) {
            if (address.getAddress() instanceof Inet6Address) {
                localV4Address = address.getAddress();
                cidr = address.getPort();
                break;
            }
        }

        java.net.InetAddress destRoute = InetAddressUtils.addressToRouteNo0Route(destIP, cidr);
        java.net.InetAddress sourceRoute = InetAddressUtils.addressToRouteNo0Route(sourceIP, cidr);

        // --- 核心路由重定向：针对 IPv6 漫游出口逻辑 ---
        if (isExitTraffic && this.isRoamingEnabled) {
             try {
                if (this.roamingGatewayV6 != null) {
                    destIP = InetAddress.getByName(this.roamingGatewayV6);
                }
                // IPv6 暂不提供默认的 .1 降级，因为 IPv6 地址分配通常不遵循此约定
             } catch (Exception ignored) {}
        } else if (gateway != null && !Objects.equals(destRoute, sourceRoute)) {
            destIP = gateway;
        }
        if (localV4Address == null) {
            Log.e(TAG, "Couldn't determine local address");
            return;
        }

        long localMac = virtualNetworkConfig.getMac();
        long[] nextDeadline = new long[1];

        // 确定目标 MAC 地址
        long destMac;
        boolean sendNSPacket = false;
        boolean isGatewayIP = (this.isRoamingEnabled && isExitTraffic);

        if (this.isNeighborSolicitation(packetData)) {
            // 收到本地 NS 报文，根据 NDP 表记录确定是否广播查询
            if (this.ndpTable.hasMacForAddress(destIP)) {
                destMac = this.ndpTable.getMacForAddress(destIP);
            } else {
                destMac = InetAddressUtils.ipv6ToMulticastAddress(destIP);
            }
        } else if (this.isIPv6Multicast(destIP)) {
            // 多播报文
            destMac = multicastAddressToMAC(destIP);
        } else if (this.isNeighborAdvertisement(packetData)) {
            // 收到本地 NA 报文
            if (this.ndpTable.hasMacForAddress(destIP)) {
                destMac = this.ndpTable.getMacForAddress(destIP);
            } else {
                // 目标 MAC 未知，不发送数据包
                destMac = 0L;
            }
            sendNSPacket = !isGatewayIP; // [固化] 漫游网关 MAC 未知时不触发查询，直接封死
        } else {
            // 收到普通数据包，根据 NDP 表记录确定是否发送 NS 请求
            if (this.ndpTable.hasMacForAddress(destIP)) {
                // 目标地址 MAC 已知
                destMac = this.ndpTable.getMacForAddress(destIP);
            } else {
                destMac = 0L;
                sendNSPacket = !isGatewayIP; // [固化] 漫游网关 MAC 未知时不触发查询，直接封死
            }
        }
        
        // [固化] Kill Switch 日志
        if (isGatewayIP && destMac == 0L) {
             writeAuditLog("MODE2_BLOCK_V6", "Gateway unreachable: " + destIP.getHostAddress());
        }
        // 发送数据包
        if (destMac != 0L) {
            ResultCode result = this.node.processVirtualNetworkFrame(System.currentTimeMillis(), activeNetworkId, localMac, destMac, IPV6_PACKET, 0, packetData, nextDeadline);
            if (result != ResultCode.RESULT_OK) {
                Log.e(TAG, "Error calling processVirtualNetworkFrame: " + result.toString());
            } else {
                DebugLog.d(TAG, "Packet sent to ZT");
                this.ztService.setNextBackgroundTaskDeadline(nextDeadline[0]);
            }
        }
        // 发送 NS 请求
        if (sendNSPacket) {
            if (destMac == 0L) {
                destMac = InetAddressUtils.ipv6ToMulticastAddress(destIP);
            }
            Log.d(TAG, "Sending Neighbor Solicitation");
            packetData = this.ndpTable.getNeighborSolicitationPacket(sourceIP, destIP, localMac);
            ResultCode result = this.node.processVirtualNetworkFrame(System.currentTimeMillis(), activeNetworkId, localMac, destMac, IPV6_PACKET, 0, packetData, nextDeadline);
            if (result != ResultCode.RESULT_OK) {
                Log.e(TAG, "Error calling processVirtualNetworkFrame: " + result.toString());
            } else {
                Log.d(TAG, "Neighbor Solicitation sent to ZT");
                this.ztService.setNextBackgroundTaskDeadline(nextDeadline[0]);
            }
        }

    }

    /**
     * [固化：模式一极速路径] 标准 P2P IPv6 转发
     */
    private void processNormalIPv6(byte[] packetData, InetAddress destIP, InetAddress sourceIP, long targetNetworkId, Route route) {
        if (destIP == null || sourceIP == null) return;
        
        VirtualNetworkConfig virtualNetworkConfig = this.ztService.getVirtualNetworkConfig(targetNetworkId);
        if (virtualNetworkConfig == null || this.node == null) return;

        if (this.isIPv6Multicast(destIP)) {
            this.node.multicastSubscribe(targetNetworkId, multicastAddressToMAC(destIP));
        }

        InetAddress gateway = route != null ? route.getGateway() : null;
        InetSocketAddress[] ztAddresses = virtualNetworkConfig.getAssignedAddresses();
        InetAddress localV6Address = null;
        int cidr = 0;
        for (InetSocketAddress address : ztAddresses) {
            if (address.getAddress() instanceof Inet6Address) {
                localV6Address = address.getAddress();
                cidr = address.getPort();
                break;
            }
        }
        if (localV6Address == null) return;

        java.net.InetAddress destRoute = InetAddressUtils.addressToRouteNo0Route(destIP, cidr);
        java.net.InetAddress sourceRoute = InetAddressUtils.addressToRouteNo0Route(sourceIP, cidr);
        
        if (gateway != null && !Objects.equals(destRoute, sourceRoute)) {
            destIP = gateway;
        }

        long localMac = virtualNetworkConfig.getMac();
        long[] nextDeadline = new long[1];
        long destMac;
        boolean sendNSPacket = false;

        if (this.isNeighborSolicitation(packetData)) {
            destMac = this.ndpTable.hasMacForAddress(destIP) ? this.ndpTable.getMacForAddress(destIP) : InetAddressUtils.ipv6ToMulticastAddress(destIP);
        } else if (this.isIPv6Multicast(destIP)) {
            destMac = multicastAddressToMAC(destIP);
        } else if (this.isNeighborAdvertisement(packetData)) {
            destMac = this.ndpTable.hasMacForAddress(destIP) ? this.ndpTable.getMacForAddress(destIP) : 0L;
            sendNSPacket = true;
        } else {
            if (this.ndpTable.hasMacForAddress(destIP)) {
                destMac = this.ndpTable.getMacForAddress(destIP);
            } else {
                destMac = 0L;
                sendNSPacket = true;
            }
        }

        if (destMac != 0L) {
            this.node.processVirtualNetworkFrame(System.currentTimeMillis(), targetNetworkId, localMac, destMac, IPV6_PACKET, 0, packetData, nextDeadline);
            this.ztService.setNextBackgroundTaskDeadline(nextDeadline[0]);
        }
        if (sendNSPacket) {
            if (destMac == 0L) destMac = InetAddressUtils.ipv6ToMulticastAddress(destIP);
            byte[] nsReq = this.ndpTable.getNeighborSolicitationPacket(sourceIP, destIP, localMac);
            this.node.processVirtualNetworkFrame(System.currentTimeMillis(), targetNetworkId, localMac, destMac, IPV6_PACKET, 0, nsReq, nextDeadline);
            this.ztService.setNextBackgroundTaskDeadline(nextDeadline[0]);
        }
    }

    public void interrupt() {
        if (this.receiveThread != null) {
            try {
                this.in.close();
                this.out.close();
            } catch (IOException e) {
                Log.e(TAG, "Error stopping in/out: " + e.getMessage(), e);
            }
            this.receiveThread.interrupt();
            try {
                this.receiveThread.join();
            } catch (InterruptedException ignored) {
            }
        }
    }

    public void join() throws InterruptedException {
        this.receiveThread.join();
    }

    private boolean isNeighborSolicitation(byte[] packetData) {
        return packetData[6] == 58 && packetData[40] == -121;
    }

    private boolean isNeighborAdvertisement(byte[] packetData) {
        return packetData[6] == 58 && packetData[40] == -120;
    }

    public boolean isRunning() {
        Thread thread = this.receiveThread;
        if (thread == null) {
            return false;
        }
        return thread.isAlive();
    }

    /**
     * 根据 Node ID 获取缓存中的虚拟 IP
     */
    public String getIPForNodeId(long nodeId) {
        if (this.arpTable == null) return null;
        // ZeroTier 默认 MAC 转换逻辑：OUI(32) + NodeID(40bit)
        long mac = 0x320000000000L | (nodeId & 0xFFFFFFFFFFL);
        java.net.InetAddress addr = this.arpTable.getAddressForMac(mac);
        if (addr != null) {
            return addr.getHostAddress();
        }
        return null;
    }

    /**
     * 响应并处理 ZT 网络发送至本节点的以太网帧
     */
    @Override
    public void onVirtualNetworkFrame(long networkId, long srcMac, long destMac, long etherType,
                                      long vlanId, byte[] frameData) {
        DebugLog.d(TAG, "Got Virtual Network Frame. " +
                " Network ID: " + StringUtils.networkIdToString(networkId) +
                " Source MAC: " + StringUtils.macAddressToString(srcMac) +
                " Dest MAC: " + StringUtils.macAddressToString(destMac) +
                " Ether type: " + StringUtils.etherTypeToString(etherType) +
                " VLAN ID: " + vlanId + " Frame Length: " + frameData.length);
        if (this.node == null) {
            Log.e(TAG, "Node is null in onVirtualNetworkFrame");
        } else if (this.vpnSocket == null) {
            Log.e(TAG, "vpnSocket is null!");
        } else if (this.in == null || this.out == null) {
            Log.e(TAG, "no in/out streams");
        } else if (etherType == ARP_PACKET) {
            // 收到 ARP 包。更新 ARP 表，若需要则进行应答
            Log.d(TAG, "Got ARP Packet");
            ARPReplyData arpReply = this.arpTable.processARPPacket(frameData);
            if (arpReply != null && arpReply.getDestMac() != 0 && arpReply.getDestAddress() != null) {
                // 获取本地 V4 地址
                VirtualNetworkConfig networkConfig = this.node.networkConfig(networkId);
                if (networkConfig == null) {
                    Log.w(TAG, "Network config not yet available for ARP reply.");
                    return;
                }
                InetAddress localV4Address = null;
                for (InetSocketAddress address : networkConfig.getAssignedAddresses()) {
                    if (address.getAddress() instanceof Inet4Address) {
                        localV4Address = address.getAddress();
                        break;
                    }
                }
                // 构造并返回 ARP 应答
                if (localV4Address != null) {
                    long[] nextDeadline = new long[1];
                    byte[] packetData = this.arpTable.getReplyPacket(networkConfig.getMac(),
                            localV4Address, arpReply.getDestMac(), arpReply.getDestAddress());
                    ResultCode result = this.node
                            .processVirtualNetworkFrame(System.currentTimeMillis(), networkId,
                                    networkConfig.getMac(), srcMac, ARP_PACKET, 0,
                                    packetData, nextDeadline);
                    if (result != ResultCode.RESULT_OK) {
                        Log.e(TAG, "Error sending ARP packet: " + result.toString());
                        return;
                    }
                    Log.d(TAG, "ARP Reply Sent!");
                    this.ztService.setNextBackgroundTaskDeadline(nextDeadline[0]);
                }
            }
        } else if (etherType == IPV4_PACKET) {
            // 收到 IPv4 包。根据需要发送至 TUN
            DebugLog.d(TAG, "Got IPv4 packet. Length: " + frameData.length + " Bytes");
            try {
                InetAddress sourceIP = IPPacketUtils.getSourceIP(frameData);
                if (sourceIP != null && this.node != null) {
                    if (isIPv4Multicast(sourceIP)) {
                        com.zerotier.sdk.ResultCode result = this.node.multicastSubscribe(this.networkId, multicastAddressToMAC(sourceIP));
                        if (result != ResultCode.RESULT_OK) {
                            Log.e(TAG, "Error when calling multicastSubscribe: " + result);
                        }
                    } else {
                        this.arpTable.setAddress(sourceIP, srcMac);
                    }
                }
                this.out.write(frameData);
            } catch (Exception e) {
                Log.e(TAG, "Error writing data to vpn socket: " + e.getMessage(), e);
            }
        } else if (etherType == IPV6_PACKET) {
            // 收到 IPv6 包。根据需要发送至 TUN，并更新 NDP 表
            DebugLog.d(TAG, "Got IPv6 packet. Length: " + frameData.length + " Bytes");
            try {
                InetAddress sourceIP = IPPacketUtils.getSourceIP(frameData);
                if (sourceIP != null && this.node != null) {
                    if (isIPv6Multicast(sourceIP)) {
                        com.zerotier.sdk.ResultCode result = this.node.multicastSubscribe(this.networkId, multicastAddressToMAC(sourceIP));
                        if (result != ResultCode.RESULT_OK) {
                            Log.e(TAG, "Error when calling multicastSubscribe: " + result);
                        }
                    } else {
                        this.ndpTable.setAddress(sourceIP, srcMac);
                    }
                }
                this.out.write(frameData);
            } catch (Exception e) {
                Log.e(TAG, "Error writing data to vpn socket: " + e.getMessage(), e);
            }
        } else if (frameData.length >= 14) {
            Log.d(TAG, "Unknown Packet Type Received: 0x" + String.format("%02X%02X", frameData[12], frameData[13]));
        } else {
            Log.d(TAG, "Unknown Packet Received.  Packet Length: " + frameData.length);
        }
    }


    private Route routeForDestination(InetAddress address) {
        synchronized (this.routeMap) {
            Route bestMatch = null;
            int maxPrefix = -1;
            for (Route r : this.routeMap.keySet()) {
                if (r.belongsToRoute(address)) {
                    if (r.getPrefix() > maxPrefix) {
                        maxPrefix = r.getPrefix();
                        bestMatch = r;
                    }
                }
            }
            return bestMatch;
        }
    }

    private long networkIdForDestination(InetAddress destAddress) {
        synchronized (this.routeMap) {
            for (Route route : this.routeMap.keySet()) {
                // [逻辑修正] 排除默认路由 (0.0.0.0/0)，将其视为出口流量而非内网直连
                if (route.getPrefix() > 0 && route.belongsToRoute(destAddress)) {
                    return this.routeMap.get(route);
                }
            }
            return 0;
        }
    }

    private void writeAuditLog(String action, String detail) {
        try {
            File logFile = new File(this.ztService.getFilesDir(), "nexus_audit.log");
            FileWriter writer = new FileWriter(logFile, true);
            String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
            writer.write(String.format("[%s] [%-8s] %s\n", time, action, detail));
            writer.close();
        } catch (Exception ignored) {}
    }
}

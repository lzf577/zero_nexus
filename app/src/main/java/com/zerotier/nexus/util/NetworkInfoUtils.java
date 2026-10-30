package com.zerotier.nexus.util;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.util.Log;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.zerotier.nexus.util.ShellExecutor;

/**
 * 网络连接信息处理工具类
 */
public class NetworkInfoUtils {
    public static final String TAG = "NetworkInfoUtils";

    public enum CurrentConnection {
        CONNECTION_NONE,
        CONNECTION_MOBILE,
        CONNECTION_OTHER
    }

    public static CurrentConnection getNetworkInfoCurrentConnection(Context context) {
        ConnectivityManager connectivityManager = (ConnectivityManager) context
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager == null) {
            return CurrentConnection.CONNECTION_NONE;
        }
        if (Build.VERSION.SDK_INT >= 23) {
            NetworkCapabilities networkCapabilities = connectivityManager
                    .getNetworkCapabilities(connectivityManager.getActiveNetwork());
            if (networkCapabilities == null) {
                return CurrentConnection.CONNECTION_NONE;
            }
            if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                return CurrentConnection.CONNECTION_MOBILE;
            }
            return CurrentConnection.CONNECTION_OTHER;
        } else {
            android.net.NetworkInfo activeNetworkInfo = connectivityManager.getActiveNetworkInfo();
            if (activeNetworkInfo == null) {
                return CurrentConnection.CONNECTION_NONE;
            }
            if (!activeNetworkInfo.isConnectedOrConnecting()) {
                return CurrentConnection.CONNECTION_NONE;
            }
            if (activeNetworkInfo.getType() == 0) {
                return CurrentConnection.CONNECTION_MOBILE;
            }
            return CurrentConnection.CONNECTION_OTHER;
        }
    }

    public static List<String> listMulticastGroupOnInterface(String interfaceName, boolean isIpv6) {
        List<String> groups = new ArrayList<String>();

        String igmpFilePath;

        if (isIpv6) {
            igmpFilePath = "/proc/net/igmp6";
        } else {
            igmpFilePath = "/proc/net/igmp";
        }

        /*
         * 从 /proc/net/igmp 或 /proc/net/igmp6 的信息格式大致为:
         *
         * Idx     Device    : Count Querier       Group    Users Timer    Reporter
         * 1       tun0      :     2      V3
         *                                 010000E0     1 0:00000000               0
         * 2       wlan0     :     1      V3
         *                                 010000E0     1 0:00000000               0
         *
         * 因此，解析时需要先找到目标 interface 的行，然后开始读取若干行的组播组信息。
         */
        try (BufferedReader igmpInfo = new BufferedReader(new FileReader(igmpFilePath))) {
            boolean foundTargetInterface = false;
            String line;

            while ((line = igmpInfo.readLine()) != null) {
                String[] row = line.split("\\s+", -1);

                if (!foundTargetInterface) {
                    if (row.length > 1 && row[1].equals(interfaceName)) {
                        // 找到目标 interface 的行
                        foundTargetInterface = true;
                    }
                } else {
                    if (row[0].equals("")) {
                        groups.add(row[1]);
                    } else {
                        // 目标 interface 的信息结束
                        foundTargetInterface = false;
                    }
                }
            }
        } catch (FileNotFoundException e) {
            Log.e(TAG, "IGMP info file not found", e);
        } catch (IOException e) {
            Log.e(TAG, "Error reading IGMP info", e);
        }
        return groups;
    }

    /**
     * 获取公网出口网卡 (如 wlan0, rmnet_data0)
     */
    public static String findDefaultInterface(Context context) {
        // 优先使用 Android 官方 API 获取物理网卡名
        if (context != null) {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                Network[] networks = cm.getAllNetworks();
                for (Network network : networks) {
                    NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                    if (caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                        // 必须是 WIFI 或 蜂窝网络
                        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || 
                            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                            LinkProperties lp = cm.getLinkProperties(network);
                            if (lp != null && lp.getInterfaceName() != null) {
                                String ifName = lp.getInterfaceName();
                                if (ifName.startsWith("wlan") || ifName.startsWith("rmnet")) {
                                    return ifName;
                                }
                            }
                        }
                    }
                }
            }
        }

        // 备选方案 1: 尝试查看所有路由表 (Android 物理网卡路由常在特定 table 中)
        ShellExecutor.Result result = ShellExecutor.exec("ip route show table all", false);
        if (result.isSuccess()) {
            String[] lines = result.stdout.split("\n");
            for (String line : lines) {
                // 排除 lo, tun, zt, vpn 开头的接口
                if (line.contains("default") && line.contains("dev")) {
                    String[] parts = line.split("\\s+");
                    for (int i = 0; i < parts.length; i++) {
                        if ("dev".equals(parts[i]) && i + 1 < parts.length) {
                            String ifName = parts[i + 1];
                            if (ifName.startsWith("wlan") || ifName.startsWith("rmnet")) {
                                return ifName;
                            }
                        }
                    }
                }
            }
        }
        
        // 备选方案 2: 寻找常见的物理网卡名并确认其 UP
        ShellExecutor.Result linkResult = ShellExecutor.exec("ip link show", false);
        if (linkResult.isSuccess()) {
            String[] lines = linkResult.stdout.split("\n");
            for (String line : lines) {
                if (line.contains(": ") && (line.contains("wlan") || line.contains("rmnet") || 
                                          line.contains("eth") || line.contains("ccmni") || line.contains("ppp"))) {
                    if (line.contains("UP") || line.contains("LOWER_UP") || line.contains("state UP")) {
                        // 修正切割逻辑：处理开头的索引号
                        String namePart = line.split(": ")[1].trim();
                        String name = namePart.split("\\s+")[0];
                        if (name.contains("@")) name = name.split("@")[0];
                        if (!name.startsWith("zt") && !name.startsWith("tun")) {
                            return name;
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * 获取公网出口网卡 IP 地址
     */
    public static String findDefaultInterfaceIp(Context context) {
        String ifName = findDefaultInterface(context);
        return getInterfaceIp(ifName);
    }

    /**
     * 获取 ZeroTier 虚拟网卡 (以 zt 开头或 VPN tun) 
     */
    public static String findZtInterface(Context context) {
        String fallback = null;
        try {
            java.util.Enumeration<java.net.NetworkInterface> interfaces = java.net.NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                java.net.NetworkInterface nif = interfaces.nextElement();
                String name = nif.getName();
                
                // [修复] 剥离别名后缀 (如 tun0:2 -> tun0)，ip rule 等内核操作不支持别名
                String cleanName = name.contains(":") ? name.split(":")[0] : name;
                
                 // 寻找特征网卡
                if (cleanName.startsWith("tun") || cleanName.startsWith("zt") || cleanName.startsWith("vpn")) {
                    // 只要正在运行且有 IP 的，优先返回
                    if (nif.getInetAddresses().hasMoreElements()) {
                        return cleanName;
                    }
                    // 没有 IP 但匹配名字的作为备份
                    fallback = cleanName;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Native interface scan failed", e);
        }
        // 如果扫描不到任何网卡，尝试最后的回退
        return (fallback != null) ? fallback : "tun0";
    }

    /**

     * 获取指定网卡的 IPv4 地址 (原生 Java API 方案)
     */
    public static String getInterfaceIp(String ifName) {
        if (ifName == null || ifName.isEmpty()) return null;
        try {
            java.net.NetworkInterface nif = java.net.NetworkInterface.getByName(ifName);
            if (nif != null) {
                java.util.Enumeration<java.net.InetAddress> addrs = nif.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress addr = addrs.nextElement();
                    if (addr instanceof java.net.Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "getInterfaceIp failed", e);
        }
        return null;
    }
    /**
     * 获取指定网卡的 CIDR 子网段 (如 10.5.5.0/24)
     */
    public static String getInterfaceSubnet(String ifName) {
        if (ifName == null || ifName.isEmpty()) return null;
        try {
            java.net.NetworkInterface nif = java.net.NetworkInterface.getByName(ifName);
            if (nif != null) {
                for (java.net.InterfaceAddress addr : nif.getInterfaceAddresses()) {
                    java.net.InetAddress inetAddr = addr.getAddress();
                    if (inetAddr instanceof java.net.Inet4Address) {
                        int prefixLen = addr.getNetworkPrefixLength();
                        byte[] ip = inetAddr.getAddress();
                        
                        // 计算子网起始地址
                        int mask = 0xffffffff << (32 - prefixLen);
                        int ipInt = ((ip[0] & 0xFF) << 24) | ((ip[1] & 0xFF) << 16) | 
                                    ((ip[2] & 0xFF) << 8) | (ip[3] & 0xFF);
                        int subnetInt = ipInt & mask;
                        
                        String subnetIp = String.format("%d.%d.%d.%d", 
                            (subnetInt >> 24) & 0xFF, (subnetInt >> 16) & 0xFF,
                            (subnetInt >> 8) & 0xFF, subnetInt & 0xFF);
                        
                        return subnetIp + "/" + prefixLen;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "getInterfaceSubnet failed", e);
        }
        return null;
    }

    /**
     * 获取指定网卡的 MTU (从 /sys/class/net 读取)
     */
    public static int getInterfaceMtu(String ifName) {
        if (ifName == null || ifName.isEmpty()) return 1500;
        try (BufferedReader br = new BufferedReader(new FileReader("/sys/class/net/" + ifName + "/mtu"))) {
            return Integer.parseInt(br.readLine().trim());
        } catch (Exception e) {
            Log.w(TAG, "Failed to read MTU for " + ifName + ", falling back to 1500");
            return 1500;
        }
    }

    /**
     * 流量统计追踪器
     */
    public static class TrafficStatsTracker {
        private java.util.Map<String, long[]> lastStats = new java.util.HashMap<>();
        private java.util.Map<String, Long> lastTime = new java.util.HashMap<>();
        private java.util.Map<String, String> lastSpeedStr = new java.util.HashMap<>();

        public String getSpeed(String ifName) {
            if (ifName == null || ifName.isEmpty()) return "0 B/s";
            
            long rx = readLongFromFile("/sys/class/net/" + ifName + "/statistics/rx_bytes");
            long tx = readLongFromFile("/sys/class/net/" + ifName + "/statistics/tx_bytes");
            long now = System.currentTimeMillis();
            
            if (lastTime.containsKey(ifName)) {
                long dt = now - lastTime.get(ifName);
                if (dt > 100) { // 采样间隔至少 100ms
                    long[] last = lastStats.get(ifName);
                    double rxSpeed = (double) (rx - last[0]) / dt * 1000.0;
                    double txSpeed = (double) (tx - last[1]) / dt * 1000.0;
                    String speedStr = String.format("↑ %s/s  ↓ %s/s", formatSize(txSpeed), formatSize(rxSpeed));
                    
                    lastStats.put(ifName, new long[]{rx, tx});
                    lastTime.put(ifName, now);
                    lastSpeedStr.put(ifName, speedStr);
                    return speedStr;
                } else {
                    // 太快了，返回上次缓存结果
                    return lastSpeedStr.getOrDefault(ifName, "0 B/s");
                }
            } else {
                lastStats.put(ifName, new long[]{rx, tx});
                lastTime.put(ifName, now);
                lastSpeedStr.put(ifName, "0 B/s");
            }
            return "0 B/s";
        }
    }

    private static TrafficStatsTracker globalTracker = new TrafficStatsTracker();

    /**
     * 获取指定网卡的实时速率 (使用全局追踪器)
     */
    public static String getInterfaceSpeed(String ifName) {
        return globalTracker.getSpeed(ifName);
    }

    private static boolean needsRootForStats = false;

    private static long readLongFromFile(String path) {
        // 1. 如果已知需要 Root，直接跳过常规读取
        if (!needsRootForStats) {
            try (BufferedReader br = new BufferedReader(new FileReader(path))) {
                return Long.parseLong(br.readLine().trim());
            } catch (Exception e) {
                needsRootForStats = true;
                Log.i(TAG, "Access denied to " + path + ", switching to Root fallback.");
            }
        }
        
        // 2. Root 回退方案 (缓存已生效或常规读取失败)
        if (needsRootForStats) {
            ShellExecutor.Result result = ShellExecutor.exec("cat " + path, true);
            if (result.isSuccess()) {
                try {
                    return Long.parseLong(result.stdout.trim());
                } catch (Exception ignored) {}
            }
        }
        return 0;
    }

    public static String formatSize(double bytes) {
        if (bytes < 1024) return String.format("%.0f B", bytes);
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format("%.1f KB", kb);
        double mb = kb / 1024.0;
        return String.format("%.1f MB", mb);
    }

    /**
     * 获取当前系统正在使用的 DNS 服务器列表
     */
    public static List<String> getSystemDnsServers(Context context) {
        List<String> dnsServers = new ArrayList<>();
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Network activeNetwork = cm.getActiveNetwork();
                    LinkProperties linkProperties = cm.getLinkProperties(activeNetwork);
                    if (linkProperties != null) {
                        for (java.net.InetAddress dns : linkProperties.getDnsServers()) {
                            dnsServers.add(dns.getHostAddress());
                        }
                    }
                } else {
                    // 低版本兼容处理
                    java.lang.reflect.Method method = cm.getClass().getMethod("getActiveLinkProperties");
                    Object lp = method.invoke(cm);
                    if (lp != null) {
                        java.lang.reflect.Method getDnsMethod = lp.getClass().getMethod("getDnsServers");
                        java.util.Collection<java.net.InetAddress> dnsList = (java.util.Collection<java.net.InetAddress>) getDnsMethod.invoke(lp);
                        for (java.net.InetAddress dns : dnsList) {
                            dnsServers.add(dns.getHostAddress());
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get system DNS servers", e);
        }
        return dnsServers;
    }

    /**
     * 获取指定网卡的 DNS 服务器 (首选)
     */
    public static String getInterfaceDns(Context context) {
        List<String> dnsList = getSystemDnsServers(context);
        if (dnsList != null && !dnsList.isEmpty()) {
            return dnsList.get(0);
        }
        return "8.8.8.8"; // 兜底使用 Google DNS
    }
}


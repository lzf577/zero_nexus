package com.zerotier.nexus.service;

import android.util.Log;

import com.zerotier.nexus.util.NetworkInfoUtils;
import com.zerotier.nexus.util.ShellExecutor;

import java.util.ArrayList;
import java.util.List;

/**
 * 出口模式逻辑管理器
 * (Exit Node Logic Manager)
 */
public class ExitNodeManager {
    private static final String TAG = "ExitNodeManager";
    
    private static ExitNodeManager instance;
    private android.content.Context context;
    private String lastZtIf = null;
    private String lastExtIf = null;
    private boolean isActive = false;
    private boolean isNonRoot = false;
    private Thread guardThread = null;
    private static final int GUARD_INTERVAL_MS = 10000; // 10秒检查一次

    private ExitNodeManager() {}

    public void init(android.content.Context context) {
        this.context = context.getApplicationContext();
    }

    public static synchronized ExitNodeManager getInstance() {
        if (instance == null) {
            instance = new ExitNodeManager();
        }
        return instance;
    }

    public boolean isActive() {
        // [深层修复] 只要系统中存在 MASQUERADE 规则，就认为处于激活状态，增强 UI 鲁棒性
        String[] iptablesCmds = {"iptables-nft", "iptables"};
        for (String cmd : iptablesCmds) {
            ShellExecutor.Result result = ShellExecutor.exec(cmd + " -t nat -L POSTROUTING -n", true);
            if (result.isSuccess() && result.stdout.contains("MASQUERADE")) {
                return true;
            }
        }
        
        if (isNonRoot) {
            return ProxyEngine.getInstance().isRunning();
        }
        return isActive;
    }

    private void log(String msg) {
        Log.i(TAG, msg);
        if (context == null) return;
        try {
            java.io.File logFile = new java.io.File(context.getFilesDir(), "kernel_nat.log");
            java.io.FileOutputStream fos = new java.io.FileOutputStream(logFile, true);
            String timestamp = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(new java.util.Date());
            fos.write(String.format("[%s] %s\n", timestamp, msg).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            fos.close();
            
            // 限制文件大小在 100KB 以内
            if (logFile.length() > 100 * 1024) {
                logFile.delete();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to write kernel log: " + e.getMessage());
        }
    }

    /**
     * 开启出口模式
     * @param ztIf ZeroTier 虚拟网卡名
     * @param extIf 公网出口网卡名 (如 wlan0)
     */
    public synchronized boolean enable(String ztIf, String extIf) {
        if (ztIf == null || extIf == null) return false;
        
        // 幂等性检查：如果当前已经以相同配置开启，则无需重新执行
        if (isActive() && ztIf.equals(lastZtIf) && extIf.equals(lastExtIf)) {
            Log.d(TAG, "Exit Node already active with same configuration, skipping.");
            return true;
        }
        
        Log.i(TAG, "Enabling Exit Node mode: " + ztIf + " -> " + extIf);
        log(">>> START: Enabling Kernel NAT " + ztIf + " -> " + extIf);
        
        // 1. 开启内核转发
        // [深层修复] 在开启转发前尝试放低 SELinux 姿态，否则在某些 ROM 上修改 ip_forward 会被静默禁止
        ShellExecutor.exec("setenforce 0", true);
        ShellExecutor.exec("echo 1 > /proc/sys/net/ipv4/ip_forward", true);
        ShellExecutor.exec("echo 1 > /proc/sys/net/ipv4/conf/all/forwarding", true);
        ShellExecutor.exec("echo 1 > /proc/sys/net/ipv4/conf/default/forwarding", true);
        ShellExecutor.exec("sysctl -w net.ipv4.ip_forward=1", true);
        ShellExecutor.exec("sysctl -w net.ipv4.conf.all.forwarding=1", true);
        
        // [新增] 更多层级的转发守护和特性支持
        ShellExecutor.exec("setprop net.ipv4.ip_forward 1", true);
        ShellExecutor.exec("echo 1 > /proc/sys/net/ipv4/ip_dynaddr", true); // 动态 IP 地址支持
        
        // [新增] 调整 rp_filter，防止由于非对称路由导致的丢包 (出口模式必备)
        ShellExecutor.exec("echo 0 > /proc/sys/net/ipv4/conf/all/rp_filter", true);
        ShellExecutor.exec("echo 0 > /proc/sys/net/ipv4/conf/default/rp_filter", true);
        ShellExecutor.exec(String.format("echo 0 > /proc/sys/net/ipv4/conf/%s/rp_filter", ztIf), true);
        ShellExecutor.exec(String.format("echo 0 > /proc/sys/net/ipv4/conf/%s/rp_filter", extIf), true);

        // [加固] 使用 ndc 和 settings 强制开启官方转发逻辑，防止后台回跳
        ShellExecutor.exec("ndc ipfwd enable zerotierfix", true);
        ShellExecutor.exec("settings put global low_power_sticky 0", true); // 防止省电模式拦截
        
        // 验证转发是否开启
        ShellExecutor.Result fwdCheck = ShellExecutor.exec("cat /proc/sys/net/ipv4/ip_forward", true);
        if (!fwdCheck.stdout.trim().equals("1")) {
            Log.e(TAG, "Failed to enable IP forwarding, trying alternative...");
            ShellExecutor.exec("ndc ipfwd enable forced", true);
            // 最后的挣扎：直接设置 prop
            ShellExecutor.exec("setprop net.wan.ip_forward 1", true);
        }
        
        // [核心] 动态获取 ZT 网卡子网
        String ztSubnet = NetworkInfoUtils.getInterfaceSubnet(ztIf);
        if (ztSubnet == null) {
            Log.w(TAG, "Could not detect ZT subnet automatically, falling back to 10.5.5.0/24");
            ztSubnet = "10.5.5.0/24";
        } else {
            Log.i(TAG, "Detected ZT subnet: " + ztSubnet);
        }

        // 2. 清理旧规则 (尽可能清理 Legacy, NFT 和 ndc)
        disableNdcRules(ztIf, extIf);
        // [修复] 这里的清理需要更彻底，包括删除所有匹配子网的 PBR 规则
        cleanUpExistingRules(ztIf, extIf, ztSubnet);
        
        // 4. 应用规则 (直接使用纯手动模式，绕过 ndc 的黑盒逻辑)
        Log.d(TAG, "Using Pure Manual Mode for absolute control...");
        boolean success = applyManualRules(ztIf, extIf, ztSubnet);
        
        // 5. 解决安卓路由表隔离问题 (Policy Routing Override)
        if (success) {
            String tableId = findDefaultTable(extIf);
            applyRoutingOverride(ztIf, extIf, ztSubnet, tableId);
            // 6. 终极修复方案：DNS 重定向 + MSS 修正
            int extMtu = NetworkInfoUtils.getInterfaceMtu(extIf);
            String targetDns = NetworkInfoUtils.getInterfaceDns(context);
            applyUltimateFix(ztIf, extIf, extMtu, ztSubnet, targetDns); 
        }

        if (success) {
            this.lastZtIf = ztIf;
            this.lastExtIf = extIf;
            this.isActive = true;
            this.isNonRoot = false;
            
            // [调整] 阶段 2 支持 Root NAT 与 TUIC 并行开启，不再主动关闭
            
            startGuardThread(ztIf, extIf);
            return true;
        } else {
            Log.e(TAG, "Failed to enable Exit Node with all available backends");
            return false;
        }
    }

    /**
     * [新增] 彻底清理旧的转发和路由规则，防止重复注入和陈旧路由
     */
    private void cleanUpExistingRules(String ztIf, String extIf, String ztSubnet) {
        String[] tools = {"iptables", "iptables-nft"};
        for (String cmd : tools) {
            // A. 清理 MASQUERADE (循环删除直到报错，处理冗余)
            while (ShellExecutor.exec(String.format("%s -t nat -D POSTROUTING -s %s -j MASQUERADE", cmd, ztSubnet), true).isSuccess());
            while (ShellExecutor.exec(String.format("%s -t nat -D POSTROUTING -o %s -j MASQUERADE", cmd, extIf), true).isSuccess());
            
            // B. 清理 FORWARD ACCEPT
            while (ShellExecutor.exec(String.format("%s -D FORWARD -j ACCEPT", cmd), true).isSuccess());
            while (ShellExecutor.exec(String.format("%s -D FORWARD -s %s -j ACCEPT", cmd, ztSubnet), true).isSuccess());
            while (ShellExecutor.exec(String.format("%s -D FORWARD -d %s -j ACCEPT", cmd, ztSubnet), true).isSuccess());
            
            // C. 清理 TCPMSS
            while (ShellExecutor.exec(String.format("%s -t mangle -D FORWARD -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu", cmd), true).isSuccess());
            while (ShellExecutor.exec(String.format("%s -t mangle -D FORWARD -s %s -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --set-mss 1200", cmd, ztSubnet), true).isSuccess());
        }

        // D. 清理 PBR 路由规则 (循环删除所有可能冲突的特征规则)
        while (ShellExecutor.exec(String.format("ip rule del from %s", ztSubnet), true).isSuccess());
        while (ShellExecutor.exec(String.format("ip rule del to %s", ztSubnet), true).isSuccess());
        while (ShellExecutor.exec(String.format("ip rule del iif %s", ztIf), true).isSuccess());
    }

    /**
     * 开启非 Root (代理) 出口模式
     */
    public synchronized boolean enableNonRoot(android.content.Context context, String protocol, String uuid, String password, int port) {
        Log.i(TAG, "Enabling Non-Root Exit Node (Proxy Mode) [" + protocol + "] on port: " + port);
        ProxyEngine.getInstance().startServer(context, protocol, uuid, password, port);
        this.isActive = true;
        this.isNonRoot = true;
        return true;
    }

    /**
     * 关闭非 Root (代理) 出口模式
     */
    public synchronized void disableNonRoot(android.content.Context context) {
        Log.i(TAG, "Disabling Non-Root Exit Node (Proxy Mode)");
        ProxyEngine.getInstance().stop();
        if (isNonRoot) {
            this.isActive = false;
            this.isNonRoot = false;
        }
    }

    private boolean applyManualRules(String ztIf, String extIf, String ztSubnet) {
        // 1. 核心转发开关全开
        ShellExecutor.exec("echo 1 > /proc/sys/net/ipv4/ip_forward", true);
        ShellExecutor.exec("echo 1 > /proc/sys/net/ipv4/conf/all/forwarding", true);
        
        // 3. 尝试利用安卓原生热点引擎
        Log.d(TAG, "Attempting Native Tethering Bridge (Auto-Prime)...");
        ShellExecutor.exec("ndc ipfwd enable zerotierfix", true);
        ShellExecutor.exec(String.format("ndc tether interface add %s", ztIf), true);
        ShellExecutor.exec(String.format("ndc nat enable %s %s 1", ztIf, extIf), true);
        
        String[] tools = {"iptables-nft", "iptables"};
        boolean anySuccess = false;
        for (String cmd : tools) {
            // A. “地毯式” NAT：不对准特定网卡，确保无论哪张卡联网都能转出去
            ShellExecutor.exec(String.format("%s -t nat -I POSTROUTING 1 -s %s -j MASQUERADE", cmd, ztSubnet), true);
            ShellExecutor.exec(String.format("%s -t nat -I POSTROUTING 1 -o %s -j MASQUERADE", cmd, extIf), true);
            
            // [新增] 显式放行代理端口 (UDP 10004)，确保外部数据能传给 tuic
            ShellExecutor.exec(String.format("%s -I INPUT 1 -p udp --dport 10004 -j ACCEPT", cmd), true);
            
            // B. 最顶级 FORWARD 放行
            ShellExecutor.exec(String.format("%s -I FORWARD 1 -j ACCEPT", cmd), true);
            
            // [加固] TCP MSS 修正：防止大包导致的网页打开缓慢或无法访问 (初级修正，由 applyUltimateFix 深度修正)
            ShellExecutor.exec(String.format("%s -t mangle -I FORWARD 1 -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu", cmd), true);
            
            // [新增] 确保规则在 FORWARD 链最前端且不重复 (双重注入加固)
            ShellExecutor.exec(String.format("%s -I FORWARD 1 -s %s -j ACCEPT", cmd, ztSubnet), true);
            ShellExecutor.exec(String.format("%s -I FORWARD 1 -d %s -j ACCEPT", cmd, ztSubnet), true);

            // 检查 NAT 是否成功
            ShellExecutor.Result r = ShellExecutor.exec(cmd + " -t nat -L POSTROUTING -n", true);
            if (r.stdout.contains("MASQUERADE") || r.stdout.contains("tetherctrl")) {
                anySuccess = true;
            }
        }
        return anySuccess;
    }

    private boolean applyNdcRules(String ztIf, String extIf) {
        // ndc 是安卓原生的网络共享/NAT 管理工具，能自动处理路由表，比纯 iptables 更可靠
        ShellExecutor.exec("ndc ipfwd enable zerotierfix", true);
        
        // [终极加固] 暴力开启系统转发，防止 ndc 在某些设备上不生效
        ShellExecutor.exec("echo 1 > /proc/sys/net/ipv4/ip_forward", true);

        // ndc nat enable [internalInterface] [externalInterface] [addRoutes]
        ShellExecutor.Result r2 = ShellExecutor.exec(String.format("ndc nat enable %s %s 1", ztIf, extIf), true);
        if (!r2.stdout.contains("200")) {
            Log.e(TAG, "ndc nat enable failed: " + r2.stdout);
            // 这里不直接返回，尝试继续。有些系统可能报错成功。
        }
        return r2.stdout.contains("200");
    }

    private void applyRoutingOverride(String ztIf, String extIf, String ztSubnet, String tableId) {
        // [极高优先级] 本地回路保护：目的地是 ZT 内网或者本机 IP 的包，绝对不能走策略路由，否则 PING 不通
        // 优先级设为 8000，确保在所有策略之前执行
        ShellExecutor.exec(String.format("ip rule add to %s lookup main prio 8000", ztSubnet), true);

        if (tableId != null) {
            Log.i(TAG, "Detected WAN routing table: " + tableId + " for interface " + extIf + ". Injecting overrides.");
            // [核心] 强制源地址重定向
            ShellExecutor.exec(String.format("ip rule add from %s lookup %s prio 11500", ztSubnet, tableId), true);
            
            // [核心] 强制入接口重定向：任何从 tun0 进来的非本地包直接查找外网表
            ShellExecutor.exec(String.format("ip rule add iif %s lookup %s prio 11000", ztIf, tableId), true);
        } else {
            Log.w(TAG, "Could not detect routing table for " + extIf + ". Using 'main' as fallback.");
            ShellExecutor.exec(String.format("ip rule add from %s lookup main prio 11500", ztSubnet), true);
            ShellExecutor.exec(String.format("ip rule add iif %s lookup main prio 11000", ztIf), true);
        }
    }

    public void applyUltimateFix(String ztIf, String extIf, int mtu, String ztSubnet, String targetDns) {
        // [调整] 针对物理网卡自动计算 MSS (MTU - 40)，如果 MTU 异常则回退到 1200
        // 1200 是 ZeroTier 建议的安全 MSS 值，能适配绝大部分复杂网络
        int mss = (mtu >= 576 && mtu <= 1500) ? (mtu - 40) : 1200; 
        Log.i(TAG, String.format("Applying Ultimate Fix: MTU=%d, Calculated MSS=%d, TargetDNS=%s", mtu, mss, targetDns));
        
        String[] iptablesCmds = {"iptables-nft", "iptables"};
        for (String cmd : iptablesCmds) {
            // A. 清理旧 MSS 规则 (由于 MSS 是动态的，必须清理旧的以防冲突)
            ShellExecutor.exec(String.format("%s -t mangle -D FORWARD -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu", cmd), true);
            ShellExecutor.exec(String.format("%s -t mangle -D FORWARD -s %s -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --set-mss %d", cmd, ztSubnet, mss), true);
            
            // B. 动态 MSS 锁定：优先使用 set-mss 显式锁定计算出的值，防止 clamp-mss 失效
            // [加固] 使用 -I 1 确保规则在最前面
            ShellExecutor.exec(String.format("%s -t mangle -I FORWARD 1 -s %s -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --set-mss %d", cmd, ztSubnet, mss), true);
            ShellExecutor.exec(String.format("%s -t mangle -I FORWARD 1 -d %s -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --set-mss %d", cmd, ztSubnet, mss), true);
            
            // C. TTL 伪装：躲避运营商对热点分流的检测
            ShellExecutor.exec(String.format("%s -t mangle -I POSTROUTING 1 -o %s -j TTL --ttl-set 64", cmd, extIf), true);
            
            // D. FORWARD 链显式放行 (二次加固)
            ShellExecutor.exec(String.format("%s -I FORWARD 1 -i %s -j ACCEPT", cmd, ztIf), true);
            ShellExecutor.exec(String.format("%s -I FORWARD 1 -o %s -j ACCEPT", cmd, ztIf), true);
            ShellExecutor.exec(String.format("%s -I FORWARD 1 -i %s -m state --state RELATED,ESTABLISHED -j ACCEPT", cmd, extIf), true);

            // [新增] E. DNS 重定向 (劫持)：强制让 ZT 流量使用本地最优 DNS
            if (targetDns != null) {
                // 清理旧规则
                ShellExecutor.exec(String.format("%s -t nat -D PREROUTING -i %s -p udp --dport 53 -j DNAT --to-destination %s", cmd, ztIf, targetDns), true);
                ShellExecutor.exec(String.format("%s -t nat -D PREROUTING -i %s -p tcp --dport 53 -j DNAT --to-destination %s", cmd, ztIf, targetDns), true);
                
                // 插入新规则
                ShellExecutor.exec(String.format("%s -t nat -I PREROUTING 1 -i %s -p udp --dport 53 -j DNAT --to-destination %s", cmd, ztIf, targetDns), true);
                ShellExecutor.exec(String.format("%s -t nat -I PREROUTING 1 -i %s -p tcp --dport 53 -j DNAT --to-destination %s", cmd, ztIf, targetDns), true);
            }
        }
    }

    private String findDefaultTable(String extIf) {
        // 先尝试精准匹配
        ShellExecutor.Result result = ShellExecutor.exec("ip route show table all", true);
        if (result.isSuccess()) {
            String[] lines = result.stdout.split("\n");
            
            // 1. 第一优先级：寻找包含 default 且 明确 指向物理网卡的 table
            // 增加对 netd 表的识别：通常是数字，或者是某些厂商定义的标识如 wlan0, rmnet_data0
            for (String line : lines) {
                if (line.contains("default") && line.contains("table ") && 
                    line.contains("dev " + extIf)) {
                    String[] parts = line.split("table ");
                    if (parts.length > 1) {
                        String table = parts[1].trim().split("\\s+")[0];
                        // 过滤掉 local, main, default 等保留表
                        if (!table.equals("local") && !table.equals("main") && !table.equals("default")) {
                            Log.i(TAG, "Found dynamic routing table: " + table + " for interface " + extIf);
                            return table;
                        }
                    }
                }
            }
            
            // 2. 第二优先级：如果没有找到带 table 命名的，尝试查找带 uid 规则关联的表 (Android 11+)
            for (String line : lines) {
                if (line.contains("default") && line.contains("dev " + extIf) && !line.contains("table ")) {
                    // 如果在 main 表中找到了，直接返回 main
                    Log.i(TAG, "Found default route for " + extIf + " in legacy table (main).");
                    return "main";
                }
            }

            // 3. 第三优先级：只要有 default 的非系统表都行 (兜底数字表，尽量选最大的，通常是最近生成的)
            String lastNumericTable = null;
            for (String line : lines) {
                if (line.contains("default") && line.contains("table ")) {
                    String[] parts = line.split("table ");
                    if (parts.length > 1) {
                        String table = parts[1].trim().split("\\s+")[0];
                        if (table.matches("\\d+")) lastNumericTable = table;
                    }
                }
            }
            if (lastNumericTable != null) return lastNumericTable;
        }
        return null; // 无法自动定位，通常会落回 main (虽然后果堪忧)
    }

    /**
     * 关闭出口模式
     */
    public synchronized void disable() {
        if (!isActive && !isActive()) return;
        
        if (isNonRoot) {
            ProxyEngine.getInstance().stop();
            this.isActive = false;
            this.isNonRoot = false;
            return;
        }

        if (lastZtIf == null || lastExtIf == null) return;

        Log.i(TAG, "Disabling Exit Node mode");
        log(">>> STOP: Disabling Kernel NAT");
        stopGuardThread();
        disableNdcRules(lastZtIf, lastExtIf);
        
        // 动态获取子网进行清理 (如果失败则回退清理 10.5.5.0/24)
        String ztSubnet = NetworkInfoUtils.getInterfaceSubnet(lastZtIf);
        if (ztSubnet == null) ztSubnet = "10.5.5.0/24";

        // [修复] 调用集中的清理函数
        cleanUpExistingRules(lastZtIf, lastExtIf, ztSubnet);
        
        this.isActive = false;
    }

    private void disableNdcRules(String ztIf, String extIf) {
        ShellExecutor.exec(String.format("ndc nat disable %s %s", ztIf, extIf), true);
        ShellExecutor.exec("ndc ipfwd disable zerotierfix", true);
    }

    private List<String> getDisableCommands(String iptablesTool, String ztIf, String extIf) {
        List<String> cmds = new ArrayList<>();
        cmds.add(String.format("%s -t nat -D POSTROUTING -o %s -j MASQUERADE", iptablesTool, extIf));
        cmds.add(String.format("%s -D FORWARD -i %s -o %s -j ACCEPT", iptablesTool, ztIf, extIf));
        cmds.add(String.format("%s -D FORWARD -i %s -o %s -m state --state RELATED,ESTABLISHED -j ACCEPT", iptablesTool, extIf, ztIf));
        cmds.add(String.format("%s -t mangle -D FORWARD -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu", iptablesTool));
        return cmds;
    }

    /**
     * 设置网卡 MTU
     */
    public boolean setInterfaceMtu(String ifName, int mtu) {
        if (ifName == null) return false;
        String cmd = String.format("ip link set dev %s mtu %d", ifName, mtu);
        return ShellExecutor.exec(cmd, true).isSuccess();
    }

    /**
     * 检查 UDP 端口是否被占用
     */
    public boolean isUdpPortInUse(int port) {
        try (java.net.DatagramSocket socket = new java.net.DatagramSocket(port)) {
            socket.setReuseAddress(true);
            return false;
        } catch (java.net.SocketException e) {
            return true;
        }
    }

    /**
     * 检查 TCP 端口是否被占用 (IPv4 loopback)
     */
    public boolean isTcpPortInUse(int port) {
        try (java.net.ServerSocket socket = new java.net.ServerSocket()) {
            socket.setReuseAddress(true);
            socket.bind(new java.net.InetSocketAddress("127.0.0.1", port));
            return false;
        } catch (java.io.IOException e) {
            return true;
        }
    }

    /**
     * 开启 BBR 拥塞控制
     */
    public boolean applyBbr(boolean enable) {
        if (enable) {
            List<String> cmds = new ArrayList<>();
            cmds.add("sysctl -w net.core.default_qdisc=fq");
            cmds.add("sysctl -w net.ipv4.tcp_congestion_control=bbr");
            return ShellExecutor.exec(cmds, true).isSuccess();
        }
        return true; // 不主动关闭以防影响稳定性
    }

    /**
     * UDP 缓冲区优化
     */
    public boolean applyUdpTuning(boolean enable) {
        if (enable) {
            List<String> cmds = new ArrayList<>();
            // 调优参数源自 zerotier_actions
            cmds.add("sysctl -w net.core.rmem_max=26214400");
            cmds.add("sysctl -w net.core.wmem_max=26214400");
            cmds.add("sysctl -w net.ipv4.udp_rmem_min=16384");
            cmds.add("sysctl -w net.ipv4.udp_wmem_min=16384");
            return ShellExecutor.exec(cmds, true).isSuccess();
        }
        return true;
    }

    /**
     * 验证是否有实时流量经过转发链 (用于自动化检测)
     * @return 命中规则的包数量，若为 0 或解析失败则返回 -1
     */
    public long verifyForwardingActivity() {
        if (!isActive) return -1;
        
        String[] tools = {"iptables-nft", "iptables"};
        for (String cmd : tools) {
            // 查看 FORWARD 链的包统计信息 (-v)
            ShellExecutor.Result result = ShellExecutor.exec(cmd + " -L FORWARD -v -n", true);
            if (result.isSuccess()) {
                String[] lines = result.stdout.split("\n");
                for (String line : lines) {
                    // 查找包含 zt 接口且被 ACCEPT 的行
                    if ((line.contains("zt") || line.contains("tun")) && line.contains("ACCEPT")) {
                        // 典型的 iptables -v 输出首列为包数量 (pkts)
                        String pktsStr = line.trim().split("\\s+")[0];
                        try {
                            // 处理类似 1.2M 这种缩写
                            if (pktsStr.endsWith("K")) return (long)(Double.parseDouble(pktsStr.substring(0, pktsStr.length()-1)) * 1000);
                            if (pktsStr.endsWith("M")) return (long)(Double.parseDouble(pktsStr.substring(0, pktsStr.length()-1)) * 1000000);
                            if (pktsStr.endsWith("G")) return (long)(Double.parseDouble(pktsStr.substring(0, pktsStr.length()-1)) * 1000000000);
                            return Long.parseLong(pktsStr);
                        } catch (Exception e) {
                            // 继续匹配
                        }
                    }
                }
            }
        }
        return 0;
    }

    /**
     * 获取总转发流量 (字节数)
     * 解析 iptables -t nat -L POSTROUTING -n -v 结果
     */
    public long getForwardingTotalBytes() {
        String[] cmds = {"iptables", "iptables-nft"};
        try {
            for (String cmd : cmds) {
                ShellExecutor.Result res = ShellExecutor.exec(cmd + " -t nat -L POSTROUTING -n -v", true);
                if (res.isSuccess()) {
                    String[] lines = res.stdout.split("\n");
                    for (String line : lines) {
                        // 查找包含 MASQUERADE 且包含 10.5.5.0/24 (网段) 的行
                        if ((line.contains("MASQUERADE") || line.contains("tetherctrl")) && 
                            (line.contains("10.5.5") || line.contains("0.0.0/0"))) {
                            String[] parts = line.trim().split("\\s+");
                            if (parts.length >= 2) {
                                // parts[0] 是 pkts, parts[1] 是 bytes
                                String bytesStr = parts[1];
                                if (bytesStr.endsWith("K")) return (long) (Double.parseDouble(bytesStr.replace("K", "")) * 1024);
                                if (bytesStr.endsWith("M")) return (long) (Double.parseDouble(bytesStr.replace("M", "")) * 1024 * 1024);
                                if (bytesStr.endsWith("G")) return (long) (Double.parseDouble(bytesStr.replace("G", "")) * 1024 * 1024 * 1024);
                                return Long.parseLong(bytesStr);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to parse forwarding bytes: " + e.getMessage());
        }
        return 0;
    }

    private synchronized void startGuardThread(final String ztIf, final String extIf) {
        stopGuardThread();
        Log.i(TAG, "Starting IP Forwarding guard thread...");
        guardThread = new Thread(() -> {
            while (!Thread.interrupted()) {
                try {
                    Thread.sleep(GUARD_INTERVAL_MS);
                    
                    // 检查转发开关是否被关闭
                    ShellExecutor.Result fwdCheck = ShellExecutor.exec("cat /proc/sys/net/ipv4/ip_forward", true);
                    if (fwdCheck.isSuccess() && !fwdCheck.stdout.trim().equals("1")) {
                        Log.w(TAG, "[Guard] IP Forwarding was disabled by system! Re-enabling...");
                        ShellExecutor.exec("echo 1 > /proc/sys/net/ipv4/ip_forward", true);
                        ShellExecutor.exec("sysctl -w net.ipv4.ip_forward=1", true);
                        ShellExecutor.exec("setprop net.ipv4.ip_forward 1", true);
                        ShellExecutor.exec("setprop net.wan.ip_forward 1", true);
                        ShellExecutor.exec("ndc ipfwd enable zerotierfix", true);
                    }
                    
                    // [加固] 检查 NAT 规则是否存在 (针对 Root 模式)
                    if (!isNonRoot && !isActive()) {
                        log("[Guard] NAT MASQUERADE rules disappeared! Re-applying...");
                        enable(ztIf, extIf);
                    }
                    
                    // 检查是否有流量通过，作为连通性参考
                    long pkts = verifyForwardingActivity();
                    if (pkts == 0) {
                        Log.v(TAG, "[Guard] No traffic detected in FORWARD chain yet.");
                    } else {
                        Log.v(TAG, "[Guard] Forwarding active, pkts: " + pkts);
                    }
                    
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    Log.e(TAG, "[Guard] Error in guard thread", e);
                }
            }
            Log.i(TAG, "IP Forwarding guard thread stopped.");
        }, "IPForwardGuard");
        guardThread.start();
    }

    private synchronized void stopGuardThread() {
        if (guardThread != null) {
            guardThread.interrupt();
            guardThread = null;
        }
    }
}

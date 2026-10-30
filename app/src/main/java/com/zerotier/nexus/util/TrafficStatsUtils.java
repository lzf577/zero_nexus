package com.zerotier.nexus.util;

import android.util.Log;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * 流量统计工具类
 * 通过解析 /proc/net/dev 获取网卡级流量
 */
public class TrafficStatsUtils {
    private static final String TAG = "TrafficStatsUtils";

    public static class TrafficInfo {
        public long rxBytes;
        public long txBytes;

        public TrafficInfo(long rxBytes, long txBytes) {
            this.rxBytes = rxBytes;
            this.txBytes = txBytes;
        }
    }

    /**
     * 获取指定网卡的流量信息
     */
    public static TrafficInfo getInterfaceTraffic(String iface) {
        if (iface == null) return new TrafficInfo(0, 0);
        
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/net/dev"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains(iface + ":")) {
                    String[] parts = line.split(":")[1].trim().split("\\s+");
                    // /proc/net/dev 格式：
                    // face |bytes    packets errs drop fifo frame compressed multicast|bytes ...
                    //       0        1       2    3    4    5     6          7         8
                    long rx = Long.parseLong(parts[0]);
                    long tx = Long.parseLong(parts[8]);
                    return new TrafficInfo(rx, tx);
                }
            }
        } catch (IOException | NumberFormatException e) {
            Log.e(TAG, "Error reading /proc/net/dev", e);
        }
        return new TrafficInfo(0, 0);
    }

    /**
     * 格式化速率
     */
    public static String formatSpeed(long bytesPerSecond) {
        if (bytesPerSecond < 1024) {
            return bytesPerSecond + " B/s";
        } else if (bytesPerSecond < 1024 * 1024) {
            return String.format("%.1f KB/s", bytesPerSecond / 1024.0);
        } else {
            return String.format("%.2f MB/s", bytesPerSecond / (1024.0 * 1024.0));
        }
    }
}

package com.zerotier.nexus.util;

import android.content.Context;
import android.util.Log;

import com.zerotier.nexus.ZerotierFixApplication;
import com.zerotier.nexus.model.AssignedAddress;
import com.zerotier.nexus.model.DaoSession;
import com.zerotier.nexus.model.Network;
import com.zerotier.nexus.model.NetworkConfig;

import java.io.File;
import java.util.List;

/**
 * 网络缓存管理工具类
 */
public class NetworkCacheManager {
    private static final String TAG = "NetworkCacheManager";

    /**
     * 清理指定网络的网络缓存 (物理文件与数据库记录)
     *
     * @param context 上下文
     * @param network 目标网络
     */
    public static void clearNetworkCache(Context context, Network network) {
        if (network == null || context == null) return;
        String networkIdStr = network.getNetworkIdStr();
        Log.i(TAG, "Clearing network cache for: " + networkIdStr);

        // 1. 清理 ZeroTier 物理文件
        File filesDir = context.getFilesDir();
        
        // networks.d/ 指定配置
        File networksDir = new File(filesDir, "networks.d");
        if (networksDir.exists() && networksDir.isDirectory()) {
            File[] configs = networksDir.listFiles((dir, name) -> name.startsWith(networkIdStr));
            if (configs != null) {
                for (File config : configs) {
                    if (config.delete()) {
                        Log.d(TAG, "Deleted config file: " + config.getName());
                    }
                }
            }
        }
        
        // peers.d/ 路径缓存 (全量清理比较稳妥，因为 ZT 内核可能持有旧路径)
        File peersDir = new File(filesDir, "peers.d");
        if (deleteFileRecursive(peersDir)) {
            Log.d(TAG, "Deleted peers.d directory content");
        }

        // 2. 清理数据库记录 (AssignedAddress)
        // 注意：不删除 Network 本身，只删除关联的动态分配地址，强制重新获取
        try {
            DaoSession daoSession = ((ZerotierFixApplication) context.getApplicationContext()).getDaoSession();
            NetworkConfig config = network.getNetworkConfig();
            if (config != null) {
                List<AssignedAddress> addresses = config.getAssignedAddresses();
                if (addresses != null && !addresses.isEmpty()) {
                    for (AssignedAddress addr : addresses) {
                        daoSession.getAssignedAddressDao().delete(addr);
                    }
                    Log.d(TAG, "Deleted " + addresses.size() + " assigned addresses from DB");
                }
            }
            daoSession.clear();
        } catch (Exception e) {
            Log.e(TAG, "Failed to clear DB records", e);
        }
    }

    private static boolean deleteFileRecursive(File file) {
        if (file == null || !file.exists()) return false;
        boolean success = true;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    success &= deleteFileRecursive(child);
                }
            }
        }
        return file.delete() && success;
    }
}

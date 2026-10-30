package com.zerotier.nexus.util;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 代理二进制内核管理器 (Proxy Binary Manager)
 * 负责将 tuic 二进制文件从 assets 部署到系统私有目录并授予执行权限。
 */
public class ProxyBinaryManager {
    private static final String TAG = "ProxyBinaryManager";
    private static final String BINARY_NAME = "tuic";

    /**
     * 部署二进制内核
     */
    public static void deploy(Context context) {
        File binFile = getBinaryPath(context);
        if (binFile.exists()) {
            return; // 已部署
        }

        // 确定 CPU 架构
        String abi = Build.SUPPORTED_ABIS[0];
        String assetPath = "bin/" + abi + "/" + BINARY_NAME;

        try (InputStream is = context.getAssets().open(assetPath);
             FileOutputStream os = new FileOutputStream(binFile)) {
            
            byte[] buffer = new byte[8192];
            int read;
            while ((read = is.read(buffer)) != -1) {
                os.write(buffer, 0, read);
            }
            os.flush();
            
            // 授予执行权限
            binFile.setExecutable(true, false);
            Log.i(TAG, "Binary deployed successfully to: " + binFile.getAbsolutePath());
            
        } catch (IOException e) {
            Log.e(TAG, "Failed to deploy proxy binary: " + e.getMessage(), e);
        }
    }

    /**
     * 获取二进制内核路径
     */
    public static File getBinaryPath(Context context) {
        return new File(context.getFilesDir(), BINARY_NAME);
    }
}

package com.zerotier.nexus.util;

import android.util.Log;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Shell 命令执行工具类，支持 Root (su) 执行。
 * (Utility for executing shell commands, supports Root via su)
 */
public class ShellExecutor {
    private static final String TAG = "ShellExecutor";

    public static class Result {
        public int exitCode;
        public String stdout;
        public String stderr;

        public Result(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }

        public boolean isSuccess() {
            return exitCode == 0;
        }

        @Override
        public String toString() {
            return "Result{" +
                    "exitCode=" + exitCode +
                    ", stdout='" + stdout + '\'' +
                    ", stderr='" + stderr + '\'' +
                    '}';
        }
    }

    /**
     * 检查系统是否具备 Root (su) 环境
     */
    public static boolean isRootAvailable() {
        return exec("id", true).isSuccess();
    }

    /**
     * 执行单条命令
     */
    public static Result exec(String command, boolean useRoot) {
        // [修复] 命名空间逃逸 (必须包含 -- 分隔符防止后续参数被 nsenter 误判)
        if (useRoot && (command.startsWith("ip ") || command.startsWith("iptables") || 
                        command.startsWith("ndc") || command.startsWith("sysctl") || 
                        command.startsWith("setenforce") || command.startsWith("setprop") ||
                        command.startsWith("echo") || command.startsWith("cat"))) {
            command = "nsenter -t 1 -n -m -- " + command;
        }
        List<String> cmds = new ArrayList<>();
        cmds.add(command);
        return exec(cmds, useRoot);
    }

    /**
     * 执行多条命令 (Script 模式)
     */
    public static Result exec(List<String> commands, boolean useRoot) {
        int exitCode = -1;
        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        Process process = null;
        DataOutputStream os = null;

        try {
            process = Runtime.getRuntime().exec(useRoot ? "su" : "sh");
            os = new DataOutputStream(process.getOutputStream());

            for (String cmd : commands) {
                // [修复] List 模式也需要进行命名空间逃逸
                if (useRoot && (cmd.startsWith("ip ") || cmd.startsWith("iptables") || 
                                cmd.startsWith("ndc") || cmd.startsWith("sysctl") || 
                                cmd.startsWith("setenforce") || cmd.startsWith("setprop") ||
                                cmd.startsWith("echo") || cmd.startsWith("cat"))) {
                    cmd = "nsenter -t 1 -n -m -- " + cmd;
                }
                os.writeBytes(cmd + "\n");
            }
            os.writeBytes("exit\n");
            os.flush();

            BufferedReader outReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            BufferedReader errReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));

            String line;
            while ((line = outReader.readLine()) != null) {
                stdout.append(line).append("\n");
            }
            while ((line = errReader.readLine()) != null) {
                stderr.append(line).append("\n");
            }

            exitCode = process.waitFor();
            
        } catch (IOException | InterruptedException e) {
            Log.e(TAG, "Error executing shell commands", e);
            stderr.append(e.getMessage());
        } finally {
            try {
                if (os != null) os.close();
                if (process != null) process.destroy();
            } catch (IOException e) {
                // Ignore
            }
        }

        return new Result(exitCode, stdout.toString().trim(), stderr.toString().trim());
    }
}

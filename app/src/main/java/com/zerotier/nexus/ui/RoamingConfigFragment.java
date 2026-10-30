package com.zerotier.nexus.ui;

import android.content.SharedPreferences;
import android.util.Log;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputLayout;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import com.zerotier.nexus.R;
import com.zerotier.nexus.events.NetworkConfigChangedByUserEvent;
import com.zerotier.nexus.util.Constants;

import org.greenrobot.eventbus.EventBus;
import org.json.JSONObject;

import android.content.Intent;
import com.zerotier.nexus.util.ProxyBinaryManager;
import java.util.UUID;

public class RoamingConfigFragment extends Fragment {

    private SharedPreferences prefs;


    private TextInputLayout tilProxyHost;
    private EditText etProxyHost;
    private EditText etProxyPassword;
    private EditText etProxyUuid;
    private SwitchMaterial swProxyEnabled;

    private android.widget.TextView tvProxyLatency;
    private MaterialButton btnViewLogs;
    
    private android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable latencyRunnable;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_roaming_config, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());


        tilProxyHost = view.findViewById(R.id.til_proxy_host_global);
        etProxyHost = view.findViewById(R.id.et_proxy_host_global);
        etProxyPassword = view.findViewById(R.id.et_proxy_password_global);
        etProxyUuid = view.findViewById(R.id.et_proxy_uuid_global);
        swProxyEnabled = view.findViewById(R.id.sw_proxy_enabled_global);

        tvProxyLatency = view.findViewById(R.id.tv_proxy_latency);
        btnViewLogs = view.findViewById(R.id.btn_view_proxy_logs);

        // 自动部署二进制内核
        ProxyBinaryManager.deploy(requireContext());

        loadSettings();
        setupListeners();
        
        // 延迟采样逻辑
        latencyRunnable = new Runnable() {
            @Override
            public void run() {
                updateLatency();
                mainHandler.postDelayed(this, 10000); // 10秒采样一次
            }
        };
    }

    private android.content.BroadcastReceiver portReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context context, android.content.Intent intent) {
            int port = intent.getIntExtra("port", 1081);
            tvProxyLatency.setTag(port);
            updateLatencyDisplayWithPort(port);
        }
    };

    private void updateLatencyDisplayWithPort(int port) {
        String currentText = tvProxyLatency.getText().toString();
        if (currentText.contains("(L:")) {
            currentText = currentText.substring(0, currentText.indexOf("(L:"));
        }
        tvProxyLatency.setText(String.format("%s (L:%d)", currentText.trim(), port));
    }

    @Override
    public void onStart() {
        super.onStart();
        requireContext().registerReceiver(portReceiver, new android.content.IntentFilter("com.zerotier.nexus.PROXY_PORT_UPDATE"));
        mainHandler.post(latencyRunnable);
    }

    @Override
    public void onStop() {
        super.onStop();
        requireContext().unregisterReceiver(portReceiver);
        mainHandler.removeCallbacks(latencyRunnable);
    }

    private void updateLatency() {
        if (!isAdded()) return;
        boolean isRoamingOn = prefs.getBoolean("global_roaming_enabled", false);
        boolean isProxyOn = swProxyEnabled.isChecked();
        if (!isRoamingOn || !isProxyOn) {
            tvProxyLatency.setText("--");
            return;
        }

        String hostPort = etProxyHost.getText().toString().trim();
        if (hostPort.isEmpty()) return;

        new Thread(() -> {
            String host;
            if (hostPort.contains(":")) {
                host = hostPort.split(":")[0];
            } else {
                host = hostPort;
            }

            // 使用 ping 命令获取 1 次往返，并解析结果
            com.zerotier.nexus.util.ShellExecutor.Result result = 
                com.zerotier.nexus.util.ShellExecutor.exec("ping -c 1 -W 2 " + host, false);
            
            if (result.isSuccess() && result.stdout.contains("time=")) {
                try {
                    String out = result.stdout;
                    String timeStr = out.substring(out.indexOf("time=") + 5);
                    timeStr = timeStr.substring(0, timeStr.indexOf(" "));
                    double rtt = Double.parseDouble(timeStr);
                    
                    mainHandler.post(() -> {
                        if (isAdded()) {
                            String latencyText = String.format("%.1f ms", rtt);
                            Object tag = tvProxyLatency.getTag();
                            if (tag instanceof Integer) {
                                latencyText += String.format(" (L:%d)", (int) tag);
                            }
                            tvProxyLatency.setText(latencyText);
                            tvProxyLatency.setTextColor(getResources().getColor(rtt < 80 ? R.color.dashboard_accent_green : R.color.dashboard_text_sub));
                        }
                    });
                } catch (Exception e) {
                    mainHandler.post(() -> { if (isAdded()) tvProxyLatency.setText("ERR"); });
                }
            } else {
                mainHandler.post(() -> {
                    if (isAdded()) {
                        String latencyText = "超时/断开";
                        Object tag = tvProxyLatency.getTag();
                        if (tag instanceof Integer) {
                            latencyText += String.format(" (L:%d)", (int) tag);
                        }
                        tvProxyLatency.setText(latencyText);
                    }
                });
            }
        }).start();
    }

    private void loadSettings() {

        
        // 强制使用 TUIC v5
        prefs.edit().putString(Constants.PREF_PROXY_PROTOCOL, "TUIC v5").apply();

        String host = prefs.getString(Constants.PREF_PROXY_REMOTE_HOST, "");
        String port = prefs.getString(Constants.PREF_PROXY_REMOTE_PORT, "");
        if (!host.isEmpty() && !port.isEmpty()) {
            etProxyHost.setText(host + ":" + port);
        } else {
            etProxyHost.setText(host);
        }

        etProxyPassword.setText(prefs.getString(Constants.PREF_PROXY_PASSWORD, ""));
        etProxyUuid.setText(prefs.getString(Constants.PREF_PROXY_UUID, ""));
        swProxyEnabled.setChecked(prefs.getBoolean(Constants.PREF_PROXY_MODE, false));


    }



    private void setupListeners() {


        // 2. 扫码导入逻辑
        tilProxyHost.setEndIconOnClickListener(v -> {
            IntentIntegrator integrator = IntentIntegrator.forSupportFragment(this);
            integrator.setCaptureActivity(QRScanActivity.class);
            integrator.setPrompt("请对准出口配置二维码");
            integrator.setBeepEnabled(true);
            integrator.setOrientationLocked(true);
            integrator.initiateScan();
        });

        swProxyEnabled.setOnCheckedChangeListener((bv, isChecked) -> {
            saveProxySettings();
            prefs.edit().putBoolean(Constants.PREF_PROXY_MODE, isChecked).apply();

            // 即时生效：通过 EventBus 通知 Service 重新加载配置
            long lastNwid = prefs.getLong("last_activated_network_id", 0);
            com.zerotier.nexus.model.Network network = null;
            if (lastNwid != 0) {
                com.zerotier.nexus.model.DaoSession daoSession = ((com.zerotier.nexus.ZerotierFixApplication) requireActivity().getApplication()).getDaoSession();
                network = daoSession.getNetworkDao().load(lastNwid);
            }
            org.greenrobot.eventbus.EventBus.getDefault().post(new com.zerotier.nexus.events.NetworkConfigChangedByUserEvent(network));
        });
        
        // 当焦点失去时自动保存内容
        View.OnFocusChangeListener autoSaveListener = (v, hasFocus) -> {
            if (!hasFocus) saveProxySettings();
        };
        etProxyHost.setOnFocusChangeListener(autoSaveListener);
        etProxyPassword.setOnFocusChangeListener(autoSaveListener);
        etProxyUuid.setOnFocusChangeListener(autoSaveListener);

        // 3. 诊断工具
        btnViewLogs.setOnClickListener(v -> showProxyLogs());
    }

    private void showProxyLogs() {
        try {
            java.io.File logFile = new java.io.File(requireContext().getFilesDir(), "proxy_debug.log");
            if (!logFile.exists()) {
                Toast.makeText(getContext(), "无可用日志 (尚未开启服务)", Toast.LENGTH_SHORT).show();
                return;
            }

            // 读取最后 100 行
            java.util.List<String> lines = java.nio.file.Files.readAllLines(logFile.toPath());
            int start = Math.max(0, lines.size() - 100);
            String displayLogs = android.text.TextUtils.join("\n", lines.subList(start, lines.size()));

            androidx.appcompat.app.AlertDialog dialog = new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                    .setTitle("客户端运行日志 (Sing-box)")
                    .setMessage(displayLogs.isEmpty() ? "日志为空" : displayLogs)
                    .setPositiveButton("确定", null)
                    .show();
            // 让日志内容可选中复制
            android.widget.TextView textView = dialog.findViewById(android.R.id.message);
            if (textView != null) {
                textView.setTextIsSelectable(true);
            }
        } catch (Exception e) {
            Toast.makeText(getContext(), "日志读取失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void saveProxySettings() {
        boolean isProxyEnabled = swProxyEnabled.isChecked();
        String hostPort = etProxyHost.getText().toString().trim();
        String host = "";
        String port = "";
        if (hostPort.contains(":")) {
            String[] parts = hostPort.split(":");
            host = parts[0];
            port = parts[1];
        } else {
            host = hostPort;
        }

        prefs.edit()
                .putBoolean(Constants.PREF_PROXY_MODE, isProxyEnabled)
                .putString(Constants.PREF_PROXY_PROTOCOL, "TUIC v5")
                .putString(Constants.PREF_PROXY_REMOTE_HOST, host.trim())
                .putString(Constants.PREF_PROXY_REMOTE_PORT, port.trim().isEmpty() ? "10004" : port.trim())
                .putString(Constants.PREF_PROXY_PASSWORD, etProxyPassword.getText().toString().trim())
                .putString(Constants.PREF_PROXY_UUID, etProxyUuid.getText().toString().trim())
                .apply();
        
        // [逻辑修正] 将设置同步到数据库 NetworkConfig 中
        com.zerotier.nexus.model.DaoSession daoSession = ((com.zerotier.nexus.ZerotierFixApplication) requireActivity().getApplication()).getDaoSession();
        com.zerotier.nexus.model.Network network = daoSession.getNetworkDao().queryBuilder()
                .where(com.zerotier.nexus.model.NetworkDao.Properties.LastActivated.eq(true))
                .unique();
        if (network != null && network.getNetworkConfig() != null) {
            com.zerotier.nexus.model.NetworkConfig config = network.getNetworkConfig();
            config.setProxyEnabled(isProxyEnabled);
            config.setProxyHost(host.trim());
            try {
                config.setProxyPort(Integer.parseInt(port.trim().isEmpty() ? "10004" : port.trim()));
            } catch (Exception ignored) {}
            
            // 更新数据库
            daoSession.getNetworkConfigDao().update(config);
            android.util.Log.e("RoamingConfig", "DB Sync: ProxyEnabled=" + isProxyEnabled + " for Network " + Long.toHexString(network.getNetworkId()));
        }
        
        // 发送事件通知 Service 刷新
        org.greenrobot.eventbus.EventBus.getDefault().post(new com.zerotier.nexus.events.NetworkConfigChangedByUserEvent());
    }

    @Override
    public void onResume() {
        super.onResume();
        mainHandler.post(latencyRunnable);
    }

    @Override
    public void onPause() {
        super.onPause();
        saveProxySettings();
        mainHandler.removeCallbacks(latencyRunnable);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        IntentResult result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (result != null) {
            if (result.getContents() == null) {
                Toast.makeText(getContext(), "取消扫码", Toast.LENGTH_SHORT).show();
            } else {
                parseQrResult(result.getContents());
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    private void parseQrResult(String content) {
        try {
            JSONObject json = new JSONObject(content);
            String host = json.getString("h");
            int port = json.getInt("p");
            String pwd = json.getString("w");
            String uuid = json.getString("u");

            String mode = json.optString("mode", "unknown");
            String modeDesc = mode.equals("root") ? " (内核转发模式)" : (mode.equals("proxy") ? " (用户态代理模式)" : "");

            // 更新 UI
            etProxyHost.setText(host + ":" + port);
            etProxyPassword.setText(pwd);
            etProxyUuid.setText(uuid);
            swProxyEnabled.setChecked(true);

            // 保存设置
            saveProxySettings();
            
            Toast.makeText(getContext(), "导入配置成功" + modeDesc, Toast.LENGTH_LONG).show();
            
            // 立即刷新数据库
            refreshNetworkConfigInDb();
            
        } catch (Exception e) {
            Log.e("RoamingConfig", "Scan failed", e);
            Toast.makeText(getContext(), "解析二维码失败: 请确保扫描的是出口配置", Toast.LENGTH_LONG).show();
        }
    }




    private void refreshNetworkConfigInDb() {
        com.zerotier.nexus.model.DaoSession daoSession = ((com.zerotier.nexus.ZerotierFixApplication) requireActivity().getApplication()).getDaoSession();
        com.zerotier.nexus.model.Network network = daoSession.getNetworkDao().queryBuilder()
                .where(com.zerotier.nexus.model.NetworkDao.Properties.LastActivated.eq(true))
                .unique();
        if (network != null && network.getNetworkConfig() != null) {
            saveProxySettings(); // 这会通过 prefs 更新同步
        }
    }
}

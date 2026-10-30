package com.zerotier.nexus.ui;

import android.content.SharedPreferences;
import java.io.File;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.util.Log;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.journeyapps.barcodescanner.BarcodeEncoder;
import com.google.zxing.BarcodeFormat;

import com.zerotier.nexus.R;
import com.zerotier.nexus.ZerotierFixApplication;
import com.zerotier.nexus.model.DaoSession;
import com.zerotier.nexus.model.NetworkConfig;
import com.zerotier.nexus.service.ExitNodeManager;
import com.zerotier.nexus.util.Constants;
import com.zerotier.nexus.util.NetworkInfoUtils;
import com.zerotier.nexus.util.ProxyBinaryManager;
import com.zerotier.nexus.util.ShellExecutor;

import org.json.JSONObject;

import java.util.UUID;

/**
 * 出口节点配置页面 (服务端配置)
 */
public class ExitNodeFragment extends Fragment {

    private SwitchMaterial switchExitServiceMaster;
    private LinearLayout layoutExitServiceMaster;
    
    // 系统状态
private TextView tvExtIf;
    private TextView tvRootStatus;
    private TextView tvFwdStatus;
    private TextView tvNatStatus;
    private TextView tvExitDns;
    
    // 流量统计
    private TextView tvWanSpeed;
    private TextView tvZtSpeed;
    
    // 流量统计
    private EditText etServerAddressDisplay;
    private com.google.android.material.textfield.TextInputLayout tilServerAddressDisplay;
    private EditText etServerPort;
    private EditText etServerPassword;
    private EditText etServerUuid;
    private MaterialButton btnGeneratePassword;
    private MaterialButton btnGenerateUuid;
    
    // 运行模式
    private MaterialButtonToggleGroup toggleExitMode;
    private View cardSystemStatus;
    private View cardProxyConfig;
    
    // 链路优化
    private SwitchMaterial switchBbr;
    private SwitchMaterial switchUdpTune;
    private MaterialButton btnViewLogs;
    private MaterialButton btnViewNatLogs;

    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable trafficRunnable;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_exit_node_config, container, false);
    }

    private void showProxyLogs() {
        try {
            File logFile = new File(requireContext().getFilesDir(), "proxy_debug.log");
            if (!logFile.exists() || logFile.length() == 0) {
                Toast.makeText(getContext(), "目前无日志记录" , Toast.LENGTH_SHORT).show();
                return;
            }

            // 读取最后 100 行
java.util.List<String> lines = java.nio.file.Files.readAllLines(logFile.toPath());
            int start = Math.max(0, lines.size() - 100);
            String displayLogs = android.text.TextUtils.join("\n", lines.subList(start, lines.size()));

            androidx.appcompat.app.AlertDialog dialog = new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                    .setTitle("服务端运行日志 (Sing-box)")
                    .setMessage(displayLogs.isEmpty() ? "日志内容为空" : displayLogs)
                    .setPositiveButton("我知道了", null)
                    .show();
            // 让日志内容可选中复制
            android.widget.TextView textView = dialog.findViewById(android.R.id.message);
            if (textView != null) {
                textView.setTextIsSelectable(true);
            }
        } catch (Exception e) {
            Toast.makeText(getContext(), "读取日志失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void showNatLogs() {
        try {
            File logFile = new File(requireContext().getFilesDir(), "kernel_nat.log");
            if (!logFile.exists() || logFile.length() == 0) {
                Toast.makeText(getContext(), "目前无转发日志记录" , Toast.LENGTH_SHORT).show();
                return;
            }

            // 读取最后 100 行
java.util.List<String> lines = java.nio.file.Files.readAllLines(logFile.toPath());
            int start = Math.max(0, lines.size() - 100);
            String displayLogs = android.text.TextUtils.join("\n", lines.subList(start, lines.size()));

            androidx.appcompat.app.AlertDialog dialog = new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                    .setTitle("内核转发日志 (Kernel NAT)")
                    .setMessage(displayLogs.isEmpty() ? "日志内容为空" : displayLogs)
                    .setPositiveButton("我知道了", null)
                    .show();
            // 让日志内容可选中复制
            android.widget.TextView textView = dialog.findViewById(android.R.id.message);
            if (textView != null) {
                textView.setTextIsSelectable(true);
            }
        } catch (Exception e) {
            Toast.makeText(getContext(), "读取日志失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        // 绑定视图
        switchExitServiceMaster = view.findViewById(R.id.switch_exit_service_master);
        layoutExitServiceMaster = view.findViewById(R.id.layout_exit_service_master);
        
        tvExtIf = view.findViewById(R.id.tv_exit_ext_if);
        tvRootStatus = view.findViewById(R.id.tv_exit_root_status);
        tvFwdStatus = view.findViewById(R.id.tv_exit_fwd_status);
        tvNatStatus = view.findViewById(R.id.tv_exit_nat_status);
        tvExitDns = view.findViewById(R.id.tv_exit_dns);
        
        tvWanSpeed = view.findViewById(R.id.tv_exit_wan_speed);
        tvZtSpeed = view.findViewById(R.id.tv_exit_zt_speed);
        
        etServerAddressDisplay = view.findViewById(R.id.et_server_address_display);
        tilServerAddressDisplay = view.findViewById(R.id.til_server_address_display);
        etServerPort = view.findViewById(R.id.et_server_port);
        etServerPassword = view.findViewById(R.id.et_server_password);
        etServerUuid = view.findViewById(R.id.tv_server_uuid);
        btnGeneratePassword = view.findViewById(R.id.btn_generate_server_password);
        btnGenerateUuid = view.findViewById(R.id.btn_generate_server_uuid);
        
        switchBbr = view.findViewById(R.id.switch_bbr);
        switchUdpTune = view.findViewById(R.id.switch_udp_tune);
        btnViewLogs = view.findViewById(R.id.btn_exit_view_logs);
        btnViewNatLogs = view.findViewById(R.id.btn_exit_view_nat_logs);
        
        toggleExitMode = view.findViewById(R.id.toggle_exit_mode);
        cardSystemStatus = view.findViewById(R.id.card_exit_system_status);
        cardProxyConfig = view.findViewById(R.id.card_exit_proxy_config);

        setupListeners();
        
        if (btnViewLogs != null) {
            btnViewLogs.setOnClickListener(v -> showProxyLogs());
        }
        if (btnViewNatLogs != null) {
            btnViewNatLogs.setOnClickListener(v -> showNatLogs());
        }
        
        // 自动部署二进制内核
ProxyBinaryManager.deploy(requireContext());
        
        // 流量统计轮询
        trafficRunnable = new Runnable() {
            @Override
            public void run() {
                updateTrafficStats();
                refreshZtStatus(); 
                mainHandler.postDelayed(this, 5000); // 增加采样间隔至 5 秒
}
        };
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshStatus();
        mainHandler.post(trafficRunnable);
    }

    @Override
    public void onPause() {
        super.onPause();
        mainHandler.removeCallbacks(trafficRunnable);
    }

    private void setupListeners() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getContext());

        // 1. 总开关监听 (互斥逻辑)
        layoutExitServiceMaster.setOnClickListener(v -> switchExitServiceMaster.toggle());

        switchExitServiceMaster.setOnCheckedChangeListener((buttonView, isChecked) -> {
            // 仅处理用户触发的事件，防止 refreshStatus 中的 setChecked 导致逻辑重排或循环
if (!buttonView.isPressed() && !layoutExitServiceMaster.isPressed()) {
                return;
            }

            if (isChecked) {
                // [优化] 开启时自动检测 Root 权限
                if (!ShellExecutor.isRootAvailable()) {
                    toggleExitMode.check(R.id.btn_mode_proxy);
                    prefs.edit().putBoolean(Constants.PREF_EXIT_NODE_NON_ROOT, true).apply();
                    updateModeVisibility(true);
                    Toast.makeText(getContext(), "未检测到 ROOT，自动转为 TUIC 代理模式", Toast.LENGTH_LONG).show();
                }

                // 开启时，检查漫游客户端是否开启
if (prefs.getBoolean("global_roaming_enabled", false)) {
                    prefs.edit().putBoolean("global_roaming_enabled", false).apply();
                    Toast.makeText(getContext(), "互斥逻辑：已自动关闭‘漫游配置’客户端模式", Toast.LENGTH_LONG).show();
                }

                // 执行开启
startExitService(prefs);
            } else {
                ExitNodeManager.getInstance().disable();
            }
            prefs.edit().putBoolean(Constants.PREF_EXIT_NODE_GLOBAL_ENABLED, isChecked).apply();
            syncExitStateToDatabase(isChecked);
            refreshStatus();
        });

        if (tilServerAddressDisplay != null) {
            tilServerAddressDisplay.setEndIconOnClickListener(v -> showConfigurationQrCode(prefs));
        }

        btnGeneratePassword.setOnClickListener(v -> {
            String pwd = generateRandomString(12);
            etServerPassword.setText(pwd);
            prefs.edit().putString(Constants.PREF_EXIT_NODE_PASSWORD, pwd).apply();
        });

        btnGenerateUuid.setOnClickListener(v -> {
            String uuid = UUID.randomUUID().toString();
            etServerUuid.setText(uuid);
            prefs.edit().putString(Constants.PREF_EXIT_NODE_UUID, uuid).apply();
        });

        etServerPort.addTextChangedListener(buildTextWatcher(Constants.PREF_EXIT_NODE_PORT, prefs));
        etServerPassword.addTextChangedListener(buildTextWatcher(Constants.PREF_EXIT_NODE_PASSWORD, prefs));
        etServerUuid.addTextChangedListener(buildTextWatcher(Constants.PREF_EXIT_NODE_UUID, prefs));

        // 5. 运行模式切换监听
        toggleExitMode.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            boolean isProxyMode = (checkedId == R.id.btn_mode_proxy);
            prefs.edit().putBoolean(Constants.PREF_EXIT_NODE_NON_ROOT, isProxyMode).apply();
            updateModeVisibility(isProxyMode);
        });

        // 4. 链路优化
        switchBbr.setOnCheckedChangeListener((bv, isChecked) -> {
            if (ExitNodeManager.getInstance().applyBbr(isChecked)) {
                if (isChecked) Toast.makeText(getContext(), "BBR 已启用", Toast.LENGTH_SHORT).show();
            } else {
                switchBbr.setChecked(false);
            }
        });

        switchUdpTune.setOnCheckedChangeListener((bv, isChecked) -> {
            if (isChecked) {
                if (ShellExecutor.isRootAvailable()) {
                    ExitNodeManager.getInstance().applyUdpTuning(true);
                } else {
                    Toast.makeText(getContext(), "此功能需要 Root 权限", Toast.LENGTH_SHORT).show();
                    switchUdpTune.setChecked(false);
                }
            }
        });
        
        // 初始值加载
loadInitialValues(prefs);
    }

    private void loadInitialValues(SharedPreferences prefs) {
        // 加载常规设置
        boolean isProxyMode = prefs.getBoolean(Constants.PREF_EXIT_NODE_NON_ROOT, false);
        toggleExitMode.check(isProxyMode ? R.id.btn_mode_proxy : R.id.btn_mode_nat);
        updateModeVisibility(isProxyMode);
        
        etServerPort.setText(prefs.getString(Constants.PREF_EXIT_NODE_PORT, "10004"));
        etServerPassword.setText(prefs.getString(Constants.PREF_EXIT_NODE_PASSWORD, ""));
        etServerUuid.setText(prefs.getString(Constants.PREF_EXIT_NODE_UUID, ""));
    }

    private void updateModeVisibility(boolean isProxyMode) {
        cardSystemStatus.setVisibility(isProxyMode ? View.GONE : View.VISIBLE);
        // 如果是 Root 模式，Proxy Config 卡片作为“可选加固”显示
        // 如果是 Proxy 模式，Proxy Config 卡片作为“核心配置”显示
        // 这里暂时都显示，但可以调整标题或描述
        TextView tvProxyTitle = cardProxyConfig.findViewById(R.id.tv_proxy_title_label); // 需要在 XML 增加 ID 或者动态找
        if (tvProxyTitle != null) {
            tvProxyTitle.setText(isProxyMode ? "代理服务核心配置 (免 Root)" : "代理接入配置 (可选)");
        }
    }

    private void startExitService(SharedPreferences prefs) {
        boolean isProxyMode = prefs.getBoolean(Constants.PREF_EXIT_NODE_NON_ROOT, false);
        boolean rootSuccess = false;
        boolean proxySuccess = false;

        String portStr = prefs.getString(Constants.PREF_EXIT_NODE_PORT, "10004");
        int portVal = 10004;
        try { portVal = Integer.parseInt(portStr); } catch (Exception ignored) {}
        final int port = portVal;

        // --- 1. 端口占用检查 ---
        if (ExitNodeManager.getInstance().isUdpPortInUse(port)) {
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle("端口冲突提示")
                    .setMessage(String.format("UDP 端口 %d 已被占用。虽然本应用仍会尝试强制开启，但可能会导致代理服务启动失败。建议更换端口或关闭竞争应用。", port))
                    .setPositiveButton("继续开启", (d, w) -> continueStartingExitService(prefs, isProxyMode, port))
                    .setNegativeButton("前往修改", null)
                    .show();
            return;
        }

        continueStartingExitService(prefs, isProxyMode, port);
    }

    private void continueStartingExitService(SharedPreferences prefs, boolean isProxyMode, int port) {
        boolean rootSuccess = false;
        boolean proxySuccess = false;

        // 1. 根据当前模式执行开启逻辑
        if (!isProxyMode) {
            // Root 模式
            if (ShellExecutor.isRootAvailable()) {
                String ztIf = NetworkInfoUtils.findZtInterface(getContext());
                String extIf = NetworkInfoUtils.findDefaultInterface(getContext());
                if (ztIf != null && extIf != null) {
                    if (ExitNodeManager.getInstance().enable(ztIf, extIf)) {
                        rootSuccess = true;
                    } else {
                        Toast.makeText(getContext(), "内核转发开启失败", Toast.LENGTH_SHORT).show();
                    }
                }
            } else {
                Toast.makeText(getContext(), "未检测到 Root 权限，无法切换至内核模式", Toast.LENGTH_LONG).show();
                toggleExitMode.check(R.id.btn_mode_proxy);
                return;
            }
        }

        // 2. 无论什么模式下，均尝试启动 TUIC 服务端
        // Root 模式下作为备选接入点，Non-Root 模式下作为核心引擎
String protocol = "TUIC v5";
        String uuid = prefs.getString(Constants.PREF_EXIT_NODE_UUID, "");
        String pwd = prefs.getString(Constants.PREF_EXIT_NODE_PASSWORD, "");
        
        try {
            proxySuccess = ExitNodeManager.getInstance().enableNonRoot(getContext(), protocol, uuid, pwd, port);
        } catch (Exception e) {
            Log.e("ExitNodeFragment", "Proxy start failed", e);
        }

        // 更新总开关状态
boolean totalActive = rootSuccess || proxySuccess;
        switchExitServiceMaster.setChecked(totalActive);
        if (totalActive) {
            String msg = (rootSuccess && proxySuccess) ? "网关与代理均已就绪" : 
                         (rootSuccess ? "出口网关已开启 (Root NAT)" : "代理出口模式已开启");
            Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(getContext(), "启动失败：请检查Root权限或配置", Toast.LENGTH_LONG).show();
            switchExitServiceMaster.setChecked(false);
        }
    }
    
    private void syncExitStateToDatabase(boolean isChecked) {
        try {
            com.zerotier.nexus.model.DaoSession daoSession = ((com.zerotier.nexus.ZerotierFixApplication) requireActivity().getApplication()).getDaoSession();
            com.zerotier.nexus.model.Network network = daoSession.getNetworkDao().queryBuilder()
                    .where(com.zerotier.nexus.model.NetworkDao.Properties.LastActivated.eq(true))
                    .unique();
            if (network != null && network.getNetworkConfig() != null) {
                // [修正] 互斥逻辑：若开启了“出口网关”（服务端），则必须关闭该网络的“漫游出口”（客户端）
                if (isChecked) {
                    network.getNetworkConfig().setAllowExitRoaming(false);
                    daoSession.getNetworkConfigDao().update(network.getNetworkConfig());
                    android.util.Log.i("ExitNodeFragment", "DB Sync: Disabled allowExitRoaming for network " + Long.toHexString(network.getNetworkId()) + " due to Exit Node enabled");
                }
            }
        } catch (Exception e) {
            android.util.Log.e("ExitNodeFragment", "DB Sync Failed: " + e.getMessage());
        }
    }

    private void refreshStatus() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getContext());
        
        // 1. 同步总开关
boolean active = ExitNodeManager.getInstance().isActive();
        switchExitServiceMaster.setChecked(active);
        
        // 2. 刷新系统状态
String extIf = NetworkInfoUtils.findDefaultInterface(getContext());
        tvExtIf.setText(extIf != null ? extIf : "未连接");
        
        boolean hasRoot = ShellExecutor.isRootAvailable();
        tvRootStatus.setText(hasRoot ? "已获得" : "未获得");
        tvRootStatus.setTextColor(getResources().getColor(hasRoot ? R.color.dashboard_accent_green : R.color.dashboard_text_sub));
        
        // 检查转发开关是否实际开启(改用 Root 确保读取成功)
        ShellExecutor.Result fwd = ShellExecutor.exec("cat /proc/sys/net/ipv4/ip_forward", true);
        boolean fwdOn = fwd.isSuccess() && fwd.stdout.trim().equals("1");
        tvFwdStatus.setText(fwdOn ? "已开启" : "已关闭");
        tvFwdStatus.setTextColor(getResources().getColor(fwdOn ? R.color.dashboard_accent_green : R.color.dashboard_text_sub));
        
        boolean proxyRunning = com.zerotier.nexus.service.ProxyEngine.getInstance().isRunning();
        String activeDesc = "未开启";
        if (active && proxyRunning) activeDesc = "NAT+代理";
        else if (active) activeDesc = "内核 NAT";
        else if (proxyRunning) activeDesc = "TUIC 代理";
        
        tvNatStatus.setText(activeDesc);
        tvNatStatus.setTextColor(getResources().getColor((active || proxyRunning) ? R.color.dashboard_accent_green : R.color.dashboard_text_sub));

        // 刷新 DNS 状态
java.util.List<String> dnsList = NetworkInfoUtils.getSystemDnsServers(getContext());
        if (!dnsList.isEmpty()) {
            tvExitDns.setText(android.text.TextUtils.join(", ", dnsList));
        } else {
            tvExitDns.setText("未获取到");
        }

        // 3. 刷新代理地址显示
        refreshZtStatus();
    }

    /**
     * [修复] 独立的 IP 探测与 UI 刷新逻辑，支持双模式
     */
    private void refreshZtStatus() {
        if (!isAdded()) return;
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getContext());
        boolean isProxyMode = prefs.getBoolean(Constants.PREF_EXIT_NODE_NON_ROOT, false);
        
        String connectIp = null;
        if (isProxyMode) {
            String ztIf = NetworkInfoUtils.findZtInterface(getContext());
            connectIp = NetworkInfoUtils.getInterfaceIp(ztIf);
            if (connectIp == null) {
                connectIp = prefs.getString(Constants.PREF_LAST_KNOWN_ZT_IP, null);
            }
        } else {
            connectIp = NetworkInfoUtils.findDefaultInterfaceIp(getContext());
        }

        String port = prefs.getString(Constants.PREF_EXIT_NODE_PORT, "10004");

        if (connectIp != null) {
            etServerAddressDisplay.setText(String.format("%s : %s", connectIp, port));
            etServerAddressDisplay.setTextColor(getResources().getColor(R.color.dashboard_accent_green));
        } else {
            etServerAddressDisplay.setText(isProxyMode ? "未探测到 (请先连接 ZT)" : "未探测到 (请检查网络)");
            etServerAddressDisplay.setTextColor(getResources().getColor(R.color.dashboard_text_sub));
        }
    }

    private void updateTrafficStats() {
        if (!isAdded()) return;
        
        // [修复] 不再依赖 Service 静态变量，直接探测当前网卡的实时速率
        String extIf = NetworkInfoUtils.findDefaultInterface(getContext());
        String ztIf = NetworkInfoUtils.findZtInterface(getContext());
        
        if (extIf != null) {
            tvWanSpeed.setText(NetworkInfoUtils.getInterfaceSpeed(extIf));
        } else {
            tvWanSpeed.setText("0 B/s");
        }
        
        if (ztIf != null) {
            tvZtSpeed.setText(NetworkInfoUtils.getInterfaceSpeed(ztIf));
        } else {
            tvZtSpeed.setText("0 B/s");
        }
    }

    private void showConfigurationQrCode(SharedPreferences prefs) {
        boolean isProxyMode = prefs.getBoolean(Constants.PREF_EXIT_NODE_NON_ROOT, false);
        String connectIp = null;

        if (isProxyMode) {
            // 纯代理模式：连接的是 ZT 虚拟网卡的 IP
            String ztIf = NetworkInfoUtils.findZtInterface(getContext());
            connectIp = NetworkInfoUtils.getInterfaceIp(ztIf);
            if (connectIp == null) {
                connectIp = prefs.getString(Constants.PREF_LAST_KNOWN_ZT_IP, null);
            }
        } else {
            // Root 模式：通常连接的是手机物理网卡的公网内网 IP
            connectIp = NetworkInfoUtils.findDefaultInterfaceIp(getContext());
        }

        String port = prefs.getString(Constants.PREF_EXIT_NODE_PORT, "10004");
        String password = prefs.getString(Constants.PREF_EXIT_NODE_PASSWORD, "");
        String uuid = prefs.getString(Constants.PREF_EXIT_NODE_UUID, "");

        if (connectIp == null) {
            Toast.makeText(getContext(), "获取连接 IP 失败，请检查网络", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            // 封装为紧凑 JSON 协议
            JSONObject json = new JSONObject();
            json.put("h", connectIp);
            json.put("p", Integer.parseInt(port));
            json.put("w", password);
            json.put("u", uuid);
            json.put("mode", isProxyMode ? "proxy" : "root");
            // [新增] 加入运营商 DNS 列表，供客户端同步
            java.util.List<String> dnsList = NetworkInfoUtils.getSystemDnsServers(getContext());
            if (!dnsList.isEmpty()) {
                json.put("d", new org.json.JSONArray(dnsList));
            }
            String qrData = json.toString();

            // 生成二维码
            com.journeyapps.barcodescanner.BarcodeEncoder barcodeEncoder = new com.journeyapps.barcodescanner.BarcodeEncoder();
            android.graphics.Bitmap bitmap = barcodeEncoder.encodeBitmap(qrData, BarcodeFormat.QR_CODE, 600, 600);

            // 弹出对话框展示
            android.widget.ImageView imageView = new android.widget.ImageView(getContext());
            imageView.setImageBitmap(bitmap);
            imageView.setPadding(32, 32, 32, 32);

            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle("出口配置分享")
                    .setMessage("请在另一台设备的“漫游配置”中扫码导入")
                    .setView(imageView)
                    .setPositiveButton("确定", null)
                    .show();

        } catch (Exception e) {
            Toast.makeText(getContext(), "生成二维码失败 " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private TextWatcher buildTextWatcher(String key, SharedPreferences prefs) {
        return new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                prefs.edit().putString(key, s.toString()).apply();
                // 仅刷新显示，不触发递归
                String ztIf = NetworkInfoUtils.findZtInterface(getContext());
                String ztIp = NetworkInfoUtils.getInterfaceIp(ztIf);
                if (ztIp != null && key.equals(Constants.PREF_EXIT_NODE_PORT)) {
                    etServerAddressDisplay.setText(String.format("%s : %s", ztIp, s.toString()));
                }
            }
        };
    }

    private String generateRandomString(int length) {
        String chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        java.util.Random rnd = new java.util.Random();
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) sb.append(chars.charAt(rnd.nextInt(chars.length())));
        return sb.toString();
    }
}

package com.zerotier.nexus.ui.view;

import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.UiThread;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.zerotier.sdk.VirtualNetworkConfig;
import com.zerotier.sdk.util.StringUtils;

import com.zerotier.nexus.R;
import com.zerotier.nexus.ZerotierFixApplication;
import com.zerotier.nexus.model.DaoSession;
import com.zerotier.nexus.model.DnsServer;
import com.zerotier.nexus.model.DnsServerDao;
import com.zerotier.nexus.model.Network;
import com.zerotier.nexus.model.NetworkConfig;
import com.zerotier.nexus.model.type.DNSMode;
import com.zerotier.nexus.model.type.NetworkStatus;
import com.zerotier.nexus.model.type.NetworkType;
import com.zerotier.nexus.ui.NetworkListFragment;
import com.zerotier.nexus.ui.viewmodel.NetworkDetailModel;
import com.zerotier.nexus.events.NetworkConfigChangedByUserEvent;
import com.zerotier.nexus.util.Constants;

import org.greenrobot.eventbus.EventBus;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * 展示网络详细信息的 fragment
 *
 * @author kaaass
 */
public class NetworkDetailFragment extends Fragment {
    private static final String TAG = "NetworkDetailView";

    private NetworkDetailModel viewModel;
    private TextView idView;
    private TextView nameView;
    private TextView statusView;
    private TextView typeView;
    private TextView macView;
    private TextView mtuView;
    private TextView broadcastView;
    private TextView bridgingView;

    private TextView ipAddressesView;
    private TextView dnsModeView;
    private TextView dnsServersView;
    private View dnsModeRow;
    private View dnsServersRow;
    private View dnsServersDivider;

    private SwitchMaterial mobileDataSwitch;
    private com.google.android.material.switchmaterial.SwitchMaterial ipv6Switch;
    private com.google.android.material.switchmaterial.SwitchMaterial roamingSwitch;


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 获取 ViewModel
        this.viewModel = (NetworkDetailModel) new ViewModelProvider(this).get(NetworkDetailModel.class);

        // 如果参数中有网络 ID，就加载对应网络的信息
        if (getArguments() != null) {
            long networkId = getArguments().getLong(NetworkListFragment.NETWORK_ID_MESSAGE);
            viewModel.doRetrieveDetail(networkId);
        } else {
            Log.e(TAG, "Network ID is not set!");
        }
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_network_detail, container, false);

        // 获取各个控件
        this.idView = view.findViewById(R.id.network_detail_network_id);
        this.nameView = view.findViewById(R.id.network_detail_network_name);
        this.statusView = view.findViewById(R.id.network_status_textview);
        this.typeView = view.findViewById(R.id.network_type_textview);
        this.macView = view.findViewById(R.id.network_mac_textview);
        this.mtuView = view.findViewById(R.id.network_mtu_textview);
        this.broadcastView = view.findViewById(R.id.network_broadcast_textview);
        this.bridgingView = view.findViewById(R.id.network_bridging_textview);

        this.ipAddressesView = view.findViewById(R.id.network_ipaddresses_textview);
        this.dnsModeView = view.findViewById(R.id.network_dns_mode_textview);
        this.dnsServersView = view.findViewById(R.id.network_dns_servers_textview);
        this.dnsModeRow = view.findViewById(R.id.row_dns_mode);
        this.dnsServersRow = view.findViewById(R.id.row_dns_servers);
        this.dnsServersDivider = view.findViewById(R.id.divider_dns_servers);

        this.mobileDataSwitch = view.findViewById(R.id.network_detail_mobile_data_switch);
        this.ipv6Switch = view.findViewById(R.id.network_detail_ipv6_switch);
        this.roamingSwitch = view.findViewById(R.id.network_detail_roaming_switch);

        // 返回按钮
        view.findViewById(R.id.btn_back).setOnClickListener(v -> requireActivity().onBackPressed());

        // DNS 模式点击
        this.dnsModeRow.setOnClickListener(v -> showDnsModeSelectionDialog());

        // DNS 服务器点击
        this.dnsServersRow.setOnClickListener(v -> showEditDnsServersDialog());

        // 设置对应的 UI 更新操作
        viewModel.getNetwork().observe(getViewLifecycleOwner(), this::updateNetwork);
        viewModel.getNetworkConfig().observe(getViewLifecycleOwner(), this::updateNetworkConfig);
        viewModel.getVirtualNetworkConfig().observe(getViewLifecycleOwner(), this::updateVirtualNetworkConfig);

        return view;
    }

    /**
     * 更新网络基本信息相关的 UI
     */
    @UiThread
    private void updateNetwork(Network network) {
        if (network == null) {
            return;
        }
        this.idView.setText(network.getNetworkIdStr());
        if (network.getNetworkName() != null && !network.getNetworkName().isEmpty()) {
            this.nameView.setText(network.getNetworkName());
        } else {
            this.nameView.setText(getString(R.string.empty_network_name));
        }
    }

    /**
     * 更新持久化网络配置相关的 UI
     */
    @UiThread
    private void updateNetworkConfig(NetworkConfig networkConfig) {
        if (networkConfig == null) {
            return;
        }

        // 移动网络开关
        this.mobileDataSwitch.setOnCheckedChangeListener(null);
        this.mobileDataSwitch.setChecked(networkConfig.getAllowMobileData());
        this.mobileDataSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Network network = viewModel.getNetwork().getValue();
            if (network != null) {
                networkConfig.setAllowMobileData(isChecked);
                networkConfig.update();
                EventBus.getDefault().post(new NetworkConfigChangedByUserEvent(network));
            }
        });

        // 屏蔽 IPv6 开关
        this.ipv6Switch.setOnCheckedChangeListener(null);
        this.ipv6Switch.setChecked(networkConfig.getDisableIPv6());
        this.ipv6Switch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Network network = viewModel.getNetwork().getValue();
            if (network != null) {
                networkConfig.setDisableIPv6(isChecked);
                networkConfig.update();
                EventBus.getDefault().post(new NetworkConfigChangedByUserEvent(network));
            }
        });

        // 客户端漫游开关
        this.roamingSwitch.setOnCheckedChangeListener(null);
        this.roamingSwitch.setChecked(networkConfig.getAllowExitRoaming());
        this.roamingSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Network network = viewModel.getNetwork().getValue();
            if (network != null) {
                networkConfig.setAllowExitRoaming(isChecked);
                networkConfig.update();
                
                // 同步到全局 Preference 供漫游页采样使用
                android.content.SharedPreferences prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext());
                prefs.edit().putBoolean("global_roaming_enabled", isChecked).apply();

                if (isChecked) {
                    // 互斥逻辑：如果开启漫游（客户端模式），协调关闭出口（服务器模式）
                    if (prefs.getBoolean(Constants.PREF_EXIT_NODE_GLOBAL_ENABLED, false)) {
                        prefs.edit().putBoolean(Constants.PREF_EXIT_NODE_GLOBAL_ENABLED, false).apply();
                        com.zerotier.nexus.service.ExitNodeManager.getInstance().disable();
                        android.widget.Toast.makeText(getContext(), "互斥逻辑：已自动关闭正在运行的‘出口网关模式’", android.widget.Toast.LENGTH_SHORT).show();
                    }
                }

                EventBus.getDefault().post(new NetworkConfigChangedByUserEvent(network));
            }
        });

        // DNS 信息
        updateDnsInfo(networkConfig, viewModel.getVirtualNetworkConfig().getValue());
    }

    @UiThread
    private void updateDnsInfo(NetworkConfig networkConfig, VirtualNetworkConfig virtualNetworkConfig) {
        if (networkConfig == null) return;
        
        // DNS 模式与服务器列表抓取
        int dnsMode = networkConfig.getDnsMode();
        List<String> serverIps = new ArrayList<>();
        String strDnsMode;
        
        switch (dnsMode) {
            case 1: // NETWORK_DNS
                strDnsMode = getString(R.string.dns_mode_network_default);
                // 实时从虚拟网络配置中获取下发的 DNS
                if (virtualNetworkConfig != null && virtualNetworkConfig.getDns() != null) {
                    List<InetSocketAddress> servers = virtualNetworkConfig.getDns().getServers();
                    if (servers != null) {
                        for (InetSocketAddress addr : servers) {
                            serverIps.add(addr.getAddress().getHostAddress());
                        }
                    }
                }
                break;
            case 0: // NO_DNS
                strDnsMode = getString(R.string.dns_mode_ignore);
                // [修复] 不干预模式下，显示手机内部系统 DNS
                List<String> systemDns = com.zerotier.nexus.util.NetworkInfoUtils.getSystemDnsServers(getContext());
                if (systemDns != null) {
                    serverIps.addAll(systemDns);
                }
                break;
            case 2: // CUSTOM_DNS
                strDnsMode = getString(R.string.dns_mode_custom_override);
                // 从本地持久化配置获取自定义 DNS
                List<com.zerotier.nexus.model.DnsServer> customServers = networkConfig.getDnsServers();
                if (customServers != null) {
                    for (com.zerotier.nexus.model.DnsServer server : customServers) {
                        serverIps.add(server.getNameserver());
                    }
                }
                break;
            default:
                strDnsMode = getString(R.string.dns_mode_ignore);
                break;
        }

        // 拼接最直观的模式显示
        this.dnsModeView.setText(strDnsMode);
        
        // 如果有服务器地址（或者是自定义模式），则显示服务器行
        if (!serverIps.isEmpty() || dnsMode == 2) {
            StringBuilder sb = new StringBuilder();
            if (serverIps.isEmpty()) {
                sb.append(getString(R.string.click_to_change));
            } else {
                for (int i = 0; i < serverIps.size(); i++) {
                    sb.append(serverIps.get(i));
                    if (i < serverIps.size() - 1) sb.append("\n");
                }
            }
            this.dnsServersView.setText(sb.toString());
            this.dnsServersRow.setVisibility(View.VISIBLE);
            this.dnsServersDivider.setVisibility(View.VISIBLE);
        } else {
            this.dnsServersRow.setVisibility(View.GONE);
            this.dnsServersDivider.setVisibility(View.GONE);
        }
    }

    private void showDnsModeSelectionDialog() {
        NetworkConfig config = viewModel.getNetworkConfig().getValue();
        if (config == null) return;

        String[] items = {
                getString(R.string.dns_mode_ignore),
                getString(R.string.dns_mode_network_default),
                getString(R.string.dns_mode_custom_override)
        };
        int currentMode = config.getDnsMode();
        // 映射模式到索引: 0->0, 1->1, 2->2
        int checkedItem = (currentMode >= 0 && currentMode <= 2) ? currentMode : 0;

        new MaterialAlertDialogBuilder(requireContext(), R.style.Theme_ZerotierFix_DashboardDialog)
                .setTitle(R.string.network_dns_mode)
                .setSingleChoiceItems(items, checkedItem, (dialog, which) -> {
                    config.setDnsMode(which);
                    config.setUseCustomDNS(which == 2);
                    config.update();
                    
                    dialog.dismiss();
                    updateDnsInfo(config, viewModel.getVirtualNetworkConfig().getValue());
                    EventBus.getDefault().post(new NetworkConfigChangedByUserEvent(viewModel.getNetwork().getValue()));
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showEditDnsServersDialog() {
        NetworkConfig config = viewModel.getNetworkConfig().getValue();
        if (config == null || config.getDnsMode() != 2) return;

        View dialogView = LayoutInflater.from(getContext()).inflate(R.layout.dialog_edit_dns, null);
        EditText etDns = dialogView.findViewById(R.id.et_dns_servers);
        
        // 加载当前服务器
        List<DnsServer> servers = config.getDnsServers();
        StringBuilder sb = new StringBuilder();
        if (servers != null) {
            for (int i = 0; i < servers.size(); i++) {
                sb.append(servers.get(i).getNameserver());
                if (i < servers.size() - 1) sb.append("\n");
            }
        }
        etDns.setText(sb.toString());

        new MaterialAlertDialogBuilder(requireContext(), R.style.Theme_ZerotierFix_DashboardDialog)
                .setTitle(R.string.dns_servers_label)
                .setView(dialogView)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String input = etDns.getText().toString().trim();
                    saveCustomDnsServers(config, input);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void saveCustomDnsServers(NetworkConfig config, String input) {
        try {
            DaoSession daoSession = ((ZerotierFixApplication) requireActivity().getApplication()).getDaoSession();
            DnsServerDao dnsServerDao = daoSession.getDnsServerDao();
            long networkId = config.getId();

            // 删除旧的
            dnsServerDao.queryBuilder()
                    .where(DnsServerDao.Properties.NetworkId.eq(networkId))
                    .buildDelete()
                    .executeDeleteWithoutDetachingEntities();

            // 插入新的
            String[] ips = input.split("[\\s,\\n]+");
            for (String ip : ips) {
                if (ip.isEmpty()) continue;
                DnsServer server = new DnsServer();
                server.setNetworkId(networkId);
                server.setNameserver(ip);
                dnsServerDao.insert(server);
            }

            // 刷新配置关联
            config.resetDnsServers();
            config.update();

            updateDnsInfo(config, viewModel.getVirtualNetworkConfig().getValue());
            EventBus.getDefault().post(new NetworkConfigChangedByUserEvent(viewModel.getNetwork().getValue()));
        } catch (Exception e) {
            Log.e(TAG, "Failed to save DNS servers", e);
            Toast.makeText(getContext(), "保存失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 更新虚拟网络配置（从 ZT 服务实时获取的网络动态配置）相关的 UI
     */
    @UiThread
    private void updateVirtualNetworkConfig(VirtualNetworkConfig virtualNetworkConfig) {
        if (virtualNetworkConfig == null) {
            return;
        }

        // 网络类型
        com.zerotier.sdk.VirtualNetworkType ztType = virtualNetworkConfig.getType();
        NetworkType type = NetworkType.fromVirtualNetworkType(ztType);
        this.typeView.setText(type.toStringId());

        // 网络状态
        com.zerotier.sdk.VirtualNetworkStatus ztStatus = virtualNetworkConfig.getStatus();
        NetworkStatus status = NetworkStatus.fromVirtualNetworkStatus(ztStatus);
        this.statusView.setText(status.toStringId());
        if (status == NetworkStatus.OK) {
            this.statusView.setTextColor(getResources().getColor(R.color.dashboard_accent_green));
        } else {
            this.statusView.setTextColor(getResources().getColor(R.color.dashboard_text_sub));
        }

        // MAC、MTU、广播、桥接
        this.macView.setText(StringUtils.macAddressToString(virtualNetworkConfig.getMac()));
        this.mtuView.setText(String.valueOf(virtualNetworkConfig.getMtu()));
        this.broadcastView.setText(booleanToLocalString(virtualNetworkConfig.isBroadcastEnabled()));
        this.bridgingView.setText(booleanToLocalString(virtualNetworkConfig.isBridge()));

        // 分配的 IP 地址
        InetSocketAddress[] addresses = virtualNetworkConfig.getAssignedAddresses();
        StringBuilder strAssignedAddresses = new StringBuilder();

        for (int i = 0; i < addresses.length; i++) {
            String addr = inetSocketAddressToString(addresses[i]);
            if (addr == null) continue;
            strAssignedAddresses.append(addr);
            if (i < addresses.length - 1) {
                strAssignedAddresses.append('\n');
            }
        }
        this.ipAddressesView.setText(strAssignedAddresses.toString());

        // 同时触发 DNS 信息更新（以抓取实时的控制器下发 DNS）
        updateDnsInfo(viewModel.getNetworkConfig().getValue(), virtualNetworkConfig);
    }


    private String booleanToLocalString(boolean z) {
        return z ? getString(R.string.enabled) : getString(R.string.disabled);
    }

    private String inetSocketAddressToString(InetSocketAddress inetSocketAddress) {
        if (inetSocketAddress == null) {
            return null;
        }

        NetworkConfig config = viewModel.getNetworkConfig().getValue();
        boolean disableIpv6 = config != null && config.getDisableIPv6();

        try {
            InetAddress address = inetSocketAddress.getAddress();

            // 如果禁用了 IPv6，那么就不显示 IPv6 地址
            if (address instanceof Inet6Address && disableIpv6) {
                return null;
            }

            // 去除地址前面的斜杠
            String strAddress = address.toString();
            if (strAddress.startsWith("/")) {
                strAddress = strAddress.substring(1);
            }

            // 拼接地址和前缀
            return strAddress + "/" + inetSocketAddress.getPort();
        } catch (Exception ignored) {
        }
        return null;
    }
}

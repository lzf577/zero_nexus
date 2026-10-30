package com.zerotier.nexus.ui;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.ConnectivityManager;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import com.zerotier.sdk.NodeStatus;
import com.zerotier.sdk.Version;

import com.zerotier.nexus.BuildConfig;
import com.zerotier.nexus.R;
import com.zerotier.nexus.ZerotierFixApplication;
import com.zerotier.nexus.events.AfterJoinNetworkEvent;
import com.zerotier.nexus.events.IsServiceRunningReplyEvent;
import com.zerotier.nexus.events.IsServiceRunningRequestEvent;
import com.zerotier.nexus.events.NetworkListCheckedChangeEvent;
import com.zerotier.nexus.events.NetworkListReplyEvent;
import com.zerotier.nexus.events.NetworkListRequestEvent;
import com.zerotier.nexus.events.NodeDestroyedEvent;
import com.zerotier.nexus.events.NodeIDEvent;
import com.zerotier.nexus.events.NodeStatusEvent;
import com.zerotier.nexus.events.NodeStatusRequestEvent;
import com.zerotier.nexus.events.OrbitMoonEvent;
import com.zerotier.nexus.events.StopEvent;
import com.zerotier.nexus.events.VPNErrorEvent;
import com.zerotier.nexus.events.VirtualNetworkConfigChangedEvent;
import com.zerotier.nexus.model.AppNode;
import com.zerotier.nexus.model.AssignedAddress;
import com.zerotier.nexus.model.AssignedAddressDao;
import com.zerotier.nexus.model.DaoSession;
import com.zerotier.nexus.model.MoonOrbit;
import com.zerotier.nexus.model.Network;
import com.zerotier.nexus.model.NetworkConfig;
import com.zerotier.nexus.model.NetworkConfigDao;
import com.zerotier.nexus.model.NetworkDao;
import com.zerotier.nexus.model.type.NetworkStatus;
import com.zerotier.nexus.service.ZeroTierOneService;
import com.zerotier.nexus.ui.view.NetworkDetailActivity;
import com.zerotier.nexus.ui.viewmodel.NetworkListModel;
import com.zerotier.nexus.util.Constants;
import com.zerotier.nexus.util.DatabaseUtils;
import com.zerotier.nexus.util.NetworkCacheManager;
import com.zerotier.nexus.util.StringUtils;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import lombok.ToString;

// TODO: clear up
public class NetworkListFragment extends Fragment {
    public static final String NETWORK_ID_MESSAGE = "com.zerotier.one.network-id";
    public static final String TAG = "NetworkListFragment";
    private final EventBus eventBus;
    private final List<Network> mNetworks = new ArrayList<>();
    boolean mIsBound = false;
    private RecyclerViewAdapter recyclerViewAdapter;
    private RecyclerView recyclerView;
    private ZeroTierOneService mBoundService;
    private final ServiceConnection mConnection = new ServiceConnection() {
        /* class com.zerotier.one.ui.NetworkListFragment.AnonymousClass1 */

        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            NetworkListFragment.this.mBoundService = ((ZeroTierOneService.ZeroTierBinder) iBinder).getService();
        }

        public void onServiceDisconnected(ComponentName componentName) {
            NetworkListFragment.this.mBoundService = null;
            NetworkListFragment.this.setIsBound(false);
        }
    };
    private TextView nodeIdView;
    private TextView nodeStatusView;
    private TextView nodeClientVersionView;
    private ImageView nodeStatusIcon;
    private com.zerotier.sdk.Version mClientVersion;
    private TextView publicIpView;

    private View emptyView = null;
    final private RecyclerView.AdapterDataObserver checkIfEmptyObserver = new RecyclerView.AdapterDataObserver() {
        @Override
        public void onChanged() {
            checkIfEmpty();
        }

        @Override
        public void onItemRangeInserted(int positionStart, int itemCount) {
            checkIfEmpty();
        }

        @Override
        public void onItemRangeRemoved(int positionStart, int itemCount) {
            checkIfEmpty();
        }

        /**
         * 检查列表是否为空
         */
        void checkIfEmpty() {
            if (emptyView != null && recyclerViewAdapter != null) {
                final boolean emptyViewVisible = recyclerViewAdapter.getItemCount() == 0;
                emptyView.setVisibility(emptyViewVisible ? View.VISIBLE : View.GONE);
                recyclerView.setVisibility(emptyViewVisible ? View.GONE : View.VISIBLE);
            }
        }
    };
    private ActivityResultLauncher<Intent> vpnAuthLauncher;
    private NetworkListModel viewModel;

    private final android.content.BroadcastReceiver networkChangeReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (android.net.ConnectivityManager.CONNECTIVITY_ACTION.equals(intent.getAction())) {
                fetchPublicIp();
            }
        }
    };

    public NetworkListFragment() {
        Log.d(TAG, "Network List Fragment created");
        this.eventBus = EventBus.getDefault();
    }

    /* access modifiers changed from: package-private */
    public synchronized void setIsBound(boolean z) {
        this.mIsBound = z;
    }

    /* access modifiers changed from: package-private */
    public synchronized boolean isBound() {
        return this.mIsBound;
    }

    /* access modifiers changed from: package-private */
    public void doBindService() {
        if (!isBound()) {
            if (requireActivity().bindService(new Intent(getActivity(), ZeroTierOneService.class), this.mConnection, Context.BIND_NOT_FOREGROUND | Context.BIND_DEBUG_UNBIND)) {
                setIsBound(true);
            }
        }
    }

    /* access modifiers changed from: package-private */
    public void doUnbindService() {
        if (isBound()) {
            try {
                requireActivity().unbindService(this.mConnection);
            } catch (Exception e) {
                Log.e(TAG, "", e);
            } catch (Throwable th) {
                setIsBound(false);
                throw th;
            }
            setIsBound(false);
        }
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        // 初始化 VPN 授权结果回调
        vpnAuthLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), (activityResult) -> {
            int result = activityResult.getResultCode();
            Log.d(TAG, "Returned from AUTH_VPN");
            if (result == -1) {
                // 得到授权，连接网络
                startService(this.viewModel.getNetworkId());
            } else if (result == 0) {
                // 未授权
                updateNetworkListAndNotify();
            }
        });
    }

    @Override
    public void onStart() {
        super.onStart();
        this.eventBus.post(new NetworkListRequestEvent());

        // 初始化节点及服务状态
        this.eventBus.post(new NodeStatusRequestEvent());
        this.eventBus.post(new IsServiceRunningRequestEvent());

        // 检查通知权限
        androidx.core.app.NotificationManagerCompat notificationManager = NotificationManagerCompat.from(requireContext());
        if (!notificationManager.areNotificationsEnabled()) {
            // 无通知权限
            showNoNotificationAlertDialog();
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        doUnbindService();
    }

    @Override
    public void onPause() {
        super.onPause();
        this.eventBus.unregister(this);
        try {
            requireContext().unregisterReceiver(networkChangeReceiver);
        } catch (Exception e) {
            Log.e(TAG, "Unregister receiver failed", e);
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_network_list, container, false);


        // 空列表提示
        this.emptyView = view.findViewById(R.id.no_data);

        // 列表、适配器设置
        this.recyclerView = view.findViewById(R.id.joined_networks_list);
        this.recyclerView.setClickable(true);
        this.recyclerView.setLongClickable(true);
        this.recyclerViewAdapter = new RecyclerViewAdapter(this.mNetworks);
        this.recyclerViewAdapter.registerAdapterDataObserver(checkIfEmptyObserver);
        this.recyclerView.setAdapter(this.recyclerViewAdapter);

        // 网络状态栏设置
        this.nodeIdView = view.findViewById(R.id.node_id);
        this.nodeStatusView = view.findViewById(R.id.node_status);
        this.nodeStatusIcon = view.findViewById(R.id.node_status_icon);
        this.nodeClientVersionView = view.findViewById(R.id.client_version);
        setNodeIdText();
        TextView appVersionView = view.findViewById(R.id.app_version);
        appVersionView.setText(String.format(getString(R.string.app_version_format),
                BuildConfig.VERSION_NAME));

        appVersionView.setText(String.format(getString(R.string.app_version_format),
                BuildConfig.VERSION_NAME));

        this.publicIpView = view.findViewById(R.id.public_ip);
        fetchPublicIp();

        // 恢复缓存的内核版本
        android.content.SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
        String cachedVersion = prefs.getString(Constants.PREF_CACHED_KERNEL_VERSION, null);
        if (cachedVersion != null && this.nodeClientVersionView != null) {
            this.nodeClientVersionView.setText(getString(R.string.kernel_version_format, cachedVersion));
        }

        // 加载网络数据
        updateNetworkListAndNotify();

        // 当前连接网络变更时更新列表
        this.viewModel.getConnectNetworkId().observe(getViewLifecycleOwner(), networkId ->
                this.recyclerViewAdapter.notifyDataSetChanged());

        return view;
    }

    private void fetchPublicIp() {
        if (publicIpView != null && getActivity() != null) {
            getActivity().runOnUiThread(() -> publicIpView.setText("获取中..."));
        }
        new Thread(() -> {
            String ip = null;
            String location = "";
            
            // 1. 优先尝试获取 IP 和地理位置
            try {
                java.net.URL url = new java.net.URL("http://ip-api.com/json/?lang=zh-CN");
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(3000);
                java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(conn.getInputStream(), "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = in.readLine()) != null) {
                    sb.append(line);
                }
                in.close();
                org.json.JSONObject json = new org.json.JSONObject(sb.toString());
                if ("success".equals(json.optString("status"))) {
                    ip = json.getString("query");
                    String country = json.optString("country", "");
                    String region = json.optString("regionName", "");
                    String city = json.optString("city", "");
                    
                    StringBuilder locBuilder = new StringBuilder();
                    if (!country.isEmpty()) locBuilder.append(country);
                    if (!region.isEmpty() && !region.equals(country)) locBuilder.append(" ").append(region);
                    if (!city.isEmpty() && !city.equals(region)) locBuilder.append(" ").append(city);
                    location = locBuilder.toString().trim();
                }
            } catch (Exception e) {
                // 忽略异常，转入备用方案
            }

            // 2. 如果带有位置的接口失败，使用备用纯 IP 接口
            if (ip == null || ip.isEmpty()) {
                String[] urls = {
                    "http://members.3322.org/dyndns/getip",
                    "https://api.ip.sb/ip",
                    "https://ifconfig.me/ip",
                    "https://api.ipify.org"
                };
                for (String urlStr : urls) {
                    try {
                        java.net.URL url = new java.net.URL(urlStr);
                        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                        conn.setRequestMethod("GET");
                        conn.setConnectTimeout(3000);
                        conn.setReadTimeout(3000);
                        java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(conn.getInputStream()));
                        String tmpIp = in.readLine();
                        in.close();
                        if (tmpIp != null && !tmpIp.trim().isEmpty() && tmpIp.matches(".*[0-9]+.*")) {
                            ip = tmpIp.trim();
                            break;
                        }
                    } catch (Exception e) {
                        // 尝试下一个 URL
                    }
                }
            }

            final String finalResult;
            if (ip != null && !ip.isEmpty()) {
                if (!location.isEmpty()) {
                    finalResult = ip + "  " + location;
                } else {
                    finalResult = ip;
                }
            } else {
                finalResult = null;
            }

            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (publicIpView != null) {
                        if (finalResult != null) {
                            publicIpView.setText(finalResult);
                        } else {
                            publicIpView.setText("获取失败");
                        }
                    }
                });
            }
        }).start();
    }

    /**
     * 发送连接至指定网络的 Intent。将请求 VPN 权限后启动 ZT 服务
     *
     * @param networkId 网络号
     */
    private void sendStartServiceIntent(long networkId) {
        Intent prepare = VpnService.prepare(requireActivity());
        if (prepare != null) {
            // 等待 VPN 授权后连接网络
            this.viewModel.setNetworkId(networkId);
            vpnAuthLauncher.launch(prepare);
            return;
        }
        Log.d(TAG, "Intent is NULL.  Already approved.");
        startService(networkId);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        Log.d(TAG, "NetworkListFragment.onCreate");
        super.onCreate(savedInstanceState);
        PreferenceManager.setDefaultValues(getActivity(), R.xml.preferences, false);
        setHasOptionsMenu(true);

        // 获取 ViewModel
        this.viewModel = new ViewModelProvider(requireActivity()).get(NetworkListModel.class);
    }

    @Override
    public void onResume() {
        super.onResume();
        this.nodeStatusView.setText(R.string.status_offline);
        this.eventBus.register(this);
        this.eventBus.post(new IsServiceRunningRequestEvent());
        updateNetworkListAndNotify();
        this.eventBus.post(new NetworkListRequestEvent());
        this.eventBus.post(new NodeStatusRequestEvent());
        
        try {
            android.content.IntentFilter filter = new android.content.IntentFilter(android.net.ConnectivityManager.CONNECTIVITY_ACTION);
            requireContext().registerReceiver(networkChangeReceiver, filter);
        } catch (Exception e) {
            Log.e(TAG, "Register receiver failed", e);
        }
    }



    @Override
    public void onDestroy() {
        super.onDestroy();
        doUnbindService();
    }

    private List<Network> getNetworkList() {
        DaoSession daoSession = ((ZerotierFixApplication) requireActivity().getApplication()).getDaoSession();
        daoSession.clear();
        return daoSession.getNetworkDao().queryBuilder().orderAsc(NetworkDao.Properties.NetworkId).build().forCurrentThread().list();
    }

    private void setNodeIdText() {
        List<AppNode> list = ((ZerotierFixApplication) requireActivity().getApplication()).getDaoSession().getAppNodeDao().queryBuilder().build().forCurrentThread().list();
        if (!list.isEmpty()) {
            this.nodeIdView.setText(list.get(0).getNodeIdStr());
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onIsServiceRunningReply(IsServiceRunningReplyEvent event) {
        if (event.isRunning()) {
            doBindService();
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onNetworkListReply(NetworkListReplyEvent event) {
        Log.d(TAG, "Got connecting network list");
        // 更新当前连接的网络
        com.zerotier.sdk.VirtualNetworkConfig[] networks = event.getNetworkList();
        for (com.zerotier.sdk.VirtualNetworkConfig network : networks) {
            this.viewModel.doChangeConnectNetwork(network.getNwid());
        }
        // 更新网络列表
        updateNetworkListAndNotify();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onVirtualNetworkConfigChanged(VirtualNetworkConfigChangedEvent event) {
        Log.d(TAG, "Got Network Info");
        com.zerotier.sdk.VirtualNetworkConfig config = event.getVirtualNetworkConfig();

        // Toast 提示网络状态
        NetworkStatus status = NetworkStatus.fromVirtualNetworkStatus(config.getStatus());
        String networkId = com.zerotier.sdk.util.StringUtils.networkIdToString(config.getNwid());
        String message = null;
        switch (status) {
            case OK:
                message = getString(R.string.toast_network_status_ok, networkId);
                break;
            case ACCESS_DENIED:
                message = getString(R.string.toast_network_status_access_denied, networkId);
                break;
            case NOT_FOUND:
                message = getString(R.string.toast_network_status_not_found, networkId);
                break;
            case PORT_ERROR:
                message = getString(R.string.toast_network_status_port_error, networkId);
                break;
            case CLIENT_TOO_OLD:
                message = getString(R.string.toast_network_status_client_too_old, networkId);
                break;
            case AUTHENTICATION_REQUIRED:
                message = getString(R.string.toast_network_status_authentication_required, networkId);
                break;
            case REQUESTING_CONFIGURATION:
            default:
                break;
        }
        if (message != null && getContext() != null) {
            Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
        }

        // 更新网络列表
        updateNetworkListAndNotify();
        // 延时刷新公网 IP（等待路由表生效，延长至 10 秒）
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(this::fetchPublicIp, 10000);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onNodeID(NodeIDEvent nodeIDEvent) {
        setNodeIdText();
    }

    /**
     * 节点状态事件回调
     *
     * @param event 事件
     */
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onNodeStatus(NodeStatusEvent event) {
        NodeStatus status = event.getStatus();
        this.mClientVersion = event.getClientVersion();
        // 更新在线状态
        if (status.isOnline()) {
            this.nodeStatusView.setText(R.string.status_online);
            this.nodeStatusView.setTextColor(getResources().getColor(R.color.dashboard_accent_green));
            this.nodeStatusIcon.setImageTintList(android.content.res.ColorStateList.valueOf(getResources().getColor(R.color.dashboard_accent_green)));
            if (this.nodeIdView != null) {
                this.nodeIdView.setText(Long.toHexString(status.getAddress()));
            }
        } else {
            setOfflineState();
        }
        // 更新客户端版本
        if (this.nodeClientVersionView != null && mClientVersion != null) {
            String clientVersionStr = StringUtils.toString(mClientVersion);
            this.nodeClientVersionView.setText(getString(R.string.kernel_version_format, clientVersionStr));

            // 缓存版本到本地，以便断连时显示
            PreferenceManager.getDefaultSharedPreferences(requireContext())
                    .edit()
                    .putString(Constants.PREF_CACHED_KERNEL_VERSION, clientVersionStr)
                    .apply();
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onNodeDestroyed(NodeDestroyedEvent event) {
        setOfflineState();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onVPNError(VPNErrorEvent event) {
        String errorMessage = event.getMessage();
        String message = getString(R.string.toast_vpn_error, errorMessage);
        if (getContext() != null) {
            Toast.makeText(getContext(), message, Toast.LENGTH_LONG).show();
        }
        updateNetworkListAndNotify();
    }

    private void setOfflineState() {
        TextView textView = this.nodeStatusView;
        if (textView != null) {
            textView.setText(R.string.status_offline);
            textView.setTextColor(getResources().getColor(R.color.dashboard_accent_red));
            if (this.nodeStatusIcon != null) {
                this.nodeStatusIcon.setImageTintList(android.content.res.ColorStateList.valueOf(getResources().getColor(R.color.dashboard_accent_red)));
            }
        }
    }

    /**
     * 更新网络列表
     */
    private void updateNetworkList() {
        List<Network> networkList = getNetworkList();
        if (networkList != null) {
            // 更新列表
            this.mNetworks.clear();
            this.mNetworks.addAll(networkList);
        }
    }

    /**
     * 更新网络列表与 UI
     */
    public void updateNetworkListAndNotify() {
        // 更新数据
        updateNetworkList();
        // 更新列表
        if (this.recyclerViewAdapter != null) {
            this.recyclerViewAdapter.notifyDataSetChanged();
        }
    }

    /**
     * 启动 ZT 服务连接至指定网络
     *
     * @param networkId 网络号
     */
    private void startService(long networkId) {
        if (getActivity() == null) return;
        Intent intent = new Intent(getActivity(), ZeroTierOneService.class);
        intent.putExtra(ZeroTierOneService.ZT1_NETWORK_ID, networkId);
        doBindService();
        getActivity().startService(intent);
    }

    /**
     * 停止 ZT 服务
     */
    private void stopService() {
        if (this.mBoundService != null) {
            this.mBoundService.stopZeroTier();
        }
        Intent intent = new Intent(requireActivity(), ZeroTierOneService.class);
        this.eventBus.post(new StopEvent());
        if (!requireActivity().stopService(intent)) {
            Log.e(TAG, "stopService() returned false");
        }
        doUnbindService();
        // 延时刷新公网 IP（等待 VPN 隧道销毁，延长至 10 秒）
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(this::fetchPublicIp, 10000);
    }

    /**
     * 获得 Moon 入轨配置列表
     */
    private List<MoonOrbit> getMoonOrbitList() {
        DaoSession daoSession = ((ZerotierFixApplication) requireActivity().getApplication()).getDaoSession();
        return daoSession.getMoonOrbitDao().loadAll();
    }

    /**
     * 加入网络后事件回调
     *
     * @param event 事件
     */
    @Subscribe
    public void onAfterJoinNetworkEvent(AfterJoinNetworkEvent event) {
        Log.d(TAG, "Event on: AfterJoinNetworkEvent");
        // 设置网络 orbit
        List<MoonOrbit> moonOrbits = NetworkListFragment.this.getMoonOrbitList();
        this.eventBus.post(new OrbitMoonEvent(moonOrbits));
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onNetworkListCheckedChangeEvent(NetworkListCheckedChangeEvent event) {
        android.widget.CompoundButton switchHandle = event.getSwitchHandle();
        boolean checked = event.isChecked();
        Network selectedNetwork = event.getSelectedNetwork();
        if (checked) {
            // 退出已连接的网络
            Long connectedNetworkId = this.viewModel.getConnectNetworkId().getValue();
            if (connectedNetworkId != null) {
                this.mBoundService.leaveNetwork(connectedNetworkId);
            }
            stopService();
            this.viewModel.doChangeConnectNetwork(null);
            // 启动网络
            android.content.Context context = requireContext();
            NetworkConfig config = selectedNetwork.getNetworkConfig();
            boolean useCellularData = (config != null) ? config.getAllowMobileData() : true;
            android.net.NetworkInfo activeNetworkInfo = ((ConnectivityManager) context
                    .getSystemService(Context.CONNECTIVITY_SERVICE))
                    .getActiveNetworkInfo();
            if (activeNetworkInfo == null || !activeNetworkInfo.isConnectedOrConnecting()) {
                // 设备无网络
                if (getActivity() != null) {
                    requireActivity().runOnUiThread(() -> {
                        Toast.makeText(NetworkListFragment.this.getContext(), R.string.toast_no_network, Toast.LENGTH_SHORT).show();
                        switchHandle.setChecked(false);
                    });
                }
            } else if (useCellularData || !(activeNetworkInfo.getType() == 0)) {
                // 可以连接至网络
                // 更新 DB 中的网络状态
                DatabaseUtils.writeLock.lock();
                try {
                    for (Network network : this.mNetworks) {
                        network.setLastActivated(false);
                        network.update();
                    }
                    selectedNetwork.setLastActivated(true);
                    selectedNetwork.update();
                } finally {
                    DatabaseUtils.writeLock.unlock();
                }
                // 连接目标网络
                // [优化] 切换网络前先清理旧缓存，确保路径刷新
                NetworkCacheManager.clearNetworkCache(requireContext(), selectedNetwork);
                
                if (!this.isBound()) {
                    this.sendStartServiceIntent(selectedNetwork.getNetworkId());
                } else {
                    this.mBoundService.joinNetwork(selectedNetwork.getNetworkId());
                }
                this.viewModel.doChangeConnectNetwork(selectedNetwork.getNetworkId());
                Log.d(TAG, "Joining Network: " + selectedNetwork.getNetworkIdStr());
            } else {
                // 移动数据且未确认
                if (getActivity() != null) {
                    requireActivity().runOnUiThread(() -> {
                        Toast.makeText(this.getContext(), R.string.toast_mobile_data, Toast.LENGTH_SHORT).show();
                        switchHandle.setChecked(false);
                    });
                }
            }
        } else {
            // 关闭网络
            Log.d(TAG, "Leaving Leaving Network: " + selectedNetwork.getNetworkIdStr());
            if (this.isBound() && this.mBoundService != null) {
                this.mBoundService.leaveNetwork(selectedNetwork.getNetworkId());
                this.doUnbindService();
            }
            this.stopService();
            this.viewModel.doChangeConnectNetwork(null);
        }
    }


    /**
     * 显示无通知权限的提示框。若用户选择过 “不再提示”，则此方法将不进行任何操作
     */
    private void showNoNotificationAlertDialog() {
        // 检查是否选择过 “不再提示”，若是则不显示
        android.content.SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(requireContext());

        if (sharedPreferences.getBoolean(Constants.PREF_DISABLE_NO_NOTIFICATION_ALERT, false)) {
            return;
        }

        // 显示提示框
        View view = LayoutInflater.from(getContext()).inflate(R.layout.dialog_no_notification_alert, null);

        AlertDialog.Builder builder = new AlertDialog.Builder(getContext())
                .setView(view)
                .setTitle(R.string.dialog_no_notification_alert_title)
                .setPositiveButton(R.string.open_notification_settings, (dialog, which) -> {
                    // 打开 APP 的通知设置
                    Intent intent = new Intent();

                    intent.setAction("android.settings.APP_NOTIFICATION_SETTINGS");
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    intent.putExtra("app_package", requireContext().getPackageName());
                    intent.putExtra("app_uid", requireContext().getApplicationInfo().uid);
                    intent.putExtra("android.provider.extra.APP_PACKAGE", requireContext().getPackageName());
                    startActivity(intent);
                })
                .setNegativeButton(R.string.dont_show_again, (dialog, which) -> {
                    // 设置不再提示此对话框
                    sharedPreferences.edit()
                            .putBoolean(Constants.PREF_DISABLE_NO_NOTIFICATION_ALERT, true)
                            .apply();
                })
                .setCancelable(true);

        builder.create().show();
    }

    /**
     * 网络信息列表适配器
     */
    public class RecyclerViewAdapter extends RecyclerView.Adapter<RecyclerViewAdapter.ViewHolder> {

        private final List<Network> mValues;

        public RecyclerViewAdapter(List<Network> items) {
            this.mValues = items;
            Log.d(NetworkListFragment.TAG, "Created network list item adapter");
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.list_item_network, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(final RecyclerViewAdapter.ViewHolder holder, int position) {
            Network network = mValues.get(position);
            holder.mItem = network;
            // 设置文本信息
            holder.mNetworkId.setText(network.getNetworkIdStr());
            String networkName = network.getNetworkName();
            if (networkName != null && !networkName.isEmpty()) {
                holder.mNetworkName.setText(networkName);
            } else {
                holder.mNetworkName.setText(R.string.empty_network_name);
            }
            // 设置点击事件
            holder.mView.setOnClickListener(holder::onClick);
            // 设置长按事件
            holder.mView.setOnLongClickListener(holder::onLongClick);
            // 判断连接状态
            Long connectedNetworkId = NetworkListFragment.this.viewModel
                    .getConnectNetworkId().getValue();
            boolean connected = connectedNetworkId != null &&
                    connectedNetworkId.equals(network.getNetworkId());
            // 设置开关
            holder.mSwitch.setOnCheckedChangeListener(null);
            holder.mSwitch.setChecked(connected);
            holder.mSwitch.setOnCheckedChangeListener(holder::onSwitchCheckedChanged);

            // 设置全局漫游图标 - 三态升级版 (字段对齐)
            com.zerotier.nexus.model.NetworkConfig networkConfig = network.getNetworkConfig();
            boolean isRoaming = networkConfig != null && networkConfig.getAllowExitRoaming();
            boolean isTuic = networkConfig != null && networkConfig.getProxyEnabled();

            if (!isRoaming) {
                // 1. 无漫游
                holder.mRoamingIndicator.setText(R.string.roaming_status_none);
                holder.mRoamingIndicator.setTextColor(getResources().getColor(R.color.dashboard_text_sub));
                holder.mRoamingIcon.setImageTintList(android.content.res.ColorStateList.valueOf(getResources().getColor(R.color.dashboard_text_sub)));
            } else if (!isTuic) {
                // 2. 普通漫游
                holder.mRoamingIndicator.setText(R.string.roaming_status_normal);
                holder.mRoamingIndicator.setTextColor(getResources().getColor(R.color.dashboard_accent_green));
                holder.mRoamingIcon.setImageTintList(android.content.res.ColorStateList.valueOf(getResources().getColor(R.color.dashboard_accent_green)));
            } else {
                // 3. 增强漫游 (亮青色)
                holder.mRoamingIndicator.setText(R.string.roaming_status_enhanced);
                holder.mRoamingIndicator.setTextColor(getResources().getColor(R.color.teal_200));
                holder.mRoamingIcon.setImageTintList(android.content.res.ColorStateList.valueOf(getResources().getColor(R.color.teal_200)));
            }
        }

        @Override
        public int getItemCount() {
            return mValues.size();
        }

        @ToString
        public class ViewHolder extends RecyclerView.ViewHolder {
            public final View mView;
            public final TextView mNetworkId;
            public final TextView mNetworkName;
            public final SwitchCompat mSwitch;
            public final TextView mRoamingIndicator;
            public final ImageView mRoamingIcon;
            public Network mItem;

            public ViewHolder(View view) {
                super(view);
                mView = view;
                mNetworkId = view.findViewById(R.id.network_list_network_id);
                mNetworkName = view.findViewById(R.id.network_list_network_name);
                mSwitch = view.findViewById(R.id.network_start_network_switch);
                mRoamingIndicator = view.findViewById(R.id.network_list_roaming_indicator);
                mRoamingIcon = view.findViewById(R.id.network_list_roaming_icon);
            }

            /**
             * 单击列表项打开网络详细页面
             */
            public void onClick(View view) {
                Log.d(NetworkListFragment.TAG, "ConvertView OnClickListener");
                Intent intent = new Intent(NetworkListFragment.this.getActivity(), NetworkDetailActivity.class);
                intent.putExtra(NetworkListFragment.NETWORK_ID_MESSAGE, this.mItem.getNetworkId());
                NetworkListFragment.this.startActivity(intent);
            }

            /**
             * 长按列表项创建弹出菜单
             */
            public boolean onLongClick(View view) {
                NetworkListFragment that = NetworkListFragment.this;
                Log.d(NetworkListFragment.TAG, "ConvertView OnLongClickListener");

                BottomSheetDialog bottomSheetDialog = new BottomSheetDialog(that.requireContext(), R.style.Theme_ZerotierFix_DashboardDialog);
                View dialogView = LayoutInflater.from(that.requireContext()).inflate(R.layout.dialog_network_context_menu, null);

                // 设置标题
                TextView nameView = dialogView.findViewById(R.id.menu_network_name);
                TextView idView = dialogView.findViewById(R.id.menu_network_id);
                String name = mItem.getNetworkName();
                nameView.setText(name == null || name.isEmpty() ? getString(R.string.empty_network_name) : name);
                idView.setText("id: " + mItem.getNetworkIdStr());

                // 复制任务
                dialogView.findViewById(R.id.menu_item_copy_id).setOnClickListener(v -> {
                    ClipboardManager clipboard = (ClipboardManager) that.requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
                    ClipData clip = ClipData.newPlainText(getString(R.string.network_id), this.mItem.getNetworkIdStr());
                    clipboard.setPrimaryClip(clip);
                    Toast.makeText(that.getContext(), R.string.text_copied, Toast.LENGTH_SHORT).show();
                    bottomSheetDialog.dismiss();
                });

                // 清理任务
                dialogView.findViewById(R.id.menu_item_clear_cache).setOnClickListener(v -> {
                    bottomSheetDialog.dismiss();
                    new MaterialAlertDialogBuilder(that.requireContext())
                            .setTitle(R.string.clear_cache_confirm_title)
                            .setMessage(R.string.clear_cache_confirm_message)
                            .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                                that.clearNetworkCache(this.mItem);
                            })
                            .setNegativeButton(android.R.string.cancel, null)
                            .show();
                });

                // 删除任务
                dialogView.findViewById(R.id.menu_item_delete).setOnClickListener(v -> {
                    bottomSheetDialog.dismiss();
                    // 删除对应网络
                    DaoSession daoSession = ((ZerotierFixApplication) that.requireActivity().getApplication()).getDaoSession();
                    AssignedAddressDao assignedAddressDao = daoSession.getAssignedAddressDao();
                    NetworkConfigDao networkConfigDao = daoSession.getNetworkConfigDao();
                    NetworkDao networkDao = daoSession.getNetworkDao();
                    if (this.mItem != null) {
                        // 如果删除的是当前连接的网络，则停止服务
                        Long connectedNetworkId = that.viewModel.getConnectNetworkId().getValue();
                        if (this.mItem.getNetworkId().equals(connectedNetworkId)) {
                            that.stopService();
                        }
                        // 从 DB 中删除网络
                        NetworkConfig networkConfig = this.mItem.getNetworkConfig();
                        if (networkConfig != null) {
                            List<AssignedAddress> assignedAddresses = networkConfig.getAssignedAddresses();
                            if (!assignedAddresses.isEmpty()) {
                                for (AssignedAddress assignedAddress : assignedAddresses) {
                                    assignedAddressDao.delete(assignedAddress);
                                }
                            }
                            networkConfigDao.delete(networkConfig);
                        }
                        networkDao.delete(this.mItem);
                    }
                    daoSession.clear();
                    // 更新数据
                    that.updateNetworkListAndNotify();
                });

                bottomSheetDialog.setContentView(dialogView);
                bottomSheetDialog.show();
                return true;
            }

            /**
             * 点击开启网络开关
             */
            public void onSwitchCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                NetworkListFragment.this.eventBus.post(new NetworkListCheckedChangeEvent(
                        this.mSwitch,
                        isChecked,
                        this.mItem
                ));
            }
        }
    }

    /**
     * 清理选中网络的所有本地网络缓存
     */
    private void clearNetworkCache(Network network) {
        if (network == null) return;
        long networkId = network.getNetworkId();

        // 1. 如果当前正在连接此网络，先停止服务
        Long connectedNetworkId = this.viewModel.getConnectNetworkId().getValue();
        if (Long.valueOf(networkId).equals(connectedNetworkId)) {
            stopService();
            this.viewModel.doChangeConnectNetwork(null);
        }

        // 2. 调用工具类清理物理文件与数据库记录
        NetworkCacheManager.clearNetworkCache(requireContext(), network);

        // 3. 重置 UI 并提示
        updateNetworkListAndNotify();
        Toast.makeText(getContext(), R.string.clear_cache_success, Toast.LENGTH_LONG).show();
    }

    private void deleteFileRecursive(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteFileRecursive(child);
                }
            }
        }
        file.delete();
    }
}

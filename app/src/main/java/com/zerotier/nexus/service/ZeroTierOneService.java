package com.zerotier.nexus.service;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.Toast;

import android.content.pm.ServiceInfo;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.zerotier.sdk.Event;
import com.zerotier.sdk.EventListener;
import com.zerotier.sdk.Node;
import com.zerotier.sdk.Peer;
import com.zerotier.sdk.ResultCode;
import com.zerotier.sdk.VirtualNetworkConfig;
import com.zerotier.sdk.VirtualNetworkConfigListener;
import com.zerotier.sdk.VirtualNetworkConfigOperation;
import com.zerotier.sdk.VirtualNetworkRoute;
import com.zerotier.sdk.VirtualNetworkStatus;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Inet4Address;
import java.net.DatagramSocket;
import java.io.FileInputStream;
import java.io.FileOutputStream;

import com.zerotier.nexus.model.DaoSession;

import com.zerotier.nexus.R;
import com.zerotier.nexus.ZerotierFixApplication;
import com.zerotier.nexus.events.AfterJoinNetworkEvent;
import com.zerotier.nexus.events.ErrorEvent;
import com.zerotier.nexus.events.IsServiceRunningReplyEvent;
import com.zerotier.nexus.events.IsServiceRunningRequestEvent;
import com.zerotier.nexus.events.ManualDisconnectEvent;
import com.zerotier.nexus.events.NetworkConfigChangedByUserEvent;
import com.zerotier.nexus.events.NetworkListReplyEvent;
import com.zerotier.nexus.events.NetworkListRequestEvent;
import com.zerotier.nexus.events.NetworkReconfigureEvent;
import com.zerotier.nexus.events.NodeDestroyedEvent;
import com.zerotier.nexus.events.NodeIDEvent;
import com.zerotier.nexus.events.NodeStatusEvent;
import com.zerotier.nexus.events.NodeStatusRequestEvent;
import com.zerotier.nexus.events.OrbitMoonEvent;
import com.zerotier.nexus.events.PeerInfoReplyEvent;
import com.zerotier.nexus.events.PeerInfoRequestEvent;
import com.zerotier.nexus.events.StopEvent;
import com.zerotier.nexus.events.VPNErrorEvent;
import com.zerotier.nexus.events.VirtualNetworkConfigChangedEvent;
import com.zerotier.nexus.events.VirtualNetworkConfigReplyEvent;
import com.zerotier.nexus.events.VirtualNetworkConfigRequestEvent;
import com.zerotier.nexus.model.AppNode;
import com.zerotier.nexus.model.MoonOrbit;
import com.zerotier.nexus.model.Network;
import com.zerotier.nexus.model.NetworkDao;
import com.zerotier.nexus.model.NetworkConfig;
import com.zerotier.nexus.model.type.DNSMode;
import com.zerotier.nexus.ui.NetworkListActivity;
import com.zerotier.nexus.util.Constants;
import com.zerotier.nexus.util.DatabaseUtils;
import com.zerotier.nexus.util.InetAddressUtils;
import com.zerotier.nexus.util.NetworkCacheManager;
import com.zerotier.nexus.util.NetworkInfoUtils;

import com.zerotier.nexus.util.StringUtils;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

// TODO: clear up
public class ZeroTierOneService extends VpnService implements Runnable, EventListener, VirtualNetworkConfigListener {
    public static final int MSG_JOIN_NETWORK = 1;
    public static final int MSG_LEAVE_NETWORK = 2;
    public static final String ZT1_NETWORK_ID = "com.zerotier.one.network_id";
    public static final String ZT1_USE_DEFAULT_ROUTE = "com.zerotier.one.use_default_route";
    private static final String[] DISALLOWED_APPS = {"com.android.vending"};
    private static final String TAG = "ZT1_Service";
    private static final int ZT_NOTIFICATION_TAG = 5919812;

    /**
     * 兼容性常量：VPN 前台服务类型 (API 29+)
     */
    private static final int FOREGROUND_SERVICE_TYPE_VPN = 0x00000100;
    private final IBinder mBinder = new ZeroTierBinder();
    private final DataStore dataStore = new DataStore(this);
    private final EventBus eventBus = EventBus.getDefault();
    private final Map<Long, VirtualNetworkConfig> virtualNetworkConfigMap = new HashMap();
    FileInputStream in;
    FileOutputStream out;
    DatagramSocket svrSocket;
    ParcelFileDescriptor vpnSocket;
    private int bindCount = 0;
    private boolean disableIPv6 = false;
    private int mStartID = -1;
    private long networkId = 0;
    private long nextBackgroundTaskDeadline = 0;
    private Node node;
    private NotificationManager notificationManager;
    private TunTapAdapter tunTapAdapter;
    private final Object reconfigLock = new Object();
    private boolean lastIsRouteViaZeroTier = false;

    // --- 实时流量统计 ---
    private static String lastWanSpeed = "0 B/s";
    private static String lastZtSpeed = "0 B/s";
    private Handler trafficHandler = new Handler(Looper.getMainLooper());
    private Runnable trafficRunnable;
    private UdpCom udpCom;
    private Thread udpThread;
    private long lastDiscoveryTime = 0;
    private Thread v4MulticastScanner = new Thread() {
        /* class com.zerotier.one.service.ZeroTierOneService.AnonymousClass1 */
        List<String> subscriptions = new ArrayList<>();

        @Override
        public void run() {
            Log.d(ZeroTierOneService.TAG, "IPv4 Multicast Scanner Thread Started.");
            while (!isInterrupted()) {
                try {
                    List<String> groups = NetworkInfoUtils.listMulticastGroupOnInterface("tun0", false);

                    ArrayList<String> arrayList2 = new ArrayList<>(this.subscriptions);
                    ArrayList<String> arrayList3 = new ArrayList<>(groups);
                    arrayList3.removeAll(arrayList2);
                    for (String str : arrayList3) {
                        try {
                            byte[] hexStringToByteArray = StringUtils.hexStringToBytes(str);
                            for (int i = 0; i < hexStringToByteArray.length / 2; i++) {
                                byte b = hexStringToByteArray[i];
                                hexStringToByteArray[i] = hexStringToByteArray[(hexStringToByteArray.length - i) - 1];
                                hexStringToByteArray[(hexStringToByteArray.length - i) - 1] = b;
                            }
                            ResultCode multicastSubscribe = ZeroTierOneService.this.node.multicastSubscribe(ZeroTierOneService.this.networkId, TunTapAdapter.multicastAddressToMAC(InetAddress.getByAddress(hexStringToByteArray)));
                            if (multicastSubscribe != ResultCode.RESULT_OK) {
                                Log.e(ZeroTierOneService.TAG, "Error when calling multicastSubscribe: " + multicastSubscribe);
                            }
                        } catch (Exception e) {
                            Log.e(ZeroTierOneService.TAG, e.toString(), e);
                        }
                    }
                    arrayList2.removeAll(new ArrayList<>(groups));
                    for (String str2 : arrayList2) {
                        try {
                            byte[] hexStringToByteArray2 = StringUtils.hexStringToBytes(str2);
                            for (int i2 = 0; i2 < hexStringToByteArray2.length / 2; i2++) {
                                byte b2 = hexStringToByteArray2[i2];
                                hexStringToByteArray2[i2] = hexStringToByteArray2[(hexStringToByteArray2.length - i2) - 1];
                                hexStringToByteArray2[(hexStringToByteArray2.length - i2) - 1] = b2;
                            }
                            ResultCode multicastUnsubscribe = ZeroTierOneService.this.node.multicastUnsubscribe(ZeroTierOneService.this.networkId, TunTapAdapter.multicastAddressToMAC(InetAddress.getByAddress(hexStringToByteArray2)));
                            if (multicastUnsubscribe != ResultCode.RESULT_OK) {
                                Log.e(ZeroTierOneService.TAG, "Error when calling multicastUnsubscribe: " + multicastUnsubscribe);
                            }
                        } catch (Exception e) {
                            Log.e(ZeroTierOneService.TAG, e.toString(), e);
                        }
                    }
                    this.subscriptions = groups;
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Log.d(ZeroTierOneService.TAG, "V4 Multicast Scanner Thread Interrupted", e);
                    break;
                }
            }
            Log.d(ZeroTierOneService.TAG, "IPv4 Multicast Scanner Thread Ended.");
        }
    };
    private Thread v6MulticastScanner = new Thread() {
        /* class com.zerotier.one.service.ZeroTierOneService.AnonymousClass2 */
        List<String> subscriptions = new ArrayList<>();

        @Override
        public void run() {
            Log.d(ZeroTierOneService.TAG, "IPv6 Multicast Scanner Thread Started.");
            while (!isInterrupted()) {
                try {
                    List<String> groups = NetworkInfoUtils.listMulticastGroupOnInterface("tun0", true);

                    ArrayList<String> arrayList2 = new ArrayList<>(this.subscriptions);
                    ArrayList<String> arrayList3 = new ArrayList<>(groups);
                    arrayList3.removeAll(arrayList2);
                    for (String str : arrayList3) {
                        try {
                            ResultCode multicastSubscribe = ZeroTierOneService.this.node.multicastSubscribe(ZeroTierOneService.this.networkId, TunTapAdapter.multicastAddressToMAC(InetAddress.getByAddress(StringUtils.hexStringToBytes(str))));
                            if (multicastSubscribe != ResultCode.RESULT_OK) {
                                Log.e(ZeroTierOneService.TAG, "Error when calling multicastSubscribe: " + multicastSubscribe);
                            }
                        } catch (Exception e) {
                            Log.e(ZeroTierOneService.TAG, e.toString(), e);
                        }
                    }
                    arrayList2.removeAll(new ArrayList<>(groups));
                    for (String str2 : arrayList2) {
                        try {
                            ResultCode multicastUnsubscribe = ZeroTierOneService.this.node.multicastUnsubscribe(ZeroTierOneService.this.networkId, TunTapAdapter.multicastAddressToMAC(InetAddress.getByAddress(StringUtils.hexStringToBytes(str2))));
                            if (multicastUnsubscribe != ResultCode.RESULT_OK) {
                                Log.e(ZeroTierOneService.TAG, "Error when calling multicastUnsubscribe: " + multicastUnsubscribe);
                            }
                        } catch (Exception e) {
                            Log.e(ZeroTierOneService.TAG, e.toString(), e);
                        }
                    }
                    this.subscriptions = groups;
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Log.d(ZeroTierOneService.TAG, "V6 Multicast Scanner Thread Interrupted", e);
                    break;
                }
            }
            Log.d(ZeroTierOneService.TAG, "IPv6 Multicast Scanner Thread Ended.");
        }
    };
    private Thread vpnThread;

    public VirtualNetworkConfig getVirtualNetworkConfig(long j) {
        VirtualNetworkConfig virtualNetworkConfig;
        synchronized (this.virtualNetworkConfigMap) {
            virtualNetworkConfig = this.virtualNetworkConfigMap.get(Long.valueOf(j));
        }
        return virtualNetworkConfig;
    }

    public VirtualNetworkConfig setVirtualNetworkConfig(long j, VirtualNetworkConfig virtualNetworkConfig) {
        VirtualNetworkConfig put;
        synchronized (this.virtualNetworkConfigMap) {
            put = this.virtualNetworkConfigMap.put(Long.valueOf(j), virtualNetworkConfig);
        }
        return put;
    }

    public VirtualNetworkConfig clearVirtualNetworkConfig(long j) {
        VirtualNetworkConfig remove;
        synchronized (this.virtualNetworkConfigMap) {
            remove = this.virtualNetworkConfigMap.remove(Long.valueOf(j));
        }
        return remove;
    }

    private void logBindCount() {
        Log.i(TAG, "Bind Count: " + this.bindCount);
    }

    public IBinder onBind(Intent intent) {
        Log.d(TAG, "Bound by: " + getPackageManager().getNameForUid(Binder.getCallingUid()));
        this.bindCount++;
        logBindCount();
        return this.mBinder;
    }

    public boolean onUnbind(Intent intent) {
        Log.d(TAG, "Unbound by: " + getPackageManager().getNameForUid(Binder.getCallingUid()));
        this.bindCount--;
        logBindCount();
        return false;
    }

    /* access modifiers changed from: protected */
    public void setNextBackgroundTaskDeadline(long j) {
        synchronized (this) {
            this.nextBackgroundTaskDeadline = j;
        }
    }

    /**
     * 启动 ZT 服务，连接至给定网络或最近连接的网络
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "onStartCommand: STARTING (startid=" + startId + ")");
        long networkId;
        if (startId == 3) {
            Log.i(TAG, "Authorizing VPN");
            return START_NOT_STICKY;
        } else if (intent == null) {
            Log.e(TAG, "NULL intent.  Cannot start");
            return START_NOT_STICKY;
        }
        this.mStartID = startId;

        // 注册事件总线监听器
        if (!this.eventBus.isRegistered(this)) {
            this.eventBus.register(this);
        }
        
        // --- NEXUS AUDIT INITIALIZATION ---
        try {
            java.io.File logFile = new java.io.File(getFilesDir(), "nexus_audit.log");
            java.io.FileWriter writer = new java.io.FileWriter(logFile, false); // Overwrite every start
            writer.write(">>> NEXUS AUDIT LOG INITIALIZED: " + new java.util.Date().toString() + "\n");
            writer.close();
            Log.e("NexusAudit", "Audit log file initialized at " + logFile.getAbsolutePath());
        } catch (Exception e) {
            Log.e("NexusAudit", "Failed to init audit log", e);
        }

        // updateTunnelConfig(null); // Moved after networkId is determined

        // 初始化流量统计任务
        if (trafficRunnable == null) {
            trafficRunnable = new Runnable() {
                @Override
                public void run() {
                    updateRealtimeTraffic();
                    trafficHandler.postDelayed(this, 5000); // 降低频率至 5 秒一次
                }
            };
            trafficHandler.post(trafficRunnable);
        }

        // 显示正在启动通知，提升至前台服务
        createNotificationChannel();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ZT_NOTIFICATION_TAG, getStartingNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(ZT_NOTIFICATION_TAG, getStartingNotification());
        }

        // 确定待启动的网络 ID
        if (intent.hasExtra(ZT1_NETWORK_ID)) {
            // Intent 中指定了目标网络，直接使用此 ID
            networkId = intent.getLongExtra(ZT1_NETWORK_ID, 0);
        } else {
            // 默认启用最近一次启动的网络
            DatabaseUtils.readLock.lock();
            try {
                DaoSession daoSession = ((ZerotierFixApplication) getApplication()).getDaoSession();
                daoSession.clear();
                List<Network> lastActivatedNetworks = daoSession.getNetworkDao().queryBuilder()
                        .where(NetworkDao.Properties.LastActivated.eq(true))
                        .list();
                if (lastActivatedNetworks == null || lastActivatedNetworks.isEmpty()) {
                    Log.e(TAG, "Couldn't find last activated connection");
                    return START_NOT_STICKY;
                } else if (lastActivatedNetworks.size() > 1) {
                    Log.e(TAG, "Multiple networks marked as last connected: " + lastActivatedNetworks.size());
                    for (Network network : lastActivatedNetworks) {
                        Log.e(TAG, "ID: " + Long.toHexString(network.getNetworkId()));
                    }
                    stopSelf(startId);
                    return START_NOT_STICKY;
                } else {
                    networkId = lastActivatedNetworks.get(0).getNetworkId();
                    Log.i(TAG, "Got Always On request for ZeroTier");
                }
            } finally {
                DatabaseUtils.readLock.unlock();
            }
        }
        if (networkId == 0) {
            Log.e(TAG, "Network ID not provided to service");
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        this.networkId = networkId;
        updateTunnelConfig(null); // Trigger initial tunnel config

        // 检查当前的网络环境 (从数据库读取针对该网络的配置)
        android.content.SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        DaoSession daoSession = ((ZerotierFixApplication) getApplication()).getDaoSession();
        Network networkEntity = daoSession.getNetworkDao().load(this.networkId);
        NetworkConfig config = (networkEntity != null) ? networkEntity.getNetworkConfig() : null;
        boolean useCellularData = (config != null) ? config.getAllowMobileData() : true;
        this.disableIPv6 = (config != null) && config.getDisableIPv6();
        NetworkInfoUtils.CurrentConnection currentNetworkInfo = NetworkInfoUtils.getNetworkInfoCurrentConnection(this);

        if (currentNetworkInfo == NetworkInfoUtils.CurrentConnection.CONNECTION_NONE) {
            // 未连接网络
            Toast.makeText(this, R.string.toast_no_network, Toast.LENGTH_SHORT).show();
            stopSelf(this.mStartID);
            return START_NOT_STICKY;
        } else if (currentNetworkInfo == NetworkInfoUtils.CurrentConnection.CONNECTION_MOBILE &&
                !useCellularData) {
            // 使用移动网络，但未在设置中允许移动网络访问
            Toast.makeText(this, R.string.toast_mobile_data, Toast.LENGTH_LONG).show();
            stopSelf(this.mStartID);
            return START_NOT_STICKY;
        }

        // 启动 ZT 服务
        synchronized (this) {
            try {
                // 创建本地 ZT 服务 Socket，监听本地端口
                if (this.svrSocket == null) {
                    this.svrSocket = new DatagramSocket(null);
                    this.svrSocket.setReuseAddress(true);
                    this.svrSocket.setSoTimeout(1000);
                    this.svrSocket.bind(new InetSocketAddress(9994));
                }
                if (!protect(this.svrSocket)) {
                    Log.e(TAG, "Error protecting UDP socket from feedback loop.");
                }

                // 创建本地节点
                if (this.node == null) {
                    this.udpCom = new UdpCom(this, this.svrSocket);
                    this.tunTapAdapter = new TunTapAdapter(this, networkId);

                    // 创建节点对象并初始化
                    DataStore ds = this.dataStore;
                    this.node = new Node(System.currentTimeMillis());
                    ResultCode result = this.node.init(ds, ds, this.udpCom, this, this.tunTapAdapter, this, null);

                    if (result == ResultCode.RESULT_OK) {
                        Log.d(TAG, "ZeroTierOne Node Initialized");
                    } else {
                        Log.e(TAG, "Error starting ZT1 Node: " + result);
                        return START_NOT_STICKY;
                    }
                    this.onNodeStatusRequest(null);

                    // 持久化当前节点信息
                    long address = this.node.address();
                    DatabaseUtils.writeLock.lock();
                    try {
                        com.zerotier.nexus.model.AppNodeDao appNodeDao = ((ZerotierFixApplication) getApplication())
                                .getDaoSession().getAppNodeDao();
                        List<AppNode> nodesList = appNodeDao.queryBuilder().build()
                                .forCurrentThread().list();
                        if (nodesList.isEmpty()) {
                            AppNode appNode = new AppNode();
                            appNode.setNodeId(address);
                            appNode.setNodeIdStr(String.format("%10x", address));
                            appNodeDao.insert(appNode);
                        } else {
                            AppNode appNode = nodesList.get(0);
                            appNode.setNodeId(address);
                            appNode.setNodeIdStr(String.format("%10x", address));
                            appNodeDao.save(appNode);
                        }
                    } finally {
                        DatabaseUtils.writeLock.unlock();
                    }

                    this.eventBus.post(new NodeIDEvent(address));
                    this.udpCom.setNode(this.node);
                    this.tunTapAdapter.setNode(this.node);

                    // 启动 UDP 消息处理线程
                    Thread thread = new Thread(this.udpCom, "UDP Communication Thread");
                    this.udpThread = thread;
                    thread.start();
                }

                // 创建并启动 VPN 服务线程
                if (this.vpnThread == null) {
                    Thread thread = new Thread(this, "ZeroTier Service Thread");
                    this.vpnThread = thread;
                    thread.start();
                }

                // 启动 UDP 消息处理线程
                if (!this.udpThread.isAlive()) {
                    this.udpThread.start();
                }
            } catch (Exception e) {
                Log.e(TAG, e.toString(), e);
                return START_NOT_STICKY;
            }
        }
        joinNetwork(networkId);

        // 自动入轨 Moon
        if (preferences.getBoolean(Constants.PREF_MOON_USE_CUSTOM, false)) {
            new Thread(() -> {
                try {
                    DatabaseUtils.readLock.lock();
                    List<MoonOrbit> moons = daoSession.getMoonOrbitDao().loadAll();
                    for (MoonOrbit moon : moons) {
                        Log.i(TAG, "Auto-orbiting moon: " + Long.toHexString(moon.getMoonWorldId()));
                        orbitNetwork(moon.getMoonWorldId(), moon.getMoonSeed());
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error during auto-orbiting: " + e.getMessage());
                } finally {
                    DatabaseUtils.readLock.unlock();
                }
            }, "Moon-Auto-Orbit").start();
        }

        return START_STICKY;
    }

    public void stopZeroTier() {
        if (this.svrSocket != null) {
            this.svrSocket.close();
            this.svrSocket = null;
        }
        if (this.udpThread != null && this.udpThread.isAlive()) {
            this.udpThread.interrupt();
            try {
                this.udpThread.join();
            } catch (InterruptedException ignored) {
            }
            this.udpThread = null;
        }
        if (this.tunTapAdapter != null && this.tunTapAdapter.isRunning()) {
            this.tunTapAdapter.setNode(null);
            this.tunTapAdapter.interrupt();
            try {
                this.tunTapAdapter.join();
            } catch (InterruptedException ignored) {
            }
            this.tunTapAdapter = null;
        }
        if (this.vpnThread != null && this.vpnThread.isAlive()) {
            this.vpnThread.interrupt();
            try {
                this.vpnThread.join();
            } catch (InterruptedException ignored) {
            }
            this.vpnThread = null;
        }
        if (this.v4MulticastScanner != null) {
            this.v4MulticastScanner.interrupt();
            try {
                this.v4MulticastScanner.join();
            } catch (InterruptedException ignored) {
            }
            this.v4MulticastScanner = null;
        }
        if (this.v6MulticastScanner != null) {
            this.v6MulticastScanner.interrupt();
            try {
                this.v6MulticastScanner.join();
            } catch (InterruptedException ignored) {
            }
            this.v6MulticastScanner = null;
        }
        if (this.vpnSocket != null) {
            try {
                this.vpnSocket.close();
            } catch (Exception e) {
                Log.e(TAG, "Error closing VPN socket: " + e, e);
            }
            this.vpnSocket = null;
        }
        if (this.node != null) {
            this.eventBus.post(new NodeDestroyedEvent());
            this.node.close();
            this.node = null;
        }
        if (this.eventBus.isRegistered(this)) {
            this.eventBus.unregister(this);
        }
        if (this.notificationManager != null) {
            this.notificationManager.cancel(ZT_NOTIFICATION_TAG);
        }
        if (!stopSelfResult(this.mStartID)) {
            Log.e(TAG, "stopSelfResult() failed!");
        }
    }

    public void onDestroy() {
        try {
            stopZeroTier();
            if (this.vpnSocket != null) {
                try {
                    this.vpnSocket.close();
                } catch (Exception e) {
                    Log.e(TAG, "Error closing VPN socket: " + e, e);
                }
                this.vpnSocket = null;
            }
            stopSelf(this.mStartID);
            if (this.eventBus.isRegistered(this)) {
                this.eventBus.unregister(this);
            }
        } catch (Exception e) {
            Log.e(TAG, e.toString(), e);
        } finally {
            super.onDestroy();
        }
    }

    public void onRevoke() {
        stopZeroTier();
        if (this.vpnSocket != null) {
            try {
                this.vpnSocket.close();
            } catch (Exception e) {
                Log.e(TAG, "Error closing VPN socket: " + e, e);
            }
            this.vpnSocket = null;
        }
        stopSelf(this.mStartID);
        if (this.eventBus.isRegistered(this)) {
            this.eventBus.unregister(this);
        }
        super.onRevoke();
    }

    public void run() {
        Log.d(TAG, "ZeroTierOne Service Started");
        Log.d(TAG, "This Node Address: " + com.zerotier.sdk.util.StringUtils.addressToString(this.node.address()));
        while (!Thread.interrupted()) {
            try {
                // 在后台任务截止期前循环进行后台任务
                long taskDeadline = this.nextBackgroundTaskDeadline;
                long currentTime = System.currentTimeMillis();
                int cmp = Long.compare(taskDeadline, currentTime);
                if (cmp <= 0) {
                    long[] newDeadline = {0};
                    ResultCode taskResult = this.node.processBackgroundTasks(currentTime, newDeadline);
                    synchronized (this) {
                        this.nextBackgroundTaskDeadline = newDeadline[0];
                    }
                    if (taskResult != ResultCode.RESULT_OK) {
                        Log.e(TAG, "Error on processBackgroundTasks: " + taskResult.toString());
                        shutdown();
                    }
                }
                Thread.sleep(cmp > 0 ? taskDeadline - currentTime : 100);
            } catch (InterruptedException ignored) {
                break;
            } catch (Exception e) {
                Log.e(TAG, e.toString(), e);
            }
        }
        Log.d(TAG, "ZeroTierOne Service Ended");
    }

    @Subscribe(threadMode = ThreadMode.POSTING)
    public void onStopEvent(StopEvent stopEvent) {
        stopZeroTier();
    }

    @Subscribe(threadMode = ThreadMode.POSTING)
    public void onManualDisconnect(ManualDisconnectEvent manualDisconnectEvent) {
        stopZeroTier();
    }

    @Subscribe(threadMode = ThreadMode.POSTING)
    public void onIsServiceRunningRequest(IsServiceRunningRequestEvent event) {
        this.eventBus.post(new IsServiceRunningReplyEvent(true));
    }

    /**
     * 加入 ZT 网络
     */
    public void joinNetwork(long networkId) {
        if (this.node == null) {
            Log.e(TAG, "Can't join network if ZeroTier isn't running");
            return;
        }
        // 连接到新网络
        ResultCode result = this.node.join(networkId);
        if (result != ResultCode.RESULT_OK) {
            this.eventBus.post(new ErrorEvent(result));
            return;
        }
        // 连接后事件
        this.eventBus.post(new AfterJoinNetworkEvent());
    }

    /**
     * 离开 ZT 网络
     */
    public void leaveNetwork(long networkId) {
        if (this.node == null) {
            Log.e(TAG, "Can't leave network if ZeroTier isn't running");
            return;
        }
        com.zerotier.sdk.ResultCode result = this.node.leave(networkId);
        if (result != ResultCode.RESULT_OK) {
            this.eventBus.post(new ErrorEvent(result));
            return;
        }
        com.zerotier.sdk.VirtualNetworkConfig[] networkConfigs = this.node.networkConfigs();
        if (networkConfigs != null && networkConfigs.length != 0) {
            return;
        }
        stopZeroTier();
        if (this.vpnSocket != null) {
            try {
                this.vpnSocket.close();
            } catch (Exception e) {
                Log.e(TAG, "Error closing VPN socket", e);
            }
            this.vpnSocket = null;
        }
        stopSelf(this.mStartID);
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onNetworkListRequest(NetworkListRequestEvent requestNetworkListEvent) {
        VirtualNetworkConfig[] networks;
        Node node2 = this.node;
        if (node2 != null && (networks = node2.networkConfigs()) != null && networks.length > 0) {
            this.eventBus.post(new NetworkListReplyEvent(networks));
        }
    }

    /**
     * 请求节点状态事件回调
     *
     * @param event 事件
     */
    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onNodeStatusRequest(NodeStatusRequestEvent event) {
        // 返回节点状态
        if (this.node != null) {
            this.eventBus.post(new NodeStatusEvent(this.node.status(), this.node.getVersion()));
        }
    }

    /**
     * 请求 Peer 信息事件回调
     */
    private void sendPeerInfoUpdate() {
        if (this.node == null) {
            this.eventBus.post(new PeerInfoReplyEvent(null, null));
            return;
        }

        Peer[] peers = this.node.peers();
        int count = (peers == null ? 0 : peers.length);
        Log.i(TAG, "RequestPeerInfo: Found " + count + " peers");
        
        Map<Long, String> nodeToIp = new HashMap<>();
        if (peers != null) {
            for (int i = 0; i < peers.length; i++) {
                Peer p = peers[i];
                long addr = p.getAddress();
                
                // 从 TunTapAdapter 获取对应的虚拟 IP
                if (this.tunTapAdapter != null) {
                    String virtualIp = this.tunTapAdapter.getIPForNodeId(addr);
                    if (virtualIp != null) {
                        nodeToIp.put(addr, virtualIp);
                    }
                }
                
                Log.d(TAG, String.format("  - Peer[%d]: ID=%s, Role=%s, Paths=%d, Latency=%dms, VirtualIP=%s",
                        i, Long.toHexString(addr), p.getRole(),
                        (p.getPaths() != null ? p.getPaths().length : 0), p.getLatency(),
                        nodeToIp.getOrDefault(addr, "N/A")));
            }
        }
        
        this.eventBus.post(new PeerInfoReplyEvent(peers, nodeToIp));
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onRequestPeerInfo(PeerInfoRequestEvent event) {
        if (this.node != null) {
            stimulatePeerDiscovery();
            sendPeerInfoUpdate();
        } else {
            this.eventBus.post(new PeerInfoReplyEvent(null, null));
        }
    }
    
    private void stimulatePeerDiscovery() {
        if (this.node == null) return;
        
        // 增加频率限制 (30 秒内仅允许一次全量探测)
        long now = System.currentTimeMillis();
        if (now - lastDiscoveryTime < 30000) {
            Log.d(TAG, "Discovery pulse: Throttled (Cooldown active)");
            return;
        }
        lastDiscoveryTime = now;
        
        // 1. 物理层探测：发送 UDP 广播，尝试在本地局域网发现 ZT 节点
        if (this.out != null) {
            try {
                byte[] packet = new byte[28];
                packet[0] = 0x45;
                packet[2] = 0x00; packet[3] = 0x1C;
                packet[8] = 0x40; packet[9] = 17;
                packet[16] = (byte) 255; packet[17] = (byte) 255;
                packet[18] = (byte) 255; packet[19] = (byte) 255;
                packet[20] = 0x27; packet[21] = 0x09;
                packet[22] = 0x27; packet[23] = 0x09;
                packet[24] = 0x00; packet[25] = 0x08;
                
                this.out.write(packet);
                this.out.flush();
                Log.d(TAG, "Discovery pulse: Physical UDP broadcast sent");
            } catch (Exception e) {
                Log.w(TAG, "Discovery pulse: Physical UDP failed: " + e.getMessage());
            }
        }

        // 2. 虚拟层探测：遍历各网络发送控制流量
        VirtualNetworkConfig[] configs = this.node.networkConfigs();
        if (configs != null) {
            for (VirtualNetworkConfig config : configs) {
                if (config.getStatus() != VirtualNetworkStatus.NETWORK_STATUS_OK) continue;
                
                long nwid = config.getNwid();
                long localMac = config.getMac();
                
                InetAddress localIp = null;
                for (InetSocketAddress addr : config.getAssignedAddresses()) {
                    if (addr.getAddress() instanceof java.net.Inet4Address) {
                        localIp = addr.getAddress();
                        break;
                    }
                }
                
                if (localIp != null) {
                    long[] nextDeadline = new long[1];
                    try {
                        // 2.1 无故 ARP (Gratuitous ARP)
                        // 作用：宣告自己的存在，让网络中所有节点记录我们的 MAC，触发 P2P
                        byte[] arpPacket = new byte[28];
                        arpPacket[0] = 0; arpPacket[1] = 1; // Ethernet
                        arpPacket[2] = 8; arpPacket[3] = 0; // IPv4
                        arpPacket[4] = 6; arpPacket[5] = 4;
                        arpPacket[6] = 0; arpPacket[7] = 1; // Request
                        
                        byte[] macBytes = com.zerotier.nexus.util.StringUtils.macToBytes(localMac);
                        System.arraycopy(macBytes, 0, arpPacket, 8, 6);
                        System.arraycopy(localIp.getAddress(), 0, arpPacket, 14, 4);
                        // 无故 ARP 的 Target IP 设为自己的 IP
                        System.arraycopy(localIp.getAddress(), 0, arpPacket, 24, 4);
                        
                        this.node.processVirtualNetworkFrame(System.currentTimeMillis(), nwid,
                                localMac, 0xFFFFFFFFFFFFL, 0x0806, 0, arpPacket, nextDeadline);

                        // 2.2 ICMP 全网广播 (Ping 255.255.255.255)
                        // 作用：强制所有节点的 ZT 内核处理一次合法的 frame，建立直接路径
                        byte[] icmpPacket = new byte[28];
                        icmpPacket[0] = 0x45; // Version, IHL
                        icmpPacket[2] = 0x00; icmpPacket[3] = 28; // Total Length
                        icmpPacket[8] = 64;   // TTL
                        icmpPacket[9] = 1;    // Protocol (ICMP)
                        System.arraycopy(localIp.getAddress(), 0, icmpPacket, 12, 4); // Source IP
                        icmpPacket[16] = (byte) 255; icmpPacket[17] = (byte) 255; 
                        icmpPacket[18] = (byte) 255; icmpPacket[19] = (byte) 255; // Dest IP
                        // ICMP Echo Header (Type=8, Code=0)
                        icmpPacket[20] = 8;
                        icmpPacket[21] = 0;
                        // Checksum (Simplified Zero)
                        icmpPacket[22] = 0; icmpPacket[23] = 0;
                        
                        this.node.processVirtualNetworkFrame(System.currentTimeMillis(), nwid,
                                localMac, 0xFFFFFFFFFFFFL, 0x0800, 0, icmpPacket, nextDeadline);
                        
                        Log.d(TAG, "Discovery pulse: Virtual ARP & ICMP broadcast sent for network " + Long.toHexString(nwid));
                    } catch (Exception e) {
                        Log.w(TAG, "Discovery pulse: Virtual stimulation failed: " + e.getMessage());
                    }
                }
            }
        }
    }

    /**
     * 请求网络配置事件回调
     */
    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onVirtualNetworkConfigRequest(VirtualNetworkConfigRequestEvent event) {
        if (this.node == null) {
            this.eventBus.post(new VirtualNetworkConfigReplyEvent(null));
            return;
        }
        VirtualNetworkConfig config = this.node.networkConfig(event.getNetworkId());
        this.eventBus.post(new VirtualNetworkConfigReplyEvent(config));
    }

    @Subscribe(threadMode = ThreadMode.ASYNC)
    public void onNetworkReconfigure(NetworkReconfigureEvent event) {
        boolean isChanged = event.isChanged();
        Network network = event.getNetwork();
        VirtualNetworkConfig networkConfig = event.getVirtualNetworkConfig();
        boolean configUpdated = isChanged && updateTunnelConfig(network);
        boolean networkIsOk = networkConfig.getStatus() == VirtualNetworkStatus.NETWORK_STATUS_OK;

        if (configUpdated || !networkIsOk) {
            this.eventBus.post(new VirtualNetworkConfigChangedEvent(networkConfig));
        }
    }

    @Subscribe(threadMode = ThreadMode.ASYNC)
    public void onNetworkConfigChangedByUser(NetworkConfigChangedByUserEvent event) {
        synchronized (reconfigLock) {
            Log.wtf(TAG, "!!! [ACTION] NetworkConfigChangedByUserEvent received !!!");
            Network network = event.getNetwork();
            
            // 获取最新活跃网络
            if (network == null) {
                DaoSession daoSession = ((ZerotierFixApplication) getApplication()).getDaoSession();
                network = daoSession.getNetworkDao().queryBuilder()
                        .where(NetworkDao.Properties.LastActivated.eq(true))
                        .unique();
            }
            
            if (network == null) {
                Log.w(TAG, "No active network found during config change.");
                return;
            }

            // 判定是否需要“硬更新” (VPN 接口重启)
            boolean newIsRouteViaZeroTier = checkIsRouteViaZeroTier(network);

            // [核心修复] 只要用户手动触发了配置变更，由于可能涉及 DNS、MTU 等 VpnService 关键参数，
            // 必须强制执行“硬更新” (VPN 接口重启) 以应用新设置。
            // [新增] 在重启前清理旧缓存，确保 DNS 下发或路由变更能立即刷新
            NetworkCacheManager.clearNetworkCache(this, network);
            
            updateTunnelConfig(network);
            checkAndApplyExitNode();
            
            // 更新缓存状态
            this.lastIsRouteViaZeroTier = newIsRouteViaZeroTier;
        }
    }

    private boolean checkIsRouteViaZeroTier(Network network) {
        android.content.SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        NetworkConfig networkConfig = (network != null) ? network.getNetworkConfig() : null;
        
        boolean globalMasterOn = preferences.getBoolean("global_roaming_enabled", false);
        boolean allowExit = (networkConfig != null && networkConfig.getAllowExitRoaming());
        boolean routeViaZT = (networkConfig != null && networkConfig.getRouteViaZeroTier());

        // [修复] 增加实时配置降级
        if (network != null && networkConfig == null && this.node != null) {
            com.zerotier.sdk.VirtualNetworkConfig vpc = this.node.networkConfig(network.getNetworkId());
            if (vpc != null) {
                for (com.zerotier.sdk.VirtualNetworkRoute r : vpc.getRoutes()) {
                    if (r.getTarget().getAddress().getHostAddress().equals("0.0.0.0") && r.getTarget().getPort() == 0) {
                        routeViaZT = true;
                        break;
                    }
                }
            }
        }
        
        return globalMasterOn && (allowExit || routeViaZT);
    }

    private void checkAndApplyExitNode() {
        Log.w(TAG, "checkAndApplyExitNode: SHIELDED/DISABLED by user request.");
        if (ExitNodeManager.getInstance().isActive()) {
            stopForwardingReinforcer();
            ExitNodeManager.getInstance().disable();
        }
        return;
        /*
        // 全局检查：是否有任何一个网络需要且可以开启出口模式
        Network targetNetwork = null;
        
        DatabaseUtils.writeLock.lock();
        try {
            com.zerotier.nexus.model.NetworkDao networkDao = ((ZerotierFixApplication) getApplication())
                    .getDaoSession()
                    .getNetworkDao();
            List<Network> networks = networkDao.loadAll();
            
            for (Network net : networks) {
                NetworkConfig netCfg = net.getNetworkConfig();
                if (netCfg != null && netCfg.getAllowExitRoaming()) {
                    VirtualNetworkConfig vpc = getVirtualNetworkConfig(net.getNetworkId());
                    if (vpc != null && vpc.getStatus() == VirtualNetworkStatus.NETWORK_STATUS_OK) {
                        // 找到一个可以作为出口的网络
                        targetNetwork = net;
                        break;
                    }
                }
            }
        } finally {
            DatabaseUtils.writeLock.unlock();
        }

        // --- 逻辑修复：引入全局主开关校验 ---
        android.content.SharedPreferences prefs = android.preference.PreferenceManager.getDefaultSharedPreferences(this);
        boolean globalMasterOn = prefs.getBoolean(com.zerotier.nexus.util.Constants.PREF_EXIT_NODE_GLOBAL_ENABLED, false);
        
        Log.d(TAG, "checkAndApplyExitNode: targetNetwork=" + (targetNetwork != null ? Long.toHexString(targetNetwork.getNetworkId()) : "null") + 
                   ", globalMaster=" + globalMasterOn);

        if (targetNetwork != null) {
            // ... (省略中间检测代码，保持逻辑一致)
            NetworkConfig targetNetCfg = targetNetwork.getNetworkConfig();
            String ztIf = null;
            String extIf = null;
            
            for (int i = 0; i < 5; i++) {
                ztIf = NetworkInfoUtils.findZtInterface(this);
                extIf = NetworkInfoUtils.findDefaultInterface(this);
                if (ztIf != null && extIf != null) break;
                Log.w(TAG, String.format("Waiting for interfaces (Attempt %d/5): zt=%s, ext=%s", i+1, ztIf, extIf));
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
            }

            if (ztIf != null && extIf != null) {
                Log.i(TAG, "Global Exit Node check: Enabling for " + ztIf + " -> " + extIf);
                boolean success = ExitNodeManager.getInstance().enable(ztIf, extIf);
                
                if (success) {
                    Log.i(TAG, "Successfully activated Gateway Mode using native engine.");
                    startForwardingReinforcer(ztIf, extIf);
                    
                    int finalMtu = 1280;
                    VirtualNetworkConfig vpc = getVirtualNetworkConfig(targetNetwork.getNetworkId());
                    int controllerMtu = vpc != null ? vpc.getMtu() : 0;
                    try {
                        if (targetNetCfg.getMtu() != null && !targetNetCfg.getMtu().isEmpty()) {
                            finalMtu = Integer.parseInt(targetNetCfg.getMtu());
                        } else if (controllerMtu > 0) {
                            finalMtu = controllerMtu;
                        }
                    } catch (NumberFormatException ignored) {}
                    
                    String ztSubnet = NetworkInfoUtils.getInterfaceSubnet(ztIf);
                    if (ztSubnet == null) ztSubnet = "10.5.5.0/24";
                    
                    ExitNodeManager.getInstance().setInterfaceMtu(ztIf, finalMtu);
                    String targetDns = NetworkInfoUtils.getInterfaceDns(this);
                    ExitNodeManager.getInstance().applyUltimateFix(ztIf, extIf, finalMtu, ztSubnet, targetDns);

                    Log.e("NexusAudit", ">>> EXIT NODE ACTIVATED: " + ztIf + " -> " + extIf + " (" + ztSubnet + ")");

                    // --- 阶段三：刷新代理状态 (隔离保护) ---
                    try {
                        refreshProxyState(targetNetwork);
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to refresh proxy state: " + e.getMessage(), e);
                    }
                }
            }
        } else if (!globalMasterOn) {
            // 没有任何网络标记出口模式，且全局总开关已关闭，确保物理关闭
            if (ExitNodeManager.getInstance().isActive()) {
                Log.i(TAG, "Global check: Disabling Exit Node as no network is marked and global master is OFF.");
                stopForwardingReinforcer();
                ExitNodeManager.getInstance().disable();
            }
            refreshProxyState(null); 
        } else {
            Log.d(TAG, "Global check: targetNetwork is null but globalMaster is ON, skipping mandatory disable.");
        }
        */
    }

    /**
     * [重构] 实时判定并刷新代理引擎状态 (TUIC v5)
     */
    private void refreshProxyState(Network network) {
        android.content.SharedPreferences prefs = android.preference.PreferenceManager.getDefaultSharedPreferences(this);
        boolean globalRoamingEnabled = prefs.getBoolean("global_roaming_enabled", false);
        boolean proxySwitchOn = prefs.getBoolean(Constants.PREF_PROXY_MODE, false);
        boolean isGatewayActive = ExitNodeManager.getInstance().isActive();
        
        Network targetNet = network;
        if (targetNet == null) {
            DaoSession daoSession = ((ZerotierFixApplication) getApplication()).getDaoSession();
            targetNet = daoSession.getNetworkDao().queryBuilder()
                    .where(NetworkDao.Properties.LastActivated.eq(true))
                    .unique();
        }
        NetworkConfig networkConfig = (targetNet != null) ? targetNet.getNetworkConfig() : null;

        // 判定协议与核心参数
        String protocol = prefs.getString(Constants.PREF_PROXY_PROTOCOL, "TUIC v5");
        String uuid = prefs.getString(Constants.PREF_PROXY_UUID, "");
        String password = prefs.getString(Constants.PREF_PROXY_PASSWORD, "");
        
        boolean isProxyStarted = false;
        String proxyHost = "";
        int proxyPort = 10004;

        // [屏蔽] 禁用代理引擎启动逻辑
        Log.w(TAG, "ProxyEngine logic: SHIELDED/DISABLED by user request.");
        ProxyEngine.getInstance().stop();

        /*
        // --- 逻辑分支 A：客户端模式 (漫游加速) ---
        if (globalRoamingEnabled && proxySwitchOn) {
            proxyHost = prefs.getString(Constants.PREF_PROXY_REMOTE_HOST, "");
            try { proxyPort = Integer.parseInt(prefs.getString(Constants.PREF_PROXY_REMOTE_PORT, "10004").trim()); } catch (Exception ignored) {}

            final String finalHost = proxyHost;
            final int finalRemotePort = proxyPort;
            if (!proxyHost.isEmpty()) {
                Log.i(TAG, "ProxyState: Starting Client -> " + proxyHost + ":" + proxyPort);
                ProxyEngine.getInstance().startClient(this, proxyHost, proxyPort, uuid, password, actualLocalPort -> {
                    // 当端口确定后，同步至 Adapter 并记录日志
                    if (this.tunTapAdapter != null) {
                        this.tunTapAdapter.updateProxyConfig(true, true, finalHost, actualLocalPort);
                    }
                    Log.e("NexusAudit", ">>> MODE 3: TUIC CLIENT ACTIVATED (" + finalHost + "), Local SOCKS: " + actualLocalPort);
                    
                    // 广播给 UI (可选，目前 UI 通过采样观察)
                    android.content.Intent intent = new android.content.Intent("com.zerotier.nexus.PROXY_PORT_UPDATE");
                    intent.putExtra("port", actualLocalPort);
                    sendBroadcast(intent);
                });
                isProxyStarted = true;
            }
        }

        // --- 逻辑分支 B：服务端模式 (出口中转) ---
        if (!isProxyStarted && isGatewayActive) {
            try { proxyPort = Integer.parseInt(prefs.getString(Constants.PREF_EXIT_NODE_PORT, "10004").trim()); } catch (Exception ignored) {}
            
            Log.i(TAG, "ProxyState: Starting Server on port " + proxyPort);
            ProxyEngine.getInstance().startServer(this, protocol, uuid, password, proxyPort);
            isProxyStarted = true;
            Log.e("NexusAudit", ">>> MODE 4: TUIC SERVER ACTIVATED on " + proxyPort);
        }

        if (!isProxyStarted) {
            Log.i(TAG, "ProxyState: No proxy role active, stopping engine.");
            ProxyEngine.getInstance().stop();
        }
        */

        // --- 核心同步：同步至 TunTapAdapter ---
        if (this.tunTapAdapter != null) {
            // [修复] 增加实时配置降级，防止数据库加载延迟导致漫游失效
            boolean allowExit = (networkConfig != null && networkConfig.getAllowExitRoaming());
            boolean routeViaZT = (networkConfig != null && networkConfig.getRouteViaZeroTier());
            
            // 降级：如果数据库未就位，尝试从 SDK 获取
            if (targetNet != null && networkConfig == null && this.node != null) {
                com.zerotier.sdk.VirtualNetworkConfig vpc = this.node.networkConfig(targetNet.getNetworkId());
                if (vpc != null) {
                    for (com.zerotier.sdk.VirtualNetworkRoute r : vpc.getRoutes()) {
                        if (r.getTarget().getAddress().getHostAddress().equals("0.0.0.0") && r.getTarget().getPort() == 0) {
                            routeViaZT = true;
                            break;
                        }
                    }
                }
            }

            boolean isAnyRoamingActive = false; // [屏蔽] 强制回退至基础 P2P 模式
            
            // [优化] 如果是客户端代理模式，TunTapAdapter 的 updateProxyConfig 会由 ProxyEngine 的回调异步触发
            // 此时不应再次调用同步版本，防止 overwrite 正确的 localSocksPort 为远程 proxyPort
            if (!(globalRoamingEnabled && proxySwitchOn && !proxyHost.isEmpty())) {
                this.tunTapAdapter.updateProxyConfig(isProxyStarted, isAnyRoamingActive, proxyHost, proxyPort);
            } else {
                // 仅同步漫游标记，其余由回调处理
                this.tunTapAdapter.updateProxyConfig(isProxyStarted, isAnyRoamingActive, null, -1);
            }
            
            if (isAnyRoamingActive) {
                // 同步漫游网络 ID
                if (targetNet != null) {
                    this.tunTapAdapter.updateRoamingNetwork(targetNet.getNetworkId());
                    
                    // --- 动态网关检测逻辑 ---
                    VirtualNetworkConfig vpc = getVirtualNetworkConfig(targetNet.getNetworkId());
                    if (vpc != null) {
                        String v4Gateway = null;
                        String v6Gateway = null;
                        for (com.zerotier.sdk.VirtualNetworkRoute r : vpc.getRoutes()) {
                            String target = r.getTarget().getAddress().getHostAddress();
                            int mask = r.getTarget().getPort();
                            if (target.equals("0.0.0.0") && mask == 0) {
                                if (r.getVia() != null) v4Gateway = r.getVia().getAddress().getHostAddress();
                            } else if (target.equals("::") && mask == 0) {
                                if (r.getVia() != null) v6Gateway = r.getVia().getAddress().getHostAddress();
                            }
                        }
                        if (v4Gateway != null || v6Gateway != null) {
                            this.tunTapAdapter.updateRoamingGateways(v4Gateway, v6Gateway);
                        }
                    }
                }
                
                // 主动触发 P2P 发现刺激
                stimulatePeerDiscovery();
                Log.e("NexusAudit", ">>> MODE 2: P2P ROAMING SYNCED (NetworkID=" + (targetNet != null ? targetNet.getNetworkId() : "N/A") + ")");
            } else {
                this.tunTapAdapter.updateRoamingNetwork(0);
                Log.e("NexusAudit", ">>> MODE 1: BASIC P2P (No Roaming) ACTIVATED");
            }
        }
    }

    private final Handler reinforcerHandler = new Handler(Looper.getMainLooper());
    private String lastReinforceZtIf = null;
    private String lastReinforceExtIf = null;
    private final Runnable reinforcerRunnable = new Runnable() {
        @Override
        public void run() {
            if (ExitNodeManager.getInstance().isActive() && lastReinforceZtIf != null) {
                Log.d(TAG, "Reinforcing Exit Node rules (Periodic)...");
                ExitNodeManager.getInstance().enable(lastReinforceZtIf, lastReinforceExtIf);
                reinforcerHandler.postDelayed(this, 10000); // 每 10 秒加固一次
            }
        }
    };

    private void startForwardingReinforcer(String ztIf, String extIf) {
        this.lastReinforceZtIf = ztIf;
        this.lastReinforceExtIf = extIf;
        reinforcerHandler.removeCallbacks(reinforcerRunnable);
        reinforcerHandler.postDelayed(reinforcerRunnable, 5000);
    }

    private void stopForwardingReinforcer() {
        this.lastReinforceZtIf = null;
        this.lastReinforceExtIf = null;
        reinforcerHandler.removeCallbacks(reinforcerRunnable);
    }

    /**
     * Zerotier 事件回调
     *
     * @param event {@link Event} enum
     */
    @Override
    public void onEvent(Event event) {
        Log.d(TAG, "Event: " + event.toString());
        // 更新节点状态
        if (this.node != null && this.node.isInited()) {
            this.eventBus.post(new NodeStatusEvent(this.node.status(), this.node.getVersion()));
        }
    }

    @Override // com.zerotier.sdk.EventListener
    public void onTrace(String str) {
        Log.d(TAG, "Trace: " + str);
    }

    /**
     * 当 ZT 网络配置发生更新
     */
    @Override
    public int onNetworkConfigurationUpdated(long networkId, VirtualNetworkConfigOperation op, VirtualNetworkConfig config) {
        Log.i(TAG, "Virtual Network Config Operation: " + op);
        DatabaseUtils.writeLock.lock();
        try {
            // 查找网络 ID 对应的配置
            com.zerotier.nexus.model.NetworkDao networkDao = ((ZerotierFixApplication) getApplication())
                    .getDaoSession()
                    .getNetworkDao();
            List<Network> matchedNetwork = networkDao.queryBuilder()
                    .where(NetworkDao.Properties.NetworkId.eq(networkId))
                    .list();
            if (matchedNetwork.size() != 1) {
                Log.e(TAG, "Database is inconsistent: Network not found for ID " + Long.toHexString(networkId));
                return 0; // Or some appropriate error code
            }
            Network network = matchedNetwork.get(0);
            // 根据当前网络状态确定更改配置的行为
            switch (op) {
                case VIRTUAL_NETWORK_CONFIG_OPERATION_UP:
                    Log.d(TAG, "Network Type: " + config.getType() + " Network Status: " + config.getStatus() + " Network Name: " + config.getName() + " ");
                    // 将网络配置的更新交给第一次 Update
                    break;
                case VIRTUAL_NETWORK_CONFIG_OPERATION_CONFIG_UPDATE:
                    Log.i(TAG, "Network Config Update!");
                    boolean isChanged = setVirtualNetworkConfigAndUpdateDatabase(network, config);
                    this.eventBus.post(new NetworkReconfigureEvent(isChanged, network, config));
                    
                    // 当网络状态变为 OK 时，检查并应用出口模式
                    if (config.getStatus() == VirtualNetworkStatus.NETWORK_STATUS_OK) {
                        checkAndApplyExitNode();
                    }
                    break;
                case VIRTUAL_NETWORK_CONFIG_OPERATION_DOWN:
                case VIRTUAL_NETWORK_CONFIG_OPERATION_DESTROY:
                    Log.d(TAG, "Network Down!");
                    clearVirtualNetworkConfig(networkId);
                    checkAndApplyExitNode(); // 网络掉线也要重新检查出口状态
                    break;
            }
            return 0;
        } finally {
            DatabaseUtils.writeLock.unlock();
        }
    }

    private boolean setVirtualNetworkConfigAndUpdateDatabase(Network network, VirtualNetworkConfig virtualNetworkConfig) {
        if ((DatabaseUtils.writeLock instanceof ReentrantReadWriteLock.WriteLock) && !((ReentrantReadWriteLock.WriteLock) DatabaseUtils.writeLock).isHeldByCurrentThread()) {
            throw new IllegalStateException("DatabaseUtils.writeLock not held");
        }
        VirtualNetworkConfig virtualNetworkConfig2 = getVirtualNetworkConfig(network.getNetworkId());
        setVirtualNetworkConfig(network.getNetworkId(), virtualNetworkConfig);
        String networkName = virtualNetworkConfig.getName();
        if (networkName != null && !networkName.isEmpty()) {
            network.setNetworkName(networkName);
        }
        network.update();
        return !virtualNetworkConfig.equals(virtualNetworkConfig2);
    }

    protected void shutdown() {
        stopZeroTier();
        if (this.vpnSocket != null) {
            try {
                this.vpnSocket.close();
            } catch (Exception e) {
                Log.e(TAG, "Error closing VPN socket", e);
            }
            this.vpnSocket = null;
        }
        stopSelf(this.mStartID);
    }

    private boolean updateTunnelConfig(Network network) {
        Log.wtf(TAG, "!!! [ACTION] updateTunnelConfig() triggered !!!");
        try {
            long networkId = (network != null) ? network.getNetworkId() : this.networkId;
            if (networkId == 0) {
                Log.e(TAG, "Cannot update tunnel config: Network ID is 0");
                return false;
            }
            com.zerotier.nexus.model.NetworkConfig networkConfig = (network != null) ? network.getNetworkConfig() : null;
            VirtualNetworkConfig virtualNetworkConfig = getVirtualNetworkConfig(networkId);
            if (virtualNetworkConfig == null) {
                return false;
            }

            // 获取网络配置
            android.content.SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
            boolean globalRoamingEnabled = preferences.getBoolean("global_roaming_enabled", false);
            this.disableIPv6 = networkConfig != null && networkConfig.getDisableIPv6();

            // 安全关闭旧的适配器
            try {
                if (this.tunTapAdapter != null && this.tunTapAdapter.isRunning()) {
                    this.tunTapAdapter.interrupt();
                    // 这里不阻塞主线程太久
                }
                if (this.tunTapAdapter != null) {
                    this.tunTapAdapter.clearRouteMap();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error stopping old adapter: " + e);
            }

            // 重启 VPN Socket
            if (this.vpnSocket != null) {
                try {
                    this.vpnSocket.close();
                    if (this.in != null) this.in.close();
                    if (this.out != null) this.out.close();
                } catch (Exception e) {
                    Log.e(TAG, "Error closing VPN socket: " + e, e);
                }
                this.vpnSocket = null;
                this.in = null;
                this.out = null;
            }

            // 初始化透明代理引擎
            refreshProxyState(network);

            // 配置 VPN
            Log.i(TAG, "Configuring VpnService.Builder");
            VpnService.Builder builder = new VpnService.Builder();
            InetSocketAddress[] assignedAddresses = virtualNetworkConfig.getAssignedAddresses();

            // [逻辑统一] 判定是否需要接管全局流量 (漫游模式)
            // 核心原则：globalMasterOn 是总控 (Kill Switch)，如果关闭则强制禁用漫游
            boolean isRouteViaZeroTier = checkIsRouteViaZeroTier(network);
            this.lastIsRouteViaZeroTier = isRouteViaZeroTier;
            
            Log.i(TAG, "Roaming Decision 2.0: finalRouteViaZT=" + isRouteViaZeroTier);

        // 遍历 ZT 网络中当前设备的 IP 地址，组播配置
        for (InetSocketAddress vpnAddress : assignedAddresses) {
            Log.d(TAG, "Adding VPN Address: " + vpnAddress.getAddress()
                    + " Mac: " + com.zerotier.sdk.util.StringUtils.macAddressToString(virtualNetworkConfig.getMac()));
            byte[] rawAddress = vpnAddress.getAddress().getAddress();

            if (!this.disableIPv6 || !(vpnAddress.getAddress() instanceof Inet6Address)) {
                java.net.InetAddress address = vpnAddress.getAddress();
                int port = vpnAddress.getPort();
                InetAddress route = InetAddressUtils.addressToRoute(address, port);
                if (route == null) {
                    Log.e(TAG, "NULL route calculated!");
                    continue;
                }

                // 计算 VPN 地址相关的组播 MAC 与 ADI
                long multicastGroup;
                long multicastAdi;
                if (rawAddress.length == 4) {
                    // IPv4
                    multicastGroup = InetAddressUtils.BROADCAST_MAC_ADDRESS;
                    multicastAdi = ByteBuffer.wrap(rawAddress).getInt();
                } else {
                    // IPv6
                    multicastGroup = ByteBuffer.wrap(new byte[]{
                                    0, 0, 0x33, 0x33, (byte) 0xFF, rawAddress[13], rawAddress[14], rawAddress[15]})
                            .getLong();
                    multicastAdi = 0;
                }

                // 订阅组播并添加至 TUN TAP 路由
                if (this.node != null) {
                    ResultCode result = this.node.multicastSubscribe(networkId, multicastGroup, multicastAdi);
                    if (result != ResultCode.RESULT_OK) {
                        Log.e(TAG, "Error joining multicast group");
                    } else {
                        Log.d(TAG, "Joined multicast group");
                    }
                }
                builder.addAddress(address, port);
                // [修复] 将虚拟 IP 持久化，供 UI 探测回退使用
                if (address instanceof java.net.Inet4Address) {
                    preferences.edit().putString(Constants.PREF_LAST_KNOWN_ZT_IP, address.getHostAddress()).apply();
                }

                builder.addRoute(route, port);
                this.tunTapAdapter.addRouteAndNetwork(new Route(route, port), networkId);
            }
        }

        // 遍历网络的路由规则，将网络负责路由的地址路由至 VPN
        try {
            InetAddress v4Loopback = InetAddress.getByName("0.0.0.0");
            InetAddress v6Loopback = InetAddress.getByName("::");
            if (virtualNetworkConfig.getRoutes().length > 0) {
                for (VirtualNetworkRoute routeConfig : virtualNetworkConfig.getRoutes()) {
                    InetSocketAddress target = routeConfig.getTarget();
                    InetSocketAddress via = routeConfig.getVia();
                    InetAddress targetAddress = target.getAddress();
                    int targetPort = target.getPort();
                    InetAddress viaAddress = InetAddressUtils.addressToRoute(targetAddress, targetPort);

                    boolean isIPv6Route = (targetAddress instanceof Inet6Address) || (viaAddress instanceof Inet6Address);
                    boolean isDisabledV6Route = this.disableIPv6 && isIPv6Route;
                    boolean shouldRouteToZerotier = viaAddress != null && (
                            isRouteViaZeroTier
                                    || (!viaAddress.equals(v4Loopback) && !viaAddress.equals(v6Loopback))
                    );
                    if (!isDisabledV6Route && shouldRouteToZerotier) {
                        builder.addRoute(viaAddress, targetPort);
                        Route route = new Route(viaAddress, targetPort);
                        if (via != null) {
                            route.setGateway(via.getAddress());
                        }
                        this.tunTapAdapter.addRouteAndNetwork(route, networkId);
                    }
                }
            }
            if (isRouteViaZeroTier) {
                try {
                    InetAddress v4Default = InetAddress.getByName("0.0.0.0");
                    builder.addRoute(v4Default, 0);
                    Log.i(TAG, "Forced adding default v4 route for client roaming");
                } catch (Exception e) {
                    Log.e(TAG, "Failed to add forced default route", e);
                }
            }
            builder.addRoute(InetAddress.getByName("224.0.0.0"), 4);
        } catch (Exception e) {
            this.eventBus.post(new VPNErrorEvent(e.getLocalizedMessage()));
            return false;
        }

        if (Build.VERSION.SDK_INT >= 29) {
            builder.setMetered(false);
        }
        addDNSServers(builder, network, isRouteViaZeroTier);

        // 配置 MTU
        int mtu = virtualNetworkConfig.getMtu();
        Log.i(TAG, "MTU from Network Config: " + mtu);
        if (mtu == 0) {
            mtu = 2800;
        }
        Log.i(TAG, "MTU Set: " + mtu);
        builder.setMtu(mtu);

        builder.setSession(Constants.VPN_SESSION_NAME);

        // 设置部分 APP 不经过 VPN
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            // [关键修复] 无论是否漫游，必须排除自身，防止加密 UDP 流量重入隧道导致死循环
            try {
                builder.addDisallowedApplication(getPackageName());
                Log.d(TAG, "Excluding self from VPN tunnel to prevent routing loop.");
            } catch (Exception e) {
                Log.e(TAG, "Failed to exclude self package", e);
            }

            // 只有非漫游模式才排除其他 APP（漫游模式需要接管全局）
            if (!isRouteViaZeroTier) {
                for (String app : DISALLOWED_APPS) {
                    try {
                        builder.addDisallowedApplication(app);
                    } catch (Exception e3) {
                        Log.e(TAG, "Cannot disallow app: " + app, e3);
                    }
                }
            }
        }

        // 建立 VPN 连接
        android.util.Log.e(TAG, "ESTABLISHING_VPN: Calling builder.establish()...");
        this.vpnSocket = builder.establish();
        if (this.vpnSocket == null) {
            android.util.Log.e(TAG, "VPN_FAIL: builder.establish() returned NULL!");
            this.eventBus.post(new VPNErrorEvent(getString(R.string.toast_vpn_application_not_prepared)));
            return false;
        }
        
        int fd = this.vpnSocket.getFd();
        android.util.Log.e(TAG, "VPN_IO_READY: FD = " + fd);
        
        this.in = new FileInputStream(this.vpnSocket.getFileDescriptor());
        this.out = new FileOutputStream(this.vpnSocket.getFileDescriptor());
        
        if (this.tunTapAdapter != null) {
            this.tunTapAdapter.setVpnSocket(this.vpnSocket);
            this.tunTapAdapter.setFileStreams(this.in, this.out);
            this.tunTapAdapter.startThreads();
            android.util.Log.e(TAG, "VPN_SUCCESS: TunTapAdapter streams linked and threads started.");
        } else {
            android.util.Log.e(TAG, "VPN_CRITICAL_ERROR: TunTapAdapter is NULL during establishment!");
        }

        // 状态栏提示
        createNotificationChannel();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ZT_NOTIFICATION_TAG, buildConnectedNotification(network), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(ZT_NOTIFICATION_TAG, buildConnectedNotification(network));
        }
        Log.i(TAG, "ZeroTier One Connected");

        // 旧版本 Android 多播处理
        if (Build.VERSION.SDK_INT < 29) {
            if (this.v4MulticastScanner != null && !this.v4MulticastScanner.isAlive()) {
                this.v4MulticastScanner.start();
            }
            if (!this.disableIPv6 && this.v6MulticastScanner != null && !this.v6MulticastScanner.isAlive()) {
                this.v6MulticastScanner.start();
            }
        }
        return true;
        } catch (Throwable t) {
            Log.e(TAG, "Critical error in updateTunnelConfig: " + t.getMessage(), t);
            return false;
        }
    }

    private void addDNSServers(VpnService.Builder builder, Network network, boolean isRouteViaZeroTier) {
        com.zerotier.nexus.model.NetworkConfig networkConfig;
        try {
            network.refresh(); // [关键] 强制刷新网络对象，避免关联对象缓存
            networkConfig = network.getNetworkConfig();
            if (networkConfig != null) {
                networkConfig.refresh(); // 刷新配置对象
                networkConfig.resetDnsServers(); // 刷新关联的 DNS 服务器列表
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting network config: " + e.getMessage());
            return;
        }
        VirtualNetworkConfig virtualNetworkConfig = getVirtualNetworkConfig(network.getNetworkId());
        if (virtualNetworkConfig == null) return;
        
        DNSMode dnsMode = DNSMode.fromInt(networkConfig.getDnsMode());

        switch (dnsMode) {
            case NETWORK_DNS:
                if (virtualNetworkConfig.getDns() == null) {
                    return;
                }
                builder.addSearchDomain(virtualNetworkConfig.getDns().getDomain());
                for (InetSocketAddress inetSocketAddress : virtualNetworkConfig.getDns().getServers()) {
                    InetAddress address = inetSocketAddress.getAddress();
                    if (address instanceof Inet4Address) {
                        builder.addDnsServer(address);
                    } else if ((address instanceof Inet6Address) && !this.disableIPv6) {
                        builder.addDnsServer(address);
                    }
                }
                break;
            case CUSTOM_DNS:
                for (com.zerotier.nexus.model.DnsServer dnsServer : networkConfig.getDnsServers()) {
                    try {
                        InetAddress byName = InetAddress.getByName(dnsServer.getNameserver());
                        if (byName instanceof Inet4Address) {
                            builder.addDnsServer(byName);
                        } else if ((byName instanceof Inet6Address) && !this.disableIPv6) {
                            builder.addDnsServer(byName);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Exception parsing DNS server: " + e, e);
                    }
                }
                break;
            case NO_DNS:
            default:
                // [新需求] 不干预模式：使用手机内部 DNS
                List<String> systemDns = NetworkInfoUtils.getSystemDnsServers(this);
                if (systemDns != null && !systemDns.isEmpty()) {
                    for (String dns : systemDns) {
                        try {
                            builder.addDnsServer(dns);
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to add system DNS: " + dns);
                        }
                    }
                    Log.i(TAG, "Ignore mode: Injected system DNS servers: " + systemDns);
                }
                break;
        }

        // [关键修复] 如果开启了漫游（全局代理）但没有任何 DNS 被配置，
        // 必须注入兜底 DNS，否则会导致所有域名解析失败，表现为“无法上网”。
        if (isRouteViaZeroTier) {
            // 统计已成功添加的 DNS 数量
            boolean hasAnyDns = false;
            if (dnsMode == DNSMode.NETWORK_DNS && virtualNetworkConfig.getDns() != null) {
                hasAnyDns = !virtualNetworkConfig.getDns().getServers().isEmpty();
            } else if (dnsMode == DNSMode.CUSTOM_DNS) {
                List<com.zerotier.nexus.model.DnsServer> servers = networkConfig.getDnsServers();
                hasAnyDns = servers != null && !servers.isEmpty();
            } else if (dnsMode == DNSMode.NO_DNS) {
                // 不干预模式下，由于我们已经注入了系统 DNS，所以视为已有 DNS，不再触发 8.8.8.8 兜底
                hasAnyDns = true; 
            }

            if (!hasAnyDns) {
                try {
                    builder.addDnsServer("8.8.8.8");
                    builder.addDnsServer("1.1.1.1");
                    Log.i(TAG, "Roaming enabled without DNS config or list is empty. Injected fallback DNS servers (8.8.8.8/1.1.1.1)");
                } catch (Exception e) {
                    Log.e(TAG, "Failed to add fallback DNS", e);
                }
            }
        }
    }

    /**
     * 入轨事件
     */
    @Subscribe
    public void onOrbitMoonEvent(OrbitMoonEvent event) {
        if (this.node == null) {
            Log.e(TAG, "Can't orbit network if ZeroTier isn't running");
            return;
        }
        // 入轨
        for (MoonOrbit moonOrbit : event.getMoonOrbits()) {
            Log.i(TAG, "Orbiting moon: " + Long.toHexString(moonOrbit.getMoonWorldId()));
            this.orbitNetwork(moonOrbit.getMoonWorldId(), moonOrbit.getMoonSeed());
        }
    }

    /**
     * 当前网络入轨 Moon
     *
     * @param moonWorldId Moon 节点地址
     * @param moonSeed    Moon 种子节点地址
     */
    public void orbitNetwork(Long moonWorldId, Long moonSeed) {
        if (this.node == null) {
            Log.e(TAG, "Can't orbit network if ZeroTier isn't running");
            return;
        }
        // 入轨
        ResultCode result = this.node.orbit(moonWorldId, moonSeed);
        if (result != ResultCode.RESULT_OK) {
            Log.e(TAG, "Failed to orbit " + Long.toHexString(moonWorldId));
            this.eventBus.post(new ErrorEvent(result));
        }
    }

    private android.app.Notification buildConnectedNotification(Network network) {
        int pendingIntentFlag = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) {
            pendingIntentFlag |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pendingIntent =
                PendingIntent.getActivity(this, 0,
                        new Intent(this, NetworkListActivity.class)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                                        | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        , pendingIntentFlag);
        String connectedText = "";
        android.content.SharedPreferences prefs = android.preference.PreferenceManager.getDefaultSharedPreferences(this);
        com.zerotier.nexus.model.NetworkConfig networkConfig = (network != null) ? network.getNetworkConfig() : null;

        boolean globalMasterOn = prefs.getBoolean("global_roaming_enabled", false);
        boolean isEnhancedRoaming = globalMasterOn && (networkConfig != null && networkConfig.getAllowExitRoaming());
        boolean isStandardDefaultRoute = (networkConfig != null && networkConfig.getRouteViaZeroTier());
        boolean isRouteViaZeroTier = globalMasterOn && (isEnhancedRoaming || isStandardDefaultRoute);

        if (isRouteViaZeroTier) {
            try {
                connectedText = getString(R.string.notification_connected_roaming);
            } catch (Exception e) {
                connectedText = "已连接 (漫游模式)";
            }
        } else {
            try {
                connectedText = getString(R.string.notification_text_connected, network.getNetworkIdStr());
            } catch (Exception e) {
                connectedText = "已连接至 ZeroTier";
            }
        }
        
        return new NotificationCompat.Builder(this, Constants.CHANNEL_ID)
                .setPriority(1)
                .setOngoing(true)
                .setOnlyAlertOnce(true) // 修复：防止流量更新时重复响铃
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getString(R.string.notification_title_connected))
                .setContentText(connectedText)
                .setColor(ContextCompat.getColor(getApplicationContext(), R.color.zerotier_orange))
                .setContentIntent(pendingIntent)
                .build();
    }

    public class ZeroTierBinder extends Binder {
        public ZeroTierBinder() {
        }

        public ZeroTierOneService getService() {
            return ZeroTierOneService.this;
        }
    }

    private void createNotificationChannel() {
        if (this.notificationManager == null) {
            this.notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        }
        if (Build.VERSION.SDK_INT >= 26) {
            String channelName = getString(R.string.channel_name);
            String description = getString(R.string.channel_description);
            android.app.NotificationChannel channel = new android.app.NotificationChannel(
                    Constants.CHANNEL_ID, channelName, NotificationManager.IMPORTANCE_HIGH); // 恢复：允许首显弹窗
            channel.setDescription(description);
            this.notificationManager.createNotificationChannel(channel);
        }
    }

    private android.app.Notification getStartingNotification() {
        int pendingIntentFlag = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) {
            pendingIntentFlag |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pendingIntent =
                PendingIntent.getActivity(this, 0,
                        new Intent(this, NetworkListActivity.class)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                                        | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        , pendingIntentFlag);
        return new NotificationCompat.Builder(this, Constants.CHANNEL_ID)
                .setPriority(1)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.notification_text_starting))
                .setColor(ContextCompat.getColor(getApplicationContext(), R.color.zerotier_orange))
                .setContentIntent(pendingIntent)
                .build();
    }

    private void updateRealtimeTraffic() {
        String extIf = NetworkInfoUtils.findDefaultInterface(this);
        String ztIf = NetworkInfoUtils.findZtInterface(this);
        
        if (extIf != null) lastWanSpeed = NetworkInfoUtils.getInterfaceSpeed(extIf);
        if (ztIf != null) lastZtSpeed = NetworkInfoUtils.getInterfaceSpeed(ztIf);
        
        // 如果有网络连接，不再触发通知栏刷新（根据最新需求移除）
        if (this.node != null) {
            // refreshNotification();
        }
    }

    private void refreshNotification() {
        try {
            // 获取当前活跃网络
            com.zerotier.nexus.model.DaoSession daoSession = ((com.zerotier.nexus.ZerotierFixApplication) getApplication()).getDaoSession();
            com.zerotier.nexus.model.Network network = daoSession.getNetworkDao().queryBuilder()
                    .where(com.zerotier.nexus.model.NetworkDao.Properties.LastActivated.eq(true))
                    .unique();
            if (network != null) {
                this.notificationManager.notify(ZT_NOTIFICATION_TAG, buildConnectedNotification(network));
            }
        } catch (Exception ignored) {}
    }

    public static String getLastWanSpeed() { return lastWanSpeed; }
    public static String getLastZtSpeed() { return lastZtSpeed; }
}

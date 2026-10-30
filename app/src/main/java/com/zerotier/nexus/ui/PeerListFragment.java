package com.zerotier.nexus.ui;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import java.util.HashMap;
import java.util.Map;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.snackbar.BaseTransientBottomBar;
import com.google.android.material.snackbar.Snackbar;
import androidx.core.content.ContextCompat;
import com.zerotier.sdk.Peer;
import com.zerotier.sdk.PeerPhysicalPath;
import com.zerotier.sdk.PeerRole;

import com.zerotier.nexus.R;
import com.zerotier.nexus.events.PeerInfoReplyEvent;
import com.zerotier.nexus.events.PeerInfoRequestEvent;
import com.zerotier.nexus.util.StringUtils;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import lombok.ToString;

/**
 * Peer 信息展示 fragment
 *
 * @author kaaass
 */
public class PeerListFragment extends Fragment {

    public static final String TAG = "PeerListFragment";

    private final List<Peer> peerList = new ArrayList<>();
    private final Map<Long, String> nodeIdToManagedIp = new HashMap<>();

    private EventBus eventBus = null;

    private RecyclerViewAdapter recyclerViewAdapter = null;

    private RecyclerView recyclerView = null;

    private SwipeRefreshLayout swipeRefreshLayout = null;

    private View emptyView = null;
    private final Handler autoRefreshHandler = new Handler(Looper.getMainLooper());
    private final Runnable autoRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (eventBus != null) {
                android.util.Log.d("PeerListFragment", "Auto-refresh: Requesting PeerInfo...");
                eventBus.post(new PeerInfoRequestEvent());
            }
            autoRefreshHandler.postDelayed(this, 5000);
        }
    };

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

    public PeerListFragment() {
    }

    @Override
    public void onStart() {
        super.onStart();
        if (this.eventBus == null) {
            this.eventBus = EventBus.getDefault();
        }
        if (!this.eventBus.isRegistered(this)) {
            this.eventBus.register(this);
        }
        // 启动自动刷新
        android.util.Log.d("PeerListFragment", "onStart: Starting auto-refresh scheduler");
        autoRefreshHandler.removeCallbacks(autoRefreshRunnable);
        autoRefreshHandler.post(autoRefreshRunnable); // 立即执行一次
    }

    @Override
    public void onStop() {
        super.onStop();
        // 停止自动刷新
        autoRefreshHandler.removeCallbacks(autoRefreshRunnable);
        if (this.eventBus != null && this.eventBus.isRegistered(this)) {
            this.eventBus.unregister(this);
        }
    }

    /**
     * Peer 类型转为文本
     */
    public static int peerRoleToString(PeerRole peerRole) {
        if (peerRole == null) return R.string.unknown;
        switch (peerRole) {
            case PEER_ROLE_PLANET:
                return R.string.peer_role_planet;
            case PEER_ROLE_LEAF:
                return R.string.peer_role_leaf;
            case PEER_ROLE_MOON:
                return R.string.peer_role_moon;
        }
        return R.string.unknown;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        this.eventBus = EventBus.getDefault();
        View view = inflater.inflate(R.layout.fragment_peer_list, container, false);

        // 空列表提示
        this.emptyView = view.findViewById(R.id.no_data);

        // 设置适配器
        this.recyclerView = view.findViewById(R.id.list_peer);
        Context context = this.recyclerView.getContext();
        this.recyclerView.setLayoutManager(new LinearLayoutManager(context));
        this.recyclerViewAdapter = new RecyclerViewAdapter(this.peerList);
        this.recyclerViewAdapter.registerAdapterDataObserver(checkIfEmptyObserver);
        this.recyclerView.setAdapter(this.recyclerViewAdapter);

        // 设置下拉刷新
        this.swipeRefreshLayout = view.findViewById(R.id.swipe_refresh_peer);
        this.swipeRefreshLayout.setOnRefreshListener(this::onRefresh);
        this.swipeRefreshLayout.setColorSchemeResources(R.color.dashboard_accent_green);

        // 更新数据
        this.onRefresh();

        return view;
    }

    /**
     * 刷新列表
     */
    public void onRefresh() {
        this.eventBus.post(new PeerInfoRequestEvent());
        // 超时自动重置刷新状态
        new Thread(() -> {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ignored) {
            }
            Activity activity = getActivity();
            if (activity != null && this.swipeRefreshLayout != null) {
                activity.runOnUiThread(() -> this.swipeRefreshLayout.setRefreshing(false));
            }
        }).start();
    }

    /**
     * 收到 peer 信息
     */
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onPeerInfoReplyEvent(PeerInfoReplyEvent event) {
        Peer[] peers = event.getPeers();
        int peerCount = (peers == null ? 0 : peers.length);
        android.util.Log.i("PeerListFragment", "onPeerInfoReplyEvent: RECEIVED TOTAL PEERS = " + peerCount);
        if (peers != null) {
            for (int i = 0; i < peers.length; i++) {
                Peer p = peers[i];
                android.util.Log.d("PeerListFragment", String.format("  [%d] ADDR=%s ROLE=%s PATHS=%d LAT=%d",
                        i, Long.toHexString(p.getAddress()), p.getRole(), 
                        (p.getPaths() != null ? p.getPaths().length : 0), p.getLatency()));
            }
        }
        
        if (peers == null) {
            Snackbar.make(requireView(), R.string.fail_retrieve_peer_list, BaseTransientBottomBar.LENGTH_LONG).show();
            return;
        }
        // 更新数据列表
        this.peerList.clear();
        Collections.addAll(this.peerList, peers);
        
        // 更新虚拟 IP 映射
        this.nodeIdToManagedIp.clear();
        if (event.getNodeToIp() != null) {
            this.nodeIdToManagedIp.putAll(event.getNodeToIp());
        }
        
        // 排序逻辑：PLANET > MOON > LEAF (还原用户习惯)
        Collections.sort(this.peerList, (p1, p2) -> {
            // 安全检查
            if (p1 == null || p2 == null) return 0;
            PeerRole role1 = p1.getRole();
            PeerRole role2 = p2.getRole();
            
            // 定义权重：PLANET = 10, MOON = 5, LEAF = 1, UNKNOWN = 0
            int w1 = (role1 == PeerRole.PEER_ROLE_PLANET) ? 10 : (role1 == PeerRole.PEER_ROLE_MOON ? 5 : (role1 == PeerRole.PEER_ROLE_LEAF ? 1 : 0));
            int w2 = (role2 == PeerRole.PEER_ROLE_PLANET) ? 10 : (role2 == PeerRole.PEER_ROLE_MOON ? 5 : (role2 == PeerRole.PEER_ROLE_LEAF ? 1 : 0));
            
            if (w1 != w2) {
                // 权重大的排在前面 (降序)
                return Integer.compare(w2, w1);
            }
            
            // 同角色按 Node ID 排序
            return Long.compare(p1.getAddress(), p2.getAddress());
        });
        
        this.recyclerViewAdapter.notifyDataSetChanged();
        this.swipeRefreshLayout.setRefreshing(false);
    }

    /**
     * Peer 列表适配器
     */
    public class RecyclerViewAdapter extends RecyclerView.Adapter<RecyclerViewAdapter.ViewHolder> {

        private final List<Peer> mValues;

        public RecyclerViewAdapter(List<Peer> items) {
            mValues = items;
        }

        @NonNull
        @Override
        public RecyclerViewAdapter.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.list_item_peer, parent, false);
            return new RecyclerViewAdapter.ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(final RecyclerViewAdapter.ViewHolder holder, int position) {
            Peer peer = mValues.get(position);
            holder.mItem = peer;
            holder.mAddress.setText(Long.toHexString(peer.getAddress()));
            holder.mRole.setText(peerRoleToString(peer.getRole()));
            // 客户端版本
            String clientVersion = getString(R.string.unknown_version);
            if (peer.getVersionMajor() > 0) {
                clientVersion = StringUtils.peerVersionString(peer);
            }
            holder.mVersion.setText(clientVersion);
            // 延迟
            long latency = peer.getLatency();
            holder.mLatency.setText(String.format(getString(R.string.peer_lat), latency));
            if (latency > 0 && latency < 100) {
                holder.mLatency.setTextColor(ContextCompat.getColor(getContext(), R.color.dashboard_accent_green));
            } else if (latency >= 100 && latency < 300) {
                holder.mLatency.setTextColor(ContextCompat.getColor(getContext(), R.color.zerotier_orange));
            } else {
                holder.mLatency.setTextColor(ContextCompat.getColor(getContext(), R.color.dashboard_accent_red));
            }
            // 路径展示逻辑：优先显示 Preferred（首选），若无则显示最近活跃的物理路径
            PeerPhysicalPath bestPath = null;
            if (peer.getPaths() != null && peer.getPaths().length > 0) {
                long maxLastReceive = -1;
                for (PeerPhysicalPath path : peer.getPaths()) {
                    if (path.isPreferred()) {
                        bestPath = path;
                        break;
                    }
                    if (path.getLastReceive() > maxLastReceive) {
                        maxLastReceive = path.getLastReceive();
                        bestPath = path;
                    }
                }
            }
            
            String strPathDisplay;
            if (bestPath != null) {
                strPathDisplay = StringUtils.toString(bestPath.getAddress());
                // 如果不是首选路径，可以加一个标识说明正在尝试直连
                if (!bestPath.isPreferred()) {
                    strPathDisplay += " (Handshaking)"; // 正在握手/尝试直连
                }
            } else {
                strPathDisplay = getString(R.string.peer_relay);
            }
            holder.mPath.setText(strPathDisplay);

            // 显示虚拟 IP (Managed IP)
            String managedIp = nodeIdToManagedIp.get(peer.getAddress());
            if (managedIp != null) {
                holder.mManagedIp.setVisibility(View.VISIBLE);
                holder.mManagedIp.setText(managedIp);
            } else {
                holder.mManagedIp.setVisibility(View.GONE);
            }
        }

        @Override
        public int getItemCount() {
            return mValues.size();
        }

        @ToString
        public class ViewHolder extends RecyclerView.ViewHolder {
            public final View mView;
            public final TextView mAddress;
            public final TextView mRole;
            public final TextView mVersion;
            public final TextView mLatency;
            public final TextView mPath;
            public final TextView mManagedIp;
            public Peer mItem;

            public ViewHolder(View view) {
                super(view);
                mView = view;
                mAddress = view.findViewById(R.id.list_peer_addr);
                mRole = view.findViewById(R.id.list_peer_role);
                mVersion = view.findViewById(R.id.list_peer_ver);
                mLatency = view.findViewById(R.id.list_peer_lat);
                mPath = view.findViewById(R.id.list_peer_path);
                mManagedIp = view.findViewById(R.id.list_peer_managed_ip);
            }
        }
    }
}

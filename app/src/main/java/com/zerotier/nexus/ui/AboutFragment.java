package com.zerotier.nexus.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;

import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import java.io.OutputStream;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import com.zerotier.nexus.BuildConfig;
import com.zerotier.nexus.R;
import com.zerotier.nexus.events.NodeStatusEvent;
import com.zerotier.nexus.events.NodeStatusRequestEvent;
import com.zerotier.nexus.ui.view.BigEasterEggAnimationView;
import com.zerotier.nexus.util.Constants;
import com.zerotier.nexus.util.StringUtils;

import org.apache.commons.io.FileUtils;
import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

/**
 * 关于页面 fragment
 *
 * @author kaaass
 */
public class AboutFragment extends Fragment {

    private TextView kernelVersionView;
    private TextView appVersionView;
    private ImageView logoView;
    private ImageView easterEggIndicator;
    private View btnNexusPlanet;
    private View btnJoinEasterEggNetwork;
    private BigEasterEggAnimationView animationView;

    private int clickCount = 0;
    private long lastClickTime = 0;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_about, container, false);

        kernelVersionView = view.findViewById(R.id.about_kernel_version);
        appVersionView = view.findViewById(R.id.about_app_version);
        logoView = view.findViewById(R.id.about_logo);
        easterEggIndicator = view.findViewById(R.id.easter_egg_indicator);
        btnNexusPlanet = view.findViewById(R.id.btn_nexus_planet_easter);
        btnJoinEasterEggNetwork = view.findViewById(R.id.btn_join_network_easter);
        animationView = view.findViewById(R.id.about_easter_egg_anim);
        
        ImageView alipayQr = view.findViewById(R.id.donate_alipay_qr);
        ImageView wechatQr = view.findViewById(R.id.donate_wechat_qr);
        ImageView wechatFriendQr = view.findViewById(R.id.qr_wechat_friend);
        
        TextView licenseGpl = view.findViewById(R.id.about_license_nexus_gpl);
        TextView licenseMpl = view.findViewById(R.id.about_license_zt_mpl);
        TextView licenseOther = view.findViewById(R.id.about_license_zt_other);

        if (alipayQr != null) {
            alipayQr.setOnClickListener(v -> showQrDialog(R.drawable.donate_alipay, "Alipay"));
        }
        if (wechatQr != null) {
            wechatQr.setOnClickListener(v -> showQrDialog(R.drawable.donate_wechat, "WeChat"));
        }
        if (wechatFriendQr != null) {
            wechatFriendQr.setOnClickListener(v -> showQrDialog(R.drawable.qr_wechat_friend, "WeChat Contact"));
        }
        
        if (licenseGpl != null) {
            licenseGpl.setOnClickListener(v -> openLicense(LicenseFragment.newInstance("license_nexus_gpl.txt", "Zerotier Nexus 开源协议 (GPLv2)")));
        }
        if (licenseMpl != null) {
            licenseMpl.setOnClickListener(v -> openLicense(LicenseFragment.newInstance("license_zt_mpl.txt", "ZeroTier 核心协议 (MPLv2)")));
        }
        if (licenseOther != null) {
            licenseOther.setOnClickListener(v -> openLicense(LicenseFragment.newInstance("license_zt_other.txt", "ZeroTier 附加声明")));
        }

        // 设置应用版本
        appVersionView.setText(getString(R.string.about_app_version_label) + ": " + BuildConfig.VERSION_NAME);

        // 恢复缓存的内核版本
        android.content.SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
        String cachedVersion = prefs.getString(Constants.PREF_CACHED_KERNEL_VERSION, null);
        setKernelVersionText(cachedVersion);

        setupEasterEgg();

        return view;
    }

    private void setupEasterEgg() {
        logoView.setOnClickListener(v -> {
            long now = System.currentTimeMillis();
            if (now - lastClickTime < 500) {
                clickCount++;
            } else {
                clickCount = 1;
            }
            lastClickTime = now;

            if (clickCount >= 10) {
                if (easterEggIndicator.getVisibility() != View.VISIBLE) {
                    Toast.makeText(requireContext(), R.string.easter_egg_title, Toast.LENGTH_SHORT).show();
                    easterEggIndicator.setVisibility(View.VISIBLE);
                    btnNexusPlanet.setVisibility(View.VISIBLE);
                    btnJoinEasterEggNetwork.setVisibility(View.VISIBLE);
                }
            }
        });

        easterEggIndicator.setOnClickListener(v -> {
            animationView.startEasterEggAnimation(null);
        });

        btnNexusPlanet.setOnClickListener(v -> {
            applyNexusPlanet();
        });

        btnJoinEasterEggNetwork.setOnClickListener(v -> {
            joinEasterEggNetwork();
        });
    }

    private void applyNexusPlanet() {
        try {
            InputStream in = requireContext().getAssets().open("planet");
            File dest = new File(requireContext().getFilesDir(), Constants.FILE_CUSTOM_PLANET);
            FileUtils.copyInputStreamToFile(in, dest);

            androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext())
                    .edit()
                    .putBoolean(Constants.PREF_PLANET_USE_CUSTOM, true)
                    .apply();

            Toast.makeText(requireContext(), R.string.set_planet_succ, Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Toast.makeText(requireContext(), R.string.cannot_open_planet, Toast.LENGTH_SHORT).show();
        }
    }

    private void joinEasterEggNetwork() {
        try {
            String idStr = "645ae69a72a847d5";
            long idLong = com.zerotier.nexus.util.NetworkIdUtils.hexStringToLong(idStr);
            
            com.zerotier.nexus.model.DaoSession daoSession = ((com.zerotier.nexus.ZerotierFixApplication) requireActivity().getApplication()).getDaoSession();
            com.zerotier.nexus.model.NetworkDao networkDao = daoSession.getNetworkDao();
            
            if (!networkDao.queryBuilder()
                    .where(com.zerotier.nexus.model.NetworkDao.Properties.NetworkId.eq(idLong), new org.greenrobot.greendao.query.WhereCondition[0])
                    .build().forCurrentThread().list().isEmpty()) {
                Toast.makeText(getContext(), "彩蛋网络已在列表中", Toast.LENGTH_SHORT).show();
                return;
            }

            com.zerotier.nexus.model.Network network = new com.zerotier.nexus.model.Network();
            network.setNetworkId(idLong);
            network.setNetworkIdStr(idStr);
            
            com.zerotier.nexus.model.NetworkConfig config = new com.zerotier.nexus.model.NetworkConfig();
            config.setId(idLong);
            android.content.SharedPreferences prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext());
            config.setRouteViaZeroTier(prefs.getBoolean("network_as_exit", false));
            config.setDnsMode(0);
            config.setUseCustomDNS(false);
            config.setAllowMobileData(true);
            config.setDisableIPv6(false);
            
            daoSession.getNetworkConfigDao().insert(config);
            network.setNetworkConfigId(idLong);
            networkDao.insert(network);
            
            Toast.makeText(getContext(), "彩蛋: 成功加入测试网络 " + idStr, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(getContext(), "加入失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void showQrDialog(int drawableResId, String type) {
        ImageView imageView = new ImageView(getContext());
        imageView.setImageResource(drawableResId);
        imageView.setPadding(32, 32, 32, 32);

        new MaterialAlertDialogBuilder(requireContext(), R.style.Theme_ZerotierFix_DashboardDialog)
                .setTitle("Donate (" + type + ")")
                .setView(imageView)
                .setPositiveButton("保存到相册", (dialog, which) -> {
                    saveImageToGallery(drawableResId, type);
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void saveImageToGallery(int drawableResId, String type) {
        try {
            Drawable drawable = androidx.core.content.ContextCompat.getDrawable(requireContext(), drawableResId);
            if (!(drawable instanceof BitmapDrawable)) return;
            Bitmap bitmap = ((BitmapDrawable) drawable).getBitmap();

            String filename = "Donate_" + type + "_" + System.currentTimeMillis() + ".png";
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, filename);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES);
                values.put(MediaStore.Images.Media.IS_PENDING, 1);
            }

            Uri uri = requireContext().getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri != null) {
                try (OutputStream out = requireContext().getContentResolver().openOutputStream(uri)) {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    values.clear();
                    values.put(MediaStore.Images.Media.IS_PENDING, 0);
                    requireContext().getContentResolver().update(uri, values, null, null);
                }
                Toast.makeText(requireContext(), "保存成功", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(requireContext(), "保存失败", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(requireContext(), "保存出错: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        EventBus.getDefault().register(this);
        // 请求节点状态以刷新版本核心
        EventBus.getDefault().post(new NodeStatusRequestEvent());
    }

    @Override
    public void onStop() {
        super.onStop();
        EventBus.getDefault().unregister(this);
    }

    private void setKernelVersionText(String version) {
        if (version == null || version.isEmpty()) {
            version = "---";
        }
        kernelVersionView.setText(getString(R.string.about_kernel_version_label) + ": " + version);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onNodeStatus(NodeStatusEvent event) {
        if (event.getClientVersion() != null) {
            String clientVersionStr = StringUtils.toString(event.getClientVersion());
            setKernelVersionText(clientVersionStr);
        }
    }

    private void openLicense(Fragment licenseFragment) {
        if (getActivity() instanceof NetworkListActivity) {
            ((NetworkListActivity) getActivity()).switchFragment(licenseFragment, "License", true);
        }
    }
}

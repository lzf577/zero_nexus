package com.zerotier.nexus.ui;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.switchmaterial.SwitchMaterial;

import com.zerotier.nexus.R;
import com.zerotier.nexus.ZerotierFixApplication;
import com.zerotier.nexus.model.DaoSession;
import com.zerotier.nexus.model.Network;
import com.zerotier.nexus.model.NetworkConfig;
import com.zerotier.nexus.model.NetworkDao;
import com.zerotier.nexus.service.ZeroTierOneService;
import com.zerotier.nexus.util.Constants;
import com.zerotier.nexus.util.FileUtil;
import com.zerotier.nexus.util.NetworkIdUtils;

import org.apache.commons.io.FileUtils;
import org.greenrobot.greendao.query.WhereCondition;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Arrays;

public class JoinNetworkFragment extends Fragment {

    private static final String TAG = "JoinNetworkFragment";
    private static final byte[] PLANET_FILE_HEADER = {1, 0};

    private SharedPreferences prefs;
    private EditText etNetworkId;
    private MaterialButton btnJoin;
    private RadioGroup rgDnsMode;
    private View tilCustomDns;
    private EditText etCustomDns;
    private SwitchMaterial swAllowMobile;
    private SwitchMaterial swDisableIpv6;

    private ActivityResultLauncher<Intent> planetFileSelectLauncher;

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        this.planetFileSelectLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == -1 && result.getData() != null) {
                        Uri uri = result.getData().getData();
                        if (uri != null) {
                            handlePlanetFileUri(uri);
                        }
                    }
                });
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_join_network, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());

        etNetworkId = view.findViewById(R.id.et_network_id);
        btnJoin = view.findViewById(R.id.btn_join);
        rgDnsMode = view.findViewById(R.id.rg_dns_mode);
        tilCustomDns = view.findViewById(R.id.til_custom_dns);
        etCustomDns = view.findViewById(R.id.et_custom_dns);
        swAllowMobile = view.findViewById(R.id.sw_allow_mobile);
        swDisableIpv6 = view.findViewById(R.id.sw_disable_ipv6);

        setupListeners(view);
    }

    private void setupListeners(View view) {
        etNetworkId.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                btnJoin.setEnabled(s.length() == 16);
            }
        });

        btnJoin.setOnClickListener(v -> handleJoinNetwork());

        rgDnsMode.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.rb_dns_custom) {
                tilCustomDns.setVisibility(View.VISIBLE);
            } else {
                tilCustomDns.setVisibility(View.GONE);
            }
        });

        view.findViewById(R.id.btn_import_planet_file).setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.setType("*/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            planetFileSelectLauncher.launch(intent);
        });

        view.findViewById(R.id.btn_import_planet_url).setOnClickListener(v -> showPlanetUrlDialog());
    }

    private void handleJoinNetwork() {
        String idStr = etNetworkId.getText().toString().toLowerCase().trim();
        if (idStr.length() != 16) {
            Toast.makeText(getContext(), "无效的网络 ID", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            long idLong = NetworkIdUtils.hexStringToLong(idStr);
            DaoSession daoSession = ((ZerotierFixApplication) requireActivity().getApplication()).getDaoSession();
            NetworkDao networkDao = daoSession.getNetworkDao();

            // Check if already exists
            if (!networkDao.queryBuilder()
                    .where(NetworkDao.Properties.NetworkId.eq(idLong), new WhereCondition[0])
                    .build().list().isEmpty()) {
                Toast.makeText(getContext(), "该网络已存在于列表中", Toast.LENGTH_SHORT).show();
                return;
            }

            // Create Network
            Network network = new Network();
            network.setNetworkId(idLong);
            network.setNetworkIdStr(idStr);
            network.setConnected(false);
            network.setLastActivated(true);

            // Create Config based on UI selections
            NetworkConfig config = new NetworkConfig();
            config.setId(idLong);
            
            // DNS Mode: 1=None (rb_dns_ignore), 2=Network (rb_dns_network), 3=Custom (rb_dns_custom)
            // Wait, looking at SettingsDashboardFragment: config.setDnsMode(0) was No DNS.
            // Let's use: 0 = No DNS, 1 = Network DNS, 2 = Custom DNS
            int checkedId = rgDnsMode.getCheckedRadioButtonId();
            if (checkedId == R.id.rb_dns_ignore) {
                config.setDnsMode(0);
            } else if (checkedId == R.id.rb_dns_network) {
                config.setDnsMode(1);
            } else if (checkedId == R.id.rb_dns_custom) {
                config.setDnsMode(2);
                config.setUseCustomDNS(true);
                // Save custom DNS server
                String dnsStr = etCustomDns.getText().toString().trim();
                if (!dnsStr.isEmpty()) {
                    com.zerotier.nexus.model.DnsServer dnsServer = new com.zerotier.nexus.model.DnsServer();
                    dnsServer.setNetworkId(idLong);
                    dnsServer.setNameserver(dnsStr);
                    daoSession.getDnsServerDao().insert(dnsServer);
                }
            }

            config.setAllowMobileData(swAllowMobile.isChecked());
            config.setDisableIPv6(swDisableIpv6.isChecked());
            config.setRouteViaZeroTier(false); // Default false for new networks

            daoSession.getNetworkConfigDao().insert(config);
            network.setNetworkConfigId(idLong);
            networkDao.insert(network);

            Toast.makeText(getContext(), "已加入网络: " + idStr, Toast.LENGTH_SHORT).show();
            
            // Start service to join
            Intent intent = new Intent(getActivity(), ZeroTierOneService.class);
            intent.putExtra(ZeroTierOneService.ZT1_NETWORK_ID, idLong);
            requireActivity().startService(intent);

            // Switch back to Node List
            if (getActivity() instanceof NetworkListActivity) {
                ((NetworkListActivity) getActivity()).onBackPressed();
            }

        } catch (Exception e) {
            Log.e(TAG, "Error joining network", e);
            Toast.makeText(getContext(), "加入失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void handlePlanetFileUri(Uri uri) {
        try (InputStream in = requireContext().getContentResolver().openInputStream(uri)) {
            File temp = FileUtil.tempFile(requireContext());
            FileUtils.copyInputStreamToFile(in, temp);
            if (verifyAndMovePlanet(temp)) {
                prefs.edit().putBoolean(Constants.PREF_PLANET_USE_CUSTOM, true).apply();
                Snackbar.make(requireView(), "Planet 文件导入成功", Snackbar.LENGTH_LONG).show();
            } else {
                Toast.makeText(getContext(), "无效的 Planet 文件格式", Toast.LENGTH_SHORT).show();
            }
        } catch (IOException e) {
            Toast.makeText(getContext(), "读取文件失败", Toast.LENGTH_SHORT).show();
        } finally {
            FileUtil.clearTempFile(requireContext());
        }
    }

    private boolean verifyAndMovePlanet(File temp) {
        byte[] header = new byte[PLANET_FILE_HEADER.length];
        try (FileInputStream in = new FileInputStream(temp)) {
            if (in.read(header) != PLANET_FILE_HEADER.length || !Arrays.equals(header, PLANET_FILE_HEADER)) {
                return false;
            }
        } catch (IOException e) {
            return false;
        }
        File dest = new File(requireActivity().getFilesDir(), Constants.FILE_CUSTOM_PLANET);
        return temp.renameTo(dest);
    }

    private void showPlanetUrlDialog() {
        View view = LayoutInflater.from(getContext()).inflate(R.layout.dialog_planet_url, null);
        EditText etUrl = view.findViewById(R.id.planet_url);

        new MaterialAlertDialogBuilder(requireContext(), R.style.Theme_ZerotierFix_DashboardDialog)
                .setView(view)
                .setTitle("从 URL 导入 Planet")
                .setPositiveButton("下载并导入", (dialog, which) -> {
                    String urlStr = etUrl.getText().toString();
                    try {
                        URL url = new URL(urlStr);
                        downloadPlanet(url);
                    } catch (MalformedURLException e) {
                        Toast.makeText(getContext(), "无效的 URL 格式", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void downloadPlanet(URL url) {
        new Thread(() -> {
            try {
                File temp = FileUtil.tempFile(requireContext());
                FileUtils.copyURLToFile(url, temp, 5000, 30000);
                if (verifyAndMovePlanet(temp)) {
                    prefs.edit().putBoolean(Constants.PREF_PLANET_USE_CUSTOM, true).apply();
                    getActivity().runOnUiThread(() -> 
                        Snackbar.make(requireView(), "Planet 下载并导入成功", Snackbar.LENGTH_LONG).show());
                } else {
                    getActivity().runOnUiThread(() -> 
                        Toast.makeText(getContext(), "Planet 格式校验失败", Toast.LENGTH_SHORT).show());
                }
            } catch (IOException e) {
                getActivity().runOnUiThread(() -> 
                    Toast.makeText(getContext(), "下载失败", Toast.LENGTH_SHORT).show());
            } finally {
                FileUtil.clearTempFile(requireContext());
            }
        }).start();
    }
}

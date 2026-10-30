package com.zerotier.nexus.ui;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.BaseTransientBottomBar;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.switchmaterial.SwitchMaterial;

import com.zerotier.nexus.R;
import com.zerotier.nexus.ZerotierFixApplication;
import com.zerotier.nexus.service.ZeroTierOneService;
import com.zerotier.nexus.util.Constants;
import com.zerotier.nexus.events.OrbitMoonEvent;
import com.zerotier.nexus.model.DaoSession;
import com.zerotier.nexus.model.MoonOrbit;
import com.zerotier.nexus.model.MoonOrbitDao;
import com.zerotier.nexus.util.DatabaseUtils;
import com.zerotier.nexus.util.FileUtil;

import org.apache.commons.io.FileUtils;
import org.greenrobot.eventbus.EventBus;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;

import com.zerotier.nexus.model.Network;
import com.zerotier.nexus.model.NetworkConfig;
import com.zerotier.nexus.model.NetworkDao;
import com.zerotier.nexus.util.NetworkIdUtils;
import org.greenrobot.greendao.query.WhereCondition;
import android.text.Editable;
import android.text.TextWatcher;
import com.google.android.material.button.MaterialButton;

/**
 * A modern replacement for the traditional settings page.
 */
public class SettingsDashboardFragment extends Fragment implements SharedPreferences.OnSharedPreferenceChangeListener {

    private static final String TAG = "SettingsDashboard";
    private static final byte[] PLANET_FILE_HEADER = {1, 0};
    private static final byte[] MOON_FILE_HEADER = {0x7f};
    private static final int PLANET_DOWNLOAD_TIMEOUT = 30000;
    public static final int PLANET_DOWNLOAD_CONN_TIMEOUT = 5 * 1000;

    private SharedPreferences prefs;
    private SwitchMaterial switchPlanetCustom;
    private View btnSetPlanet;
    
    private EditText editJoinId;
    private MaterialButton btnJoinNetwork;

    private Dialog planetDialog = null;
    private Dialog loadingDialog = null;
    private ActivityResultLauncher<Intent> planetFileSelectLauncher = null;

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        this.planetFileSelectLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), (activityResult) -> {
            if (activityResult.getResultCode() == -1 && activityResult.getData() != null) {
                Uri uriData = activityResult.getData().getData();
                if (uriData == null) return;
                try (InputStream in = requireContext().getContentResolver().openInputStream(uriData)) {
                    FileUtils.copyInputStreamToFile(in, FileUtil.tempFile(requireContext()));
                    if (dealTempPlanetFile()) {
                        Snackbar.make(requireView(), R.string.set_planet_succ, BaseTransientBottomBar.LENGTH_LONG).show();
                        closePlanetDialog();
                    } else {
                        Toast.makeText(getContext(), R.string.planet_wrong_format, Toast.LENGTH_LONG).show();
                    }
                } catch (IOException e) {
                    Toast.makeText(getContext(), R.string.cannot_open_planet, Toast.LENGTH_LONG).show();
                } finally {
                    FileUtil.clearTempFile(requireContext());
                }
            }
        });

    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings_dashboard, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
        
        initViews(view);
        loadSettings();
        setupListeners();
        updatePlanetStatus();
    }

    private void initViews(View v) {
        switchPlanetCustom = v.findViewById(R.id.switch_planet_custom);
        btnSetPlanet = v.findViewById(R.id.btn_set_planet);
        editJoinId = v.findViewById(R.id.join_network_edit_text);
        btnJoinNetwork = v.findViewById(R.id.button_join_network);
    }

    private void loadSettings() {
        switchPlanetCustom.setChecked(prefs.getBoolean(Constants.PREF_PLANET_USE_CUSTOM, false));
    }

    private void setupListeners() {
        switchPlanetCustom.setOnCheckedChangeListener((btn, isChecked) -> {
            prefs.edit().putBoolean(Constants.PREF_PLANET_USE_CUSTOM, isChecked).apply();
            updatePlanetStatus();
            if (isChecked && customPlanetFileNotExit()) {
                showPlanetDialog();
            }
        });

        btnSetPlanet.setOnClickListener(v -> showPlanetDialog());

        editJoinId.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                btnJoinNetwork.setEnabled(s.length() == 16);
            }
        });
        btnJoinNetwork.setOnClickListener(v -> handleJoinNetwork());
    }

    private void handleJoinNetwork() {
        try {
            String idStr = editJoinId.getText().toString();
            long idLong = NetworkIdUtils.hexStringToLong(idStr);
            
            DaoSession daoSession = ((ZerotierFixApplication) requireActivity().getApplication()).getDaoSession();
            NetworkDao networkDao = daoSession.getNetworkDao();
            
            // 查重
            if (!networkDao.queryBuilder()
                    .where(NetworkDao.Properties.NetworkId.eq(idLong), new WhereCondition[0])
                    .build().forCurrentThread().list().isEmpty()) {
                Toast.makeText(getContext(), "该网络已在列表中", Toast.LENGTH_SHORT).show();
                return;
            }

            Log.d(TAG, "Joining network: " + idStr);
            
            // 插入网络基本信息
            Network network = new Network();
            network.setNetworkId(idLong);
            network.setNetworkIdStr(idStr);
            
            // 插入配置 (默认开启全局漫游)
            NetworkConfig config = new NetworkConfig();
            config.setId(idLong);
            config.setRouteViaZeroTier(prefs.getBoolean("network_as_exit", false));
            config.setDnsMode(0); // 默认无 DNS
            config.setUseCustomDNS(false);
            config.setAllowMobileData(true); // 默认允许移动网络
            config.setDisableIPv6(false);   // 默认启用 IPv6
            
            daoSession.getNetworkConfigDao().insert(config);
            network.setNetworkConfigId(idLong);
            networkDao.insert(network);
            
            Toast.makeText(getContext(), "成功加入网络, 代码: " + idStr, Toast.LENGTH_SHORT).show();
            editJoinId.setText("");
            
            // 返回主页 (列表应该已刷新)
            requireActivity().finish();
            
        } catch (Exception e) {
            Log.e(TAG, "Error joining network", e);
            Toast.makeText(getContext(), "加入失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }


    private void applyGreyscaleEffect(View view, boolean enabled) {
        view.setEnabled(enabled);
        view.setAlpha(enabled ? 1.0f : 0.35f);
        
        ColorMatrix matrix = new ColorMatrix();
        if (!enabled) {
            matrix.setSaturation(0);
        }
        ColorMatrixColorFilter filter = new ColorMatrixColorFilter(matrix);
        
        if (view instanceof ViewGroup) {
            applyFilterRecursive((ViewGroup) view, filter);
        } else if (view instanceof ImageView) {
            ((ImageView) view).setColorFilter(filter);
        }
    }

    private void applyFilterRecursive(ViewGroup container, ColorMatrixColorFilter filter) {
        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            if (child instanceof ImageView) {
                ((ImageView) child).setColorFilter(filter);
            } else if (child instanceof ViewGroup) {
                applyFilterRecursive((ViewGroup) child, filter);
            }
        }
    }

    private void updatePlanetStatus() {
        boolean useCustom = switchPlanetCustom.isChecked();
        applyGreyscaleEffect(btnSetPlanet, useCustom);
    }

    private void showPlanetDialog() {
        View view = LayoutInflater.from(getContext()).inflate(R.layout.dialog_custom_planet_select, null);
        view.findViewById(R.id.from_file).setOnClickListener(v -> showPlanetFromFileDialog());
        view.findViewById(R.id.from_url).setOnClickListener(v -> showPlanetFromUrlDialog());
        view.findViewById(R.id.from_nexus).setOnClickListener(v -> {
            applyNexusPlanet();
            if (planetDialog != null) planetDialog.dismiss();
        });

        planetDialog = new MaterialAlertDialogBuilder(requireContext(), R.style.Theme_ZerotierFix_DashboardDialog)
                .setView(view)
                .setTitle(R.string.load_planet_file)
                .setOnCancelListener(dialog -> {
                    if (customPlanetFileNotExit()) switchPlanetCustom.setChecked(false);
                })
                .create();
        planetDialog.show();
    }

    private void showPlanetFromFileDialog() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        this.planetFileSelectLauncher.launch(intent);
    }

    private void showPlanetFromUrlDialog() {
        View view = LayoutInflater.from(getContext()).inflate(R.layout.dialog_planet_url, null);
        EditText editText = view.findViewById(R.id.planet_url);

        new MaterialAlertDialogBuilder(requireContext(), R.style.Theme_ZerotierFix_DashboardDialog)
                .setView(view)
                .setTitle(R.string.import_via_url)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String urlStr = editText.getText().toString();
                    try {
                        URL fileUrl = new URL(urlStr);
                        if (planetDialog != null) planetDialog.dismiss();
                        showLoadingDialog(R.string.downloading);
                        new Thread(() -> downloadPlanet(fileUrl)).start();
                    } catch (MalformedURLException e) {
                        Toast.makeText(getContext(), R.string.wrong_url_format, Toast.LENGTH_LONG).show();
                    }
                }).show();
    }

    private void downloadPlanet(URL url) {
        try {
            FileUtils.copyURLToFile(url, FileUtil.tempFile(requireContext()), PLANET_DOWNLOAD_CONN_TIMEOUT, PLANET_DOWNLOAD_TIMEOUT);
            if (dealTempPlanetFile()) {
                requireActivity().runOnUiThread(() -> {
                    Snackbar.make(requireView(), R.string.set_planet_succ, BaseTransientBottomBar.LENGTH_LONG).show();
                    closeLoadingDialog();
                });
            } else {
                showErrorOnMain(R.string.planet_wrong_format);
            }
        } catch (IOException e) {
            int msg = (e instanceof SocketTimeoutException) ? R.string.planet_download_timeout : R.string.cannot_download_planet_file;
            showErrorOnMain(msg);
        } finally {
            FileUtil.clearTempFile(requireContext());
        }
    }

    private void showErrorOnMain(int resId) {
        requireActivity().runOnUiThread(() -> {
            Toast.makeText(getContext(), resId, Toast.LENGTH_LONG).show();
            closeLoadingDialog();
        });
    }

    private boolean dealTempPlanetFile() {
        File temp = FileUtil.tempFile(requireContext());
        byte[] buf = new byte[PLANET_FILE_HEADER.length];
        try (FileInputStream in = new FileInputStream(temp)) {
            if (in.read(buf) != PLANET_FILE_HEADER.length || !Arrays.equals(buf, PLANET_FILE_HEADER)) return false;
        } catch (IOException e) { return false; }
        File dest = new File(requireActivity().getFilesDir(), Constants.FILE_CUSTOM_PLANET);
        return temp.renameTo(dest);
    }

    private boolean customPlanetFileNotExit() {
        return !new File(requireActivity().getFilesDir(), Constants.FILE_CUSTOM_PLANET).exists();
    }

    private void applyNexusPlanet() {
        try {
            InputStream in = requireContext().getAssets().open("planet");
            File dest = new File(requireContext().getFilesDir(), Constants.FILE_CUSTOM_PLANET);
            FileUtils.copyInputStreamToFile(in, dest);

            prefs.edit().putBoolean(Constants.PREF_PLANET_USE_CUSTOM, true).apply();
            updatePlanetStatus();

            Snackbar.make(requireView(), R.string.set_planet_succ, BaseTransientBottomBar.LENGTH_LONG).show();
        } catch (IOException e) {
            Toast.makeText(getContext(), R.string.cannot_open_planet, Toast.LENGTH_LONG).show();
        }
    }

    private void closePlanetDialog() {
        if (planetDialog != null) planetDialog.dismiss();
    }

    private void showLoadingDialog(int prompt) {
        View view = LayoutInflater.from(getContext()).inflate(R.layout.dialog_loading, null);
        ((TextView) view.findViewById(R.id.prompt)).setText(prompt);

        this.loadingDialog = new MaterialAlertDialogBuilder(requireContext(), R.style.Theme_ZerotierFix_DashboardDialog)
                .setView(view)
                .setCancelable(false)
                .create();
        this.loadingDialog.show();
    }

    private void closeLoadingDialog() {
        if (loadingDialog != null) loadingDialog.dismiss();
    }

    @Override
    public void onResume() {
        super.onResume();
        prefs.registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onPause() {
        super.onPause();
        prefs.unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
        if (key.equals(Constants.PREF_NETWORK_USE_CELLULAR_DATA)) {
            if (sp.getBoolean(key, false)) requireContext().startService(new Intent(requireContext(), ZeroTierOneService.class));
        }
    }
}

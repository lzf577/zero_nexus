package com.zerotier.nexus.ui;

import android.os.Bundle;
import android.view.MenuItem;

import androidx.appcompat.app.ActionBar;
import androidx.fragment.app.Fragment;

/**
 * 设置 fragment 的容器 activity
 */
public class PrefsActivity extends SingleFragmentActivity {
    @Override
    public Fragment createFragment() {
        return new SettingsDashboardFragment();
    }

    @Override
    public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
    }
}

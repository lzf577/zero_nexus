package com.zerotier.nexus.ui;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.navigation.NavigationView;

import com.zerotier.nexus.R;

public class NetworkListActivity extends AppCompatActivity {
    private static final String TAG = "NetworkListActivity";
    private DrawerLayout drawerLayout;
    private NavigationView navigationView;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        MaterialToolbar toolbar = findViewById(R.id.global_toolbar);
        setSupportActionBar(toolbar);

        drawerLayout = findViewById(R.id.drawer_layout);
        navigationView = findViewById(R.id.nav_view);

        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(this, drawerLayout, toolbar, R.string.app_name, R.string.app_name);
        drawerLayout.addDrawerListener(toggle);
        toggle.syncState();

        navigationView.setNavigationItemSelectedListener(new NavigationView.OnNavigationItemSelectedListener() {
            @Override
            public boolean onNavigationItemSelected(@NonNull MenuItem item) {
                int id = item.getItemId();
                if (id == R.id.nav_join_network) {
                    switchFragment(new JoinNetworkFragment(), "JoinNetwork");
                    getSupportActionBar().setTitle(R.string.join_dashboard_title);
                } else if (id == R.id.nav_peer_list) {
                    switchFragment(new PeerListFragment(), "PeerList");
                    getSupportActionBar().setTitle(R.string.peer_list);
                } else if (id == R.id.nav_exit_config) {
                    switchFragment(new ExitNodeFragment(), "ExitConfig");
                    getSupportActionBar().setTitle(R.string.menu_exit_config);
                } else if (id == R.id.nav_roaming_config) {
                    switchFragment(new RoamingConfigFragment(), "RoamingConfig");
                    getSupportActionBar().setTitle(R.string.roaming_dashboard_title);
                } else if (id == R.id.nav_check_ip) {
                    Intent browserIntent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://ip138.com/"));
                    startActivity(browserIntent);
                    drawerLayout.closeDrawer(GravityCompat.START);
                    return true;
                } else if (id == R.id.nav_about) {
                    switchFragment(new AboutFragment(), "About");
                    getSupportActionBar().setTitle(R.string.menu_about);
                }
                drawerLayout.closeDrawer(GravityCompat.START);
                return true;
            }
        });

        if (savedInstanceState == null) {
            switchFragment(new NetworkListFragment(), "NodeList");
            getSupportActionBar().setTitle(R.string.app_name);
        }
    }

    public void switchFragment(Fragment fragment, String tag) {
        switchFragment(fragment, tag, false);
    }

    public void switchFragment(Fragment fragment, String tag, boolean addToBackStack) {
        androidx.fragment.app.FragmentTransaction tx = getSupportFragmentManager().beginTransaction()
                .replace(R.id.fragmentContainer, fragment, tag);
        if (addToBackStack) {
            tx.addToBackStack(tag);
        }
        tx.commit();
    }

    @Override
    public void onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START);
        } else if (getSupportFragmentManager().getBackStackEntryCount() > 0) {
            getSupportFragmentManager().popBackStack();
            getSupportFragmentManager().executePendingTransactions();
            // Restore title if coming back to About
            Fragment current = getSupportFragmentManager().findFragmentById(R.id.fragmentContainer);
            if (current instanceof AboutFragment) {
                getSupportActionBar().setTitle(R.string.menu_about);
            }
        } else {
            // 如果不在主页列表，返回主页列表
            Fragment current = getSupportFragmentManager().findFragmentByTag("NodeList");
            if (current == null || !current.isVisible()) {
                switchFragment(new NetworkListFragment(), "NodeList");
                navigationView.setCheckedItem(R.id.nav_peer_list);
                getSupportActionBar().setTitle(R.string.app_name);
            } else {
                super.onBackPressed();
            }
        }
    }
}

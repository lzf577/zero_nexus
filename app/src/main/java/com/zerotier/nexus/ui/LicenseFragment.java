package com.zerotier.nexus.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.appbar.MaterialToolbar;
import com.zerotier.nexus.R;

public class LicenseFragment extends Fragment {

    public static LicenseFragment newInstance(String filename, String title) {
        LicenseFragment fragment = new LicenseFragment();
        android.os.Bundle args = new android.os.Bundle();
        args.putString("file", filename);
        args.putString("title", title);
        fragment.setArguments(args);
        return fragment;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_license, container, false);
        
        MaterialToolbar toolbar = view.findViewById(R.id.toolbar);
        if (toolbar != null) {
            toolbar.setNavigationOnClickListener(v -> {
                requireActivity().onBackPressed();
            });
            if (getArguments() != null) {
                toolbar.setTitle(getArguments().getString("title", "开源协议"));
            }
        }
        
        android.widget.TextView licenseText = view.findViewById(R.id.license_text);
        try {
            String filename = "licenses.txt";
            if (getArguments() != null) {
                filename = getArguments().getString("file", "licenses.txt");
            }
            java.io.InputStream is = requireContext().getAssets().open(filename);
            int size = is.available();
            byte[] buffer = new byte[size];
            is.read(buffer);
            is.close();
            String text = new String(buffer, "UTF-8");
            licenseText.setText(text);
        } catch (java.io.IOException e) {
            e.printStackTrace();
            licenseText.setText("Failed to load license.");
        }
        
        return view;
    }
}

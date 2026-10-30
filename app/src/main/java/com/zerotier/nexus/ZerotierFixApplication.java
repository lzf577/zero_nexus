package com.zerotier.nexus;

import android.util.Log;

import androidx.multidex.MultiDexApplication;

import com.zerotier.nexus.model.DaoMaster;
import com.zerotier.nexus.model.DaoSession;
import com.zerotier.nexus.model.ZTOpenHelper;

/**
 * 主程序入口
 *
 * @author kaaass
 */
public class ZerotierFixApplication extends MultiDexApplication {
    private DaoSession mDaoSession;

    public void onCreate() {
        super.onCreate();
        Log.i("Application", "ZerotierFixApplication: onCreate");
        Log.i("Application", "Starting Application");
        // 创建 DAO 会话
        this.mDaoSession = new DaoMaster(
                new ZTOpenHelper(this, "ztfixdb", null)
                        .getWritableDatabase()
        ).newSession();

        // 初始化出口管理器
        com.zerotier.nexus.service.ExitNodeManager.getInstance().init(this);
    }

    public DaoSession getDaoSession() {
        return this.mDaoSession;
    }
}

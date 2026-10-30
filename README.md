# Zerotier Nexus

Zerotier Nexus 是基于 ZeroTier One 构建的增强型 Android 客户端，集成了自定义 Planet、漫游优化、链路补强（TUIC v5 / SOCKS5）以及出口节点控制等高级功能。

## ✨ 功能特性

- 🌐 **ZeroTier 核心互联**：支持加入私有与公有网络，实现低延迟 P2P 虚拟局域网直连。
- 🪐 **自定义 Planet 支持**：支持导入与重置自定义根服务器 Planet 文件，摆脱官方服务器依赖。
- 🚀 **出口节点控制 (Exit Node)**：支持内核级转发 (Root/NAT) 与用户态代理模式，内置 BBR 优化与 UDP 缓冲区调优选项。
- 🛡️ **代理链路补强**：结合底层 sing-box (tuic) 核心，优化加密传输与复杂网络环境下的漫游体验。
- 📱 **便捷交互与诊断**：支持二维码扫描导入/分享配置、网络状态监控与实时链路诊断。

## 📁 目录结构

- `app/` - Android 主应用模块（包含 UI 界面、VPN Service、ProxyEngine、二维码等）
- `core/` - ZeroTier SDK 封装及 JNI C++ 接口
- `externals/core/` - ZeroTier One 核心底层 C++ 实现
- `planet` - 根服务器 Planet 定义文件
- `deploy_all.bat` - 设备快速部署脚本

## 🛠️ 构建与编译

本项目基于 Android Gradle Plugin 8.3.2 与 NDK 进行构建：

```bash
# 赋予构建脚本执行权限
chmod +x gradlew

# 构建 Debug 版本
./gradlew assembleDebug

# 构建 Release 版本
./gradlew assembleRelease
```

构建成功后输出的 APK 位于 `app/build/outputs/apk/` 目录下。

## 📄 开源协议

本项目遵循 [LICENSE](LICENSE) 所列的相关开源许可协议。

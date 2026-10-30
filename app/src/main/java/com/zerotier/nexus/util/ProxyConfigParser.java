package com.zerotier.nexus.util;

/**
 * 代理配置解析器 (Proxy Config Parser)
 * 负责解析和持久化代理配置信息。
 */
public class ProxyConfigParser {

    /**
     * 代理配置数据类
     */
    public static class Config {
        public String protocol;
        public String host;
        public int port;
        public String password;
        public String uuid;

        @Override
        public String toString() {
            return String.format("%s://%s:%d", protocol, host, port);
        }
    }
}

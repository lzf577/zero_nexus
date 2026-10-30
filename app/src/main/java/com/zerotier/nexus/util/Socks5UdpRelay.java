package com.zerotier.nexus.util;

import android.util.Log;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.ByteBuffer;

/**
 * SOCKS5 UDP 转发器 (SOCKS5 UDP Relay)
 * 实现通过 SOCKS5 代理协议转发 UDP 数据包。
 */
public class Socks5UdpRelay {
    private static final String TAG = "Socks5UdpRelay";
    private final String proxyHost;
    private final int proxyPort;
    
    private Socket tcpSocket;
    private DatagramSocket udpSocket;
    private InetAddress relayAddress;
    private int relayPort;
    private boolean isReady = false;
    private Thread receiveThread;

    public Socks5UdpRelay(String host, int port) {
        this.proxyHost = host;
        this.proxyPort = port;
    }

    public synchronized void start() {
        if (isReady) return;
        new Thread(() -> {
            try {
                // 1. 建立 TCP 连接进行握手
                tcpSocket = new Socket(proxyHost, proxyPort);
                tcpSocket.setKeepAlive(true);
                DataOutputStream out = new DataOutputStream(tcpSocket.getOutputStream());
                DataInputStream in = new DataInputStream(tcpSocket.getInputStream());

                // 2. SOCKS5 认证 (No Auth)
                out.write(new byte[]{0x05, 0x01, 0x00});
                byte[] response = new byte[2];
                in.readFully(response);
                if (response[0] != 0x05 || response[1] != 0x00) {
                    throw new IOException("SOCKS5 authentication failed");
                }

                // 3. UDP ASSOCIATE 请求
                // 请求格式: VER(1) CMD(1) RSV(1) ATYP(1) DST.ADDR(var) DST.PORT(2)
                // CMD = 0x03 (UDP ASSOCIATE)
                out.write(new byte[]{0x05, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00});
                
                byte[] assocRes = new byte[10];
                in.readFully(assocRes);
                if (assocRes[1] != 0x00) {
                    throw new IOException("UDP ASSOCIATE failed, error code: " + assocRes[1]);
                }

                // 解析中继地址和端口
                byte atyp = assocRes[3];
                if (atyp == 0x01) { // IPv4
                    byte[] addr = new byte[4];
                    System.arraycopy(assocRes, 4, addr, 0, 4);
                    relayAddress = InetAddress.getByAddress(addr);
                    relayPort = ((assocRes[8] & 0xFF) << 8) | (assocRes[9] & 0xFF);
                } else {
                    throw new IOException("Unsupported ATYP in UDP ASSOCIATE response: " + atyp);
                }

                // 如果返回地址是 0.0.0.0，说明应使用代理主机的地址
                if (relayAddress.isAnyLocalAddress()) {
                    relayAddress = InetAddress.getByName(proxyHost);
                }

                // 4. 准备 UDP Socket
                udpSocket = new DatagramSocket();
                isReady = true;
                Log.i(TAG, "SOCKS5 UDP Relay ready. Relay address: " + relayAddress + ":" + relayPort);

                // 启动空读线程保持 TCP 连接
                startKeepAliveThread();

            } catch (Exception e) {
                Log.e(TAG, "Failed to start SOCKS5 UDP relay: " + e.getMessage());
                stop();
            }
        }).start();
    }

    private void startKeepAliveThread() {
        receiveThread = new Thread(() -> {
            try {
                byte[] buffer = new byte[1024];
                while (isReady && tcpSocket.getInputStream().read(buffer) != -1) {
                    // 仅用于保持连接，丢弃收到的任何 TCP 数据
                }
            } catch (IOException ignored) {}
            Log.w(TAG, "SOCKS5 TCP connection closed, relay stopping.");
            stop();
        });
        receiveThread.start();
    }

    public synchronized boolean isReady() {
        return isReady && udpSocket != null && !udpSocket.isClosed();
    }

    public synchronized void stop() {
        isReady = false;
        try {
            if (tcpSocket != null) tcpSocket.close();
            if (udpSocket != null) udpSocket.close();
            if (receiveThread != null) receiveThread.interrupt();
        } catch (IOException ignored) {}
        tcpSocket = null;
        udpSocket = null;
        Log.i(TAG, "SOCKS5 UDP Relay stopped.");
    }

    /**
     * 转发 UDP 数据包
     */
    public void forward(byte[] data, InetAddress destAddr, int destPort) {
        if (!isReady()) return;

        try {
            byte[] destIpBytes = destAddr.getAddress();
            int headerLen = 4 + destIpBytes.length + 2;
            byte[] packetData = new byte[headerLen + data.length];

            // SOCKS5 UDP Header
            // RSV(2) FRAG(1) ATYP(1) DST.ADDR(var) DST.PORT(2)
            ByteBuffer bb = ByteBuffer.wrap(packetData);
            bb.putShort((short) 0); // RSV
            bb.put((byte) 0);      // FRAG
            bb.put((byte) 0x01);   // ATYP (IPv4)
            bb.put(destIpBytes);   // DST.ADDR
            bb.putShort((short) destPort); // DST.PORT
            bb.put(data);          // DATA

            DatagramPacket packet = new DatagramPacket(packetData, packetData.length, relayAddress, relayPort);
            udpSocket.send(packet);
            
        } catch (Exception e) {
            Log.e(TAG, "Error forwarding UDP packet: " + e.getMessage());
        }
    }
}

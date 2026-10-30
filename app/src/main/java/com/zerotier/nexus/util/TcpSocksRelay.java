package com.zerotier.nexus.util;

import android.util.Log;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * TCP SOCKS5 转发器 (用户态 TCP 栈模拟)
 * 用于在非 Root 环境下将截获的 TCP 报文重定向至 SOCKS5 代理。
 */
public class TcpSocksRelay {
    private static final String TAG = "TcpSocksRelay";
    private final String proxyHost;
    private final int proxyPort;
    private final PacketWriter packetWriter;
    private final Map<String, TcpSession> sessions = new ConcurrentHashMap<>();
    private final ExecutorService relayExecutor = Executors.newCachedThreadPool();

    public interface PacketWriter {
        void writePacket(byte[] data);
    }

    public TcpSocksRelay(String proxyHost, int proxyPort, PacketWriter writer) {
        this.proxyHost = proxyHost;
        this.proxyPort = proxyPort;
        this.packetWriter = writer;
    }

    public boolean handlePacket(byte[] data) {
        if (IPPacketUtils.getProtocol(data) != 6) return false;

        InetAddress srcIp = IPPacketUtils.getSourceIP(data);
        InetAddress dstIp = IPPacketUtils.getDestIP(data);
        int srcPort = IPPacketUtils.getSourcePort(data);
        int dstPort = IPPacketUtils.getDestPort(data);
        byte flags = IPPacketUtils.getTcpFlags(data);
        long seq = IPPacketUtils.getTcpSeq(data);
        long ack = IPPacketUtils.getTcpAck(data);

        String key = String.format("%s:%d->%s:%d", srcIp.getHostAddress(), srcPort, dstIp.getHostAddress(), dstPort);

        if ((flags & 0x02) != 0) { // SYN
            handleSyn(key, data, srcIp, srcPort, dstIp, dstPort, seq);
            return true;
        }

        TcpSession session = sessions.get(key);
        if (session == null) {
            if ((flags & 0x04) == 0) { // Not RST
                sendRst(data, ack, seq + 1);
            }
            return true;
        }

        return session.processPacket(data, flags, seq, ack);
    }

    private void handleSyn(String key, byte[] data, InetAddress srcIp, int srcPort, InetAddress dstIp, int dstPort, long seq) {
        Log.d(TAG, "New TCP Session: " + key);
        TcpSession old = sessions.remove(key);
        if (old != null) old.close();

        TcpSession session = new TcpSession(srcIp, srcPort, dstIp, dstPort, seq + 1);
        sessions.put(key, session);

        // 回复 SYN-ACK
        byte[] synAck = IPPacketUtils.buildTcpResponse(data, (byte) 0x12, session.mySeq, session.myAck, null);
        packetWriter.writePacket(synAck);
        session.mySeq++; // SYN 占 1 位
    }

    private void sendRst(byte[] request, long seq, long ack) {
        byte[] rst = IPPacketUtils.buildTcpResponse(request, (byte) 0x04, seq, ack, null);
        packetWriter.writePacket(rst);
    }

    public void stop() {
        relayExecutor.shutdownNow();
        for (TcpSession s : sessions.values()) s.close();
        sessions.clear();
    }

    private class TcpSession {
        final InetAddress srcIp, dstIp;
        final int srcPort, dstPort;
        long mySeq = 1000; // 初始序列号
        long myAck;        // 期望从应用收到的下一个序列号
        Socket socksSocket;
        OutputStream socksOut;
        boolean isConnected = false;
        boolean isClosing = false;

        TcpSession(InetAddress srcIp, int srcPort, InetAddress dstIp, int dstPort, long initialAck) {
            this.srcIp = srcIp; this.srcPort = srcPort;
            this.dstIp = dstIp; this.dstPort = dstPort;
            this.myAck = initialAck;
            
            // 异步建立 SOCKS 连接
            relayExecutor.execute(this::connectToSocks);
        }

        private void connectToSocks() {
            try {
                socksSocket = new Socket(proxyHost, proxyPort);
                socksSocket.setKeepAlive(true);
                InputStream in = socksSocket.getInputStream();
                socksOut = socksSocket.getOutputStream();

                // SOCKS5 Handshake (CONNECT)
                socksOut.write(new byte[]{0x05, 0x01, 0x00});
                byte[] res = new byte[2];
                if (in.read(res) != 2 || res[1] != 0x00) throw new Exception("SOCKS Auth Fail");

                byte[] addrBytes = dstIp.getAddress();
                socksOut.write(new byte[]{0x05, 0x01, 0x00, 0x01});
                socksOut.write(addrBytes);
                socksOut.write(new byte[]{(byte) (dstPort >> 8), (byte) (dstPort & 0xFF)});

                byte[] connRes = new byte[10]; // IPv4 Connect response
                if (in.read(connRes) < 2 || connRes[1] != 0x00) throw new Exception("SOCKS Connect Fail");

                isConnected = true;
                Log.i(TAG, "SOCKS Connected for " + dstIp + ":" + dstPort);

                // 启动下游转发进程 (SOCKS -> TUN)
                relayExecutor.execute(() -> relaySocksToTun(in));

            } catch (Exception e) {
                Log.e(TAG, "SOCKS Connection failed: " + e.getMessage());
                close();
            }
        }

        private void relaySocksToTun(InputStream in) {
            byte[] buffer = new byte[1024]; // 保证小于 MTU
            try {
                int len;
                while ((len = in.read(buffer)) != -1) {
                    if (isClosing) break;
                    byte[] data = new byte[len];
                    System.arraycopy(buffer, 0, data, 0, len);
                    
                    // 构建 PUSH+ACK 报文
                    byte[] rawRequest = buildDummyRequest();
                    byte[] packet = IPPacketUtils.buildTcpResponse(rawRequest, (byte) 0x18, mySeq, myAck, data);
                    packetWriter.writePacket(packet);
                    
                    mySeq = (mySeq + len) & 0xFFFFFFFFL;
                }
            } catch (Exception ignored) {}
            close();
        }

        public boolean processPacket(byte[] data, byte flags, long seq, long ack) {
            if ((flags & 0x04) != 0) { // RST
                close();
                return true;
            }
            if ((flags & 0x01) != 0) { // FIN
                myAck = (seq + 1) & 0xFFFFFFFFL;
                byte[] finAck = IPPacketUtils.buildTcpResponse(data, (byte) 0x11, mySeq, myAck, null);
                packetWriter.writePacket(finAck);
                close();
                return true;
            }

            // 更新确认号
            if (seq == myAck) {
                int payloadOffset = 40; // IP(20) + TCP(20)
                int payloadLen = data.length - payloadOffset;
                if (payloadLen > 0) {
                    myAck = (myAck + payloadLen) & 0xFFFFFFFFL;
                    if (socksOut != null && isConnected) {
                        try {
                            socksOut.write(data, payloadOffset, payloadLen);
                            socksOut.flush();
                        } catch (Exception e) { close(); }
                    }
                    // 回复 ACK
                    byte[] ackRes = IPPacketUtils.buildTcpResponse(data, (byte) 0x10, mySeq, myAck, null);
                    packetWriter.writePacket(ackRes);
                }
            }
            return true;
        }

        private byte[] buildDummyRequest() {
            // 用于 buildTcpResponse 交换源目的，所以需要反向
            byte[] req = new byte[40];
            System.arraycopy(dstIp.getAddress(), 0, req, 12, 4);
            System.arraycopy(srcIp.getAddress(), 0, req, 16, 4);
            req[20] = (byte) (dstPort >> 8); req[21] = (byte) (dstPort & 0xFF);
            req[22] = (byte) (srcPort >> 8); req[23] = (byte) (srcPort & 0xFF);
            return req;
        }

        void close() {
            isClosing = true;
            try { if (socksSocket != null) socksSocket.close(); } catch (Exception ignored) {}
            sessions.values().remove(this);
        }
    }
}

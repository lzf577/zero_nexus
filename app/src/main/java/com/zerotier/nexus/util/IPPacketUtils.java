package com.zerotier.nexus.util;

import java.net.InetAddress;

public class IPPacketUtils {
    public static byte getIPVersion(byte[] data) {
        return (byte) ((data[0] >> 4) & 0x0F);
    }

    public static InetAddress getSourceIP(byte[] data) {
        if (data == null || data.length < 20) return null;
        try {
            byte version = getIPVersion(data);
            if (version == 4) {
                byte[] addr = new byte[4];
                System.arraycopy(data, 12, addr, 0, 4);
                return InetAddress.getByAddress(addr);
            } else if (version == 6 && data.length >= 40) {
                byte[] addr = new byte[16];
                System.arraycopy(data, 8, addr, 0, 16);
                return InetAddress.getByAddress(addr);
            }
            return null;
        } catch (Exception e) { return null; }
    }

    public static InetAddress getDestIP(byte[] data) {
        if (data == null || data.length < 20) return null;
        try {
            byte version = getIPVersion(data);
            if (version == 4) {
                byte[] addr = new byte[4];
                System.arraycopy(data, 16, addr, 0, 4);
                return InetAddress.getByAddress(addr);
            } else if (version == 6 && data.length >= 40) {
                byte[] addr = new byte[16];
                System.arraycopy(data, 24, addr, 0, 16);
                return InetAddress.getByAddress(addr);
            }
            return null;
        } catch (Exception e) { return null; }
    }

    public static byte getProtocol(byte[] data) {
        if (data == null || data.length < 20) return 0;
        return data[9];
    }

    public static int getSourcePort(byte[] data) {
        if (data == null || data.length < 22) return 0;
        return ((data[20] & 0xFF) << 8) | (data[21] & 0xFF);
    }

    public static int getDestPort(byte[] data) {
        if (data == null || data.length < 24) return 0;
        return ((data[22] & 0xFF) << 8) | (data[23] & 0xFF);
    }

    public static long getTcpSeq(byte[] data) {
        if (data == null || data.length < 28) return 0;
        return ((data[24] & 0xFFL) << 24) | ((data[25] & 0xFFL) << 16) |
               ((data[26] & 0xFFL) << 8) | (data[27] & 0xFFL);
    }

    public static long getTcpAck(byte[] data) {
        if (data == null || data.length < 32) return 0;
        return ((data[28] & 0xFFL) << 24) | ((data[29] & 0xFFL) << 16) |
               ((data[30] & 0xFFL) << 8) | (data[31] & 0xFFL);
    }

    public static byte getTcpFlags(byte[] data) {
        if (data == null || data.length < 34) return 0;
        return data[33];
    }

    /**
     * 构建一个虚拟的 TCP 响应包 (例如用于握手或 ACK)
     */
    public static byte[] buildTcpResponse(byte[] request, byte flags, long seq, long ack, byte[] payload) {
        int payloadLen = (payload != null) ? payload.length : 0;
        int totalLen = 20 + 20 + payloadLen; // IP(20) + TCP(20) + Payload
        byte[] packet = new byte[totalLen];

        // --- IP Header ---
        packet[0] = 0x45; // Version 4, Header Length 5 (20 bytes)
        packet[8] = 64;   // TTL
        packet[9] = 6;    // Protocol TCP
        
        // 交换源目 IP
        System.arraycopy(request, 16, packet, 12, 4); // Src = Old Dst
        System.arraycopy(request, 12, packet, 16, 4); // Dst = Old Src
        
        packet[2] = (byte) (totalLen >> 8);
        packet[3] = (byte) (totalLen & 0xFF);

        // --- TCP Header ---
        // 交换源目端口
        packet[20] = request[22]; packet[21] = request[23]; // SrcPort = Old DstPort
        packet[22] = request[20]; packet[23] = request[21]; // DstPort = Old SrcPort
        
        // SEQ / ACK
        packet[24] = (byte) (seq >> 24); packet[25] = (byte) (seq >> 16);
        packet[26] = (byte) (seq >> 8); packet[27] = (byte) (seq & 0xFF);
        
        packet[28] = (byte) (ack >> 24); packet[29] = (byte) (ack >> 16);
        packet[30] = (byte) (ack >> 8); packet[31] = (byte) (ack & 0xFF);
        
        packet[32] = 0x50; // Data offset 5 (20 bytes)
        packet[33] = flags;
        packet[34] = (byte) 0xFF; packet[35] = (byte) 0xFF; // Window Size (Large)

        // --- Payload ---
        if (payloadLen > 0) {
            System.arraycopy(payload, 0, packet, 40, payloadLen);
        }

        // --- Checksums ---
        updateChecksums(packet, totalLen);
        return packet;
    }

    public static void updateChecksums(byte[] data, int length) {
        if (data == null || data.length < length || length < 20) return;
        // IP Checksum
        data[10] = 0; data[11] = 0;
        int ipSum = calculateChecksum(data, 0, 0, 20);
        data[10] = (byte) (ipSum >> 8);
        data[11] = (byte) (ipSum & 0xFF);

        // TCP Checksum
        if (data[9] == 6 && length >= 40) {
            int tcpLen = length - 20;
            data[36] = 0; data[37] = 0;
            
            // Pseudo Header Sum
            long pSum = 0;
            for (int i = 12; i < 20; i += 2) {
                pSum += ((data[i] & 0xFF) << 8) | (data[i + 1] & 0xFF);
            }
            pSum += 6; // Protocol
            pSum += tcpLen;
            
            int tcpSum = calculateChecksum(data, pSum, 20, length);
            data[36] = (byte) (tcpSum >> 8);
            data[37] = (byte) (tcpSum & 0xFF);
        }
    }

    public static int calculateChecksum(byte[] data, long seed, int start, int end) {
        long sum = seed;
        int i = start;
        while (i < end - 1) {
            sum += ((data[i] & 0xFF) << 8) | (data[i + 1] & 0xFF);
            i += 2;
        }
        if (i < end) {
            sum += (data[i] & 0xFF) << 8;
        }
        while ((sum >> 16) > 0) {
            sum = (sum & 0xFFFF) + (sum >> 16);
        }
        return (int) (~sum & 0xFFFF);
    }
}

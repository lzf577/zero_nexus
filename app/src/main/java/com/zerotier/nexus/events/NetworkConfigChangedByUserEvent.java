package com.zerotier.nexus.events;

import com.zerotier.nexus.model.Network;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 用户更改 ZT 网络配置事件
 */
@Data
@AllArgsConstructor
@lombok.NoArgsConstructor
public class NetworkConfigChangedByUserEvent {
    private Network network;
}

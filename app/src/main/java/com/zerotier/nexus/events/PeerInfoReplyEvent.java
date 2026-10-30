package com.zerotier.nexus.events;

import com.zerotier.sdk.Peer;
import lombok.AllArgsConstructor;
import lombok.Data;
import java.util.Map;

/**
 * 应答节点信息事件
 *
 * @author kaaass
 */
@Data
@AllArgsConstructor
public class PeerInfoReplyEvent {

    private final Peer[] peers;

    private final Map<Long, String> nodeToIp;
}

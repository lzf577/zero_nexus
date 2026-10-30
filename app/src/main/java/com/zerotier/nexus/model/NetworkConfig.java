package com.zerotier.nexus.model;

import com.zerotier.nexus.model.type.NetworkStatus;
import com.zerotier.nexus.model.type.NetworkType;

import org.greenrobot.greendao.DaoException;
import org.greenrobot.greendao.annotation.Convert;
import org.greenrobot.greendao.annotation.Entity;
import org.greenrobot.greendao.annotation.Generated;
import org.greenrobot.greendao.annotation.Id;
import org.greenrobot.greendao.annotation.ToMany;
import org.greenrobot.greendao.converter.PropertyConverter;

import java.util.List;

@Entity
public class NetworkConfig {
    @Id
    private Long id;

    @Deprecated
    @Convert(converter = NetworkTypeConverter.class, columnType = Integer.class)
    private NetworkType type;

    @Deprecated
    @Convert(converter = NetworkStatusConverter.class, columnType = Integer.class)
    private NetworkStatus status;

    @Deprecated
    private String mac;

    @Deprecated
    private String mtu;

    @Deprecated
    private boolean broadcast;

    @Deprecated
    private boolean bridging;

    private boolean routeViaZeroTier;

    private boolean allowExitRoaming;

    private boolean allowMobileData;

    private boolean disableIPv6;

    @Deprecated
    private boolean useCustomDNS;

    private int dnsMode;

    private boolean proxyEnabled;
    private String proxyProtocol;
    private String proxyHost;
    private int proxyPort;
    private String proxyPassword;
    private String proxyUuid;
    private String proxyCipher;

    @Deprecated
    @ToMany(referencedJoinProperty = "networkId")
    private List<AssignedAddress> assignedAddresses;

    @ToMany(referencedJoinProperty = "networkId")
    private List<DnsServer> dnsServers;

    /**
     * Used to resolve relations
     */
    @Generated(hash = 2040040024)
    private transient DaoSession daoSession;

    /**
     * Used for active entity operations.
     */
    @Generated(hash = 1627972760)
    private transient NetworkConfigDao myDao;



    public NetworkConfig(Long id, boolean routeViaZeroTier, int dnsMode) {
        this.id = id;
        this.routeViaZeroTier = routeViaZeroTier;
        this.dnsMode = dnsMode;
    }


    @Generated(hash = 1488326330)
    public NetworkConfig(Long id, NetworkType type, NetworkStatus status, String mac, String mtu,
            boolean broadcast, boolean bridging, boolean routeViaZeroTier, boolean allowExitRoaming,
            boolean allowMobileData, boolean disableIPv6, boolean useCustomDNS, int dnsMode, boolean proxyEnabled,
            String proxyProtocol, String proxyHost, int proxyPort, String proxyPassword, String proxyUuid,
            String proxyCipher) {
        this.id = id;
        this.type = type;
        this.status = status;
        this.mac = mac;
        this.mtu = mtu;
        this.broadcast = broadcast;
        this.bridging = bridging;
        this.routeViaZeroTier = routeViaZeroTier;
        this.allowExitRoaming = allowExitRoaming;
        this.allowMobileData = allowMobileData;
        this.disableIPv6 = disableIPv6;
        this.useCustomDNS = useCustomDNS;
        this.dnsMode = dnsMode;
        this.proxyEnabled = proxyEnabled;
        this.proxyProtocol = proxyProtocol;
        this.proxyHost = proxyHost;
        this.proxyPort = proxyPort;
        this.proxyPassword = proxyPassword;
        this.proxyUuid = proxyUuid;
        this.proxyCipher = proxyCipher;
    }


    @Generated(hash = 850630533)
    public NetworkConfig() {
    }


    public Long getId() {
        return this.id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    @Deprecated
    public NetworkType getType() {
        return this.type;
    }

    @Deprecated
    public void setType(NetworkType type) {
        this.type = type;
    }

    @Deprecated
    public NetworkStatus getStatus() {
        return this.status;
    }

    @Deprecated
    public void setStatus(NetworkStatus status) {
        this.status = status;
    }

    @Deprecated
    public String getMac() {
        return this.mac;
    }

    @Deprecated
    public void setMac(String mac) {
        this.mac = mac;
    }

    @Deprecated
    public String getMtu() {
        return this.mtu;
    }

    @Deprecated
    public void setMtu(String mtu) {
        this.mtu = mtu;
    }

    @Deprecated
    public boolean getBroadcast() {
        return this.broadcast;
    }

    @Deprecated
    public void setBroadcast(boolean broadcast) {
        this.broadcast = broadcast;
    }

    @Deprecated
    public boolean getBridging() {
        return this.bridging;
    }

    @Deprecated
    public void setBridging(boolean bridging) {
        this.bridging = bridging;
    }

    public void setRouteViaZeroTier(boolean routeViaZeroTier) {
        this.routeViaZeroTier = routeViaZeroTier;
    }

    public boolean getAllowExitRoaming() {
        return this.allowExitRoaming;
    }

    public void setAllowExitRoaming(boolean allowExitRoaming) {
        this.allowExitRoaming = allowExitRoaming;
    }

    @Deprecated
    public boolean getUseCustomDNS() {
        return this.useCustomDNS;
    }

    @Deprecated
    public void setUseCustomDNS(boolean useCustomDNS) {
        this.useCustomDNS = useCustomDNS;
    }

    public int getDnsMode() {
        return this.dnsMode;
    }

    public void setDnsMode(int dnsMode) {
        this.dnsMode = dnsMode;
    }

    /**
     * To-many relationship, resolved on first access (and after reset).
     * Changes to to-many relations are not persisted, make changes to the target entity.
     */
    @Generated(hash = 1342378014)
    public List<AssignedAddress> getAssignedAddresses() {
        if (assignedAddresses == null) {
            final DaoSession daoSession = this.daoSession;
            if (daoSession == null) {
                throw new DaoException("Entity is detached from DAO context");
            }
            AssignedAddressDao targetDao = daoSession.getAssignedAddressDao();
            List<AssignedAddress> assignedAddressesNew = targetDao._queryNetworkConfig_AssignedAddresses(id);
            synchronized (this) {
                if (assignedAddresses == null) {
                    assignedAddresses = assignedAddressesNew;
                }
            }
        }
        return assignedAddresses;
    }

    /** Resets a to-many relationship, making the next get call to query for a fresh result. */
    @Generated(hash = 1705851723)
    public synchronized void resetAssignedAddresses() {
        assignedAddresses = null;
    }

    /**
     * To-many relationship, resolved on first access (and after reset).
     * Changes to to-many relations are not persisted, make changes to the target entity.
     */
    @Generated(hash = 1958390114)
    public List<DnsServer> getDnsServers() {
        if (dnsServers == null) {
            final DaoSession daoSession = this.daoSession;
            if (daoSession == null) {
                throw new DaoException("Entity is detached from DAO context");
            }
            DnsServerDao targetDao = daoSession.getDnsServerDao();
            List<DnsServer> dnsServersNew = targetDao._queryNetworkConfig_DnsServers(id);
            synchronized (this) {
                if (dnsServers == null) {
                    dnsServers = dnsServersNew;
                }
            }
        }
        return dnsServers;
    }

    /**
     * Resets a to-many relationship, making the next get call to query for a fresh result.
     */
    @Generated(hash = 980018091)
    public synchronized void resetDnsServers() {
        dnsServers = null;
    }

    /**
     * Convenient call for {@link org.greenrobot.greendao.AbstractDao#delete(Object)}.
     * Entity must attached to an entity context.
     */
    @Generated(hash = 128553479)
    public void delete() {
        if (myDao == null) {
            throw new DaoException("Entity is detached from DAO context");
        }
        myDao.delete(this);
    }

    /**
     * Convenient call for {@link org.greenrobot.greendao.AbstractDao#refresh(Object)}.
     * Entity must attached to an entity context.
     */
    @Generated(hash = 1942392019)
    public void refresh() {
        if (myDao == null) {
            throw new DaoException("Entity is detached from DAO context");
        }
        myDao.refresh(this);
    }

    /**
     * Convenient call for {@link org.greenrobot.greendao.AbstractDao#update(Object)}.
     * Entity must attached to an entity context.
     */
    @Generated(hash = 713229351)
    public void update() {
        if (myDao == null) {
            throw new DaoException("Entity is detached from DAO context");
        }
        myDao.update(this);
    }

    public boolean getRouteViaZeroTier() {
        return this.routeViaZeroTier;
    }

    public boolean getAllowMobileData() {
        return this.allowMobileData;
    }

    public void setAllowMobileData(boolean allowMobileData) {
        this.allowMobileData = allowMobileData;
    }

    public boolean getDisableIPv6() {
        return this.disableIPv6;
    }

    public void setDisableIPv6(boolean disableIPv6) {
        this.disableIPv6 = disableIPv6;
    }

    public boolean getProxyEnabled() { return this.proxyEnabled; }
    public void setProxyEnabled(boolean proxyEnabled) { this.proxyEnabled = proxyEnabled; }
    public String getProxyProtocol() { return this.proxyProtocol; }
    public void setProxyProtocol(String proxyProtocol) { this.proxyProtocol = proxyProtocol; }
    public String getProxyHost() { return this.proxyHost; }
    public void setProxyHost(String proxyHost) { this.proxyHost = proxyHost; }
    public int getProxyPort() { return this.proxyPort; }
    public void setProxyPort(int proxyPort) { this.proxyPort = proxyPort; }
    public String getProxyPassword() { return this.proxyPassword; }
    public void setProxyPassword(String proxyPassword) { this.proxyPassword = proxyPassword; }
    public String getProxyUuid() { return this.proxyUuid; }
    public void setProxyUuid(String proxyUuid) { this.proxyUuid = proxyUuid; }
    public String getProxyCipher() { return this.proxyCipher; }
    public void setProxyCipher(String proxyCipher) { this.proxyCipher = proxyCipher; }

    /** called by internal mechanisms, do not call yourself. */
    @Generated(hash = 1093510048)
    public void __setDaoSession(DaoSession daoSession) {
        this.daoSession = daoSession;
        myDao = daoSession != null ? daoSession.getNetworkConfigDao() : null;
    }

    public static class NetworkTypeConverter implements PropertyConverter<NetworkType, Integer> {
        public NetworkType convertToEntityProperty(Integer num) {
            if (num == null) {
                return null;
            }
            return NetworkType.fromInt(num);
        }

        public Integer convertToDatabaseValue(NetworkType networkType) {
            if (networkType == null) {
                return null;
            }
            return networkType.toInt();
        }
    }

    public static class NetworkStatusConverter implements PropertyConverter<NetworkStatus, Integer> {
        public NetworkStatus convertToEntityProperty(Integer num) {
            if (num == null) {
                return null;
            }
            return NetworkStatus.fromInt(num);
        }

        public Integer convertToDatabaseValue(NetworkStatus networkStatus) {
            if (networkStatus == null) {
                return null;
            }
            return networkStatus.toInt();
        }
    }
}

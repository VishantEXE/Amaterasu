package dev.questheld.host;

import android.content.Context;
import android.net.*;
import java.net.*;
import java.util.*;

final class HotspotAddress {
    static Inet4Address find(Context context) {
        Set<String> stations=new HashSet<>();
        ConnectivityManager manager=context.getSystemService(ConnectivityManager.class);
        for(Network network:manager.getAllNetworks()) {
            NetworkCapabilities caps=manager.getNetworkCapabilities(network);
            LinkProperties links=manager.getLinkProperties(network);
            if(caps!=null && links!=null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                if(links.getInterfaceName()!=null) stations.add(links.getInterfaceName());
            }
        }
        Inet4Address candidate=null;
        try {
            Enumeration<NetworkInterface> interfaces=NetworkInterface.getNetworkInterfaces();
            while(interfaces!=null && interfaces.hasMoreElements()) {
                NetworkInterface nic=interfaces.nextElement();
                String name=nic.getName().toLowerCase(Locale.ROOT);
                if(!nic.isUp() || nic.isLoopback() || stations.contains(nic.getName()) || !apInterface(name)) continue;
                Enumeration<InetAddress> addresses=nic.getInetAddresses();
                while(addresses.hasMoreElements()) {
                    InetAddress address=addresses.nextElement();
                    if(address instanceof Inet4Address && address.isSiteLocalAddress()) {
                        if(candidate!=null && !candidate.equals(address)) return null; // Ambiguous: do not guess.
                        candidate=(Inet4Address)address;
                    }
                }
            }
        } catch(SocketException ignored) {}
        return candidate;
    }
    static boolean apInterface(String name) {
        return name.startsWith("ap") || name.startsWith("wlan") || name.startsWith("swlan") || name.startsWith("softap");
    }
}

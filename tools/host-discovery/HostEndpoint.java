package dev.questheld.host;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.projection.MediaProjection;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.util.Log;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.security.GeneralSecurityException;
import java.util.UUID;

final class HostEndpoint implements AutoCloseable {
    private static final String TAG = "QuestHeldHost";
    final Pairing pairing;
    private final DiscoveryServer server;
    private final NsdManager discovery;
    private final NsdManager.RegistrationListener registration;

    HostEndpoint(Context context, Inet4Address address, MediaProjection projection, StreamSettings settings,
                 Runnable finished)
            throws IOException, GeneralSecurityException {
        SharedPreferences prefs = context.getSharedPreferences("host", Context.MODE_PRIVATE);
        HostIdentity identity = new HostIdentity();
        String hostName = "Amaterasu";
        pairing = new Pairing(prefs, identity);
        server = new DiscoveryServer(address, hostName, hostId(prefs), identity, pairing,
                context.getAssets(), projection, settings, context.getExternalFilesDir("diagnostics"), finished);
        discovery = context.getSystemService(NsdManager.class);
        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName(hostName);
        info.setServiceType("_nvstream._tcp");
        info.setPort(DiscoveryServer.HTTP_PORT);
        registration = new NsdManager.RegistrationListener() {
            @Override public void onServiceRegistered(NsdServiceInfo service) {
                Log.i(TAG, "Registered " + service.getServiceName());
            }
            @Override public void onRegistrationFailed(NsdServiceInfo service, int error) {
                Log.w(TAG, "mDNS registration failed: " + error);
            }
            @Override public void onServiceUnregistered(NsdServiceInfo service) {
                Log.i(TAG, "Unregistered " + service.getServiceName());
            }
            @Override public void onUnregistrationFailed(NsdServiceInfo service, int error) {
                Log.w(TAG, "mDNS unregistration failed: " + error);
            }
        };
        try {
            server.start();
        } catch (RuntimeException e) {
            server.close();
            throw e;
        }
        try { discovery.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration); }
        catch (RuntimeException e) { Log.w(TAG, "mDNS unavailable; manual-IP listeners remain ready", e); }
    }

    private static String hostId(SharedPreferences prefs) {
        String id = prefs.getString("id", null);
        if (id != null) return id;
        id = UUID.randomUUID().toString();
        if (!prefs.edit().putString("id", id).commit()) {
            throw new IllegalStateException("Could not save the host ID");
        }
        return id;
    }

    static Inet4Address wifiAddress(Context context) {
        ConnectivityManager networks = context.getSystemService(ConnectivityManager.class);
        for (Network network : networks.getAllNetworks()) {
            NetworkCapabilities caps = networks.getNetworkCapabilities(network);
            LinkProperties links = networks.getLinkProperties(network);
            if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || links == null) continue;
            for (LinkAddress link : links.getLinkAddresses()) {
                InetAddress address = link.getAddress();
                if (address instanceof Inet4Address && !address.isLinkLocalAddress()) {
                    return (Inet4Address) address;
                }
            }
        }
        return null;
    }

    @Override public void close() {
        try {
            discovery.unregisterService(registration);
        } catch (RuntimeException e) {
            Log.d(TAG, "mDNS registration ended or unavailable", e);
        } finally {
            server.close();
        }
    }
}

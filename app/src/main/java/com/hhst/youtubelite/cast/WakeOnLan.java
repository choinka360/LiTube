package com.hhst.youtubelite.cast;

import java.net.*;
import java.util.*;

public final class WakeOnLan {
    private WakeOnLan() {}
    public static byte[] packet(String mac) {
        String value = mac.replace(":", "").replace("-", "");
        if (!value.matches("[a-fA-F0-9]{12}")) throw new IllegalArgumentException("Enter the TV network adapter MAC address");
        byte[] address = new byte[6];
        for (int i = 0; i < 6; i++) address[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        byte[] packet = new byte[102]; Arrays.fill(packet, 0, 6, (byte) 255);
        for (int i = 0; i < 16; i++) System.arraycopy(address, 0, packet, 6 + i * 6, 6);
        return packet;
    }
    public static void send(String mac) throws Exception {
        byte[] packet = packet(mac);
        Set<InetAddress> broadcasts = new HashSet<>(); broadcasts.add(InetAddress.getByName("255.255.255.255"));
        for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!nic.isUp() || nic.isLoopback()) continue;
            for (InterfaceAddress address : nic.getInterfaceAddresses()) if (address.getBroadcast() != null) broadcasts.add(address.getBroadcast());
        }
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            for (InetAddress address : broadcasts) for (int i = 0; i < 3; i++) socket.send(new DatagramPacket(packet, packet.length, address, 9));
        }
    }
}

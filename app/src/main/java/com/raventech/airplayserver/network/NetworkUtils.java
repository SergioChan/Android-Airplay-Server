package com.raventech.airplayserver.network;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Arrays;
import java.util.Collections;
import java.util.logging.Logger;

public class NetworkUtils {
	
	private static final Logger LOG = Logger.getLogger(NetworkUtils.class.getName());
	private static String m_hostName = "RavenLab-FuckTest1";

	private static NetworkUtils instance;
	public static NetworkUtils getInstance(){
		if(instance == null){
			instance = new NetworkUtils();
		}
		return instance;
	}
	
	private NetworkUtils(){
		
	}
	
	
	/**
	 * Returns a suitable hardware address.
	 * 
	 * @return a MAC address
	 */
	public byte[] getHardwareAddress() {
		try {
			/* Search network interfaces for an interface with a valid, non-blocked hardware address */
	    	for(final NetworkInterface iface: Collections.list(NetworkInterface.getNetworkInterfaces())) {
	    		if (iface.isLoopback()){
	    			continue;
	    		}
	    		if (iface.isPointToPoint()){
	    			continue;
	    		}

	    		try {
		    		final byte[] ifaceMacAddress = iface.getHardwareAddress();
		    		if ((ifaceMacAddress != null) && (ifaceMacAddress.length == 6) && !isBlockedHardwareAddress(ifaceMacAddress)) {
		    			LOG.info("Hardware address is " + toHexString(ifaceMacAddress) + " (" + iface.getDisplayName() + ")");
		    	    	return Arrays.copyOfRange(ifaceMacAddress, 0, 6);
		    		}
	    		}
	    		catch (final Throwable e) {
	    			/* Ignore */
	    		}
	    	}
		}
		catch (final Throwable e) {
			/* Ignore */
		}

		/* Fallback to the IP address padded to 6 bytes */
		try {
			final byte[] hostAddress = Arrays.copyOfRange(InetAddress.getLocalHost().getAddress(), 0, 6);
			LOG.info("Hardware address is " + toHexString(hostAddress) + " (IP address)");
			return hostAddress;
		}
		catch (final Throwable e) {
			/* Ignore */
		}

		/* Fallback to a constant */
		LOG.info("Hardware address is 00DEADBEEF00 (last resort)");
		return new byte[] {(byte)0x00, (byte)0xDE, (byte)0xAD, (byte)0xBE, (byte)0xEF, (byte)0x00};
	}
	
	/**
	 * Converts an array of bytes to a hexadecimal string
	 * 
	 * @param bytes array of bytes
	 * @return hexadecimal representation
	 */
	private String toHexString(final byte[] bytes) {
		final StringBuilder s = new StringBuilder();
		for(final byte b: bytes) {
			final String h = Integer.toHexString(0x100 | b);
			s.append(h.substring(h.length() - 2, h.length()).toUpperCase());
		}
		return s.toString();
	}
	
	/**
	 * Decides whether or nor a given MAC address is the address of some
	 * virtual interface, like e.g. VMware's host-only interface (server-side).
	 * 
	 * @param addr a MAC address
	 * @return true if the MAC address is unsuitable as the device's hardware address
	 */
	public boolean isBlockedHardwareAddress(final byte[] addr) {
		if ((addr[0] & 0x02) != 0)
			/* Locally administered */
			return true;
		else if ((addr[0] == 0x00) && (addr[1] == 0x50) && (addr[2] == 0x56))
			/* VMware */
			return true;
		else if ((addr[0] == 0x00) && (addr[1] == 0x1C) && (addr[2] == 0x42))
			/* Parallels */
			return true;
		else if ((addr[0] == 0x00) && (addr[1] == 0x25) && (addr[2] == (byte)0xAE))
			/* Microsoft */
			return true;
		else
			return false;
	}

	public void setHostName(String str) {
		m_hostName = str;
	}

	public String getHostUtils() {
		return m_hostName;
	}

	public String getHardwareAddressString() {
		byte[] hardwareAddressBytes = getHardwareAddress();
		return toHexString(hardwareAddressBytes);
	}
	
}

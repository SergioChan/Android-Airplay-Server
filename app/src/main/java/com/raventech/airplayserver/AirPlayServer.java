package com.raventech.airplayserver;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;
import com.raventech.airplayserver.network.NetworkUtils;
import com.raventech.airplayserver.network.raop.RaopRtspPipelineFactory;

import org.jboss.netty.bootstrap.ServerBootstrap;
import org.jboss.netty.channel.ChannelHandler;
import org.jboss.netty.channel.group.ChannelGroup;
import org.jboss.netty.channel.group.ChannelGroupFuture;
import org.jboss.netty.channel.group.DefaultChannelGroup;
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory;
import org.jboss.netty.handler.execution.ExecutionHandler;
import org.jboss.netty.handler.execution.OrderedMemoryAwareThreadPoolExecutor;

/**
 * Android AirPlay Server Implementation
 * 
 * @author Rafael Almeida
 *
 */
public class AirPlayServer implements Runnable {

	private static final Logger LOG = Logger.getLogger(AirPlayServer.class.getName());
	
	/**
	 * The AirTunes/RAOP service type
	 */
	static final String AIR_TUNES_SERVICE_TYPE = "_raop._tcp.local.";
	
	/**
	 * The AirTunes/RAOP M-DNS service properties (TXT record)
	 */
	static final Map<String, String> AIRTUNES_SERVICE_PROPERTIES = Utils.map(
            "txtvers", "1",
            "tp", "UDP",
            "ch", "2",
            "ss", "16",
            "sr", "44100",
            "pw", "false",
            "sm", "false",
            "sv", "false",
            "ek", "1",
            "et", "0,1",
            "cn", "0,1",
            "vn", "3");
	
	private static AirPlayServer instance = null;
	public static AirPlayServer getIstance(){
		if(instance == null){
			instance = new AirPlayServer();
		}
		return instance;
	}

	public boolean isOn = false;
	
	/**
	 * Global executor service. Used e.g. to initialize the various netty channel factories 
	 */
	protected ExecutorService executorService;
	
	/**
	 * Channel execution handler. Spreads channel message handling over multiple threads
	 */
	protected ExecutionHandler channelExecutionHandler;
	
	/**
	 * All open RTSP channels. Used to close all open challens during shutdown.
	 */
	protected ChannelGroup channelGroup;
	
	/**
	 * JmDNS instances (one per IP address). Used to unregister the mDNS services
	 * during shutdown.
	 */
	protected List<JmDNS> jmDNSInstances;
	
	/**
	 * The AirTunes/RAOP RTSP port
	 */
	private int rtspPort = 5000; //default value
	
	private AirPlayServer(){
		//create executor service
		executorService = Executors.newCachedThreadPool();
		
		//create channel execution handler
		channelExecutionHandler = new ExecutionHandler(new OrderedMemoryAwareThreadPoolExecutor(4, 0, 0));
	
		//channel group
		channelGroup = new DefaultChannelGroup();
		
		//list of mDNS services
		jmDNSInstances = new java.util.LinkedList<JmDNS>();
	}

	public int getRtspPort() {
		return rtspPort;
	}

	public void setRtspPort(int rtspPort) {
		this.rtspPort = rtspPort;
	}

	public void run() {
		
		startService();
	}

	private void startService() {
		/* Make sure AirPlay Server shuts down gracefully */
    	Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
			@Override
			public void run() {
				onShutdown();
			}
    	}));
    	
    	LOG.info("VM Shutdown Hook added sucessfully!");
    	
    	/* Create AirTunes RTSP server */
		final ServerBootstrap airTunesRtspBootstrap = new ServerBootstrap(new NioServerSocketChannelFactory(executorService, executorService));
		airTunesRtspBootstrap.setPipelineFactory(new RaopRtspPipelineFactory());
		airTunesRtspBootstrap.setOption("reuseAddress", true);
		airTunesRtspBootstrap.setOption("child.tcpNoDelay", true);
		airTunesRtspBootstrap.setOption("child.keepAlive", true);
		
		try {
			channelGroup.add(airTunesRtspBootstrap.bind(new InetSocketAddress(Inet4Address.getByName("0.0.0.0"), getRtspPort())));
		} 
		catch (UnknownHostException e) {
			LOG.log(Level.SEVERE, "Failed to bind RTSP Bootstrap on port: " + getRtspPort(), e);
		}
		
        LOG.info("Launched RTSP service on port " + getRtspPort());

        //get Network details
        NetworkUtils networkUtils = NetworkUtils.getInstance();
        
        String hostName = networkUtils.getHostUtils();
		String hardwareAddressString = networkUtils.getHardwareAddressString();
        isOn = true;
		try {
	    	/* Create mDNS responders. */
	        synchronized(jmDNSInstances) {
		    	for(final NetworkInterface iface: Collections.list(NetworkInterface.getNetworkInterfaces())) {
		    		if ( iface.isLoopback() ){
		    			continue;
		    		}
		    		if ( iface.isPointToPoint()){
		    			continue;
		    		}
		    		if ( ! iface.isUp()){
		    			continue;
		    		}
	
		    		for(final InetAddress addr: Collections.list(iface.getInetAddresses())) {
		    			if ( ! (addr instanceof Inet4Address) && ! (addr instanceof Inet6Address) ){
		    				continue;
		    			}
	
						try {
							/* Create mDNS responder for address */
					    	final JmDNS jmDNS = JmDNS.create(addr, hostName + "-jmdns");
					    	jmDNSInstances.add(jmDNS);
	
					        /* Publish RAOP service */
					        final ServiceInfo airTunesServiceInfo = ServiceInfo.create(
					    		AIR_TUNES_SERVICE_TYPE,
					    		hardwareAddressString + "@" + hostName,
					    		getRtspPort(),
					    		0 /* weight */, 0 /* priority */,
					    		AIRTUNES_SERVICE_PROPERTIES
					    	);
					        jmDNS.registerService(airTunesServiceInfo);
							LOG.info("Registered AirTunes service '" + airTunesServiceInfo.getName() + "' on " + addr);
						}
						catch (final Throwable e) {
							LOG.log(Level.SEVERE, "Failed to publish service on " + addr, e);
						}
		    		}
		    	}
	        }

		} 
		catch (SocketException e) {
			LOG.log(Level.SEVERE, "Failed register mDNS services", e);
		}
	}

	//When the app is shutdown
	protected void onShutdown() {
		/* Close channels */
		final ChannelGroupFuture allChannelsClosed = channelGroup.close();

		/* Stop all mDNS responders */
		synchronized(jmDNSInstances) {
			for(final JmDNS jmDNS: jmDNSInstances) {
				try {
					jmDNS.unregisterAllServices();
					LOG.info("Unregistered all services on " + jmDNS.getInterface());
				}
				catch (final IOException e) {
					LOG.log(Level.WARNING, "Failed to unregister some services", e);
					
				}
			}
		}

		/* Wait for all channels to finish closing */
		allChannelsClosed.awaitUninterruptibly();
		
		/* Stop the ExecutorService */
		executorService.shutdown();

		/* Release the OrderedMemoryAwareThreadPoolExecutor */
		channelExecutionHandler.releaseExternalResources();
		isOn = false;
		
	}

	public ChannelHandler getChannelExecutionHandler() {
		return channelExecutionHandler;
	}

	public ChannelGroup getChannelGroup() {
		return channelGroup;
	}

	public ExecutorService getExecutorService() {
		return executorService;
	}

}

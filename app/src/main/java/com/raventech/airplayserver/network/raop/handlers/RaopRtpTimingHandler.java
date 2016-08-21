/*
 * This file is part of AirReceiver.
 *
 * AirReceiver is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.

 * AirReceiver is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with AirReceiver.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.raventech.airplayserver.network.raop.handlers;

import java.util.logging.Logger;

import com.raventech.airplayserver.network.raop.RaopRtpPacket;
import com.raventech.airplayserver.network.raop.RaopRtpPacket.TimingRequest;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelHandler;
import com.raventech.airplayserver.audio.AudioClock;
import com.raventech.airplayserver.network.RunningExponentialAverage;

/**
 * Handles RTP timing.
 * <p>
 * Keeps track of the offset between the local audio clock and the remote clock,
 * and uses the information to re-sync the audio output queue upon receiving a
 * sync packet.
 */
public class RaopRtpTimingHandler extends SimpleChannelHandler {
	private static Logger LOG = Logger.getLogger(RaopRtpTimingHandler.class.getName());

	/**
	 * Number of seconds between {@link TimingRequest}s.
	 */
	public static final double TIME_REQUEST_INTERVAL = 3;

	/**
	 * Thread which sends out {@link TimingRequests}s.
	 */
	private class TimingRequester implements Runnable {
		private final Channel channel;

		public TimingRequester(final Channel channel) {
			this.channel = channel;
		}

		@Override
		public void run() {
			while ( ! Thread.currentThread().isInterrupted() ) {
				final TimingRequest timingRequestPacket = new TimingRequest();
				
				timingRequestPacket.getReceivedTime().setDouble(0); /* Set by the source */
				timingRequestPacket.getReferenceTime().setDouble(0); /* Set by the source */
				timingRequestPacket.getSendTime().setDouble(audioClock.getNowSecondsTime());

				LOG.info("sending timingRequestPacket: " + timingRequestPacket);
				
				channel.write(timingRequestPacket);
				try {
					Thread.sleep(Math.round(TIME_REQUEST_INTERVAL * 1000));
				}
				catch (final InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
		}
	}

	/**
	 * Audio time source
	 */
	private final AudioClock audioClock;
	
	/**
	 * Exponential averager used to smooth the remote seconds offset
	 */
	private final RunningExponentialAverage averageRemoteSecondsOffset = new RunningExponentialAverage();
	
	/**
	 * The {@link TimingRequester} thread.
	 */
	private Thread synchronizationThread;

	private boolean started = false;
	
	public RaopRtpTimingHandler(final AudioClock audioClock) {
		this.audioClock = audioClock;
	}

	@Override
	public void channelOpen(final ChannelHandlerContext ctx, final ChannelStateEvent evt) throws Exception {
		
		channelClosed(ctx, evt);

		/* create synchronization thread if it isn't already running */
		if (synchronizationThread == null) {
			synchronizationThread = new Thread(new TimingRequester(ctx.getChannel()));
			synchronizationThread.setDaemon(true);
			synchronizationThread.setName("Time Synchronizer");
		}
		
		super.channelOpen(ctx, evt);
	}
	
	public synchronized void startTimeSync(){
		/* Start synchronization thread if it isn't already running */
		if (synchronizationThread != null && ! started) {
			synchronizationThread.start();
			LOG.info("Time synchronizer started");
			started = true;
		}
	}

	@Override
	public void channelClosed(final ChannelHandlerContext ctx, final ChannelStateEvent evt)
		throws Exception
	{
		synchronized(this) {
			if (synchronizationThread != null)
				synchronizationThread.interrupt();
		}
	}

	@Override
	public void messageReceived(final ChannelHandlerContext ctx, final MessageEvent evt)
		throws Exception
	{
		if (evt.getMessage() instanceof RaopRtpPacket.Sync){
			syncReceived((RaopRtpPacket.Sync)evt.getMessage());
		}
		else if (evt.getMessage() instanceof RaopRtpPacket.TimingResponse){
			timingResponseReceived((RaopRtpPacket.TimingResponse)evt.getMessage());
		}

		super.messageReceived(ctx, evt);
	}

	private synchronized void timingResponseReceived(final RaopRtpPacket.TimingResponse timingResponsePacket) {
		final double localReceiveSecondsTime = audioClock.getNowSecondsTime();

		/* Compute remove seconds offset, assuming that the transmission times of
		 * the timing requests and the timing response are equal
		 */
		final double localSecondsTime = localReceiveSecondsTime * 0.5 + timingResponsePacket.getReferenceTime().getDouble() * 0.5;
		
		final double remoteSecondsTime = timingResponsePacket.getReceivedTime().getDouble() * 0.5 + timingResponsePacket.getSendTime().getDouble() * 0.5;
		
		final double remoteSecondsOffset = remoteSecondsTime - localSecondsTime;

		/*
		 * Compute the overall transmission time, and use that to compute
		 * a weight of the remoteSecondsOffset we just computed. The idea
		 * here is that the quality of the estimate depends on the difference
		 * between the transmission times of request and response. We cannot
		 * measure those independently, but since they're obviously bound
		 * by the total transmission time (request + response), which we
		 * <b>can</b> measure, we can use that to judge the quality.
		 * 
		 * The constants are picked such that the weight is never larger than
		 * 1e-3, and starts to decrease rapidly for transmission times significantly
		 * larger than 1ms.
		 */
		final double localInterval = localReceiveSecondsTime - timingResponsePacket.getReferenceTime().getDouble();
		
		final double remoteInterval = timingResponsePacket.getSendTime().getDouble() - timingResponsePacket.getReceivedTime().getDouble();
		
		final double transmissionTime = Math.max(localInterval - remoteInterval, 0);
		
		final double weight = 1e-6 / (transmissionTime + 1e-3);

		/* Update exponential average */
		final double remoteSecondsOffsetPrevious = ( ! averageRemoteSecondsOffset.isEmpty() ? averageRemoteSecondsOffset.get() : 0.0);
		averageRemoteSecondsOffset.add(remoteSecondsOffset, weight);
		
		final double secondsTimeAdjustment = averageRemoteSecondsOffset.get() - remoteSecondsOffsetPrevious;

		LOG.info("Timing response with weight " + weight + " indicated offset " + remoteSecondsOffset + " thereby adjusting the averaged offset by " + secondsTimeAdjustment + " leading to the new averaged offset " + averageRemoteSecondsOffset.get());
	}

	private synchronized void syncReceived(final RaopRtpPacket.Sync syncPacket) {
		LOG.info("sync received : " + syncPacket);
		if ( ! averageRemoteSecondsOffset.isEmpty() ) {
			/* If the times are synchronized, we can correct for the transmission
			 * time of the sync packet since it contains the time it was sent as
			 * a source's NTP time.
			 */
			audioClock.setFrameTime(
				syncPacket.getTimeStampMinusLatency(),
				convertRemoteToLocalSecondsTime(syncPacket.getTime().getDouble())
			);
		}
		else {
			/* If the times aren't yet synchronized, we simply assume the sync
			 * packet's transmission time is zero.
			 */
			audioClock.setFrameTime(
				syncPacket.getTimeStampMinusLatency(),
				0.0
			);
			LOG.warning("Times synchronized, cannot correct latency of sync packet");
		}
	}

	/**
	 * Convert remote NTP time (in seconds) to local NTP time (in seconds),
	 * using the offset obtain from the TimingRequest/TimingResponse packets.
	 * 
	 * @param remoteSecondsTime remote NTP time
	 * @return local NTP time
	 */
	private double convertRemoteToLocalSecondsTime(final double remoteSecondsTime) {
		return remoteSecondsTime - averageRemoteSecondsOffset.get();
	}
}

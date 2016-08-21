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

package com.raventech.airplayserver.audio;

import java.util.Arrays;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import android.media.AudioFormat;

import android.media.AudioManager;
import android.media.AudioTrack;

/**
 * Audio output queue.
 * 
 * Serves an an {@link AudioClock} and allows samples to be queued
 * for playback at a specific time.
 */
public class AudioOutputQueue implements AudioClock {
	private static Logger LOG = Logger.getLogger(AudioOutputQueue.class.getName());

	private static final double QUEUE_LENGHT_MAX_SECONDS 	= 10;
	private static final double BUFFER_SIZE_SECONDS 		= 0.05;
	private static final double TIMING_PRECISION 			= 0.001;
	
	/**
	 * Signals that the queue is being closed.
	 * Never transitions from true to false!
	 */
	private volatile boolean closing = false;

	/**
	 *  The line's audio format
	 */
	//private final AudioFormat m_format;

	private int streamType;
	private int sampleRateInHz;
	private int channelConfig;
	private int audioFormat;
	private int bufferSizeInBytes;
	private int mode;
	
	/**
	 * True if the line's audio format is signed but
	 * the requested format was unsigned
	 */
	private final boolean convertUnsignedToSigned;

	/**
	 * Bytes per frame, i.e. number of bytes
	 * per sample times the number of channels
	 */
	private final int bytesPerFrame;

	/**
	 * Sample rate
	 */
	private final double sampleRate;

	/**
	 * Average packet size in frames.
	 * 
	 * It is used to detec the gaps between the packets and 
	 * as the number of silence frames
	 * to write on a queue underrun
	 */
	private final int packetSizeFrames;

	/**
	 * JavaSounds audio output line
	 */
	//private final SourceDataLine m_line;

	/**
	 * Android audio track (replaces the SourceDataLine)
	 */
	private AudioTrack audioTrack;
	
	/**
	 * The last frame written to the line.
	 * Used to generate filler data
	 */
	private final byte[] lineLastFrame;

	/**
	 * Packet queue, indexed by playback time
	 */
	private final ConcurrentSkipListMap<Long, byte[]> frameQueue = new ConcurrentSkipListMap<Long, byte[]>();

	/**
	 * Enqueuer thread
	 */
	private final Thread queueThread = new Thread(new EnQueuer());

	/**
	 * Number of frames appended to the line
	 */
	private long framesWrittenToLine = 0;

	/**
	 * Largest frame time seen so far
	 */
	private long latestSeenFrameTime = 0;

	/**
	 * The frame time corresponding to line time zero
	 */
	private long frameTimeOffset = 0;

	/**
	 * The seconds time corresponding to line time zero
	 */
	private double secondsTimeOffset;

	/**
	 * Requested volume
	 */
	private float requestedVolume = AudioTrack.getMaxVolume();
	
	/**
	 * Current volume
	 */
	private float currentVolume = AudioTrack.getMaxVolume();
	
	public AudioOutputQueue(final AudioStreamInformationProvider streamInfoProvider) {
		convertUnsignedToSigned = true;
		
		//setup the Audio Format options
		streamType = AudioManager.STREAM_MUSIC;
		
		sampleRateInHz = streamInfoProvider.getSampleRate();
		channelConfig = streamInfoProvider.getChannels();
		audioFormat = streamInfoProvider. getAudioFormat();
		
		sampleRate = streamInfoProvider.getSampleRate();
		
		/* Audio format-dependent stuff */
		packetSizeFrames = streamInfoProvider.getFramesPerPacket();
		bytesPerFrame = streamInfoProvider.getChannels() * streamInfoProvider.getSampleSizeInBits() / 8;
		
		//calculate the buffer size in bytes
		bufferSizeInBytes = (int)Math.pow(2, Math.ceil(Math.log(BUFFER_SIZE_SECONDS * sampleRate * bytesPerFrame) / Math.log(2.0)));
		
		mode = AudioTrack.MODE_STREAM;
		
		//create the AudioTrack
		//audioTrack = new AudioTrack(streamType, sampleRateInHz, channelConfig, audioFormat, bufferSizeInBytes, mode);
		audioTrack = new AudioTrack(streamType, sampleRateInHz, AudioFormat.CHANNEL_CONFIGURATION_STEREO, audioFormat, bufferSizeInBytes, mode);//FIXME

		LOG.info("AudioTrack created succesfully with a buffer of : " + bufferSizeInBytes + " bytes and : " + bufferSizeInBytes / bytesPerFrame + " frames.");
			
		//create initial array of "filler" bytes ....
		lineLastFrame = new byte[bytesPerFrame];
		for(int b=0; b < lineLastFrame.length; ++b){
			lineLastFrame[b] = (b % 2 == 0) ? (byte)-128 : (byte)0;
		}

		/* Create enqueuer thread and wait for the line to start.
		 * The wait guarantees that the AudioClock functions return
		 * sensible values right after construction
		 */
		queueThread.setDaemon(true);
		queueThread.setName("Audio Enqueuer");
		queueThread.setPriority(Thread.MAX_PRIORITY);
		
		/*
		queueThread.start();
		
		//while ( queueThread.isAlive() && ! m_line.isActive() ){
		while ( queueThread.isAlive() && audioTrack.getPlayState() != AudioTrack.PLAYSTATE_PLAYING){
			Thread.yield();
		}
		*/

		/* Initialize the seconds time offset now that the line is running. */
		secondsTimeOffset = 2208988800.0 +  System.currentTimeMillis() * 1e-3;
	}
	
	public void startAudioProcessing(){
		queueThread.start();
		
		//while ( queueThread.isAlive() && ! m_line.isActive() ){
		while ( queueThread.isAlive() && audioTrack.getPlayState() != AudioTrack.PLAYSTATE_PLAYING){
			Thread.yield();
		}

		/* Initialize the seconds time offset now that the line is running. */
		secondsTimeOffset = 2208988800.0 +  System.currentTimeMillis() * 1e-3;
	}
	
	/**
	 * Enqueuer thread
	 */
	private class EnQueuer implements Runnable {
		/**
		 * Enqueuer thread main method
		 */
		@Override
		public void run() {
			try {
				/* Mute line initially to prevent clicks */
				setVolume(Float.NEGATIVE_INFINITY);

				/* Start the line */
				//m_line.start();
				//start the audio track
				audioTrack.play();
				
				LOG.info("Audio Track started !!!");
				
				boolean lineMuted = true;
				boolean didWarnGap = false;
				while ( ! closing) {
					if ( ! frameQueue.isEmpty() ) {
						/* Queue filled */

						/* If the gap between the next packet and the end of line is
						 * negligible (less than one packet), we write it to the line.
						 * Otherwise, we fill the line buffer with silence and hope for
						 * further packets to appear in the queue
						 */
						final long entryFrameTime = frameQueue.firstKey();
						final long entryLineTime = convertFrameToLineTime(entryFrameTime);
						final long gapFrames = entryLineTime - getNextLineTime();
						
						
						//LOG.info("** gapFrames: " + gapFrames + " packetSizeFrames: " +  packetSizeFrames);
						
						if (gapFrames < -packetSizeFrames) {
							/* Too late for playback */
							LOG.warning("Audio data was scheduled for playback " + (-gapFrames) + " frames ago, skipping");
							frameQueue.remove(entryFrameTime);
							continue;
						}
						else if (gapFrames < packetSizeFrames) {
							/* Negligible gap between packet and line end. Prepare packet for playback */
							didWarnGap = false;

							/* Unmute line in case it was muted previously */
							if (lineMuted) {
								LOG.info("Audio data available, un-muting line");

								lineMuted = false;
								applyVolume();
							}
							else if (getVolume() != getRequestedVolume()) {
								applyVolume();
							}

							/* Get sample data and do sanity checks */
							final byte[] nextPlaybackSamples = frameQueue.remove(entryFrameTime);
							int nextPlaybackSamplesLength = nextPlaybackSamples.length;
							if (nextPlaybackSamplesLength % bytesPerFrame != 0) {
								LOG.severe("Audio data contains non-integral number of frames, ignore last " + (nextPlaybackSamplesLength % bytesPerFrame) + " bytes");
								nextPlaybackSamplesLength -= nextPlaybackSamplesLength % bytesPerFrame;
							}

							/* Append packet to line */
							LOG.finest("Audio data containing " + nextPlaybackSamplesLength / bytesPerFrame + " frames for playback time " + entryFrameTime + " found in queue, appending to the output line");
							
							appendFrames(nextPlaybackSamples, 0, nextPlaybackSamplesLength, entryLineTime);
							
							continue;
						}
						else {
							/* Gap between packet and line end. Warn */
							if ( ! didWarnGap) {
								didWarnGap = true;
								LOG.warning("Audio data missing for frame time " + getNextLineTime() + " (currently " + gapFrames + " frames), writing " + packetSizeFrames + " frames of silence");
							}
						}
					}
					else {
						/* Queue empty */

						if ( ! lineMuted) {
							lineMuted = true;
							setVolume(Float.NEGATIVE_INFINITY);
							LOG.fine("Audio data ended at frame time " + getNextLineTime() + ", writing " + packetSizeFrames + " frames of silence and muted line");
						}
					}

					appendSilence(packetSizeFrames);
				}

				//TODO: I don't think we need the appendSilence anymore when using Android API, but will evaluate that later during tests
				/* Before we exit, we fill the line's buffer with silence. This should prevent
				 * noise from being output while the line is being stopped
				 */
				//appendSilence(m_line.available() / m_bytesPerFrame);
			}
			catch (final Throwable e) {
				LOG.log(Level.SEVERE, "Audio output thread died unexpectedly", e);
			}
			finally {
				setVolume(Float.NEGATIVE_INFINITY);
				audioTrack.stop();
				audioTrack.release();
				//m_line.stop();
				//m_line.close();
			}
		}

		/**
		 * Append the range [off,off+len) from the provided sample data to the line.
		 * If the requested playback time does not match the line end time, samples are
		 * skipped or silence is inserted as necessary. If the data is marked as being
		 * just a filler, some warnings are suppressed.
		 *
		 * @param samples sample data
		 * @param off sample data offset
		 * @param len sample data length
		 * @param time playback time
		 * @param warnNonContinous warn about non-continous samples
		 */
		private void appendFrames(final byte[] samples, int off, final int len, long lineTime) {
			assert off % bytesPerFrame == 0;
			assert len % bytesPerFrame == 0;

			while (true) {
				/* Fetch line end time only once per iteration */
				final long endLineTime = getNextLineTime();

				final long timingErrorFrames = lineTime - endLineTime;
				final double timingErrorSeconds = timingErrorFrames / sampleRate;

				if (Math.abs(timingErrorSeconds) <= TIMING_PRECISION) {
					/* Samples to append scheduled exactly at line end. Just append them and be done */
					appendFrames(samples, off, len);
					break;
				}
				else if (timingErrorFrames > 0) {
					/* Samples to append scheduled after the line end. Fill the gap with silence */
					LOG.warning("Audio output non-continous (gap of " + timingErrorFrames + " frames), filling with silence");

					appendSilence((int)(lineTime - endLineTime));
				}
				else if (timingErrorFrames < 0) {
					/* Samples to append scheduled before the line end. Remove the overlapping
					 * part and retry
					 */
					LOG.warning("Audio output non-continous (overlap of " + (-timingErrorFrames) + "), skipping overlapping frames");

					off += (endLineTime - lineTime) * bytesPerFrame;
					lineTime += endLineTime - lineTime;
				}
				else {
					/* Strange universe... */
					assert false;
				}
			}
		}

		private void appendSilence(final int frames) {
			LOG.info("Appending Silence to the AudioTrack. frames: " + frames);
			
			final byte[] silenceFrames = new byte[frames * bytesPerFrame];
			for(int i = 0; i < silenceFrames.length; ++i){
				silenceFrames[i] = lineLastFrame[i % bytesPerFrame];
			}
			appendFrames(silenceFrames, 0, silenceFrames.length);
		}

		/**
		 * Append the range [off, off+len) from the provided sample data to the line.
		 *
		 * @param samples sample data
		 * @param off sample data offset
		 * @param len sample data length
		 */
		private void appendFrames(final byte[] samples, int off, int len) {
			assert off % bytesPerFrame == 0;
			assert len % bytesPerFrame == 0;

			/* Make sure that [off, off+len) does not exceed sample's bounds */
			off = Math.min(off, (samples != null) ? samples.length : 0);
			
			len = Math.min(len, (samples != null) ? samples.length - off : 0);
			
			if (len <= 0){
				return;
			}

			/* Convert samples if necessary */
			final byte[] samplesConverted = Arrays.copyOfRange(samples, off, off + len);

			byte bytTemp = 0x00;
			if (convertUnsignedToSigned) {
				/* The line expects signed PCM samples, so we must
				 * convert the unsigned PCM samples to signed.
				 * Note that this only affects the high bytes!
				 */
				for(int i=0; i < samplesConverted.length; i += 2)
				{
					samplesConverted[i] = (byte) ((samplesConverted[i] & 0xff) - 0x80);
					//add by ville
					bytTemp = samplesConverted[i];
					samplesConverted[i] = samplesConverted[i + 1];
					samplesConverted[i + 1] = bytTemp;
				}
				//for(int i=0; i < samplesConverted.length; i += 2){
				//	samplesConverted[i] = (byte)((samplesConverted[i] & 0xff) - 0x80);
				//}
			}


			/* Write samples to line */
			//final int bytesWritten = m_line.write(samplesConverted, 0, samplesConverted.length);
			final int bytesWritten = audioTrack.write(samplesConverted, 0, samplesConverted.length);
			
			if(bytesWritten == AudioTrack.ERROR_INVALID_OPERATION){
				LOG.severe("Audio Track not initialized properly");
				throw new RuntimeException("Audio Track not initialized properly: AudioTrack status: ERROR_INVALID_OPERATION");
			}
			else if(bytesWritten == AudioTrack.ERROR_BAD_VALUE){
				LOG.severe("Wrong parameters sent to Audio Track!");
				throw new RuntimeException("Wrong parameters sent to Audio Track! AudioTrack status: ERROR_BAD_VALUE");
			}
			else if (bytesWritten != len){
				LOG.warning("Audio output line accepted only " + bytesWritten + " bytes of sample data while trying to write " + samples.length + " bytes");
			}
			else{
				LOG.info(bytesWritten + " bytes written to the audio output line");
			}
			
			/* Update state */
			synchronized(AudioOutputQueue.this) {
				framesWrittenToLine += (bytesWritten / bytesPerFrame);
				
				for(int b=0; b < bytesPerFrame; ++b){
					lineLastFrame[b] = samples[off + len - (bytesPerFrame - b)];
				}

				if(LOG.isLoggable(Level.FINE)){
					LOG.finest("Audio output line end is now at " + getNextLineTime() + " after writing " + len / bytesPerFrame + " frames");
				}
			}
		}
	}

	/**
	 * Sets the AudioTrack the Stereo Volume
	 *
	 * @param volume
	 * 
	 */
	private void setVolume(float volume) {
		currentVolume = volume;
		setStereoVolume(volume, volume);
	}

	/**
	 * Sets the AudioTrack the Stereo Volume
	 *
	 * @param leftVolume
	 * @param rightVolume
	 * 
	 */
	private void setStereoVolume(float leftVolume, float rightVolume) {
		
		
		//leftVolume = AudioTrack.getMaxVolume();
		//rightVolume = AudioTrack.getMaxVolume();
		
		//validate left volume
		if(leftVolume < AudioTrack.getMinVolume()){
			leftVolume = AudioTrack.getMinVolume();
		}
		if(leftVolume > AudioTrack.getMaxVolume()){
			leftVolume = AudioTrack.getMaxVolume();
		}
		
		//validate right volume
		if(rightVolume < AudioTrack.getMinVolume()){
			rightVolume = AudioTrack.getMinVolume();
		}
		if(rightVolume > AudioTrack.getMaxVolume()){
			rightVolume = AudioTrack.getMaxVolume();
		}
		
		LOG.info("setStereoVolume() leftVolume: " + leftVolume + " rightVolume: " + rightVolume);
		
		audioTrack.setStereoVolume(leftVolume, rightVolume);
	}

	/**
	 * Returns the line's MASTER_GAIN control's value.
	 */
	private float getVolume() {
		return currentVolume;
	}

	private synchronized void applyVolume() {
		setVolume(requestedVolume);
	}

	/**
	 * Sets the desired output gain.
	 *
	 * @param volume desired gain
	 */
	public synchronized void setRequestedVolume(final float volume) {
		requestedVolume = volume;
	}

	/**
	 * Returns the desired output gain.
	 *
	 * @param gain desired gain
	 */
	public synchronized float getRequestedVolume() {
		return requestedVolume;
	}

	/**
	 * Stops audio output
	 */
	public void close() {
		closing = true;
		queueThread.interrupt();
	}

	/**
	 * Adds sample data to the queue
	 *
	 * @param playbackRemoteStartFrameTime start time of sample data
	 * @param playbackSamples sample data
	 * @return true if the sample data was added to the queue
	 */
	public synchronized boolean enqueue(final long frameTime, final byte[] frames) {
		/* Playback time of packet */
		final double packetSeconds = (double)frames.length / (double)(bytesPerFrame * sampleRate);
		
		/* Compute playback delay, i.e., the difference between the last sample's
		 * playback time and the current line time
		 */
		long nextLineTime = getNextLineTime();
		long frameToLineTime = convertFrameToLineTime(frameTime); 
		final double delay = (frameToLineTime + frames.length / bytesPerFrame - nextLineTime) / sampleRate;

		latestSeenFrameTime = Math.max(latestSeenFrameTime, frameTime);
		
		LOG.info(" delay: " + delay );
		
		if (delay < -packetSeconds) {//pass this branch ,it cause the audio on and off
			/* The whole packet is scheduled to be played in the past */
			LOG.warning("Audio data arrived " + -(delay) + " seconds too late, dropping");
			//return false;
		}
		else if (delay > QUEUE_LENGHT_MAX_SECONDS) {
			/* The packet extends further into the future that our maximum queue size.
			 * We reject it, since this is probably the result of some timing discrepancies
			 */
			LOG.warning("Audio data arrived " + delay + " seconds too early, dropping");
			return false;
		}

		LOG.info("frames added to the frameQueue. frameTime: " + frameTime + " frames.length: " + frames.length + " frames: " + frames);
		
		frameQueue.put(frameTime, frames);
		return true;
	}

	/**
	 * Removes all currently queued sample data
	 */
	public void flush() {
		frameQueue.clear();
	}

	@Override
	public synchronized void setFrameTime(final long frameTime, final double secondsTime) {
		final double ageSeconds = getNowSecondsTime() - secondsTime;
		final long lineTime = Math.round((secondsTime - secondsTimeOffset) * sampleRate);

		final long frameTimeOffsetPrevious = frameTimeOffset;
		frameTimeOffset = frameTime - lineTime;

		LOG.info("Frame time adjusted by " + (frameTimeOffset - frameTimeOffsetPrevious) + " based on timing information " + ageSeconds + " seconds old and " + (latestSeenFrameTime - frameTime) + " frames before latest seen frame time. previous: " + frameTimeOffsetPrevious + " new frameTimeOffset: " + frameTimeOffset);
	}

	@Override
	public double getNowSecondsTime() {
		double value = secondsTimeOffset + getNowLineTime() / sampleRate; 
		//LOG.info("getNowSecondsTime(): " + value);
		return value;
	}

	//@Override
	//public long getNowFrameTime() {
	//	return frameTimeOffset + getNowLineTime();
	//}

	@Override
	public double getNextSecondsTime() {
		return secondsTimeOffset + getNextLineTime() / sampleRate;
	}

	@Override
	public long getNextFrameTime() {
		return frameTimeOffset + getNextLineTime();
	}

	@Override
	public double convertFrameToSecondsTime(final long frameTime) {
		return secondsTimeOffset + (frameTime - frameTimeOffset) / sampleRate;
	}

	private synchronized long getNextLineTime() {
		return framesWrittenToLine;
	}

	private long getNowLineTime() {
		//getPlaybackHeadPosition()
		//getNotificationMarkerPosition
		//return audioTrack.getNotificationMarkerPosition();
		//return m_line.getLongFramePosition();
		if(audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING){
			long value = audioTrack.getPlaybackHeadPosition();
			return value;
		}
		else{
			LOG.warning("getNowLineTime() called while audioTrack is not on a Playing State");
			return 0;
		}
	}

	private synchronized long convertFrameToLineTime(final long entryFrameTime) {
		return entryFrameTime - frameTimeOffset;
	}
}

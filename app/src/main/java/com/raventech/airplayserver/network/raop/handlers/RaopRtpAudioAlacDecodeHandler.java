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

import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.raventech.airplayserver.audio.AudioStreamInformationProvider;
import com.raventech.airplayserver.network.raop.RaopRtpPacket;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.handler.codec.oneone.OneToOneDecoder;
import com.raventech.airplayserver.network.ProtocolException;

import android.media.AudioFormat;

import alacdecoder.AlacDecodeUtils;
import alacdecoder.AlacFile;

/**
 * Decodes the ALAC audio data in incoming audio packets to big endian unsigned PCM.
 * Also serves as an {@link AudioStreamInformationProvider}
 * 
 * This class assumes that ALAC requires no inter-packet state - it doesn't make
 * any effort to feed the packets to ALAC in the correct order.
 */
public class RaopRtpAudioAlacDecodeHandler extends OneToOneDecoder implements AudioStreamInformationProvider {
	
	private static Logger LOG = Logger.getLogger(RaopRtpAudioAlacDecodeHandler.class.getName());

	/* There are the indices into the SDP format options at which
	 * the sessions appear
	 */
	
	public static final int FORMAT_OPTION_SAMPLES_PER_FRAME = 0;
	public static final int FORMAT_OPTION_7a = 1;
	public static final int FORMAT_OPTION_BITS_PER_SAMPLE = 2;
	public static final int FORMAT_OPTION_RICE_HISTORY_MULT = 3;
	public static final int FORMAT_OPTION_RICE_INITIAL_HISTORY = 4;
	public static final int FORMAT_OPTION_RICE_KMODIFIER = 5;
	public static final int FORMAT_OPTION_7f = 6;
	public static final int FORMAT_OPTION_80 = 7;
	public static final int FORMAT_OPTION_82 = 8;
	public static final int FORMAT_OPTION_86 = 9;
	public static final int FORMAT_OPTION_8a_RATE = 10;

	/**
	 * Number of samples per ALAC frame (packet).
	 * One sample here means *two* amplitues, one
	 * for the left channel and one for the right
	 */
	private final int samplesPerFrame;
	
	/* We support only 44100 kHz */
	private final int sampleRate = 44100;
	
	private final int channels = 2;
	
	//16 bit
	private final int audioFormat = AudioFormat.ENCODING_PCM_16BIT;

	private final int sampleSizeInBits = 16;
	
	/**
	 * Decoder state
	 */
	private final AlacFile alacFile;

	/**
	 * Creates an ALAC decoder instance from a list of format options as
	 * they appear in the SDP session announcement.
	 * 
	 * @param formatOptions list of format options
	 * @throws ProtocolException if the format options are invalid for ALAC
	 */
	public RaopRtpAudioAlacDecodeHandler(final String[] formatOptions) throws ProtocolException {
		
		samplesPerFrame = Integer.valueOf(formatOptions[FORMAT_OPTION_SAMPLES_PER_FRAME]);

		/* We support only 16-bit ALAC */
		if ( Integer.valueOf(formatOptions[FORMAT_OPTION_BITS_PER_SAMPLE]) != sampleSizeInBits ) {
			throw new ProtocolException("Sample size must be 16, but was " + Integer.valueOf(formatOptions[FORMAT_OPTION_BITS_PER_SAMPLE]));
		}

		/* We support only 44100 kHz */
		int tempSampleRate = Integer.valueOf(formatOptions[FORMAT_OPTION_8a_RATE]);
		if (tempSampleRate != getSampleRate()){
			throw new ProtocolException("Sample rate must be " + getSampleRate() + ", but was " + tempSampleRate);
		}

		alacFile = AlacDecodeUtils.create_alac(getSampleSizeInBits(), getChannels());
		
		alacFile.setinfo_max_samples_per_frame = samplesPerFrame;
		alacFile.setinfo_7a = Integer.valueOf(formatOptions[FORMAT_OPTION_7a]);
		alacFile.setinfo_sample_size = getSampleSizeInBits();
		alacFile.setinfo_rice_historymult = Integer.valueOf(formatOptions[FORMAT_OPTION_RICE_HISTORY_MULT]);
		alacFile.setinfo_rice_initialhistory = Integer.valueOf(formatOptions[FORMAT_OPTION_RICE_INITIAL_HISTORY]);
		alacFile.setinfo_rice_kmodifier = Integer.valueOf(formatOptions[FORMAT_OPTION_RICE_KMODIFIER]);
		alacFile.setinfo_7f = Integer.valueOf(formatOptions[FORMAT_OPTION_7f]);
		alacFile.setinfo_80 = Integer.valueOf(formatOptions[FORMAT_OPTION_80]);
		alacFile.setinfo_82 = Integer.valueOf(formatOptions[FORMAT_OPTION_82]);
		alacFile.setinfo_86 = Integer.valueOf(formatOptions[FORMAT_OPTION_86]);
		alacFile.setinfo_8a_rate = sampleRate;

		LOG.info("Created ALAC decode for options " + Arrays.toString(formatOptions));
	}

	@Override
	protected synchronized Object decode(final ChannelHandlerContext ctx, final Channel channel, final Object msg)
		throws Exception {
		
		//check for a Audio message
		if ( ! (msg instanceof RaopRtpPacket.Audio)){
			return msg;
		}

		final RaopRtpPacket.Audio alacPacket = (RaopRtpPacket.Audio)msg;

		/* The ALAC decode sometimes reads beyond the input's bounds
		 * (but later discards the data). To alleviate, we allocate
		 * 3 spare bytes at input buffer's end.
		 */
		final byte[] alacBytes = new byte[alacPacket.getPayload().capacity() + 3];
		alacPacket.getPayload().getBytes(0, alacBytes, 0, alacPacket.getPayload().capacity());

		/* Decode ALAC to PCM */
		final int[] pcmSamples = new int[samplesPerFrame * 2];
		final int pcmSamplesBytes = AlacDecodeUtils.decode_frame(alacFile, alacBytes, pcmSamples, samplesPerFrame);

		/* decode_frame() returns the number of *bytes*, not samples! */
		final int pcmSamplesLength = pcmSamplesBytes / 4;
		final Level level = Level.FINEST;
		if (LOG.isLoggable(level)){
			LOG.log(level, "Decoded " + alacBytes.length + " bytes of ALAC audio data to " + pcmSamplesLength + " PCM samples");
		}

		/* Complain if the sender doesn't honour it's commitment */
		if (pcmSamplesLength != samplesPerFrame){
			throw new ProtocolException("Frame declared to contain " + samplesPerFrame + ", but contained " + pcmSamplesLength);
		}

		/* Assemble PCM audio packet from original packet header and decoded data.
		 * The ALAC decode emits signed PCM samples as integers. We store them as
		 * as unsigned big endian integers in the packet.
		 */
		
		RaopRtpPacket.Audio pcmPacket;
		if (alacPacket instanceof RaopRtpPacket.AudioTransmit) {
			pcmPacket = new RaopRtpPacket.AudioTransmit(pcmSamplesLength * 4);
			alacPacket.getBuffer().getBytes(0, pcmPacket.getBuffer(), 0, RaopRtpPacket.AudioTransmit.LENGTH);
		}
		else if (alacPacket instanceof RaopRtpPacket.AudioRetransmit) {
			pcmPacket = new RaopRtpPacket.AudioRetransmit(pcmSamplesLength * 4);
			alacPacket.getBuffer().getBytes(0, pcmPacket.getBuffer(), 0, RaopRtpPacket.AudioRetransmit.LENGTH);
		}
		else{
			throw new ProtocolException("Packet type " + alacPacket.getClass() + " is not supported by the ALAC decoder");
		}

		//for each PCM sample
		for(int i=0; i < pcmSamples.length; ++i) {
			/* Convert sample to big endian unsigned integer PCM */
			final int pcmSampleUnsigned = pcmSamples[i] + 0x8000;

			pcmPacket.getPayload().setByte(2*i, (pcmSampleUnsigned & 0xff00) >> 8);
			pcmPacket.getPayload().setByte(2*i + 1, pcmSampleUnsigned & 0x00ff);
		}

		return pcmPacket;
	}

	@Override
	public int getFramesPerPacket() {
		return samplesPerFrame;
	}

	@Override
	public double getPacketsPerSecond() {
		//return getAudioFormat().getSampleRate() / (double)getFramesPerPacket();
		
		return getSampleRate() / (double)getFramesPerPacket();
	}
	
	public int getSampleRate() {
		return sampleRate;
	}
	
	public int getChannels() {
		return channels;
	}
	
	public int getAudioFormat() {
		return audioFormat;
	}
	
	public int getSampleSizeInBits() {
		return sampleSizeInBits;
	}
}

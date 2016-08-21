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


/**
 * Provides information about an audio stream
 */
public interface AudioStreamInformationProvider {
	/**
	 * The JavaSoune audio format of the streamed audio
	 * @return the AudioFormat
	 */
	//public AudioFormat getAudioFormat();
	
	/**
	 * Average frames per second
	 * @return frames per second
	 */
	int getFramesPerPacket();
	
	/**
	 * Average packets per second
	 * @return packets per second
	 */
	double getPacketsPerSecond();

	//Audio Format Sample Rate
	int getSampleRate();
	
	int getSampleSizeInBits();
	
	//Number of audio channels : 1 mono / 2 stereo
	int getChannels();
	
	//The format in which the audio data is represented. See Android AudioFormat.ENCODING_PCM_16BIT and Android AudioFormat.ENCODING_PCM_8BIT
	int getAudioFormat();
}

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

import java.security.NoSuchAlgorithmException;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.crypto.Cipher;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;

import com.raventech.airplayserver.network.raop.RaopRtpPacket;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.handler.codec.oneone.OneToOneDecoder;

/**
 * De-crypt AES encoded audio data
 */
public class RaopRtpAudioDecryptionHandler extends OneToOneDecoder {
	
	private static final Logger LOG = Logger.getLogger(RaopRtpAudioDecryptionHandler.class.getName());
	
	/**
	 *  The AES cipher. We request no padding because RAOP/AirTunes only encrypts full
	 * block anyway and leaves the trailing byte unencrypted
	 */
	//private final Cipher m_aesCipher = AirTunesCrytography.getCipher("AES/CBC/NoPadding");
	private Cipher aesCipher; 
	
	/**
	 *  AES key */
	private final SecretKey m_aesKey;
	
	/**
	 * AES initialization vector
	 */
	private final IvParameterSpec m_aesIv;

	public RaopRtpAudioDecryptionHandler(final SecretKey aesKey, final IvParameterSpec aesIv) {
		m_aesKey = aesKey;
		m_aesIv = aesIv;
		
		
		String transformation = "AES/CBC/NoPadding";
        try {
        	aesCipher = Cipher.getInstance(transformation);
        	
        	LOG.info("Cipher acquired sucessfully. transformation: " + transformation);
		} 
        catch (NoSuchAlgorithmException e) {
			LOG.log(Level.SEVERE, "Error getting the Cipher. transformation: " + transformation, e);
		} 
        catch (NoSuchPaddingException e) {
        	LOG.log(Level.SEVERE, "Error getting the Cipher. transformation: " + transformation, e);
		}
        
		
	}

	@Override
	protected synchronized Object decode(final ChannelHandlerContext ctx, final Channel channel, final Object msg)
		throws Exception {
		
		//check the message type
		if (msg instanceof RaopRtpPacket.Audio) {
			final RaopRtpPacket.Audio audioPacket = (RaopRtpPacket.Audio)msg;
			final ChannelBuffer audioPayload = audioPacket.getPayload();

			/* Cipher is restarted for every packet. We simply overwrite the
			 * encrypted data with the corresponding plain text
			 */
			aesCipher.init(Cipher.DECRYPT_MODE, m_aesKey, m_aesIv);
			
			for(int i = 0; (i + 16) <= audioPayload.capacity(); i += 16) {
				byte[] block = new byte[16];//buffer for decrypting the data
				//copy the bytes to the buffer
				audioPayload.getBytes(i, block);
				//decrypt the 16 bytes block
				block = aesCipher.update(block);
				//set it back to the channel
				audioPayload.setBytes(i, block);
			}
		}

		return msg;
	}
}

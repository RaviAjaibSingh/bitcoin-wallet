package com.helioscard.wallet.bitcoin.secureelement.real;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongycastle.util.Arrays;

import com.helioscard.wallet.bitcoin.secureelement.ECKeyEntry;
import com.helioscard.wallet.bitcoin.secureelement.ECUtil;
import com.helioscard.wallet.bitcoin.secureelement.SecureElementApplet;
import com.helioscard.wallet.bitcoin.secureelement.SmartCardReader;
import com.helioscard.wallet.bitcoin.secureelement.exception.CardWasWipedException;
import com.helioscard.wallet.bitcoin.secureelement.exception.KeyAlreadyExistsException;
import com.helioscard.wallet.bitcoin.secureelement.exception.SmartCardFullException;
import com.helioscard.wallet.bitcoin.secureelement.exception.WrongPasswordException;
import com.helioscard.wallet.bitcoin.util.Util;

public class SecureElementAppletImpl extends SecureElementApplet {
	// internal state

	private static Logger _logger = LoggerFactory.getLogger(SecureElementAppletImpl.class);
	
	private static final int LENGTH_OF_PUBLIC_KEY = 65;
	private static final int LENGTH_OF_PRIVATE_KEY = 32;
	private static final int LENGTH_OF_ASSOCIATED_DATA = 64;

	private enum SecureElementState {
	    DISCONNECTED, STATE_INFORMATION_READ
	}

	private SecureElementState _currentState = SecureElementState.DISCONNECTED;

	private SmartCardReader _smartCardReader;

    private String _cardIdentifier;
	private byte[] _version = new byte[2];
	private int _passwordAttemptsLeft;
	private PINState _pinState = PINState.NOT_SET;
	private boolean _loggedIn;

	public SecureElementAppletImpl(SmartCardReader smartCardReader) {
		_smartCardReader = smartCardReader;
	}
	
	private void ensureInitialStateRead(boolean forced) throws IOException {
		if (!checkConnection()) {
			throw new IOException("Not connected.");
		}
		
		if (!forced && _currentState != SecureElementState.DISCONNECTED) {
			// we've already read in the initial state information
			_logger.info("ensureInitialStateRead: already read state information");
			return;
		}
		
		byte[] commandAPDU;
		if (_currentState == SecureElementState.DISCONNECTED) {
			// if we're not connected, we need to select the applet
			commandAPDU = new byte[] {0x00, (byte)0xa4, 0x04, 0x00, 0x08, (byte)0xff, 0x73, 0x63, 0x63, 0x6a, 0x01, 0x01, 0x01, 0x00};
		} else {
			// otherwise the applet is already selected, just send get status command
			commandAPDU = new byte[] {(byte)0x80, 0x01, 0x00, 0x00, 0x00};
		}
		
		_logger.info("Sending command to get applet status: " + Util.bytesToHex(commandAPDU));
		byte[] responseAPDU = _smartCardReader.exchangeAPDU(commandAPDU);
		_logger.info("Got response: " + Util.bytesToHex(responseAPDU));
		ensureResponseEndsWith9000(responseAPDU);

        // first 8 bytes are the card identifier
        _cardIdentifier = Util.bytesToHex(responseAPDU, 0, 8);
        _logger.info("Got card identifier of " + _cardIdentifier);

		// save the version
		_version[0] = responseAPDU[8];
		_version[1] = responseAPDU[9];

		_logger.info("Applet version: " + _version[0] + "" + _version[1]);
		
		_pinState = PINState.NOT_SET;
		if ((responseAPDU[10] & 0x80) != 0) {
			_pinState = PINState.SET; // the PIN is set
		}
		if ((responseAPDU[10] & 0x40) != 0) {
			_pinState = PINState.BLANK; // the PIN is set to blank
		}
		_logger.info("PIN state: " + _pinState);
		
		_loggedIn = (responseAPDU[10] & 0x20) != 0;
		_logger.info("Logged in: " + _loggedIn);
		
		_passwordAttemptsLeft = responseAPDU[11] & 0xFF;
		_logger.info("Password attempts left: " + _passwordAttemptsLeft);

		_currentState = SecureElementState.STATE_INFORMATION_READ;
	}
	
	private void ensureResponseEndsWith9000(byte[] responseAPDU) throws IOException {
		if (responseAPDU == null) {
			_logger.info("ensureResponseEndsWith9000: response was null");
			throw new IOException("Received null response from card.");			
		} else if (responseAPDU.length < 2) {
			_logger.info("ensureResponseEndsWith9000: response length less than 2");
			throw new IOException("Received response of less than 2 bytes");			
		}
		
		byte sw1 = responseAPDU[responseAPDU.length - 2];
		byte sw2 = responseAPDU[responseAPDU.length - 1];
		
		if (sw1 == (byte)0x90 && sw2 == (byte)0x00) {
			_logger.info("ensureResponseEndsWith9000: received good response from card");
			return;
		} else if (sw1 == (byte)0x69 && sw2 == (byte)0x82) {
			// SW_SECURITY_STATUS_NOT_SATISFIED
			_logger.info("ensureResponseEndsWith9000: wrong password");
			throw new WrongPasswordException();
		} else if (sw1 == (byte)0x6a && sw2 == (byte)0x84) {
			// SW_FILE_FULL
			throw new SmartCardFullException();
		} else if (sw1 == (byte)0x69 && sw2 == (byte)0x84) {
			// SW_INVALID_DATA
			throw new KeyAlreadyExistsException();
		} else if (sw1 == (byte)0x69 && sw2 == (byte)0x83) {
			_logger.info("ensureResponseEndsWith9000: card was wiped!");
			// force get the status again to refresh our view of the card
			// so that we know no PIN is set, for example
			ensureInitialStateRead(true);
			throw new CardWasWipedException();
		}
		
		throw new IOException("Received unknown response from card");
	}
	
	@Override
	public PINState getPINState() throws IOException {
		ensureInitialStateRead(false);
		return _pinState;
	}
	
	@Override
	public void setCardPassword(String oldPassword, String newPassword) throws IOException {
		ensureInitialStateRead(false);
		byte[] oldPasswordBytes = null;
		int oldPasswordBytesLength = 0;
		if (oldPassword != null && oldPassword.length() > 0) {
			oldPasswordBytes = oldPassword.getBytes();
			oldPasswordBytesLength = oldPasswordBytes.length;
		}
		
		byte[] newPasswordBytes = null;
		int newPasswordBytesLength = 0;
		if (newPassword != null && newPassword.length() > 0) {
			newPasswordBytes = newPassword.getBytes();
			newPasswordBytesLength = newPasswordBytes.length;
		}

		// create an APDU with p1 set to length of old password, p2 set to length of new password
		byte[] commandAPDUInitializePassword = new byte[] {(byte)0x80, 0x02, (byte)(oldPasswordBytesLength), (byte)(newPasswordBytesLength), (byte)(oldPasswordBytesLength + newPasswordBytesLength)};		
		ByteArrayOutputStream commandAPDUByteArrayOutputStream = new ByteArrayOutputStream(commandAPDUInitializePassword.length + oldPasswordBytesLength + newPasswordBytesLength);

		commandAPDUByteArrayOutputStream.write(commandAPDUInitializePassword);
		// now write the old password and new password
		if (oldPasswordBytes != null) {
			commandAPDUByteArrayOutputStream.write(oldPasswordBytes);
		}
		if (newPasswordBytes != null) {
			commandAPDUByteArrayOutputStream.write(newPasswordBytes);
		}
	
		
		byte[] finalCommandAPDU = commandAPDUByteArrayOutputStream.toByteArray();
		// don't log the APDU itself as it's sensitive
		_logger.info("Sending command APDU to set password");
		// _logger.info("APDU: " + Util.bytesToHex(finalCommandAPDU));
		byte[] responseAPDU = _smartCardReader.exchangeAPDU(finalCommandAPDU);
		_logger.info("Got response: " + Util.bytesToHex(responseAPDU));

		ensureResponseEndsWith9000(responseAPDU);

		// force a refresh the secure element state
		ensureInitialStateRead(true);
	}

	@Override
	public List<ECKeyEntry> getECPublicKeyEntries() throws IOException {
		ensureInitialStateRead(false);
		List<ECKeyEntry> list = new ArrayList<ECKeyEntry>();
		
		byte[] commandAPDU = new byte[] {(byte)0x80, 0x05, 0x00, 0x00, 0x00};
		while (true) {
			_logger.info("getECPublicKeyEntries: Sending command APDU to get public keys " + Util.bytesToHex(commandAPDU));
			byte[] responseAPDU = _smartCardReader.exchangeAPDU(commandAPDU);
			_logger.info("getECPublicKeyEntries: Got response: " + Util.bytesToHex(responseAPDU));
			ensureResponseEndsWith9000(responseAPDU);

			if (responseAPDU.length == 2) {
				// this is the end of the list, break out
				break;
			}
			
			// copy the public key bytes out
			byte[] publicKeyBytes = extractPublicKey(responseAPDU);
			
			// copy the associated data bytes out
			byte[] associatedDataBytes = extractAssociatedData(responseAPDU);

			ECKeyEntry ecKeyEntry = new ECKeyEntry(publicKeyBytes, associatedDataBytes);
			list.add(ecKeyEntry);

			 // set P1 to 01 to indicate we want to read the next key
			commandAPDU[2] = 0x01;
		}

		return list;
	}
	
	@Override
	public void close() {
		_smartCardReader.close();
	}


	@Override
	public boolean checkConnection() {
		// TODO Auto-generated method stub
		return _smartCardReader.checkConnection();
	}
	
	@Override
	public byte[] doSimpleSign(String password, byte[] publicKeyToUse, byte[] bytesToSign) throws IOException {
		ensureInitialStateRead(false);

		byte[] passwordBytes = null;
		int lengthOfPasswordBytes = 0;
		if (password != null && password.length() > 0) {
			passwordBytes = password.getBytes();
			lengthOfPasswordBytes = passwordBytes.length;
		}

		byte[] publicKey = ECUtil.getPublicKeyBytesFromEncoding(publicKeyToUse, false); // make sure we have the uncompressed form of the public key
		
		int lengthOfBytesToSign = bytesToSign.length;

		byte lengthNeededForPayload = (byte)(lengthOfPasswordBytes + LENGTH_OF_PUBLIC_KEY + lengthOfBytesToSign);
		byte[] commandAPDUHeader = new byte[] {(byte)0x80, 0x0C, (byte)lengthOfPasswordBytes, (byte)lengthOfBytesToSign, lengthNeededForPayload};
		ByteArrayOutputStream commandAPDUByteArrayOutputStream = new ByteArrayOutputStream(commandAPDUHeader.length + lengthNeededForPayload);
		
		commandAPDUByteArrayOutputStream.write(commandAPDUHeader);
		commandAPDUByteArrayOutputStream.write(publicKey);
		if (passwordBytes != null) {
			commandAPDUByteArrayOutputStream.write(passwordBytes);
		}
		commandAPDUByteArrayOutputStream.write(bytesToSign);
		
		byte[] commandAPDU = commandAPDUByteArrayOutputStream.toByteArray();

		_logger.info("doSimpleSign: Sending command APDU to start signing");
		byte[] responseAPDU = _smartCardReader.exchangeAPDU(commandAPDU);
		_logger.info("doSimpleSign: Got response: " + Util.bytesToHex(responseAPDU));
		ensureResponseEndsWith9000(responseAPDU);

		// strip off the status words and return the result
		return Arrays.copyOfRange(responseAPDU, 0, responseAPDU.length - 2);
	}

	@Override
	public void beginTransactionSigningOperation(String password, byte[] destinationAddress, long amount) throws IOException {
		ensureInitialStateRead(false);
		
		byte[] passwordBytes = password.getBytes();
		int lengthOfPasswordBytes = passwordBytes.length;
		
		int lengthOfDestinationAddress = destinationAddress.length;
		
		byte[] amountBytes = Util.longToBytes(amount);
		int lengthOFAmountBytes = amountBytes.length;

		
		byte lengthNeededForPayload = (byte)(3 + lengthOfPasswordBytes + lengthOfDestinationAddress + lengthOFAmountBytes);
		byte[] commandAPDUHeader = new byte[] {(byte)0x80, 0x08, 0x00, 0x00, lengthNeededForPayload};
		ByteArrayOutputStream commandAPDUByteArrayOutputStream = new ByteArrayOutputStream(commandAPDUHeader.length + lengthNeededForPayload);
		
		commandAPDUByteArrayOutputStream.write(commandAPDUHeader);
		commandAPDUByteArrayOutputStream.write(lengthOfPasswordBytes);
		commandAPDUByteArrayOutputStream.write(passwordBytes);
		commandAPDUByteArrayOutputStream.write(lengthOfDestinationAddress);
		commandAPDUByteArrayOutputStream.write(destinationAddress);
		commandAPDUByteArrayOutputStream.write(lengthOFAmountBytes);
		commandAPDUByteArrayOutputStream.write(amountBytes);
		
		byte[] commandAPDU = commandAPDUByteArrayOutputStream.toByteArray();
		
		_logger.info("beginTransactionSigningOperation: Sending command APDU to start signing");
		byte[] responseAPDU = _smartCardReader.exchangeAPDU(commandAPDU);
		_logger.info("beginTransactionSigningOperation: Got response: " + Util.bytesToHex(responseAPDU));
		ensureResponseEndsWith9000(responseAPDU);
	}

	@Override
	public boolean isAuthenticated() throws IOException {
		ensureInitialStateRead(false);
		return _loggedIn;
	}

	@Override
	public void login(String password) throws IOException {
		_logger.info("login: called");
		ensureInitialStateRead(false);
		if (isAuthenticated()) {
			// already authenticated, nothing to do
			_logger.info("login: already authenticated");
			return;
		}

		byte[] passwordBytes = password.getBytes();
		int lengthOfPasswordBytes = passwordBytes.length;
		
		byte[] commandAPDUHeader = new byte[] {(byte)0x80, 0x03, 0x00, 0x00, (byte)lengthOfPasswordBytes};
		
		ByteArrayOutputStream commandAPDUByteArrayOutputStream = new ByteArrayOutputStream(commandAPDUHeader.length + lengthOfPasswordBytes);
		
		commandAPDUByteArrayOutputStream.write(commandAPDUHeader);
		commandAPDUByteArrayOutputStream.write(passwordBytes);
		
		byte[] commandAPDU = commandAPDUByteArrayOutputStream.toByteArray();
		
		_logger.info("login: Sending command APDU to login");
		byte[] responseAPDU = _smartCardReader.exchangeAPDU(commandAPDU);
		_logger.info("login: Got response: " + Util.bytesToHex(responseAPDU));
		
		if (responseAPDU.length != 2) {
			_logger.error("login: received response that wasn't 2 bytes");
			throw new IOException("login: Received non 2-byte response from card");			
		}
		
		// force a status refresh from the card to update the PIN attempts left count
		ensureInitialStateRead(true);
		ensureResponseEndsWith9000(responseAPDU);
		
		return;
	}

	@Override
	public ECKeyEntry createOrInjectKey(String friendlyName, byte[] privateKey,
			byte[] publicKey) throws IOException {
		_logger.info("createOrInjectKey: called");
		ensureInitialStateRead(false);
		if (!isAuthenticated()) {
			// already authenticated, nothing to do
			_logger.error("createOrInjectKey: Not authenticated");
			throw new IOException("createOrInjectKey: Not authenticated");
		}
		
		byte[] friendlyNameBytes = friendlyName.getBytes();
		int lengthOfFriendlyNameBytes = friendlyNameBytes.length;
		
		ByteArrayOutputStream associatedDataByteArrayOutputStream = new ByteArrayOutputStream(64);
		associatedDataByteArrayOutputStream.write(ECKeyEntry.ASSOCIATED_DATA_TYPE_VERSION);
		associatedDataByteArrayOutputStream.write(0x01);
		associatedDataByteArrayOutputStream.write(0x01);
		
		associatedDataByteArrayOutputStream.write(ECKeyEntry.ASSOCIATED_DATA_TYPE_FRIENDLY_NAME);
		associatedDataByteArrayOutputStream.write(lengthOfFriendlyNameBytes);
		associatedDataByteArrayOutputStream.write(friendlyNameBytes);
		
		associatedDataByteArrayOutputStream.write(ECKeyEntry.ASSOCIATED_DATA_TYPE_GENERATION_TIME);
		associatedDataByteArrayOutputStream.write(0x08);
		associatedDataByteArrayOutputStream.write(Util.longToBytes(System.currentTimeMillis()));
		
		// we need a total of 64 bytes of associated data, pad the stream
		int lengthSoFar = associatedDataByteArrayOutputStream.size();
		int bytesToWrite = LENGTH_OF_ASSOCIATED_DATA - lengthSoFar;
		for (int i = 0; i < bytesToWrite; i++) {
			associatedDataByteArrayOutputStream.write(0);
		}
		
		byte[] associatedDataBytes = associatedDataByteArrayOutputStream.toByteArray();
		
		int lengthOfAssociatedDataBytes = associatedDataBytes.length;
		
		int lengthOfPrivateKey = 0;
		int lengthOfPublicKey = 0;
		if (privateKey != null && publicKey != null) {
			publicKey = ECUtil.getPublicKeyBytesFromEncoding(publicKey, false); // make sure we have an uncompressed encoding of the public key
			lengthOfPrivateKey = privateKey.length;
			lengthOfPublicKey = publicKey.length;
			if (lengthOfPrivateKey != LENGTH_OF_PRIVATE_KEY || lengthOfPublicKey != LENGTH_OF_PUBLIC_KEY) {
				throw new IllegalArgumentException("Key length was wrong.");
			}
		}
		int totalLengthOfCommandAPDU = lengthOfAssociatedDataBytes + lengthOfPrivateKey + lengthOfPublicKey; 
		
		byte[] commandAPDUHeader = new byte[] {(byte)0x80, 0x04, 0x00, 0x00, (byte)(totalLengthOfCommandAPDU)};
		
		ByteArrayOutputStream commandAPDUByteArrayOutputStream = new ByteArrayOutputStream(commandAPDUHeader.length + totalLengthOfCommandAPDU);
		
		commandAPDUByteArrayOutputStream.write(commandAPDUHeader);
		commandAPDUByteArrayOutputStream.write(associatedDataBytes);
		if (lengthOfPrivateKey != 0 && lengthOfPublicKey != 0) {
			// we're injecting a private/public key pair
			commandAPDUByteArrayOutputStream.write(privateKey);
			commandAPDUByteArrayOutputStream.write(publicKey);
		}
		
		byte[] commandAPDU = commandAPDUByteArrayOutputStream.toByteArray();
		
		_logger.info("createOrInjectKey: Sending command APDU to inject key: " + Util.bytesToHex(commandAPDU));
		byte[] responseAPDU = _smartCardReader.exchangeAPDU(commandAPDU);
		_logger.info("createOrInjectKey: Got response: " + Util.bytesToHex(responseAPDU));
		
		ensureResponseEndsWith9000(responseAPDU);

		// copy the public key bytes out
		byte[] publicKeyBytesFromSecureElement = extractPublicKey(responseAPDU);
		
		// copy the associated data bytes out
		byte[] associatedDataBytesFromSecureElement = extractAssociatedData(responseAPDU);

		return new ECKeyEntry(publicKeyBytesFromSecureElement, associatedDataBytesFromSecureElement);
	}
	
	private static byte[] extractPublicKey(byte[] responseAPDU) {
		byte[] publicKeyBytesFromSecureElement = new byte[LENGTH_OF_PUBLIC_KEY];
		System.arraycopy(responseAPDU, 0, publicKeyBytesFromSecureElement, 0, LENGTH_OF_PUBLIC_KEY);
		return publicKeyBytesFromSecureElement;
	}
	
	private static byte[] extractAssociatedData(byte[] responseAPDU) {
		// copy the associated data bytes out
		byte[] associatedDataBytesFromSecureElement = new byte[LENGTH_OF_ASSOCIATED_DATA];
		System.arraycopy(responseAPDU, LENGTH_OF_PUBLIC_KEY, associatedDataBytesFromSecureElement, 0, LENGTH_OF_ASSOCIATED_DATA);
		return associatedDataBytesFromSecureElement;
	}

	@Override
	public int getNumberPasswordAttemptsLeft() throws IOException {
		_logger.info("getNumberPasswordAttemptsLeft: called");
		ensureInitialStateRead(false);
		return _passwordAttemptsLeft;
	}

	@Override
	public void deleteKey(byte[] publicKey) throws IOException {
		_logger.info("deleteKey: called");
		// get the uncompressed form of the key, that's all the applet knows how to deal with
		byte[] publicKeyUncompressed = ECUtil.getPublicKeyBytesFromEncoding(publicKey, false);
		int lengthOfPublicKeyUncompressed = publicKeyUncompressed.length;
		byte[] commandAPDUHeader = new byte[] {(byte)0x80, 0x06, 0x00, 0x00, (byte)lengthOfPublicKeyUncompressed};
		
		ByteArrayOutputStream commandAPDUByteArrayOutputStream = new ByteArrayOutputStream(commandAPDUHeader.length + lengthOfPublicKeyUncompressed);
		
		commandAPDUByteArrayOutputStream.write(commandAPDUHeader);
		commandAPDUByteArrayOutputStream.write(publicKeyUncompressed);
		
		byte[] commandAPDU = commandAPDUByteArrayOutputStream.toByteArray();
		
		_logger.info("deleteKey: Sending command APDU");
		byte[] responseAPDU = _smartCardReader.exchangeAPDU(commandAPDU);
		_logger.info("deleteKey: Got response: " + Util.bytesToHex(responseAPDU));
		
		ensureResponseEndsWith9000(responseAPDU);
	}

	@Override
	public void changeLabel(byte[] publicKey, String label) throws IOException {
		_logger.info("changeLabel: called");
		ensureInitialStateRead(false);
		
		publicKey = ECUtil.getPublicKeyBytesFromEncoding(publicKey, false); // get the uncompressed form of the public key
		
		int lengthOfPublicKey = publicKey.length;
		
		if (lengthOfPublicKey != LENGTH_OF_PUBLIC_KEY) {
			throw new IllegalArgumentException("Bad public key length");
		}
		
		// first get the associated data for this key
		byte[] commandAPDUHeader = new byte[] {(byte)0x80, 0x0D, 0x00, 0x00, (byte)lengthOfPublicKey};
		
		ByteArrayOutputStream commandAPDUByteArrayOutputStream = new ByteArrayOutputStream(commandAPDUHeader.length + lengthOfPublicKey);
		
		commandAPDUByteArrayOutputStream.write(commandAPDUHeader);
		commandAPDUByteArrayOutputStream.write(publicKey);
		
		byte[] commandAPDU = commandAPDUByteArrayOutputStream.toByteArray();
		
		_logger.info("login: Sending command APDU to get associated key data");
		byte[] responseAPDU = _smartCardReader.exchangeAPDU(commandAPDU);
		_logger.info("login: Got response: " + Util.bytesToHex(responseAPDU));
		ensureResponseEndsWith9000(responseAPDU);		

		// extract the associated data
		byte[] associatedDataBytesFromSecureElement = extractAssociatedData(responseAPDU);
		

		ByteArrayOutputStream updatedAssociatedDataByteArrayOutputStream = new ByteArrayOutputStream(LENGTH_OF_ASSOCIATED_DATA);
		// we want to go through the associated data bytes, leaving all fields the same except for the label field
		ByteArrayInputStream stream = new ByteArrayInputStream(associatedDataBytesFromSecureElement);
		try {
			while (stream.available() > 0) {
				int fieldType = stream.read();
				if (fieldType == -1 || fieldType == 0) {
					// reached end of stream
					break;
				}
				int fieldLength = stream.read();
				if (fieldLength == -1) {
					// reached end of stream
					break;
				}

				// write all fields back to our output buffer except the friendly name field
				if (fieldType != ECKeyEntry.ASSOCIATED_DATA_TYPE_FRIENDLY_NAME) {
					updatedAssociatedDataByteArrayOutputStream.write(fieldType);
					updatedAssociatedDataByteArrayOutputStream.write(fieldLength);
	
					if (fieldLength == 0) {
						// 0-length field?
						_logger.info("changeLabel: read 0 length field");
					} else {
						byte[] fieldData = new byte[fieldLength];
						if (stream.read(fieldData, 0, fieldLength) == -1) {
							_logger.error("changeLabel: Field was missing bytes");
						}
						updatedAssociatedDataByteArrayOutputStream.write(fieldData);
					}
				} else {
					// just skip the friendly name field
					byte[] fieldData = new byte[fieldLength];
					if (stream.read(fieldData, 0, fieldLength) == -1) {
						_logger.error("changeLabel: friendly name was missing bytes");
					}
				}
			}
			// now write the friendly name
			updatedAssociatedDataByteArrayOutputStream.write(ECKeyEntry.ASSOCIATED_DATA_TYPE_FRIENDLY_NAME);
			if (label == null) {
				label = "";
			}
			byte[] labelBytes = label.getBytes();
			updatedAssociatedDataByteArrayOutputStream.write(labelBytes.length);
			updatedAssociatedDataByteArrayOutputStream.write(labelBytes);
			
			// we need a total of 64 bytes of associated data, pad the stream
			int lengthSoFar = updatedAssociatedDataByteArrayOutputStream.size();
			int bytesToWrite = LENGTH_OF_ASSOCIATED_DATA - lengthSoFar;
			for (int i = 0; i < bytesToWrite; i++) {
				updatedAssociatedDataByteArrayOutputStream.write(0);
			}
			
			byte[] updatedAssociatedDataBytes = updatedAssociatedDataByteArrayOutputStream.toByteArray();
			
			int overallLength = LENGTH_OF_PUBLIC_KEY + LENGTH_OF_ASSOCIATED_DATA;
			byte[] updateKeyAPDUHeader = new byte[] {(byte)0x80, 0x07, 0x00, 0x00, (byte)overallLength};
			ByteArrayOutputStream updateKeyAPDUStream = new ByteArrayOutputStream(commandAPDUHeader.length + overallLength);
			
			updateKeyAPDUStream.write(updateKeyAPDUHeader);
			updateKeyAPDUStream.write(publicKey);
			updateKeyAPDUStream.write(updatedAssociatedDataBytes);
			
			byte[] updateKeyAPDU = updateKeyAPDUStream.toByteArray();
			
			_logger.info("changeLabel: Sending command APDU to update key: " + Util.bytesToHex(updateKeyAPDU));
			byte[] updateKeyResponseAPDU = _smartCardReader.exchangeAPDU(updateKeyAPDU);
			_logger.info("changeLabel: Got response: " + Util.bytesToHex(updateKeyResponseAPDU));
			ensureResponseEndsWith9000(updateKeyResponseAPDU);			
		} finally {
			try {
				stream.close();
			} catch (IOException e) {
				_logger.error("changeLabel: error closing stream: " + e.toString());
			}
		}
	}

    @Override
    public String getCardIdentifier() throws IOException {
        ensureInitialStateRead(false);
        return _cardIdentifier;
    }

    public byte[] enableCachedSigning() throws IOException {
		_logger.info("enableCachedSigning: called");
		ensureInitialStateRead(false);
				
		// No arguments needed for the command APDU
		byte[] commandAPDU = new byte[] {(byte)0x80, 0x08, 0x00, 0x00, 0x00};
		
		_logger.info("enableCachedSigning: Sending command APDU to enable cached signing");
		byte[] responseAPDU = _smartCardReader.exchangeAPDU(commandAPDU);
		_logger.info("enableCachedSigning: Got response: " + Util.bytesToHex(responseAPDU));
		ensureResponseEndsWith9000(responseAPDU);	
		
		// the response will be the cached signature identifier which we can later use to retrieve a cached signature
		byte[] cachedSigningIdentifier = new byte[responseAPDU.length - 2];
		System.arraycopy(responseAPDU, 0, cachedSigningIdentifier, 0, cachedSigningIdentifier.length);
		
		_logger.info("enableCachedSigning: cached signing identifer is: " + Util.bytesToHex(cachedSigningIdentifier));
		
		return cachedSigningIdentifier;
    }
    
    public byte[] getCachedSigningDataForIdentifier(String password, byte[] cacheIdentifier) throws IOException {
    	_logger.info("getCachedSigningDataForIdentifier: called");
    	ensureInitialStateRead(false);

		byte[] passwordBytes = null;
		int lengthOfPasswordBytes = 0;
		if (password != null && password.length() > 0) {
			passwordBytes = password.getBytes();
			lengthOfPasswordBytes = passwordBytes.length;
		}

		byte[] commandAPDUHeader = new byte[] {(byte)0x80, 0x09, 0x00, 0x00, (byte)lengthOfPasswordBytes};
		ByteArrayOutputStream commandAPDUByteArrayOutputStream = new ByteArrayOutputStream(commandAPDUHeader.length + lengthOfPasswordBytes);
		commandAPDUByteArrayOutputStream.write(commandAPDUHeader);
		if (passwordBytes != null) {
			commandAPDUByteArrayOutputStream.write(passwordBytes);
		}
		byte[] commandAPDU = commandAPDUByteArrayOutputStream.toByteArray();

		_logger.info("getCachedSigningDataForIdentifier: Sending command APDU to get cached signing identifier");
		byte[] responseAPDU = _smartCardReader.exchangeAPDU(commandAPDU);
		_logger.info("getCachedSigningDataForIdentifier: Got response: " + Util.bytesToHex(responseAPDU));
		ensureResponseEndsWith9000(responseAPDU);
		
	    int CACHE_IDENTIFIER_LENGTH = 4;
		if (responseAPDU.length < CACHE_IDENTIFIER_LENGTH + 1 + 2) {
			// no data came back - no 4 byte identifier + at least one byte signed data + SW1 + SW2
			_logger.info("getCachedSigningDataForIdentifier: no cached signature data");
			return null;
		}
		
		// the first 4 bytes are the cache identifier, the remaining bytes are the signature data
		for (int i = 0; i < CACHE_IDENTIFIER_LENGTH; i++) {
			if (cacheIdentifier[i] != responseAPDU[i]) {
				_logger.info("getCachedSigningDataForIdentifier: cache identifiers not equal, returning nothing");
				return null;
			}
		}
		
		// return the signature data
		byte[] signatureData = new byte[responseAPDU.length - CACHE_IDENTIFIER_LENGTH - 2]; // enough space subtract the cache identifier and the SW1/SW2 bytes
		System.arraycopy(responseAPDU, CACHE_IDENTIFIER_LENGTH, signatureData, 0, signatureData.length);
		_logger.info("getCachedSigningDataForIdentifier: returning signature data of " + Util.bytesToHex(signatureData));
		return signatureData;
    }
}

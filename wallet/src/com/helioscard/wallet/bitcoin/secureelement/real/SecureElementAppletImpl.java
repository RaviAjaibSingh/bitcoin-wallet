package com.helioscard.wallet.bitcoin.secureelement.real;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.helioscard.wallet.bitcoin.secureelement.ECKeyEntry;
import com.helioscard.wallet.bitcoin.secureelement.ECUtil;
import com.helioscard.wallet.bitcoin.secureelement.PKCS5Util;
import com.helioscard.wallet.bitcoin.secureelement.SecureElementApplet;
import com.helioscard.wallet.bitcoin.secureelement.SmartCardReader;
import com.helioscard.wallet.bitcoin.secureelement.exception.CardWasWipedException;
import com.helioscard.wallet.bitcoin.secureelement.exception.KeyAlreadyExistsException;
import com.helioscard.wallet.bitcoin.secureelement.exception.SmartCardFullException;
import com.helioscard.wallet.bitcoin.secureelement.exception.WrongPasswordException;
import com.helioscard.wallet.bitcoin.secureelement.exception.WrongVersionException;
import com.helioscard.wallet.bitcoin.Constants;
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
	private int _maxNumberOfKeys;
	private int _currentNumberOfKeys;
	private long _timeOfAppletInstallation;
	private long _timeOfLastRefresh;

	private static final int LENGTH_OF_PASSWORD_PKCS5_KEY_IN_BITS = 256;
	private static final int DEFAULT_ITERATION_COUNT = 20000;
	private int _passwordMetaDataVersion = -1;
	private static final int FIELD_PASSWORD_META_DATA_VERSION = 1;
	private static final int FIELD_PASSWORD_META_DATA_PKCS5_ITERATION_COUNT = 2;
	private static final int FIELD_PASSWORD_META_DATA_PKCS5_PASSWORD_KEY_SALT = 3;
	private static final int FIELD_PASSWORD_META_DATA_PKCS5_ENCRYPTION_KEY_SALT = 4;
	private int _passwordPKCS5IterationCount;
	private byte[] _passwordPKCS5PasswordKeySalt;
	private byte[] _passwordPKCS5EncryptionKeySalt;

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
			commandAPDU = new byte[] {0x00, (byte)0xa4, 0x04, 0x00, 0x0D, (byte)0xff, (byte)0x68, (byte)0x65, (byte)0x6c, (byte)0x69, (byte)0x6f, (byte)0x73, (byte)0x63, (byte)0x61, (byte)0x72, (byte)0x64, (byte)0x01, (byte)0x01, 0x00};
		} else {
			// otherwise the applet is already selected, just send get status command
			commandAPDU = new byte[] {(byte)0x80, 0x01, 0x00, 0x00, 0x00};
		}
		
		_logger.info("Sending command to get applet status: " + Util.bytesToHex(commandAPDU));
		byte[] responseAPDU = _smartCardReader.exchangeAPDU(commandAPDU);
		_logger.info("Got response: " + Util.bytesToHex(responseAPDU));
		ensureResponseEndsWith9000(responseAPDU);
		
		readInitialStateFromResponseAPDU(responseAPDU);
	}
	
	@Override
	public int getMaxNumberOfKeys() throws IOException {
		ensureInitialStateRead(false);
		return _maxNumberOfKeys;
	}
	
	@Override
	public int getCurrentNumberOfKeys() throws IOException {
		ensureInitialStateRead(false);
		return _currentNumberOfKeys;
	}

	private void readInitialStateFromResponseAPDU(byte[] responseAPDU) throws IOException {
		
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
		
		_maxNumberOfKeys = responseAPDU[12] & 0xFF;
		_logger.info("Max number of keys: " + _maxNumberOfKeys);
		_currentNumberOfKeys = responseAPDU[13] & 0xFF;
		_logger.info("Current number of keys: " + _currentNumberOfKeys);
		
		_timeOfAppletInstallation = Util.bytesToLong(responseAPDU, 14, 8);
		_logger.info("Time of applet installation: " + _timeOfAppletInstallation);		
		_timeOfLastRefresh = Util.bytesToLong(responseAPDU, 22, 8);
		_logger.info("Time of last refresh: " + _timeOfLastRefresh);
		
		byte lengthOfPasswordMetaData = (byte)(responseAPDU[30] & 0xFF);
		_logger.info("Length of password meta data: " + lengthOfPasswordMetaData);
		
		if (lengthOfPasswordMetaData > 0) {
			// the rest is TLE encoded - read the data out
			ByteArrayInputStream stream = new ByteArrayInputStream(responseAPDU, 31, lengthOfPasswordMetaData);
			try {
				while (stream.available() > 0) {
					int fieldType = stream.read();
					if (fieldType == -1 || fieldType == 0) {
						// reached end of stream
						return;
					}
					int fieldLength = stream.read();
					if (fieldLength == -1) {
						// reached end of stream
						return;
					}
					if (fieldLength == 0) {
						// 0-length field?
						_logger.info("ensureInitialStateRead: read 0 length field");
						continue;
					}
					byte[] fieldData = new byte[fieldLength];
					if (stream.read(fieldData, 0, fieldLength) == -1) {
						_logger.error("ensureInitialStateRead: Field was missing bytes");
						return;
					}

					switch(fieldType) {
						case FIELD_PASSWORD_META_DATA_VERSION: {
							_passwordMetaDataVersion = fieldData[0] & 0xff; // expected one byte
							_logger.info("ensureInitialStateRead: read password meta data version " + _passwordMetaDataVersion);
							if (_passwordMetaDataVersion != 1) {
								throw new WrongVersionException();
							}
							break;
						}
						case FIELD_PASSWORD_META_DATA_PKCS5_ITERATION_COUNT: {
							_passwordPKCS5IterationCount = Util.bytesToInt(fieldData);
							_logger.info("ensureInitialStateRead: read iteration count of " + _passwordPKCS5IterationCount);
							break;
						}
						case FIELD_PASSWORD_META_DATA_PKCS5_PASSWORD_KEY_SALT: {
							_passwordPKCS5PasswordKeySalt = fieldData;
							_logger.info("ensureInitialStateRead: read password key salt of " + Util.bytesToHex(_passwordPKCS5PasswordKeySalt));
							break;
						}
						case FIELD_PASSWORD_META_DATA_PKCS5_ENCRYPTION_KEY_SALT: {
							_passwordPKCS5EncryptionKeySalt = fieldData;
							_logger.info("ensureInitialStateRead: read encryption key salt of " + Util.bytesToHex(_passwordPKCS5EncryptionKeySalt));
							break;
						}
						default: {
							_logger.info("ensureInitialStateRead: skipped unknown field");
							break;
						}
					}
				}
			} finally {
				try {
					stream.close();
				} catch (IOException e) {
					_logger.error("ensureInitialStateRead: error closing stream: " + e.toString());
				}
			}
		} else {
			_passwordMetaDataVersion = -1;
		}

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
			PINState currentPINState = getPINState();
			if (currentPINState == PINState.NOT_SET || currentPINState == PINState.BLANK || _passwordMetaDataVersion == -1) {
				// we received a password, but there's no password set, or we have no password meta data
				// throw an error here
				_logger.error("setCardPassword: received old password, but no password set or no password meta data");
				throw new IOException("setCardPassword: received old password, but no password set or no password meta data");
			}

			// use PKCS5 derivation to derive the old password
			oldPasswordBytes = PKCS5Util.derivePKCS5Key(oldPassword, LENGTH_OF_PASSWORD_PKCS5_KEY_IN_BITS, _passwordPKCS5PasswordKeySalt, _passwordPKCS5IterationCount);
			oldPasswordBytesLength = oldPasswordBytes.length;
		}

		byte[] newPasswordBytes = null;
		int newPasswordBytesLength = 0;
		
		byte[] passwordMetaData = null;
		int passwordMetaDataLength = 0;
		if (newPassword != null && newPassword.length() > 0) {
			// generate the iteration counts and salts for the password key and encryption key
			int newPasswordPKCS5IterationCount = DEFAULT_ITERATION_COUNT;
			byte[] newPasswordPKCS5PasswordKeySalt = new byte[LENGTH_OF_PASSWORD_PKCS5_KEY_IN_BITS / 8];
			new Random().nextBytes(newPasswordPKCS5PasswordKeySalt);
			byte[] newPasswordPKCS5EncryptionKeySalt = new byte[LENGTH_OF_PASSWORD_PKCS5_KEY_IN_BITS / 8];
			new Random().nextBytes(newPasswordPKCS5EncryptionKeySalt);
			ByteArrayOutputStream passwordMetaDataOutputStream = new ByteArrayOutputStream();
			passwordMetaDataOutputStream.write(FIELD_PASSWORD_META_DATA_VERSION);
			passwordMetaDataOutputStream.write(0x01);
			passwordMetaDataOutputStream.write(0x01); // this code only writes version 1

			passwordMetaDataOutputStream.write(FIELD_PASSWORD_META_DATA_PKCS5_ITERATION_COUNT);
			passwordMetaDataOutputStream.write(0x04);
			passwordMetaDataOutputStream.write(Util.intToBytes(newPasswordPKCS5IterationCount));

			passwordMetaDataOutputStream.write(FIELD_PASSWORD_META_DATA_PKCS5_PASSWORD_KEY_SALT);			
			passwordMetaDataOutputStream.write(newPasswordPKCS5PasswordKeySalt.length);
			passwordMetaDataOutputStream.write(newPasswordPKCS5PasswordKeySalt);

			passwordMetaDataOutputStream.write(FIELD_PASSWORD_META_DATA_PKCS5_ENCRYPTION_KEY_SALT);			
			passwordMetaDataOutputStream.write(newPasswordPKCS5EncryptionKeySalt.length);
			passwordMetaDataOutputStream.write(newPasswordPKCS5EncryptionKeySalt);

			passwordMetaData = passwordMetaDataOutputStream.toByteArray();
			passwordMetaDataLength = passwordMetaData.length;
			
			// use PKCS5 to derive the new password
			newPasswordBytes = PKCS5Util.derivePKCS5Key(newPassword, LENGTH_OF_PASSWORD_PKCS5_KEY_IN_BITS, newPasswordPKCS5PasswordKeySalt, newPasswordPKCS5IterationCount);
			newPasswordBytesLength = newPasswordBytes.length;
		}

		// create an APDU with p1 set to length of old password, p2 set to length of new password
		byte[] commandAPDUInitializePassword = new byte[] {(byte)0x80, 0x02, (byte)(oldPasswordBytesLength), (byte)(newPasswordBytesLength), (byte)(oldPasswordBytesLength + newPasswordBytesLength + passwordMetaDataLength)};		
		ByteArrayOutputStream commandAPDUByteArrayOutputStream = new ByteArrayOutputStream(commandAPDUInitializePassword.length + oldPasswordBytesLength + newPasswordBytesLength);

		commandAPDUByteArrayOutputStream.write(commandAPDUInitializePassword);
		// now write the old password and new password
		if (oldPasswordBytes != null) {
			commandAPDUByteArrayOutputStream.write(oldPasswordBytes);
		}
		if (newPasswordBytes != null) {
			commandAPDUByteArrayOutputStream.write(newPasswordBytes);
		}
		if (passwordMetaDataLength != 0) {
			commandAPDUByteArrayOutputStream.write(passwordMetaData);
		}

		byte[] finalCommandAPDU = commandAPDUByteArrayOutputStream.toByteArray();
		_logger.info("Sending command APDU to set password");
		// don't log the APDU itself as it's sensitive
		/*
		if (!Constants.PRODUCTION_BUILD) {
			_logger.info("APDU: " + Util.bytesToHex(finalCommandAPDU));
		}
		*/
		byte[] responseAPDU = _smartCardReader.exchangeAPDU(finalCommandAPDU);
		_logger.info("Got response: " + Util.bytesToHex(responseAPDU));

		ensureResponseEndsWith9000(responseAPDU);

		// force a refresh the secure element state
		readInitialStateFromResponseAPDU(responseAPDU);
	}

	@Override
	public List<ECKeyEntry> getECKeyEntries(boolean includePrivate) throws IOException {
		ensureInitialStateRead(false);
		List<ECKeyEntry> list = new ArrayList<ECKeyEntry>();
		
		byte[] commandAPDU = new byte[] {(byte)0x80, 0x05, 0x00, includePrivate ? (byte)0x01 : 0x00, 0x00};
		while (true) {
			_logger.info("getECKeyEntries: Sending command APDU to get public keys " + Util.bytesToHex(commandAPDU));
			byte[] responseAPDU = _smartCardReader.exchangeAPDU(commandAPDU);
			if (!includePrivate) {
				// don't log if we fetched the private key
				_logger.info("getECKeyEntries: Got response: " + Util.bytesToHex(responseAPDU));
			} else {
				_logger.info("getECKeyEntries: Got a response");
			}
			ensureResponseEndsWith9000(responseAPDU);

			if (responseAPDU.length == 2) {
				// this is the end of the list, break out
				break;
			}
			
			boolean isLocked = extractIsLocked(responseAPDU);
			
			// copy the public key bytes out
			byte[] publicKeyBytes = extractPublicKey(responseAPDU);
			
			// copy the associated data bytes out
			byte[] associatedDataBytes = extractAssociatedData(responseAPDU);
			
			byte[] privateKeyData = null;
			if (includePrivate && !isLocked) {
				// if we asked for the private key back, assuming the key isn't locked, check if we got the private key data
				privateKeyData = extractPrivateKey(responseAPDU);
			}

			ECKeyEntry ecKeyEntry = new ECKeyEntry(isLocked, publicKeyBytes, associatedDataBytes, privateKeyData);
			list.add(ecKeyEntry);

			 // set P1 to 01 to indicate we want to read the next key
			commandAPDU[2] = 0x01;
			
			// zero out the response APDU
			Arrays.fill(responseAPDU, 0, responseAPDU.length - 1, (byte)0);
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
	public byte[] doSimpleSign(byte[] publicKeyToUse, byte[] bytesToSign) throws IOException {
		ensureInitialStateRead(false);

		byte[] publicKey = ECUtil.getPublicKeyBytesFromEncoding(publicKeyToUse, false); // make sure we have the uncompressed form of the public key
		
		int lengthOfBytesToSign = bytesToSign.length;

		byte lengthNeededForPayload = (byte)(LENGTH_OF_PUBLIC_KEY + lengthOfBytesToSign);
		byte[] commandAPDUHeader = new byte[] {(byte)0x80, 0x0C, (byte)lengthOfBytesToSign, 0x00, lengthNeededForPayload};
		ByteArrayOutputStream commandAPDUByteArrayOutputStream = new ByteArrayOutputStream(commandAPDUHeader.length + lengthNeededForPayload);
		
		commandAPDUByteArrayOutputStream.write(commandAPDUHeader);
		commandAPDUByteArrayOutputStream.write(publicKey);
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

		// use PKCS5 derivation to derive the password
		byte[] passwordBytes = PKCS5Util.derivePKCS5Key(password, LENGTH_OF_PASSWORD_PKCS5_KEY_IN_BITS, _passwordPKCS5PasswordKeySalt, _passwordPKCS5IterationCount);
		int lengthOfPasswordBytes = passwordBytes.length;
				
		byte[] commandAPDUHeader = new byte[] {(byte)0x80, 0x03, 0x00, 0x00, (byte)lengthOfPasswordBytes};
		
		ByteArrayOutputStream commandAPDUByteArrayOutputStream = new ByteArrayOutputStream(commandAPDUHeader.length + lengthOfPasswordBytes);
		
		commandAPDUByteArrayOutputStream.write(commandAPDUHeader);
		commandAPDUByteArrayOutputStream.write(passwordBytes);
		
		byte[] commandAPDU = commandAPDUByteArrayOutputStream.toByteArray();
		
		_logger.info("login: Sending command APDU to login");
		byte[] responseAPDU = _smartCardReader.exchangeAPDU(commandAPDU);
		_logger.info("login: Got response: " + Util.bytesToHex(responseAPDU));
		
		ensureResponseEndsWith9000(responseAPDU);
		// force a status refresh from the card to update the PIN attempts left count
		readInitialStateFromResponseAPDU(responseAPDU);

		return;
	}

	@Override
	public ECKeyEntry createOrInjectKey(byte[] associatedDataBytes, String friendlyName, byte[] privateKey,
			byte[] publicKey, long creationTimeMillis) throws IOException {
		_logger.info("createOrInjectKey: called");
		ensureInitialStateRead(false);
		if (!isAuthenticated()) {
			// already authenticated, nothing to do
			_logger.error("createOrInjectKey: Not authenticated");
			throw new IOException("createOrInjectKey: Not authenticated");
		}

		int lengthOfPrivateKey = 0;
		int lengthOfPublicKey = 0;
		boolean publicKeyWasCompressed = false;
		if (privateKey != null && publicKey != null) {
			byte[] uncompressedPublicKey = ECUtil.getPublicKeyBytesFromEncoding(publicKey, false); // make sure we have an uncompressed encoding of the public key
			if (uncompressedPublicKey != publicKey) {
				// the original key was compressed
				publicKeyWasCompressed = true;
				publicKey = uncompressedPublicKey;
			}
			lengthOfPrivateKey = privateKey.length;
			lengthOfPublicKey = publicKey.length;
			if (lengthOfPrivateKey != LENGTH_OF_PRIVATE_KEY || lengthOfPublicKey != LENGTH_OF_PUBLIC_KEY) {
				throw new IllegalArgumentException("Key length was wrong.");
			}
		}

		if (associatedDataBytes == null) {
			// the caller did not supply associated data, generate it for caller
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
			associatedDataByteArrayOutputStream.write(Util.longToBytes(creationTimeMillis));

			associatedDataByteArrayOutputStream.write(ECKeyEntry.ASSOCIATED_DATA_TYPE_MISC_BIT_FIELD);
			associatedDataByteArrayOutputStream.write(0x01);
			associatedDataByteArrayOutputStream.write(publicKeyWasCompressed ? 0x80 : 0x00); 
			
			// we need a total of 64 bytes of associated data, pad the stream
			int lengthSoFar = associatedDataByteArrayOutputStream.size();
			int bytesToWrite = LENGTH_OF_ASSOCIATED_DATA - lengthSoFar;
			for (int i = 0; i < bytesToWrite; i++) {
				associatedDataByteArrayOutputStream.write(0);
			}
			
			associatedDataBytes = associatedDataByteArrayOutputStream.toByteArray();
		}
		
		int lengthOfAssociatedDataBytes = associatedDataBytes.length;
		
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

		boolean isLocked = extractIsLocked(responseAPDU);
		
		// copy the public key bytes out
		byte[] publicKeyBytesFromSecureElement = extractPublicKey(responseAPDU);
		
		// copy the associated data bytes out
		byte[] associatedDataBytesFromSecureElement = extractAssociatedData(responseAPDU);

		return new ECKeyEntry(isLocked, publicKeyBytesFromSecureElement, associatedDataBytesFromSecureElement, null);
	}
	
	private static boolean extractIsLocked(byte[] responseAPDU) {
		return responseAPDU[0] == 1;
	}
	
	private static byte[] extractPublicKey(byte[] responseAPDU) {
		byte[] publicKeyBytesFromSecureElement = new byte[LENGTH_OF_PUBLIC_KEY];
		System.arraycopy(responseAPDU, 1, publicKeyBytesFromSecureElement, 0, LENGTH_OF_PUBLIC_KEY);
		return publicKeyBytesFromSecureElement;
	}
	
	private static byte[] extractAssociatedData(byte[] responseAPDU) {
		// copy the associated data bytes out
		byte[] associatedDataBytesFromSecureElement = new byte[LENGTH_OF_ASSOCIATED_DATA];
		System.arraycopy(responseAPDU, 1 + LENGTH_OF_PUBLIC_KEY, associatedDataBytesFromSecureElement, 0, LENGTH_OF_ASSOCIATED_DATA);
		return associatedDataBytesFromSecureElement;
	}
	
	private static byte[] extractPrivateKey(byte[] responseAPDU) {
		byte[] privateKeyBytesFromSecureElement = new byte[LENGTH_OF_PRIVATE_KEY];
		System.arraycopy(responseAPDU, 1 + LENGTH_OF_PUBLIC_KEY + LENGTH_OF_ASSOCIATED_DATA, privateKeyBytesFromSecureElement, 0, LENGTH_OF_PRIVATE_KEY);
		return privateKeyBytesFromSecureElement;
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

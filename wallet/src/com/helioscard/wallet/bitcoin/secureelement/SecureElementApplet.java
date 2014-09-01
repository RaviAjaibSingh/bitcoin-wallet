package com.helioscard.wallet.bitcoin.secureelement;

import java.io.IOException;
import java.util.List;

public abstract class SecureElementApplet {
	public enum PINState {
		NOT_SET, BLANK, SET
	}

	protected SecureElementApplet() {
		// Defeat instantiation.
	}
	
	public abstract boolean isAuthenticated() throws IOException;
	
	public abstract PINState getPINState() throws IOException;
	
	public abstract void setCardPassword(String oldPassword, String newPassword) throws IOException;

	public abstract List<ECKeyEntry> getECKeyEntries(boolean includePrivate) throws IOException;

	public abstract boolean checkConnection();
	
	public abstract void close();
	
	public abstract byte[] doSimpleSign(byte[] publicKeyToUse, byte[] bytesToSign) throws IOException;
	
	public abstract void beginTransactionSigningOperation(String password, byte[] destinationAddress, long amount) throws IOException;
	
	public abstract byte[] login(String password, byte[] hashedPasswordBytes) throws IOException;
	
	public abstract ECKeyEntry createOrInjectKey(byte[] associatedDataBytes, String friendlyName, byte[] privateKey, byte[] publicKey, long creationTimeMillis) throws IOException;
	
	public abstract int getNumberPasswordAttemptsLeft() throws IOException;
	
	public abstract void deleteKey(byte[] publicKey) throws IOException;
	
	public abstract void changeLabel(byte[] publicKey, String label) throws IOException;

    public abstract String getCardIdentifier() throws IOException;
    
    public abstract byte[] enableCachedSigning() throws IOException;
    
    public abstract byte[] getCachedSigningDataForIdentifier(String password, byte[] cacheIdentifer) throws IOException;
    
	public abstract int getMaxNumberOfKeys() throws IOException;
	
	public abstract int getCurrentNumberOfKeys() throws IOException;
}

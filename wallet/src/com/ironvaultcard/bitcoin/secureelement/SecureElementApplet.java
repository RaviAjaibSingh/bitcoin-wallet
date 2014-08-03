package com.fortunacard.bitcoin.secureelement;

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

	public abstract List<ECKeyEntry> getECPublicKeyEntries() throws IOException;

	public abstract boolean checkConnection();
	
	public abstract void close();
	
	public abstract byte[] doSimpleSign(String password, byte[] publicKeyToUse, byte[] bytesToSign) throws IOException;
	
	public abstract void beginTransactionSigningOperation(String password, byte[] destinationAddress, long amount) throws IOException;
	
	public abstract void login(String password) throws IOException;
	
	public abstract ECKeyEntry createOrInjectKey(String friendlyName, byte[] privateKey, byte[] publicKey) throws IOException;
	
	public abstract int getNumberPasswordAttemptsLeft() throws IOException;
	
	public abstract void deleteKey(byte[] publicKey) throws IOException;
	
	public abstract void changeLabel(byte[] publicKey, String label) throws IOException;

    public abstract String getCardIdentifier() throws IOException;
}

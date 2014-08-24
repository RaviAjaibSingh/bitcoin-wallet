package com.helioscard.wallet.bitcoin.secureelement.simulated;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.helioscard.wallet.bitcoin.secureelement.ECKeyEntry;
import com.helioscard.wallet.bitcoin.secureelement.SecureElementApplet;
import com.helioscard.wallet.bitcoin.util.Util;

public class SecureElementAppletSimulatorImpl extends SecureElementApplet {

	private PINState _pinState = PINState.NOT_SET;

	@Override
	public PINState getPINState() {
		// TODO Auto-generated method stub
		return _pinState;
	}
	

	@Override
	public void setCardPassword(String oldPassword, String newPassword) {
		if (_pinState == PINState.NOT_SET) {
			// pretend we set the password and logged in
			_pinState = PINState.SET;
		}
	}

	@Override
	public List<ECKeyEntry> getECPublicKeyEntries() {
		List<ECKeyEntry> list = new ArrayList<ECKeyEntry>();

		byte[] publicKeyBytes = Util.hexStringToByteArray("0224A1F7144E508E236F726A06FD098BED00FA92B6D87192AEE992C86E68CA56DC"); 
		ECKeyEntry info1 = new ECKeyEntry(publicKeyBytes, null);

		list.add(info1);
		return list;
	}
	
	@Override
	public void close() {
		return;
	}

	@Override
	public boolean checkConnection() {
		// assume the simulated smart card reader is always connected
		return true;
	}

	@Override
	public void beginTransactionSigningOperation(String password, byte[] destinationAddress, long amount) {
		return;
	}


	@Override
	public byte[] doSimpleSign(byte[] publicKeyToUse,
			byte[] bytesToSign) {
		// TODO Auto-generated method stub
		return null;
	}


	@Override
	public boolean isAuthenticated() throws IOException {
		return true;
	}


	@Override
	public void login(String password) {
		// TODO Auto-generated method stub
		return;
	}


	@Override
	public ECKeyEntry createOrInjectKey(String friendlyName, byte[] privateKey,
			byte[] publicKey) throws IOException {
		return null;
	}


	@Override
	public int getNumberPasswordAttemptsLeft() throws IOException {
		return 10;
	}


	@Override
	public void deleteKey(byte[] publicKey) throws IOException {
		return;
	}


	@Override
	public void changeLabel(byte[] publicKey, String label) throws IOException {
		return;
	}

    @Override
    public String getCardIdentifier() throws IOException {
        return "CardIdentifier";
    }

    public byte[] enableCachedSigning() throws IOException {
    	return null;
    }
    
    public byte[] getCachedSigningDataForIdentifier(String password, byte[] cacheIdentifer) throws IOException {
    	return null;
    }
}

package com.fortunacard.bitcoin.wallet;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;

import com.google.bitcoin.core.Wallet;
import com.google.bitcoin.core.WalletExtension;

public class FriendlyNameWalletExtension implements WalletExtension {

	private ArrayList<String> _friendlyNames = new ArrayList<String>();
	
	@SuppressWarnings("unchecked")
	@Override
	public void deserializeWalletExtension(Wallet arg0, byte[] arg1)
			throws Exception {
		// TODO Auto-generated method stub
		ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(arg1);
		ObjectInputStream objectInputStream = new ObjectInputStream(byteArrayInputStream);
		_friendlyNames = (ArrayList<String>)objectInputStream.readObject();
		objectInputStream.close();
	}

	@Override
	public String getWalletExtensionID() {
		return "com.securecoincard.wallet.FriendlyNameWalletExtension";
	}

	@Override
	public boolean isWalletExtensionMandatory() {
		return false;
	}

	@Override
	public byte[] serializeWalletExtension() {
		try {
			ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
			ObjectOutputStream objectOutputStream = new ObjectOutputStream(byteArrayOutputStream);
			objectOutputStream.writeObject(_friendlyNames);
			objectOutputStream.flush();
			objectOutputStream.close();
			
			return byteArrayOutputStream.toByteArray();
		} catch (IOException e) {
			e.printStackTrace();
			return null;
		}
	}
	
	public void addFriendlyName(String friendlyName) {
		_friendlyNames.add(friendlyName);
	}
	
	public void replaceFriendlyName(int index, String friendlyName) {
		_friendlyNames.set(index, friendlyName);
	}
	
	public void removeFriendlyName(int index) {
		_friendlyNames.remove(index);
	}
	
	public String getFriendlyName(int index) {
		return _friendlyNames.get(index);
	}
	
	public void clearFriendlyNames() {
		_friendlyNames.clear();
	}
}

package com.helioscard.bitcoin;

import de.schildbach.wallet.ui.WalletActivity;
import de.schildbach.wallet.AddressBookProvider;
import de.schildbach.wallet.WalletApplication;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;

import com.google.bitcoin.core.Address;
import com.google.bitcoin.core.Wallet;

public class IntegrationConnector {
	public static final Class<WalletActivity> WALLET_ACTIVITY_CLASS = de.schildbach.wallet.ui.WalletActivity.class;
	
	public static Wallet getWallet(Activity activityContext) {
		return ((WalletApplication)activityContext.getApplication()).getWallet();
	}
	
	public static void deleteBlockchainAndRestartService(Activity activityContext) {
		((WalletApplication)activityContext.getApplication()).resetBlockchain();		
	}
	
	public static void setLabelForAddress(Context context, Address address, String newLabel) {
		String addressString = address.toString();
		final Uri uri = AddressBookProvider.contentUri(context.getPackageName()).buildUpon().appendPath(addressString).build();

		final String label = AddressBookProvider.resolveLabel(context, addressString);

		final boolean isAdd = label == null;
		
		final ContentValues values = new ContentValues();
		values.put(AddressBookProvider.KEY_LABEL, newLabel);

		if (newLabel == null || newLabel.isEmpty()) {
			if (!isAdd) {
				context.getContentResolver().delete(uri,  null, null);
			}
		} else if (isAdd) {
			context.getContentResolver().insert(uri, values);
		} else {
			if (!label.equals(newLabel)) {
				context.getContentResolver().update(uri, values, null, null);
			}
		}
	}
}

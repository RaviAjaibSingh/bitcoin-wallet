package com.ravsing.securecoincard;

import de.schildbach.wallet.ui.WalletActivity;
import de.schildbach.wallet.WalletApplication;

import android.app.Activity;

import com.google.bitcoin.core.Wallet;

public class IntegrationConnector {
	public static final Class<WalletActivity> WALLET_ACTIVITY_CLASS = de.schildbach.wallet.ui.WalletActivity.class;
	
	public static Wallet getWallet(Activity activityContext) {
		return ((WalletApplication)activityContext.getApplication()).getWallet();
	}
	
	public static void deleteBlockchainAndRestartService(Activity activityContext) {
		((WalletApplication)activityContext.getApplication()).resetBlockchain();		
	}
}

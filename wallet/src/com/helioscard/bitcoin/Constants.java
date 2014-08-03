package com.helioscard.bitcoin;

import com.google.bitcoin.core.NetworkParameters;
import com.google.bitcoin.params.MainNetParams;
import com.google.bitcoin.params.TestNet3Params;

public class Constants {
	public static final boolean PRODUCTION_BUILD = false;
	public static NetworkParameters NETWORK_PARAMETERS = PRODUCTION_BUILD ? MainNetParams.get() : TestNet3Params.get();

	public static final boolean USE_REAL_SMART_CARD = true;
	public static final String BITCOIN_WALLET_SUBDIRECTORY_NAME = "bitcoin-wallets";
	
	public static final String BITCOIN_CURRENCY_ABBREVIATION = "BTC";
}

package com.ironvaultcard.bitcoin.wallet;


import java.util.Arrays;
import java.util.List;

import android.content.Context;
import android.content.SharedPreferences;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import com.google.bitcoin.core.Address;
import com.google.bitcoin.core.ECKey;
import com.google.bitcoin.core.Wallet;
import com.ironvaultcard.bitcoin.Constants;
import com.ironvaultcard.bitcoin.IntegrationConnector;
import com.ironvaultcard.bitcoin.secureelement.ECKeyEntry;

public class WalletGlobals {
	private static WalletGlobals _walletGlobals;
    private static final String PREFERENCES_FILE_WALLET = "PREFERENCES_FILE_WALLET";
    private static final String PREFERENCES_FIELD_CARD_IDENTIFIER = "CardIdentifier";
	
    private static Logger _logger = LoggerFactory.getLogger(WalletGlobals.class);
    
	private String _cardIdentifier;
	
	public static WalletGlobals getInstance(Context context) {
		if (_walletGlobals == null) {
			_walletGlobals = new WalletGlobals(context);
		}
		return _walletGlobals;
	}
	
	public WalletGlobals(Context context) {
        SharedPreferences sharedPreferences = context.getSharedPreferences(PREFERENCES_FILE_WALLET, Context.MODE_PRIVATE);
        // read the last card identifier we used
        _cardIdentifier = sharedPreferences.getString(PREFERENCES_FIELD_CARD_IDENTIFIER, null);
        _logger.info("read cached cardIdentifier: " + _cardIdentifier);
		if (_cardIdentifier == null) {
			// the wallet isn't initialized yet
			_logger.info("no card identifier");
		}
	}
	
	public String getCardIdentifier() {
		return _cardIdentifier;
	}

    public boolean setCardIdentifier(Context context, String cardIdentifier) {
        // we might be switching cards, clear out the old wallet
        if (_cardIdentifier != null && _cardIdentifier.equals(cardIdentifier)) {
            _logger.info("setCardIdentifier: ignoring setCardIdentifier, already matches");
            return false;
        }

        _logger.info("setCardIdentifier: changing card identifier to: " + cardIdentifier);

        boolean cardIdentifierWasChanged = (_cardIdentifier != null);
        _cardIdentifier = cardIdentifier;

        // update the last known card identifier, so we can re-use next time if we're reloaded without the wallet
        // app being explicitly tapped by a card
        SharedPreferences sharedPreferences = context.getSharedPreferences(PREFERENCES_FILE_WALLET, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString(PREFERENCES_FIELD_CARD_IDENTIFIER, _cardIdentifier);
        editor.commit();
        return cardIdentifierWasChanged;
    }
    
	public static boolean synchronizeKeys(Context context, Wallet wallet, List<ECKeyEntry> listFromSecureElement) {		
		boolean serviceNeedsToClearAndRestart = false;
		
		// get the list from the secure element
		boolean cachedWalletWasCleared = false;
		for (ECKeyEntry keyFromSecureElement : listFromSecureElement) {
			// see if we can find the key in local cached wallet
			List<ECKey> listFromCachedWallet = wallet.getKeys();
			
			boolean keyFound = false;
			
			for (int i = 0; i < listFromCachedWallet.size(); i++) {
				ECKey keyFromCachedWallet = listFromCachedWallet.get(i);
				keyFound = Arrays.equals(keyFromCachedWallet.getPubKey(), keyFromSecureElement.getPublicKeyBytes());
				if (keyFound) {
					// we found a match - break
					// but also make sure the names are synchronized
					IntegrationConnector.setLabelForAddress(context, new Address(Constants.NETWORK_PARAMETERS, keyFromCachedWallet.getPubKeyHash()), keyFromSecureElement.getFriendlyName());
					_logger.info("synchronizeKeysWithSecureElement: matched key from secure element with cache");
					break;
				}
			}
			
			if (!keyFound) {
				_logger.info("synchronizeKeysWithSecureElement: failed to match secure element key with cache - wiping service");
                serviceNeedsToClearAndRestart = true;
				// if we got here without finding the key, then we have to clear out the cached wallet
				// and restart the service so that we can do a full peer resync and figure out how much money
				// we have

				// we know we need to stop the service and delete the block chain file now

				// delete all the keys in the cached wallet
				for (ECKey keyToDeleteInCachedWallet : listFromCachedWallet) {

					// TODO: fix this to remove friendly name
					// removeKey(keyToDeleteInCachedWallet);
					
					wallet.removeKey(keyToDeleteInCachedWallet);
					
				}
				
				wallet.clearTransactions(0);

				// _friendlyNameWalletExtension.clearFriendlyNames();
				
				cachedWalletWasCleared = true;

				// now make the keys in the cached wallet equal to the keys in the secure element
				for (ECKeyEntry keyFromSecureElementToAddToCachedWallet : listFromSecureElement) {
					_logger.info("synchronizeKeysWithSecureElement: added key from secure element to cache");
					
					// TODO: fix this to include friendly name
					// addECKeyEntryToWallet(keyFromSecureElementToAddToCachedWallet);
					addECKeyEntryToWallet(context, wallet, keyFromSecureElementToAddToCachedWallet);
				}
				
				break;
			}
		}
		
		if (!cachedWalletWasCleared) {
			// so far, so good.  All the keys from the secure element are in the cached wallet.
			// but we may have additional keys in the cached wallet that aren't in the secure element - deal with that
			// by removing those keys from the cached wallet
			List<ECKey> listFromCachedWallet = wallet.getKeys();
			for (ECKey keyFromCachedWallet : listFromCachedWallet) {
				boolean keyFound = false;
				for (ECKeyEntry keyFromSecureElement : listFromSecureElement) {
					keyFound = Arrays.equals(keyFromCachedWallet.getPubKey(), keyFromSecureElement.getPublicKeyBytes());
					if (keyFound) {
						_logger.info("synchronizeKeysWithSecureElement: found cached wallet key in secure element");
						// we found a match - break
						break;
					}						
				}
				if (!keyFound) {
					_logger.info("synchronizeKeysWithSecureElement: removing a cached wallet key");
					serviceNeedsToClearAndRestart = true;
					
					// TODO: remove the friendly name?
					// TODO: do we have to cleanup the extra transactions in the wallet?  We probably have extra ones now
					// that we don't need
					wallet.removeKey(keyFromCachedWallet);
				}
			}
		}
		
		return serviceNeedsToClearAndRestart;
	}

	public static void addECKeyEntryToWallet(Context context, Wallet wallet, ECKeyEntry keyToAdd) {
		byte[] publicKeyBytes = keyToAdd.getPublicKeyBytes();
		ECKey ecKeyToAdd = new ECKey(null, publicKeyBytes);

		long timeOfCreation = keyToAdd.getTimeOfKeyCreationSecondsSinceEpoch();
		// The secure element knows what time the key was created.  Set the key in the cached wallet to also know
		// to speed up block chain synchronization.
		if (timeOfCreation != -1) {
			ecKeyToAdd.setCreationTimeSeconds(timeOfCreation);
		}
		
		IntegrationConnector.setLabelForAddress(context, new Address(Constants.NETWORK_PARAMETERS, ecKeyToAdd.getPubKeyHash()), keyToAdd.getFriendlyName());
		wallet.addKey(ecKeyToAdd);

		// TODO: do something about friendly names
		// addKey(ecKeyToAdd, keyToAdd.getFriendlyName());
	}
	
}

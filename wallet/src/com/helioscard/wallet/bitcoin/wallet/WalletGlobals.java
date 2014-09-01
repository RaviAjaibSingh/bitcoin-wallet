package com.helioscard.wallet.bitcoin.wallet;


import java.util.Arrays;
import java.util.List;

import android.app.Activity;
import android.app.Service;
import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import com.google.bitcoin.core.Address;
import com.google.bitcoin.core.ECKey;
import com.google.bitcoin.core.Wallet;
import com.helioscard.wallet.bitcoin.Constants;
import com.helioscard.wallet.bitcoin.IntegrationConnector;
import com.helioscard.wallet.bitcoin.secureelement.ECKeyEntry;

public class WalletGlobals {
	private static WalletGlobals _walletGlobals;
    private static final String PREFERENCES_FIELD_CARD_IDENTIFIER = "HeliosCardCurrentCardIdentifier";
    private static final String PREFERENCES_FIELD_SERVICE_NEEDS_TO_REPLAY_BLOCKCHAIN = "HeliosCardServiceNeedsToReplayBlockChain";
	
    private static Logger _logger = LoggerFactory.getLogger(WalletGlobals.class);
    
	private String _cardIdentifier;
	
	public static WalletGlobals getInstance(Context context) {
		if (_walletGlobals == null) {
			_walletGlobals = new WalletGlobals(context);
		}
		return _walletGlobals;
	}
	
	public WalletGlobals(Context context) {
		Context baseContext = null;
		if (context instanceof Activity) {
			baseContext = ((Activity)context).getBaseContext();
		} else if (context instanceof Service) {
			baseContext = ((Service)context).getBaseContext();
		}
		
		SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(baseContext);
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
		Context baseContext = null;
		if (context instanceof Activity) {
			baseContext = ((Activity)context).getBaseContext();
		} else if (context instanceof Service) {
			baseContext = ((Service)context).getBaseContext();
		}

        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(baseContext);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString(PREFERENCES_FIELD_CARD_IDENTIFIER, _cardIdentifier);
        editor.commit();
        return cardIdentifierWasChanged;
    }
    
	public static boolean synchronizeKeys(Activity activityContext, Wallet wallet, List<ECKeyEntry> listFromSecureElement, boolean wipeWalletIfNeeded) {		
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
					IntegrationConnector.setLabelForAddress(activityContext, new Address(Constants.NETWORK_PARAMETERS, keyFromCachedWallet.getPubKeyHash()), keyFromSecureElement.getFriendlyName());
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
				if (wipeWalletIfNeeded) {
					persistServiceNeedsToReplayBlockchain(activityContext);
				}
                
                // delete all the keys in the cached wallet
				for (ECKey keyToDeleteInCachedWallet : listFromCachedWallet) {
					removeECKeyFromCachedWalletInternal(activityContext, wallet, keyToDeleteInCachedWallet);
				}

				cachedWalletWasCleared = true;

				// now make the keys in the cached wallet equal to the keys in the secure element
				for (ECKeyEntry keyFromSecureElementToAddToCachedWallet : listFromSecureElement) {
					_logger.info("synchronizeKeysWithSecureElement: added key from secure element to cache");
					
					// TODO: fix this to include friendly name
					// addECKeyEntryToWallet(keyFromSecureElementToAddToCachedWallet);
					addECKeyEntryToCachedWallet(activityContext, wallet, keyFromSecureElementToAddToCachedWallet);
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
					persistServiceNeedsToReplayBlockchain(activityContext);					
					
					removeECKeyFromCachedWalletInternal(activityContext, wallet, keyFromCachedWallet);
				}
			}
		}
		
		return serviceNeedsToClearAndRestart;
	}
	
	public static void persistServiceNeedsToReplayBlockchain(Activity activityContext) {
		// Call this method to indicate the contents of the wallet are about to change, and that we need to ensure
		// that the service clears the blockchain and replays it.  This is to prevent an interruption where we change the 
		// wallet and then we reset the device before we can tell the service to delete the blockchain.  On service startup,
		// the service should check to see whether the block chain needs to be replayed, and if so, replay it and clear this flag
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(activityContext.getBaseContext());
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putBoolean(PREFERENCES_FIELD_SERVICE_NEEDS_TO_REPLAY_BLOCKCHAIN, true);
        editor.commit();
        
    	Wallet wallet = IntegrationConnector.getWallet(activityContext);
    	wallet.clearTransactions(0);
    	wallet.setLastBlockSeenHeight(-1); // magic value
    	wallet.setLastBlockSeenHash(null);
	}
	
	public static void resetServiceNeedsToReplayBlockchain(Context context) {
		Context baseContext = null;
		if (context instanceof Activity) {
			baseContext = ((Activity)context).getBaseContext();
		} else if (context instanceof Service) {
			baseContext = ((Service)context).getBaseContext();
		}
		
		SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(baseContext);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putBoolean(PREFERENCES_FIELD_SERVICE_NEEDS_TO_REPLAY_BLOCKCHAIN, false);
        editor.commit();
	}
	
	public static boolean getServiceNeedsToReplayBlockchain(Context context) {
		Context baseContext = null;
		if (context instanceof Activity) {
			baseContext = ((Activity)context).getBaseContext();
		} else if (context instanceof Service) {
			baseContext = ((Service)context).getBaseContext();
		}

        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(baseContext);
        // read the last card identifier we used
        return sharedPreferences.getBoolean(PREFERENCES_FIELD_SERVICE_NEEDS_TO_REPLAY_BLOCKCHAIN, false);
	}

	public static void addECKeyEntryToCachedWallet(Context context, Wallet wallet, ECKeyEntry keyToAdd) {
		byte[] publicKeyBytes = keyToAdd.getPublicKeyBytes();
		ECKey ecKeyToAdd = new ECKey(null, publicKeyBytes);

		long timeOfCreation = keyToAdd.getTimeOfKeyCreationSecondsSinceEpoch();
		// The secure element knows what time the key was created.  Set the key in the cached wallet to also know
		// to speed up block chain synchronization.
		if (timeOfCreation != -1) {
			ecKeyToAdd.setCreationTimeSeconds(timeOfCreation);
		}
		
		IntegrationConnector.setLabelForAddress(context, ecKeyToAdd.toAddress(Constants.NETWORK_PARAMETERS), keyToAdd.getFriendlyName());
		wallet.addKey(ecKeyToAdd);

		// TODO: do something about friendly names
		// addKey(ecKeyToAdd, keyToAdd.getFriendlyName());
	}
	
	public static void removeECKeyFromCachedWallet(Activity activityContext, byte[] publicKeyBytes) {
		// remove the key from the local cached wallet
		ECKey ecKey = new ECKey(null, publicKeyBytes);
		Wallet wallet = IntegrationConnector.getWallet(activityContext);
		
		// mark that we are about to make a change to the wallet and need to resync the service
		persistServiceNeedsToReplayBlockchain(activityContext);
		
		// remove it from the cached wallet
		removeECKeyFromCachedWalletInternal(activityContext, wallet, ecKey);

		// replay the block chain
		IntegrationConnector.deleteBlockchainAndRestartService(activityContext);
	}
	
	private static void removeECKeyFromCachedWalletInternal(Context context, Wallet wallet, ECKey ecKeyToRemove) {
		wallet.removeKey(ecKeyToRemove);
		
		// now remove the address from the address book
		IntegrationConnector.setLabelForAddress(context, ecKeyToRemove.toAddress(Constants.NETWORK_PARAMETERS), null);		
	}
}

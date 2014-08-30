package com.helioscard.wallet.bitcoin.ui;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.PendingIntent;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.TagLostException;
import android.nfc.tech.IsoDep;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.os.PatternMatcher;
import android.provider.Settings;
import android.text.InputFilter;
import android.view.KeyEvent;
import android.view.View;
import android.view.View.OnKeyListener;
import android.widget.EditText;
import android.widget.Toast;

import android.support.v4.app.DialogFragment;
import android.support.v4.app.FragmentManager;

import com.actionbarsherlock.app.SherlockFragmentActivity;
import com.helioscard.wallet.bitcoin.Constants;
import com.helioscard.wallet.bitcoin.IntegrationConnector;
import com.helioscard.wallet.bitcoin.R;
import com.helioscard.wallet.bitcoin.secureelement.ECKeyEntry;
import com.helioscard.wallet.bitcoin.secureelement.ECUtil;
import com.helioscard.wallet.bitcoin.secureelement.SecureElementApplet;
import com.helioscard.wallet.bitcoin.secureelement.SmartCardReader;
import com.helioscard.wallet.bitcoin.secureelement.SecureElementApplet.PINState;
import com.helioscard.wallet.bitcoin.secureelement.androidadapter.SmartCardReaderImpl;
import com.helioscard.wallet.bitcoin.secureelement.exception.CardWasWipedException;
import com.helioscard.wallet.bitcoin.secureelement.exception.KeyAlreadyExistsException;
import com.helioscard.wallet.bitcoin.secureelement.exception.SmartCardFullException;
import com.helioscard.wallet.bitcoin.secureelement.exception.WrongPasswordException;
import com.helioscard.wallet.bitcoin.secureelement.real.SecureElementAppletImpl;
import com.helioscard.wallet.bitcoin.secureelement.simulated.SecureElementAppletSimulatorImpl;
import com.helioscard.wallet.bitcoin.wallet.WalletGlobals;

import com.google.bitcoin.core.Address;
import com.google.bitcoin.core.ECKey;
import com.google.bitcoin.core.Wallet;

public abstract class NFCAwareActivity extends SherlockFragmentActivity {
	private static Logger _logger = LoggerFactory.getLogger(NFCAwareActivity.class);
	
	private static SecureElementApplet _cachedSecureElementApplet;
    
    private NfcAdapter _nfcAdapter;
    private PendingIntent _pendingIntent;
    private IntentFilter[] _intentFiltersArray;
	
    private static final String INSTANCE_STATE_PENDING_CARD_PASSWORD = "INSTANCE_STATE_PENDING_CARD_PASSWORD";
    private static final String INSTANCE_STATE_PENDING_USE_EXISTING_SESSION_IF_POSSIBLE = "INSTANCE_STATE_PENDING_USE_EXISTING_SESSION_IF_POSSIBLE";
    private static final String INSTANCE_STATE_PENDING_ADD_KEY_LABEL = "INSTANCE_STATE_PENDING_ADD_KEY_LABEL";
    private static final String INSTANCE_STATE_PENDING_EDIT_PUBLIC_KEY = "INSTANCE_STATE_PENDING_EDIT_PUBLIC_KEY";
    private static final String INSTANCE_STATE_PENDING_EDIT_LABEL = "INSTANCE_STATE_PENDING_EDIT_LABEL";
    private static final String INSTANCE_STATE_PENDING_DELETE_KEY_PUBLIC_KEY_BYTES = "INSTANCE_STATE_PENDING_DELETE_KEY_PUBLIC_KEY_BYTES";
    private static final String INSTANCE_STATE_PENDING_BACKUP_CARD = "INSTANCE_STATE_PENDING_BACKUP_CARD";
    private static final String INSTANCE_STATE_PENDING_SAVE_KEYS_TO_CARD = "INSTANCE_STATE_PENDING_SAVE_KEYS_TO_CARD";
    
    private String _pendingCardPassword;
    private boolean _pendingUseExistingSessionIfPossible;
    private String _pendingAddKeyLabel;
    private byte[] _pendingEditPublicKey;
    private String _pendingEditLabel;
    private byte[] _pendingDeleteKeyPublicKeyBytes;
    private String _pendingChangePasswordNewPassword;
    private boolean _pendingBackupCard;
    private boolean _pendingSaveKeysToCard;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        _pendingIntent = PendingIntent.getActivity(
                this, 0, new Intent(this, getClass()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), 0);

        _nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        // if (mNfcAdapter == null) {
        //     This will never happen because we're requiring in the manifest that the device is NFC capable.
        // }

        // The card will have two NDEF records: a URL and an Android Application Record
        // Enable foreground dispatch on the URL to make sure this current activity isn't replaced by the wallet activity (since
        // that's the only activity with the URL statically declared in the manifest)
        IntentFilter ndefFilter = new IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED);
        ndefFilter.addDataScheme("https");
        ndefFilter.addDataAuthority("www.helioscard.com", null);
        ndefFilter.addDataPath("/tag.html", PatternMatcher.PATTERN_LITERAL);
        
        _intentFiltersArray = new IntentFilter[] {ndefFilter};

        if (this instanceof de.schildbach.wallet.ui.WalletActivity) {
        	showGetStartedDialogIfNeeded();
        }
        
         // if we're first launched by an NFC tag, we don't get an onNewIntent message, so route it through
         // now
         boolean startedByCardTap = processIntent(getIntent());
        
         if (WalletGlobals.getInstance(this).getCardIdentifier() == null && !(this instanceof MainActivity) && !(this instanceof InitializeCardActivity)) {
        	// This app has never been used with a card before
        	if (startedByCardTap) {
        		// But we were started by an intent which represented a card tap - everything has been handled, nothing for us to
        		// do here
        		_logger.info("onCreate: started by NFC tap, bailing");
        		EULAAndSafetyDialogFragment.promptIfNeeded(getSupportFragmentManager(), this);
        		return;
        	}
        	
        	// Otherwise, ensure we are focused on the tap to begin screen to prompt the user to tap
        	Intent intentToStartTapToBeginActivity = new Intent(this, MainActivity.class);
        	intentToStartTapToBeginActivity.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        	startActivity(intentToStartTapToBeginActivity);
        	this.finish();
        	return;
        }
 		EULAAndSafetyDialogFragment.promptIfNeeded(getSupportFragmentManager(), this);
    }
    
    @Override
    protected void onSaveInstanceState(Bundle outState) {
    	_logger.info("onSaveInstanceState: called");
    	super.onSaveInstanceState(outState);

    	outState.putString(INSTANCE_STATE_PENDING_CARD_PASSWORD, _pendingCardPassword);
    	outState.putBoolean(INSTANCE_STATE_PENDING_USE_EXISTING_SESSION_IF_POSSIBLE, _pendingUseExistingSessionIfPossible);
    	outState.putString(INSTANCE_STATE_PENDING_ADD_KEY_LABEL, _pendingAddKeyLabel);
    	outState.putByteArray(INSTANCE_STATE_PENDING_EDIT_PUBLIC_KEY, _pendingEditPublicKey);
    	outState.putString(INSTANCE_STATE_PENDING_EDIT_LABEL, _pendingEditLabel);
    	outState.putByteArray(INSTANCE_STATE_PENDING_DELETE_KEY_PUBLIC_KEY_BYTES, _pendingDeleteKeyPublicKeyBytes);
    	outState.putBoolean(INSTANCE_STATE_PENDING_BACKUP_CARD, _pendingBackupCard);
    	outState.putBoolean(INSTANCE_STATE_PENDING_SAVE_KEYS_TO_CARD, _pendingSaveKeysToCard);
    }
    
    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
    	_logger.info("onRestoreInstanceState: called");
    	super.onRestoreInstanceState(savedInstanceState);
    	
    	_pendingCardPassword = savedInstanceState.getString(INSTANCE_STATE_PENDING_CARD_PASSWORD);
    	_pendingUseExistingSessionIfPossible = savedInstanceState.getBoolean(INSTANCE_STATE_PENDING_USE_EXISTING_SESSION_IF_POSSIBLE, false);
    	_pendingAddKeyLabel = savedInstanceState.getString(INSTANCE_STATE_PENDING_ADD_KEY_LABEL);
    	_pendingEditPublicKey = savedInstanceState.getByteArray(INSTANCE_STATE_PENDING_EDIT_PUBLIC_KEY);
    	_pendingEditLabel = savedInstanceState.getString(INSTANCE_STATE_PENDING_EDIT_LABEL);
    	_pendingDeleteKeyPublicKeyBytes = savedInstanceState.getByteArray(INSTANCE_STATE_PENDING_DELETE_KEY_PUBLIC_KEY_BYTES);
    	_pendingBackupCard = savedInstanceState.getBoolean(INSTANCE_STATE_PENDING_BACKUP_CARD);
    	_pendingSaveKeysToCard = savedInstanceState.getBoolean(INSTANCE_STATE_PENDING_SAVE_KEYS_TO_CARD);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        _nfcAdapter.enableForegroundDispatch(this, _pendingIntent, _intentFiltersArray, null);
    }

    @Override
    protected void onPause() {
        super.onPause();
        _nfcAdapter.disableForegroundDispatch(this);
    }

	@Override
    public void onNewIntent(Intent intent) {
		_logger.info("onNewIntent: called");
		processIntent(intent);
		EULAAndSafetyDialogFragment.promptIfNeeded(getSupportFragmentManager(), this);
	}
	
	protected boolean doesIntentComeFromHeliosCard(Intent intent) {
		if (intent == null) {
			return false;
		}
		
		// the Helios card should have an Android Application record in it corresponding to this package
		String action = intent.getAction();
		if (action == null || !action.equals(NfcAdapter.ACTION_NDEF_DISCOVERED)) {
			return false;
		}
		
	   Parcelable[] rawMsgs = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES);
	   if (rawMsgs == null || rawMsgs.length == 0) {
		   return false;
	   }
	   
	   for (int i = 0; i < rawMsgs.length; i++) {
	        // only one message sent during the beam
	        NdefMessage msg = (NdefMessage) rawMsgs[i];
	        // record 0 contains the MIME type, record 1 is the AAR, if present
	        NdefRecord[] ndefRecords = msg.getRecords();
	        _logger.info("doesIntentComeFromHeliosCard: found " + ndefRecords.length + " NDEF records");
	        for (int j = 0; j < ndefRecords.length; j++) {
		        String payload = new String(ndefRecords[j].getPayload());
	        	_logger.info("doesIntentComeFromHeliosCard: found payload of" + payload);
		        if (ndefRecords[j].getTnf() == NdefRecord.TNF_EXTERNAL_TYPE && payload.startsWith("com.helioscard.wallet")) {
		        	_logger.info("doesIntentComeFromHeliosCard: found matching AAR record");
		        	return true;
		        }
	        }
	   }
		
		return false;
	}
    
    public boolean processIntent(Intent intent) {
		_logger.info("processIntent: called");
        if (doesIntentComeFromHeliosCard(intent)) {
        	// clear out any cached secure element that we have
        	_cachedSecureElementApplet = null;

			try {
	        	if (Constants.USE_REAL_SMART_CARD) {
		        	Tag tagFromIntent = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
		            // isoDep should be non-null, this is the only technology type we're listening for
		            SmartCardReader smartCardReader = new SmartCardReaderImpl(tagFromIntent);
		            if (!smartCardReader.checkConnection()) {
		        		_logger.info("processIntent: bailing, reader not connected");
		            	return true;
		            }
					_cachedSecureElementApplet = new SecureElementAppletImpl(smartCardReader);
	        	} else {
	        		_cachedSecureElementApplet = new SecureElementAppletSimulatorImpl();
	        	}
	        	
	        	FragmentManager fragmentManager = getSupportFragmentManager();    	
                String currentCardIdentifier = WalletGlobals.getInstance(this).getCardIdentifier();
                String newCardIdentifier = _cachedSecureElementApplet.getCardIdentifier();
	        	PromptToSaveBackupDataDialogFragment promptToSaveBackupDataDialogFragment = (PromptToSaveBackupDataDialogFragment)fragmentManager.findFragmentByTag(PromptToSaveBackupDataDialogFragment.TAG);	        	

	        	if (_pendingBackupCard) {
	        		_logger.info("processIntent: pendingBackupCard was true");
	        		// Special case - we're in the middle of a backup/restore
	        		if (_cachedSecureElementApplet.getPINState() == PINState.NOT_SET) {
	        			// this card has never been initialized - toast the user and bail
	        			Toast.makeText(this, getResources().getString(R.string.nfc_aware_activity_card_not_initialized), Toast.LENGTH_LONG).show();
	        			return true;
	        		}
	        	} else if (promptToSaveBackupDataDialogFragment != null && !_pendingSaveKeysToCard) {
	        		_logger.info("processIntent: promptToSaveBackupDataDialogFragment existed");
	        		// the user is being prompted to tap in order to save keys to a card
	        		String sourceCardIdentifier = promptToSaveBackupDataDialogFragment.getSourceCardIdentifier();
	        		if (sourceCardIdentifier != null && sourceCardIdentifier.equals(newCardIdentifier)) {
		        		_logger.info("processIntent: user tapped same card during restore");
	        			// the user tapped the same card that the backup data came from - tell the user to tap a different card
	        			Toast.makeText(this, getResources().getString(R.string.nfc_aware_activity_same_card_tapped), Toast.LENGTH_LONG).show();
	        			return true;
	        		}
	        		
	        		// There are two possibilities: this card has never had a PIN set, or it has a PIN/blank PIN
	        		// 1. It's never had a PIN set
	        		// 2. We have to login to the card to get the authenticated session.
	        		if (!_cachedSecureElementApplet.isAuthenticated()) {
	        			PINState pinState = _cachedSecureElementApplet.getPINState();
	        			if (pinState == PINState.NOT_SET) {
	        				// The new card has never been used.  If we are restoring from an existing card, make its password the same as the card we are backing up from.
	    	        		_logger.info("processIntent: card never used, setting password");
	    	        		String password = promptToSaveBackupDataDialogFragment.getPassword();
	    	        		if (password != null) {
	    	        			PleaseWaitDialogFragment pleaseWaitDialogFragment = PleaseWaitDialogFragment.show(getSupportFragmentManager());
	    	        			(new SetPasswordOnCardAsyncTask(_cachedSecureElementApplet, pleaseWaitDialogFragment, SetPasswordOnCardAsyncTaskType.DOING_RESTORE, null, password)).execute();
	    	        			return true;
	    	        		} else {
	    	        			// show an error indicating this card has no password set
	    	        			Toast.makeText(this, getResources().getString(R.string.nfc_aware_activity_error_no_password_set_on_card), Toast.LENGTH_LONG).show();
	    	        			return true;
	    	        		}
	        			} else {
	        				// The PIN on this new card is already set.  Prompt the user to login
	    	        		_logger.info("processIntent: logging on now");
	    	        		_pendingSaveKeysToCard = true;
	    	        		getSecureElementAppletPromptIfNeeded(true, false);
	    	        		return true;
	        			}
	        		}
	        		
	        		return true;
	        	}

	        	if (!_pendingBackupCard && !_pendingSaveKeysToCard) {
	        		_logger.info("processIntent: normal tap");

	        		if (handleNormalTap(_cachedSecureElementApplet, currentCardIdentifier, newCardIdentifier)) {
	        			// handleNormalTap wants us to bail out of this method (it probably restarted the app due to a key sync operation)
	        			return true;
	        		}
	        		// else otherwise fall through
	        	}

	        	PromptForPasswordDialogFragment promptForPasswordDialogFragment = (PromptForPasswordDialogFragment)fragmentManager.findFragmentByTag(PromptForPasswordDialogFragment.TAG);
	        	if (promptForPasswordDialogFragment != null) {
	        		// we're currently prompting the user to enter the password
	        		// update the dialog to have the number of password attempts left
	        		promptForPasswordDialogFragment.generatePasswordAttemptsLeftText();
	        		return true;
	        	}

	        	boolean tapRequested = false;
	        	PromptForTapDialogFragment promptForTapDialogFragment = (PromptForTapDialogFragment)fragmentManager.findFragmentByTag(PromptForTapDialogFragment.TAG);
	        	if (promptForTapDialogFragment != null) {
	        		promptForTapDialogFragment.dismiss();
	        		tapRequested = true;
	        		
	        		if (_pendingCardPassword != null) {
	        			// we requested the user to tap the card so that we could log the user in
	        			// let the loginToCard function take care of logging in and then notifying the
	        			// subclass that there's a smart card session
	        			loginToCard(_pendingCardPassword);
	        			return true;
	        		}
	        	}

                // let the activity know a card has been detected
                handleCardDetectedSuper(_cachedSecureElementApplet, tapRequested, false, null);
                
                // the wallet may have a key now - hide the get started dialog if it's showing
                hideGetStartedDialogIfNeeded();
                return true;
        	} catch(IOException e) {
        		showException(e);
        		_cachedSecureElementApplet = null;
			    _logger.error("onNewIntent: IOException getting cached secure element: " + e.toString());
        	}
        }
        
        return false;
    }

    private boolean handleNormalTap(SecureElementApplet secureElementApplet, String currentCardIdentifier, String newCardIdentifier) throws IOException {
		_logger.info("handleNormalTap: called");
    	
    	// set the card identifier appropriately in the bitcoin wallet
        WalletGlobals walletGlobals = WalletGlobals.getInstance(this);
        // Get the list of public keys from the secure element
        List<ECKeyEntry> ecPublicKeyEntries = secureElementApplet.getECKeyEntries(false);

        // Synchronize the keys with the secure element.  E.g. make sure our local cache of public keys matches
        // what's on this card
        // TODO: there's a race condition here, where the wallet has the newly synchronized keys, but it could be the case we had to
        // tell the service to stop and destroy its current block chain file.  But there's a chance the process could be terminated
        // or the device could be rebooted before the service gets a chance to do that

        boolean needsToGoToInitializationScreen = false;
        boolean cardIdentifierWasChanged = false;

    	FragmentManager fragmentManager = getSupportFragmentManager();
    	PromptOnNewCardDialogFragment promptOnNewCardDialogFragment = (PromptOnNewCardDialogFragment)fragmentManager.findFragmentByTag(PromptOnNewCardDialogFragment.TAG);
        if (currentCardIdentifier != null && !currentCardIdentifier.equals(newCardIdentifier)) {
            _logger.info("handleNormalTap: had an old card identifier, but new card was tapped");
        	// we are switching cards - prompt the user
        	if (promptOnNewCardDialogFragment != null) {
                _logger.info("handleNormalTap: already showing new card dialog fragment");
        		// we were already showing the prompt on new card dialog fragment
        		String cardBeingPromptedToSwitchToIdentifier = promptOnNewCardDialogFragment.getCardBeingPromptedToSwitchToIdentifier();
                promptOnNewCardDialogFragment.dismiss();
        		if (cardBeingPromptedToSwitchToIdentifier.equals(newCardIdentifier)) {
        			// the user tapped the card we were prompting him to switch to
                    _logger.info("handleNormalTap: switching to card that was being prompted to switch to");
        			walletGlobals.setCardIdentifier(this, newCardIdentifier);
        			cardIdentifierWasChanged = true;
        		} else {
                    _logger.info("handleNormalTap: user tapped 3rd card while being prompted to switch to 2nd");
            		PromptOnNewCardDialogFragment.prompt(fragmentManager, PromptOnNewCardDialogFragment.TYPE_NEW_CARD, currentCardIdentifier, newCardIdentifier, ecPublicKeyEntries);
            		return true;
        		}
        	} else {
                _logger.info("handleNormalTap: prompting user to switch cards");
        		// prompt the user to switch cards
        		PromptOnNewCardDialogFragment.prompt(fragmentManager, PromptOnNewCardDialogFragment.TYPE_NEW_CARD, currentCardIdentifier, newCardIdentifier, ecPublicKeyEntries);
        		return true;
        	}
        } else if (promptOnNewCardDialogFragment != null) {
        	// we were showing the prompt on new card dialog fragment, but the user tapped the old card
        	// dismiss the dialog
            _logger.info("handleNormalTap: same card tapped while showing new card dialog, dismissing");
        	promptOnNewCardDialogFragment.dismiss();
        } else if (currentCardIdentifier == null){
        	// a card was tapped, and none was registered before
            _logger.info("handleNormalTap: new card tapped and no registered old card");
        	walletGlobals.setCardIdentifier(this, newCardIdentifier);
        }
        
        if (secureElementApplet.getPINState() == PINState.NOT_SET) {
        	// this is a brand new card.  we are going to need to send the user to the initialization screen
            _logger.info("handleNormalTap: detected uninitialized card");
        	// clear out the card we're tracking
            walletGlobals.setCardIdentifier(this, null);
            if (this instanceof InitializeCardActivity) {
                _logger.info("handleNormalTap: already in InitializeCardActivity, not doing anything");
            } else {
                _logger.info("handleNormalTap: need to go to initialization screen");
                needsToGoToInitializationScreen = true;
            }
        }

    	PromptForTapOnceMoreDialogFragment promptForTapOnceMoreDialogFragment = (PromptForTapOnceMoreDialogFragment)fragmentManager.findFragmentByTag(PromptForTapOnceMoreDialogFragment.TAG);
        Wallet wallet = IntegrationConnector.getWallet(this);
        boolean serviceNeedsToClearAndRestart = walletGlobals.synchronizeKeys(this, wallet, ecPublicKeyEntries, promptForTapOnceMoreDialogFragment == null);
        if (serviceNeedsToClearAndRestart) {
            // the keys between the secure element and our cached copy of public keys didn't match
            _logger.info("handleNormalTap: service needs to clear and restart");
        }
        
        if (serviceNeedsToClearAndRestart) {
        	if (promptForTapOnceMoreDialogFragment == null) {
        		// We were tapped by a card but we weren't tracking all the keys - restart the service
        		// Also, there was no tap to finish dialog showing, or there was one, but the user tapped a different card 
        		IntegrationConnector.deleteBlockchainAndRestartService(this);
        	} else {
        		_logger.info("handleNormalTap: ignoring service needs to restart due to prompt for tap dialog");
        		serviceNeedsToClearAndRestart = false;
        	}
        }

        if (cardIdentifierWasChanged || serviceNeedsToClearAndRestart || needsToGoToInitializationScreen) {
            // We need to restart the application because we have a new card, or we have new keys, or we need to go to the initialization screen
            // We want to clear any activities off the task and basically restart the activity stack focused on a new card
            Intent intentToRelaunchApplication = new Intent(this, needsToGoToInitializationScreen ? InitializeCardActivity.class : IntegrationConnector.WALLET_ACTIVITY_CLASS);
            intentToRelaunchApplication.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intentToRelaunchApplication);
            this.finish();

            return true;
        }

    	if (promptForTapOnceMoreDialogFragment != null) {
    		// We were showing a tap to finish dialog - where we were asking the user to tap so we could
    		// synchronize the keys.  That has already been done by the time we get here, so nothing to do here.
    		promptForTapOnceMoreDialogFragment.dismiss();
    		// it's possible we generated a key - dismiss the get started dialog if appropriate
    		hideGetStartedDialogIfNeeded();
    		return true;
    	}

    	return false;
    }
    
    public void deleteKeyPreTap(byte[] deleteKeyPublicKeyBytes) {
		// get a secure element session that is authenticated (authenticated session needed to add a key)
		SecureElementApplet secureElementApplet = this.getSecureElementAppletPromptIfNeeded(true, true);
		if (secureElementApplet == null) {
			// there was no authenticated session established - the user is now being prompted to provide one, so just bail out for now
		    _logger.info("deleteKeyPreTap: waiting for authenticated session");
		    _pendingDeleteKeyPublicKeyBytes = deleteKeyPublicKeyBytes;
		    return;
		}
		
		// otherwise we can just keep going and create the key
	    _logger.info("deleteKeyPreTap: have authenticated session, creating key");
	    deleteKeyPostTap(secureElementApplet, deleteKeyPublicKeyBytes);
	}
	
	private void deleteKeyPostTap(SecureElementApplet secureElementApplet, byte[] deleteKeyPublicKeyBytes) {
		_logger.info("deleteKeyPostTap: called");
		try {
			secureElementApplet.deleteKey(deleteKeyPublicKeyBytes);
			// we just deleted a key, delete it from the cached wallet and address book too
			WalletGlobals.removeECKeyFromCachedWallet(this, deleteKeyPublicKeyBytes);
			
			// have we gone down to 0 keys?  if so, restart the app so the user will be brought to the wizard to create or import a key
            Wallet wallet = IntegrationConnector.getWallet(this);
            if (wallet.getKeys().size() == 0) {
        		_logger.info("deleteKeyPostTap: no keys left in wallet - restarting");
            	// We want to clear any activities off the task and basically restart the activity stack
                Intent intentToRelaunchApplication = new Intent(this, IntegrationConnector.WALLET_ACTIVITY_CLASS);
                intentToRelaunchApplication.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(intentToRelaunchApplication);
                this.finish();
                return;
            }            
		} catch (IOException e) {
			showException(e);
		}
	}

    
    protected void showGetStartedDialogIfNeeded() {
    	FragmentManager fragmentManager = getSupportFragmentManager();
    	PromptForGetStartedDialogFragment promptForGetStartedDialogFragment = (PromptForGetStartedDialogFragment)fragmentManager.findFragmentByTag(PromptForGetStartedDialogFragment.TAG);
    	if (promptForGetStartedDialogFragment != null) {
    		// dialog is already showing, ignore this
		    _logger.info("showGetStartedDialog: already showing get started dialog");
    		return;
    	}
    	
		// check to see if we have any wallet keys - if not, prompt the user to add one
        Wallet wallet = IntegrationConnector.getWallet(this);
        if (wallet.getKeys().size() > 0) {
		    _logger.info("showGetStartedDialog: have a key, no ned for dialog");
		    return;
        }

    	PromptForGetStartedDialogFragment.prompt(fragmentManager);
    }
    
    protected void hideGetStartedDialogIfNeeded() {
	    _logger.info("hideGetStartedDialogIfNeeded: called");
	    
        Wallet wallet = IntegrationConnector.getWallet(this);
        if (wallet.getKeys().size() > 0) {
	    	FragmentManager fragmentManager = getSupportFragmentManager();
	    	PromptForGetStartedDialogFragment promptForGetStartedDialogFragment = (PromptForGetStartedDialogFragment)fragmentManager.findFragmentByTag(PromptForGetStartedDialogFragment.TAG);
	    	if (promptForGetStartedDialogFragment != null) {
	    		// dialog is already showing, ignore this
			    _logger.info("hideGetStartedDialogIfNeeded: hiding dialog");
			    promptForGetStartedDialogFragment.dismiss();
	    		return;
	    	}
        }
    }
    
	protected void simulateSecureElementAppletDetected() {
		_logger.info("simulateSecureElementAppletDetected: called");
		_cachedSecureElementApplet = new SecureElementAppletSimulatorImpl();
		handleCardDetectedSuper(_cachedSecureElementApplet, false, false, null);
	}

	protected boolean checkIfNFCRadioOnPromptUser(boolean messageStatesNFCIsRequired) {
		if (!_nfcAdapter.isEnabled()) {
			// the NFC radio is off
			// prompt the user to turn it on
	
			AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(this);
	
			// set title
			alertDialogBuilder.setTitle(getResources().getString(R.string.nfc_aware_activity_enable_nfc_dialog_title));
	
			String alertDialogMessage = getResources().getString(messageStatesNFCIsRequired ? R.string.nfc_aware_activity_enable_nfc_dialog_message : R.string.nfc_aware_activity_enable_nfc_dialog_some_operations_will_not_work);
		    // set dialog message
			alertDialogBuilder
				.setMessage(alertDialogMessage)
				.setCancelable(false)
				.setPositiveButton(getResources().getString(R.string.general_ok), new DialogInterface.OnClickListener() {
					@SuppressLint("InlinedApi")
					public void onClick(DialogInterface dialog, int id) {
						// TODO: restore below code
						// if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
							// Settings.ACTION_NFC_SETTINGS is only available in API level 16+
							// startActivity(new Intent(Settings.ACTION_NFC_SETTINGS));
						// } else {
							// put the user in the wireless settings menu
							startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS));							
						// }
					}
				})
				.setNegativeButton(getResources().getString(R.string.general_cancel), new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface dialog, int id) {
						// if this button is clicked, just close
						// the dialog box and do nothing
						dialog.cancel();
					  }
				});
	 
			alertDialogBuilder.show();
			return false;
		}

		return true;
	}
	
	public SecureElementApplet getSecureElementAppletPromptIfNeeded(boolean requirePassword, boolean useExistingSessionIfPossible) {
		if (!checkIfNFCRadioOnPromptUser(true)) {
			// the NFC radio isn't on, the user is being prompted to turn it on
			return null;
		}

		_pendingUseExistingSessionIfPossible = useExistingSessionIfPossible;
		
		try {
			if (_cachedSecureElementApplet != null && _cachedSecureElementApplet.checkConnection()) {
				if (requirePassword && (!_cachedSecureElementApplet.isAuthenticated() || !_pendingUseExistingSessionIfPossible)) {
					// the caller is asking us for an authenticated session but we don't have one, or we do have an authenticated session
					// but the caller wants to force an authentication anyway
					showPromptForPasswordDialog();
					return null;
				}
				// we have a connection to the SecureElementApplet - return the connection 
				return _cachedSecureElementApplet;
			} else {
				_cachedSecureElementApplet = null;
				if (requirePassword) {
					showPromptForPasswordDialog();
				} else {
					showPromptForTapDialog(PromptForTapDialogFragment.TYPE_NORMAL);
				}
				return _cachedSecureElementApplet;
			}
		} catch(IOException e) {
			_cachedSecureElementApplet = null;
			return _cachedSecureElementApplet;
		}
	}
	
	private void showPromptForPasswordDialog() {
		int type = PromptForPasswordDialogFragment.TYPE_NORMAL;
		if (_pendingBackupCard) {
			type = PromptForPasswordDialogFragment.TYPE_BACKUP;
		} else if (_pendingSaveKeysToCard) {
			type = PromptForPasswordDialogFragment.TYPE_SAVE_KEYS_TO_CARD;
		}
		PromptForPasswordDialogFragment.prompt(getSupportFragmentManager(), type);
	}

	
	public void userProceededOnPasswordDialog(String password) {
	    _logger.info("userProceededOnPasswordDialog: called");
	    loginToCard(password);
	}
	
	private void loginToCard(String password) {
		_logger.info("loginToCard: called");
		// get a secure element session (prompt the user to tap if needed
		SecureElementApplet secureElementApplet = getSecureElementAppletPromptIfNeeded(false, true);
		if (secureElementApplet == null) {
			// the user is being prompted to tap - just cache the password to try later
		    _logger.info("loginToCard: waiting for session");
		    _pendingCardPassword = password;
		    return;
		}

		try {
			if (secureElementApplet.isAuthenticated()
			&& (_pendingUseExistingSessionIfPossible || secureElementApplet.getPINState() == PINState.BLANK)) {
				// if the secure element is already authenticated
				// and either it's ok to re-use the authenticated session, or the reason we're authenticated is because the PIN is blank
				// then we're good to go
				_logger.info("loginToCard: card already authenticated");
				
				handleCardDetectedSuper(secureElementApplet, true, true, password);
				return;
			}

			PleaseWaitDialogFragment pleaseWaitDialogFragment = PleaseWaitDialogFragment.show(getSupportFragmentManager());
			(new LoginToCardAsyncTask(secureElementApplet, pleaseWaitDialogFragment, password)).execute();
			
		} catch (IOException e) {
			_logger.info("loginToCard: IOException e while logging into card: " + e.toString());
			showException(e);
			_cachedSecureElementApplet = null;
		} finally {
			_pendingCardPassword = null;
		}
	}
	
	private class LoginToCardAsyncTask extends AsyncTask<Void, Void, IOException> {
		private SecureElementApplet _secureElementApplet;
		private PleaseWaitDialogFragment _pleaseWaitDialogFragment;
		private String _password;
		public LoginToCardAsyncTask(SecureElementApplet secureElementApplet, PleaseWaitDialogFragment pleaseWaitDialogFragment, String password) {
			_secureElementApplet = secureElementApplet;
			_pleaseWaitDialogFragment = pleaseWaitDialogFragment;
			_password = password;
		}
		
		@Override
		protected IOException doInBackground(Void... params) {
			try {
				_secureElementApplet.login(_password);
			} catch (IOException e) {
				return e;
			}
			return null;
		}
		
		@Override
		protected void onPostExecute(IOException e) {
			_pleaseWaitDialogFragment.dismiss();
			try {
				if (e == null) {
					_logger.info("loginToCard: successful");
					// logged in successfully
					handleCardDetectedSuper(_secureElementApplet, true, true, _password);
				} else if (e instanceof IOException) {
					// error while logging in (possibly wrong password)
					_logger.info("loginToCard: failed to login");
					
					// draw some UI for the user to indicate the error
					showException(e);
	
					// let the user try logging in again
					showPromptForPasswordDialog();
					return;
				}
			} finally {
				_password = null;
			}
		}

	}


	public SecureElementApplet getCachedSecureElementApplet() {
		// assume that if someone is explicitly asking us for the cached secure element, they
		// want to have a connection to it.  If we're not actually able to connect, clear the cache.
		if (_cachedSecureElementApplet != null) {
			if (!_cachedSecureElementApplet.checkConnection()) {
				_logger.info("getCachedSecureElementApplet: clearing cache since not connected");
				_cachedSecureElementApplet = null;
			}
		}
		return _cachedSecureElementApplet;
	}

	private void showPromptForTapOnceMoreDialog() {		
		PromptForTapOnceMoreDialogFragment.prompt(getSupportFragmentManager());
	}

	
	// if type = true - just prompts the user to tap
	// if type = false - prompts the user to tap to finish signing, connection was lost
	public void showPromptForTapDialog(int type) {
		if (_pendingBackupCard) {
			type = PromptForTapDialogFragment.TYPE_BACKUP;
		} else if (_pendingSaveKeysToCard) {
			type = PromptForTapDialogFragment.TYPE_SAVE_KEYS_TO_CARD;
		}
		PromptForTapDialogFragment.prompt(getSupportFragmentManager(), type);
	}
	
	public void promptToAddKey() {
		if (!checkIfNFCRadioOnPromptUser(true)) {
			// the NFC radio isn't on, prompt the user to turn it on and abort
			return;
		}
		
		_pendingAddKeyLabel = null;

		PromptForLabelDialogFragment.prompt(getSupportFragmentManager());
	}
	
	public void promptForBackupOrRestore() {
		if (!checkIfNFCRadioOnPromptUser(true)) {
			// the NFC radio isn't on, prompt the user to turn it on and abort
			return;
		}

		PromptForBackupOrRestoreDialogFragment.prompt(getSupportFragmentManager());
	}
	
	protected void promptToChangePassword() {
		if (!checkIfNFCRadioOnPromptUser(true)) {
			// the NFC radio isn't on, prompt the user to turn it on and abort
			return;
		}
		
		HeliosChangePasswordDialogFragment.prompt(getSupportFragmentManager());
	}
	
	public void backupCardPreTap() {
		_logger.info("backupCardPreTap: called");
		
		// Note that we do NOT use the existing session - we want to force the user to re-tap and re-enter the password
		// in case the user is holding the wrong card to the phone
		_pendingBackupCard = true;
		this.getSecureElementAppletPromptIfNeeded(true, false);
	}
	
	public void backupCardPostTap(SecureElementApplet secureElementApplet, String password) {
		_logger.info("backupCardPostTap: called");
		
		try {
			// get a list of keys from the card including private key
            List<ECKeyEntry> ecKeyEntries = _cachedSecureElementApplet.getECKeyEntries(true);
    		_logger.info("backupCardPostTap: got keys");
    		if (ecKeyEntries.size() == 0) {
    			// no keys to backup on this card
    			Toast.makeText(this,  getResources().getString(R.string.nfc_aware_activity_card_not_initialized), Toast.LENGTH_LONG).show();
    			return;
    		}

    		// ask the user to choose which keys to backup
    		ChooseKeysToBackupDialogFragment.prompt(getSupportFragmentManager(), secureElementApplet.getCardIdentifier(), ecKeyEntries, password);
		} catch (IOException e) {
			_logger.error("backupCardPostTap: received bad exception: " + e.toString());
			showException(e);
		}
	}
	
	public void promptSaveBackupData(String sourceCardIdentifier, List<ECKeyEntry> selectedList, String password, Wallet walletFromFile) {
		PromptToSaveBackupDataDialogFragment.prompt(getSupportFragmentManager(), sourceCardIdentifier, selectedList, password, walletFromFile);
	}
		
	
	public void changePasswordPreTap(String newPassword) {
		_logger.info("changePasswordPreTap: called");
		// get a secure element session that is authenticated (authenticated session needed to add a key)
		// Note that we do NOT use the existing session - we want to force the user to enter the password
		// because we need the old password in the change password command
		this.getSecureElementAppletPromptIfNeeded(true, false);
		_pendingChangePasswordNewPassword = newPassword;
	}
	
	protected void changePasswordPostTap(SecureElementApplet secureElementApplet, SetPasswordOnCardAsyncTaskType type, String oldPassword, String newPassword) {
		_logger.info("changePasswordPostTap: called");	
		PleaseWaitDialogFragment pleaseWaitDialogFragment = PleaseWaitDialogFragment.show(getSupportFragmentManager());
		(new SetPasswordOnCardAsyncTask(secureElementApplet, pleaseWaitDialogFragment, type, oldPassword, newPassword)).execute();
	}
	
	public enum SetPasswordOnCardAsyncTaskType {
		NORMAL, DOING_RESTORE, DOING_INITIALIZATION
	}
	
	private class SetPasswordOnCardAsyncTask extends AsyncTask<Void, Void, IOException> {

		
		private SecureElementApplet _secureElementApplet;
		private PleaseWaitDialogFragment _pleaseWaitDialogFragment;
		private SetPasswordOnCardAsyncTaskType _type;;
		private String _oldPassword;
		private String _newPassword;

		public SetPasswordOnCardAsyncTask(SecureElementApplet secureElementApplet, PleaseWaitDialogFragment pleaseWaitDialogFragment, SetPasswordOnCardAsyncTaskType type, String oldPassword, String newPassword) {
			_secureElementApplet = secureElementApplet;
			_pleaseWaitDialogFragment = pleaseWaitDialogFragment;
			_type = type;
			_oldPassword = oldPassword;
			_newPassword = newPassword;
		}

		@Override
		protected IOException doInBackground(Void... params) {
			try {
				_secureElementApplet.setCardPassword(_oldPassword, _newPassword);
				if (_type == SetPasswordOnCardAsyncTaskType.DOING_RESTORE) {
					_secureElementApplet.login(_newPassword);
				}
			} catch (IOException e) {
				return e;
			}
			return null;
		}
		
		@Override
		protected void onPostExecute(IOException e) {
			_pleaseWaitDialogFragment.dismiss();
			try {
				if (e == null) {
					_logger.info("changePasswordAsyncTask: successful");
					if (_type == SetPasswordOnCardAsyncTaskType.NORMAL) {
						// if we got here it worked.  Tell the user the password was changed successfully
						Toast.makeText(NFCAwareActivity.this, getResources().getString(R.string.nfc_aware_activity_password_successfully_changed), Toast.LENGTH_LONG).show();
					} else if (_type == SetPasswordOnCardAsyncTaskType.DOING_RESTORE){
						saveKeysToCardPostTap(_secureElementApplet);
					} else if (_type == SetPasswordOnCardAsyncTaskType.DOING_INITIALIZATION) {
						_logger.info("changePasswordAsyncTask: card initialized");
						// now that we have initialized this card, save the card identifier as our most recently used card
						try {
							WalletGlobals.getInstance(NFCAwareActivity.this).setCardIdentifier(NFCAwareActivity.this, _secureElementApplet.getCardIdentifier());
				            startActivity(new Intent(NFCAwareActivity.this, IntegrationConnector.WALLET_ACTIVITY_CLASS));
					    	NFCAwareActivity.this.finish();
						} catch (IOException error) {
							e = error;
							_logger.info("changePasswordAsyncTask: IO exception setting card identifier: " + e.toString());
						}
					}
				}
				if (e instanceof IOException) {
					// error while logging in (possibly wrong password)
					_logger.info("changePasswordAsyncTask: failed to change password");
					
					// draw some UI for the user to indicate the error
					showException(e);

					return;
				}
			} finally {
				_oldPassword = null;
				_newPassword = null;
			}
		}

	}

	
	public void createKeyPreTap(String labelForKey) {
		// get a secure element session that is authenticated (authenticated session needed to add a key)
		SecureElementApplet secureElementApplet = this.getSecureElementAppletPromptIfNeeded(true, true);
		if (secureElementApplet == null) {
			// there was no authenticated session established - the user is now being prompted to provide one, so just bail out for now
		    _logger.info("promptForLabelOKClicked: waiting for authenticated session");
		    _pendingAddKeyLabel = labelForKey;
		    return;
		}
		
		// otherwise we can just keep going and create the key
	    _logger.info("promptForLabelOKClicked: have authenticated session, creating key");
	    createKeyPostTap(secureElementApplet, labelForKey);
	}
	
	private void createKeyPostTap(SecureElementApplet secureElementApplet, String labelForKey) {
		_logger.info("generateKeyOnSecureElement: called");
		
		PleaseWaitDialogFragment pleaseWaitDialogFragment = PleaseWaitDialogFragment.show(getSupportFragmentManager());
		(new CreateKeyAsyncTask(secureElementApplet, pleaseWaitDialogFragment, labelForKey)).execute();
	}
	
	private class CreateKeyAsyncTask extends AsyncTask<Void, Void, IOException> {
		private SecureElementApplet _secureElementApplet;
		private PleaseWaitDialogFragment _pleaseWaitDialogFragment;
		private String _label;
		public CreateKeyAsyncTask(SecureElementApplet secureElementApplet, PleaseWaitDialogFragment pleaseWaitDialogFragment, String label) {
			_secureElementApplet = secureElementApplet;
			_pleaseWaitDialogFragment = pleaseWaitDialogFragment;
			_label = label;
			
		}
		
		@Override
		protected IOException doInBackground(Void... params) {
			try {
				ECKeyEntry keyFromSecureElementToAddToCachedWallet = _secureElementApplet.createOrInjectKey(null, _label, null, null, System.currentTimeMillis());
				// we just generated a key on the card and got back the public key bytes
				// add them to the cached wallet.  Add it assuming we don't need to restart the peergroup to see updates
				// to the key
				WalletGlobals.addECKeyEntryToCachedWallet(NFCAwareActivity.this, IntegrationConnector.getWallet(NFCAwareActivity.this), keyFromSecureElementToAddToCachedWallet);

			} catch (IOException e) {
				return e;
			}
			return null;
		}
		
		@Override
		protected void onPostExecute(IOException e) {
			_pleaseWaitDialogFragment.dismiss();
			if (e == null) {
				_logger.info("generateKeyOnSecureElement: Successfully created key");
				// Ensure the wallet address display is displaying the correct address
				IntegrationConnector.ensureWalletAddressDisplayIsUpdated(NFCAwareActivity.this);

				// we just created a key - hide the get started dialog if appropriate
				hideGetStartedDialogIfNeeded();

			} else if (e instanceof TagLostException || "Transceive failed".equals(e.toString())) {
				// On some phones like Nexus 5, generating a key results in a tag lost exception because the phone couldn't sustain enough
				// power for the card. However, the card actually generated the key - so prompt the user to retap so we get
				// at the key
				_logger.info("generateKeyOnSecureElement: TagLostException while generating key - prompting for re-tap");
				showPromptForTapOnceMoreDialog();
				
			} else {
				_logger.error("generateKeyOnSecureElement: received bad exception: " + e.toString());
				showException(e);
			}

		}

	}

	public void editKeyPreTap(byte[] editPublicKey, String editLabel) {
		_logger.info("editKeyPreTap: called");
		
		// get a secure element session that is authenticated (authenticated session needed to add a key)
		SecureElementApplet secureElementApplet = this.getSecureElementAppletPromptIfNeeded(true, true);
		if (secureElementApplet == null) {
			// there was no authenticated session established - the user is now being prompted to provide one, so just bail out for now
		    _logger.info("editKeyPreTap: waiting for authenticated session");
		    _pendingEditPublicKey = editPublicKey;
		    _pendingEditLabel = editLabel;
		    return;
		}
		
		// otherwise we can just keep going and create the key
	    _logger.info("editKeyPreTap: have authenticated session, editing key");
	    editKeyPostTap(secureElementApplet, editPublicKey, editLabel);
	}
	
	private void editKeyPostTap(SecureElementApplet secureElementApplet, byte[] editPublicKey, String editLabel) {
		_logger.info("editKeyPostTap: called");
		try {
			// update the label on the secure element
			secureElementApplet.changeLabel(editPublicKey, editLabel);
			
			// update the label in the local content provider
			IntegrationConnector.setLabelForAddress(this, new ECKey(null, editPublicKey).toAddress(Constants.NETWORK_PARAMETERS), editLabel);
			
			// update the key label in the content provider as well
		} catch (IOException e) {
			showException(e);
		}
	}
	
	private void saveKeysToCardPostTap(SecureElementApplet secureElementApplet) {
		// If we get here, we're authenticated to the new card.  Write the key data to it
    	try {
        	FragmentManager fragmentManager = getSupportFragmentManager();
    		String currentCardIdentifier = WalletGlobals.getInstance(this).getCardIdentifier();
            String newCardIdentifier = secureElementApplet.getCardIdentifier();
                        
            PromptToSaveBackupDataDialogFragment promptToSaveBackupDataDialogFragment = (PromptToSaveBackupDataDialogFragment)fragmentManager.findFragmentByTag(PromptToSaveBackupDataDialogFragment.TAG);
			List<ECKeyEntry> listOfKeys = promptToSaveBackupDataDialogFragment.getKeysToBackup();
			if (listOfKeys != null) {
				int numKeysToWrite = listOfKeys.size();

				int spaceAvailableOnCard = secureElementApplet.getMaxNumberOfKeys() - secureElementApplet.getCurrentNumberOfKeys();
				if (numKeysToWrite > spaceAvailableOnCard) {
					// there won't be enough space on the card to hold the keys we are trying to write
					// but first check to see if some of the keys we're trying to write are already on the card
					// in which case, we won't count that
					_logger.info("saveKeysToCardPostTap: trying to write " + numKeysToWrite + " but only " + spaceAvailableOnCard + " slots available");
					int actualNumKeysToWrite = numKeysToWrite;
					List<ECKeyEntry> keysOnCard = secureElementApplet.getECKeyEntries(false);
					for (ECKeyEntry ecKeyEntryToWrite : listOfKeys) {
						byte[] publicKeyBytesToWrite = ECUtil.getPublicKeyBytesFromEncoding(ecKeyEntryToWrite.getPublicKeyBytes(), false);
						
						for (ECKeyEntry keyOnCard : keysOnCard) {
							byte[] publicKeyOnCard = ECUtil.getPublicKeyBytesFromEncoding(keyOnCard.getPublicKeyBytes(), false);
							if (Arrays.equals(publicKeyBytesToWrite, publicKeyOnCard)) {
								_logger.info("saveKeysToCardPostTap: key to restore matches key on card - not counting against total");
								actualNumKeysToWrite--;
								break;
							}
						}
					}
					
					if (actualNumKeysToWrite > spaceAvailableOnCard) {
						// there really isn't enough space to write the keys - show an error to the user
						_logger.info("saveKeysToCardPostTap: trying to actually write " + actualNumKeysToWrite + " but only " + spaceAvailableOnCard + " slots available");
						String stringToShow = String.format(getResources().getString(R.string.nfc_aware_activity_not_enough_space_on_card), numKeysToWrite, spaceAvailableOnCard);
						Toast.makeText(this, stringToShow, Toast.LENGTH_LONG).show();
						return;
					}
				}
				_logger.info("saveKeysToCardPostTap: writing " + numKeysToWrite + " to new card from old card");
				for (int i = 0; i < numKeysToWrite; i++) {
					ECKeyEntry ecKeyEntry = listOfKeys.get(i);
					try {
						secureElementApplet.createOrInjectKey(ecKeyEntry.getAssociatedData(), null, ecKeyEntry.getPrivateKeyBytes(), ecKeyEntry.getPublicKeyBytes(), ecKeyEntry.getTimeOfKeyCreationSecondsSinceEpoch() * 1000);
					} catch (KeyAlreadyExistsException e) {
		        		// just skip it if we've already written this key
						_logger.info("saveKeysToCardPostTap: swallowing KeyAlreadyExistsException");
					}
				}
			} else {
				_logger.info("saveKeysToCardPostTap: restoring keys from file");
				// we are restoring keys from a file
				Wallet walletToRestore = promptToSaveBackupDataDialogFragment.getWalletFromFile();
				List<ECKey> listOfKeysFromFile = walletToRestore.getKeys();
				int numKeysToWrite = listOfKeysFromFile.size();

				
				int spaceAvailableOnCard = secureElementApplet.getMaxNumberOfKeys() - secureElementApplet.getCurrentNumberOfKeys();
				if (numKeysToWrite > spaceAvailableOnCard) {
					// there won't be enough space on the card to hold the keys we are trying to write
					// but first check to see if some of the keys we're trying to write are already on the card
					// in which case, we won't count that
					_logger.info("saveKeysToCardPostTap: trying to write " + numKeysToWrite + " but only " + spaceAvailableOnCard + " slots available");
					int actualNumKeysToWrite = numKeysToWrite;
					List<ECKeyEntry> keysOnCard = secureElementApplet.getECKeyEntries(false);
					for (ECKey ecKeyEntryToWrite : listOfKeysFromFile) {
						byte[] publicKeyBytesToWrite = ECUtil.getPublicKeyBytesFromEncoding(ecKeyEntryToWrite.getPubKey(), false);
						
						for (ECKeyEntry keyOnCard : keysOnCard) {
							byte[] publicKeyOnCard = ECUtil.getPublicKeyBytesFromEncoding(keyOnCard.getPublicKeyBytes(), false);
							if (Arrays.equals(publicKeyBytesToWrite, publicKeyOnCard)) {
								_logger.info("saveKeysToCardPostTap: key to restore matches key on card - not counting against total");
								actualNumKeysToWrite--;
								break;
							}
						}
					}
					
					if (actualNumKeysToWrite > spaceAvailableOnCard) {
						// there really isn't enough space to write the keys - show an error to the user
						_logger.info("saveKeysToCardPostTap: trying to actually write " + actualNumKeysToWrite + " but only " + spaceAvailableOnCard + " slots available");
						String stringToShow = String.format(getResources().getString(R.string.nfc_aware_activity_not_enough_space_on_card), numKeysToWrite, spaceAvailableOnCard);
						Toast.makeText(this, stringToShow, Toast.LENGTH_LONG).show();
						return;
					}
				}
				
				_logger.info("saveKeysToCardPostTap: writing " + numKeysToWrite + " to new card from file");
				for (int i = 0; i < numKeysToWrite; i++) {
					ECKey ecKey = listOfKeysFromFile.get(i);
					try {
						secureElementApplet.createOrInjectKey(null, "Key" + (i + 1), ecKey.getPrivKeyBytes(), ecKey.getPubKey(), ecKey.getCreationTimeSeconds() * 1000);
					} catch (KeyAlreadyExistsException e) {
		        		// just skip it if we've already written this key
						_logger.info("saveKeysToCardPostTap: swallowing KeyAlreadyExistsException");
					}					
				}
			}

			// If we get here, the keys have been successfully injected - write a message out
			promptToSaveBackupDataDialogFragment.dismiss();
			if (newCardIdentifier.equals(currentCardIdentifier)) {
				// The card we just restored data on to was the one we were actively tracking.  We should replay the block chain
				// and restart the app
				Toast.makeText(this, getResources().getString(R.string.nfc_aware_activity_successfully_wrote_data), Toast.LENGTH_LONG).show();
				_logger.info("saveKeysToCardPostTap: restored keys on to currently tracked card, process as normal tap");
				handleNormalTap(secureElementApplet, currentCardIdentifier, newCardIdentifier);
			} else {
				// We restored data on to a card that we're not currently tracking.  Show the user the new keys on the card
	            // get a list of keys already on the card
	            List<ECKeyEntry> ecPublicKeyEntries = secureElementApplet.getECKeyEntries(false);
				_logger.info("saveKeysToCardPostTap: restored keys on to untracked card, dropping tap");
        		PromptOnNewCardDialogFragment.prompt(fragmentManager, PromptOnNewCardDialogFragment.TYPE_SAVE_SUCCESSFUL, currentCardIdentifier, newCardIdentifier, ecPublicKeyEntries);
			}
    	} catch (IOException e) {
			_logger.error("saveKeysToCardPostTap: IOException " + e.toString());
    		showException(e);
    	}
	}

	
	protected void handleCardDetectedSuper(SecureElementApplet secureElementApplet, boolean tapRequested, boolean authenticated, String password) {
		if (_pendingAddKeyLabel != null) {
			// we had a request to add a key to the card, do that instead
			_logger.info("handleCardDetectedSuper: generating key with label");
			String pendingLabel = _pendingAddKeyLabel;
			_pendingAddKeyLabel = null;
			createKeyPostTap(secureElementApplet, pendingLabel);
			return;
		} else if (_pendingEditPublicKey != null) {
			byte[] pendingEditPublicKey = _pendingEditPublicKey;
			_pendingEditPublicKey = null;
			String pendingEditLabel = _pendingEditLabel;
			_pendingEditLabel = null;
			editKeyPostTap(secureElementApplet, pendingEditPublicKey, pendingEditLabel);
			return;
		} else if (_pendingDeleteKeyPublicKeyBytes != null) {
			byte[] pendingDeleteKeyPublicKeyBytes = _pendingDeleteKeyPublicKeyBytes;
			_pendingDeleteKeyPublicKeyBytes = null;
			deleteKeyPostTap(secureElementApplet, pendingDeleteKeyPublicKeyBytes);
			return;
		} else if (_pendingChangePasswordNewPassword != null) {
			String newPassword = _pendingChangePasswordNewPassword;
			_pendingChangePasswordNewPassword = null;
			changePasswordPostTap(secureElementApplet, SetPasswordOnCardAsyncTaskType.NORMAL, password, newPassword);
			return;
		} else if (_pendingBackupCard) {
			_pendingBackupCard = false;
			backupCardPostTap(secureElementApplet, password);
			return;
		} else if (_pendingSaveKeysToCard) {
			_pendingSaveKeysToCard = false;
			saveKeysToCardPostTap(secureElementApplet);
			return;
		}

		handleCardDetected(secureElementApplet, tapRequested, authenticated, password);
	}
	
	protected void handleCardDetected(SecureElementApplet secureElementApplet, boolean tapRequested, boolean authenticated, String password) {
		// default implementation does nothing, override to hear about card detection events
	}

	protected void resetState() {
		_pendingCardPassword = null;
		_pendingAddKeyLabel = null;
		_pendingEditPublicKey = null;
		_pendingEditLabel = null;
	    _pendingDeleteKeyPublicKeyBytes = null;
	    _pendingChangePasswordNewPassword = null;
	    _pendingBackupCard = false;
	    _pendingSaveKeysToCard = false;
	    
       	// we have no keys in the wallet - prompt the user to add one
        hideGetStartedDialogIfNeeded();
        
        userCanceledSecureElementPrompt();
	}
	
	protected void userCanceledSecureElementPrompt() {
		// subclasses can override this if they want to hear about this event
	}

	// utility method for subclasses to show errors
	public void showException(IOException e) {
		String errorMessage;
		
		if (e instanceof WrongPasswordException) {
    		// Toast.makeText(this, this.getResources().getString("Wrong password"), Toast.LENGTH_LONG).show();
			errorMessage = getResources().getString(R.string.nfc_aware_activity_error_dialog_message_wrong_password);
    	} else if (e instanceof SmartCardFullException) {
    		errorMessage = getResources().getString(R.string.nfc_aware_activity_error_dialog_message_smartcard_full);
    	} else if (e instanceof KeyAlreadyExistsException) {
    		errorMessage = getResources().getString(R.string.nfc_aware_activity_error_dialog_message_key_already_exists);
    	} else if (e instanceof TagLostException) {
    		errorMessage = getResources().getString(R.string.nfc_aware_activity_error_dialog_message_tag_lost);
    	} else if (e instanceof CardWasWipedException) {
    		_logger.info("showException: card was wiped");
    		// the card was just wiped - clear the current card identifier, reset the block chain, and restart the app
    		WalletGlobals walletGlobals = WalletGlobals.getInstance(this);
    		walletGlobals.setCardIdentifier(this, null);
      		Wallet wallet = IntegrationConnector.getWallet(this);
			List<ECKey> listFromCachedWallet = wallet.getKeys();
			if (listFromCachedWallet.size() > 0) {
	    		// persist the fact that we're about to modify the wallet, in case we can interrupted
	    		// before we get a chance to tell the service to delete the block chain and restart
	      		walletGlobals.persistServiceNeedsToReplayBlockchain(this);
	      		
	      		// Remove all the keys from the wallet
				for (int i = 0; i < listFromCachedWallet.size(); i++) {
					ECKey keyFromCachedWallet = listFromCachedWallet.get(i);
					wallet.removeKey(keyFromCachedWallet);
				}
				
				// Replay the block chain
				IntegrationConnector.deleteBlockchainAndRestartService(this);
			}

    		Intent intentToRestartApplication = new Intent(this, IntegrationConnector.WALLET_ACTIVITY_CLASS);
    		intentToRestartApplication.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
    		startActivity(intentToRestartApplication);
    		errorMessage = getResources().getString(R.string.nfc_aware_activity_error_dialog_message_card_was_wiped);
    		this.finish();
    	} else {
    		errorMessage = getResources().getString(R.string.nfc_aware_activity_error_dialog_message_unknown);
    	}
		
		Toast.makeText(this, errorMessage, Toast.LENGTH_SHORT).show();
	}
}

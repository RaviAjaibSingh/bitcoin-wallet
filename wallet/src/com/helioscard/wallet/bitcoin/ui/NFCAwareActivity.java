package com.helioscard.wallet.bitcoin.ui;

import java.io.IOException;
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
import com.helioscard.wallet.bitcoin.secureelement.SecureElementApplet;
import com.helioscard.wallet.bitcoin.secureelement.SmartCardReader;
import com.helioscard.wallet.bitcoin.secureelement.SecureElementApplet.PINState;
import com.helioscard.wallet.bitcoin.secureelement.androidadapter.SmartCardReaderImpl;
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
    
    private String _pendingCardPassword;
    private boolean _pendingUseExistingSessionIfPossible;
    private String _pendingAddKeyLabel;
    private byte[] _pendingEditPublicKey;
    private String _pendingEditLabel;
    private byte[] _pendingDeleteKeyPublicKeyBytes;
    
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

         // if we're first launched by an NFC tag, we don't get an onNewIntent message, so route it through
         // now
         boolean startedByCardTap = processIntent(getIntent());
        
         if (WalletGlobals.getInstance(this).getCardIdentifier() == null && !(this instanceof MainActivity) && !(this instanceof InitializeCardActivity)) {
        	// This app has never been used with a card before
        	if (startedByCardTap) {
        		// But we were started by an intent which represented a card tap - everything has been handled, nothing for us to
        		// do here
        		_logger.info("onCreate: started by NFC tap, bailing");
        		return;
        	}
        	
        	// Otherwise, ensure we are focused on the tap to begin screen to prompt the user to tap
        	Intent intentToStartTapToBeginActivity = new Intent(this, MainActivity.class);
        	intentToStartTapToBeginActivity.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        	startActivity(intentToStartTapToBeginActivity);
        	this.finish();
        	return;
        }
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
		_logger.info("onNewIntent: called");
        if (doesIntentComeFromHeliosCard(intent)) {
        	// clear out any cached secure element that we have
        	_cachedSecureElementApplet = null;

			try {
	        	if (Constants.USE_REAL_SMART_CARD) {
		        	Tag tagFromIntent = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
		            // isoDep should be non-null, this is the only technology type we're listening for
		            SmartCardReader smartCardReader = new SmartCardReaderImpl(tagFromIntent);
					_cachedSecureElementApplet = new SecureElementAppletImpl(smartCardReader);
	        	} else {
	        		_cachedSecureElementApplet = new SecureElementAppletSimulatorImpl();
	        	}


                // set the card identifier appropriately in the bitcoin wallet
                WalletGlobals walletGlobals = WalletGlobals.getInstance(this);
                // Get the list of public keys from the secure element
                List<ECKeyEntry> _ecPublicKeyEntries = _cachedSecureElementApplet.getECPublicKeyEntries();

                // Synchronize the keys with the secure element.  E.g. make sure our local cache of public keys matches
                // what's on this card
                // TODO: there's a race condition here, where the wallet has the newly synchronized keys, but it could be the case we had to
                // tell the service to stop and destroy its current block chain file.  But there's a chance the process could be terminated
                // or the device could be rebooted before the service gets a chance to do that
	        	FragmentManager fragmentManager = getSupportFragmentManager();
	        	PromptForTapOnceMoreDialogFragment promptForTapOnceMoreDialogFragment = (PromptForTapOnceMoreDialogFragment)fragmentManager.findFragmentByTag(PromptForTapOnceMoreDialogFragment.TAG);

                Wallet wallet = IntegrationConnector.getWallet(this);
                boolean serviceNeedsToClearAndRestart = walletGlobals.synchronizeKeys(this, wallet, _ecPublicKeyEntries, promptForTapOnceMoreDialogFragment == null);
                if (serviceNeedsToClearAndRestart) {
                    // the keys between the secure element and our cached copy of public keys didn't match
                    _logger.info("onNewIntent: service needs to clear and restart");
                }

                boolean needsToGoToInitializationScreen = false;
                boolean cardIdentifierWasChanged = false;


                if (_cachedSecureElementApplet.getPINState() == PINState.NOT_SET) {
                	// clear out our most recently used card
                	walletGlobals.setCardIdentifier(this, null);
                	
                	// this is a brand new card.  we are going to need to send the user to the initialization screen
                    _logger.info("onNewIntent: detected uninitialized card");
                    walletGlobals.setCardIdentifier(this, null); // clear out the cached card identifier
                    if (this instanceof InitializeCardActivity) {
                        _logger.info("onNewIntent: already in InitializeCardActivity, not doing anything");
                    } else {
                        _logger.info("onNewIntent: need to go to initialization screen");
                        needsToGoToInitializationScreen = true;
                    }
                } else {
                	// check if this card is different then our most recently used card, and save
                	// this card as our most recently used card
                    cardIdentifierWasChanged = walletGlobals.setCardIdentifier(this, _cachedSecureElementApplet.getCardIdentifier());
                    if (cardIdentifierWasChanged) {
                        _logger.info("onNewIntent: card identifier was changed");
                    }
                }

                
                if (serviceNeedsToClearAndRestart) {
                	if (promptForTapOnceMoreDialogFragment == null || cardIdentifierWasChanged ) {
                		// We were tapped by a card but we weren't tracking all the keys - restart the service
                		// Also, there was no tap to finish dialog showing, or there was one, but the user tapped a different card 
                		IntegrationConnector.deleteBlockchainAndRestartService(this);
                	} else {
                		_logger.info("processIntent: ignoring service needs to restart due to prompt for tap dialog");
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

	        	if (promptForTapOnceMoreDialogFragment != null) {
	        		// We were showing a tap to finish dialog - where we were asking the user to tap so we could
	        		// synchronize the keys.  That has already been done by the time we get here, so nothing to do here.
	        		promptForTapOnceMoreDialogFragment.dismiss();
	        		showGetStartedDialogIfNeeded();
	        		return true;
	        	}

                // let the activity know a card has been detected
                handleCardDetectedSuper(_cachedSecureElementApplet, tapRequested, false, null);
                return true;
        	} catch(IOException e) {
        		_cachedSecureElementApplet = null;
			    _logger.error("onNewIntent: IOException getting cached secure element: " + e.toString());
        	}
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
			// we might be down to 0 keys, in which case we need to show a dialog prompting the user to add one
			showGetStartedDialogIfNeeded();
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
					showPromptForTapDialog(true);
				}
				return _cachedSecureElementApplet;
			}
		} catch(IOException e) {
			_cachedSecureElementApplet = null;
			return _cachedSecureElementApplet;
		}
	}
	
	private void showPromptForPasswordDialog() {
		PromptForPasswordDialogFragment.prompt(getSupportFragmentManager());
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
			
			try {
				secureElementApplet.login(password);
			} catch(IOException e) {
				// error while logging in (possibly wrong password)
				_logger.info("loginToCard: failed to login");
				
				// draw some UI for the user to indicate the error
				showException(e);

				// let the user try logging in again
				showPromptForPasswordDialog();
				return;
			}

			// logged in successfully
			handleCardDetectedSuper(secureElementApplet, true, true, password);
		
		} catch (IOException e) {
			_logger.info("loginToCard: IOException e while logging into card: " + e.toString());
			showException(e);
			_cachedSecureElementApplet = null;
		} finally {
			_pendingCardPassword = null;
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
	public void showPromptForTapDialog(boolean type) {
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
		try {
			ECKeyEntry keyFromSecureElementToAddToCachedWallet = secureElementApplet.createOrInjectKey(labelForKey, null, null);
			// we just generated a key on the card and got back the public key bytes
			// add them to the cached wallet.  Add it assuming we don't need to restart the peergroup to see updates
			// to the key
			WalletGlobals.addECKeyEntryToCachedWallet(this, IntegrationConnector.getWallet(this), keyFromSecureElementToAddToCachedWallet);

			// Ensure the wallet address display is displaying the correct address
			IntegrationConnector.ensureWalletAddressDisplayIsUpdated(this);
		} catch (IOException e) {
			if (e instanceof TagLostException) {
				// On some phones like Nexus 5, generating a key results in a tag lost exception because the phone couldn't sustain enough
				// power for the card. However, the card actually generated the key - so prompt the user to retap so we get
				// at the key
				_logger.info("generateKeyOnSecureElement: TagLostException while generating key - prompting for re-tap");
				showPromptForTapOnceMoreDialog();
				
			} else {
				_logger.error("generateKeyOnSecureElement: received bad exception: " + e.toString());
				showException(e);
				showGetStartedDialogIfNeeded();
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

	
	protected void handleCardDetectedSuper(SecureElementApplet secureElementApplet, boolean tapRequested, boolean authenticated, String password) {
		if (_pendingAddKeyLabel != null && authenticated) {
			// we had a request to add a key to the card, do that instead
			_logger.info("handleCardDetectedSuper: generating key with label");
			String pendingLabel = _pendingAddKeyLabel;
			_pendingAddKeyLabel = null;
			createKeyPostTap(secureElementApplet, pendingLabel);
			return;
		} else if (_pendingEditPublicKey != null && authenticated) {
			byte[] pendingEditPublicKey = _pendingEditPublicKey;
			_pendingEditPublicKey = null;
			String pendingEditLabel = _pendingEditLabel;
			_pendingEditLabel = null;
			editKeyPostTap(secureElementApplet, pendingEditPublicKey, pendingEditLabel);
			return;
		} else if (_pendingDeleteKeyPublicKeyBytes != null && authenticated) {
			byte[] pendingDeleteKeyPublicKeyBytes = _pendingDeleteKeyPublicKeyBytes;
			_pendingDeleteKeyPublicKeyBytes = null;
			deleteKeyPreTap(pendingDeleteKeyPublicKeyBytes);
			return;
		}
		handleCardDetected(secureElementApplet, tapRequested, authenticated, password);
	}
	
	protected void handleCardDetected(SecureElementApplet secureElementApplet, boolean tapRequested, boolean authenticated, String password) {
		// default implementation does nothing, override to hear about card detection events
	}

	protected void userCanceledSecureElementPromptSuper() {
		_pendingCardPassword = null;
		_pendingAddKeyLabel = null;
		_pendingEditPublicKey = null;
		_pendingEditLabel = null;
	    _pendingDeleteKeyPublicKeyBytes = null;
		
       	// we have no keys in the wallet - prompt the user to add one
        showGetStartedDialogIfNeeded();
        
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
    	} else {
    		errorMessage = getResources().getString(R.string.nfc_aware_activity_error_dialog_message_unknown);
    	}
		
		Toast.makeText(this, errorMessage, Toast.LENGTH_SHORT).show();
	}
}

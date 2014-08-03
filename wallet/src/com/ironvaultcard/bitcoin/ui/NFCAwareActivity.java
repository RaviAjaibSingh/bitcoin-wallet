package com.fortunacard.bitcoin.ui;

import java.io.IOException;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fortunacard.bitcoin.R;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.TagLostException;
import android.nfc.tech.IsoDep;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputFilter;
import android.view.KeyEvent;
import android.view.View;
import android.view.View.OnKeyListener;
import android.widget.EditText;
import android.widget.Toast;

import com.actionbarsherlock.app.SherlockFragmentActivity;
import com.fortunacard.bitcoin.Constants;
import com.fortunacard.bitcoin.IntegrationConnector;
import com.fortunacard.bitcoin.secureelement.ECKeyEntry;
import com.fortunacard.bitcoin.secureelement.SecureElementApplet;
import com.fortunacard.bitcoin.secureelement.SmartCardReader;
import com.fortunacard.bitcoin.secureelement.SecureElementApplet.PINState;
import com.fortunacard.bitcoin.secureelement.androidadapter.SmartCardReaderImpl;
import com.fortunacard.bitcoin.secureelement.exception.SmartCardFullException;
import com.fortunacard.bitcoin.secureelement.exception.WrongPasswordException;
import com.fortunacard.bitcoin.secureelement.real.SecureElementAppletImpl;
import com.fortunacard.bitcoin.secureelement.simulated.SecureElementAppletSimulatorImpl;
import com.fortunacard.bitcoin.wallet.WalletGlobals;

public abstract class NFCAwareActivity extends SherlockFragmentActivity {
	private static Logger _logger = LoggerFactory.getLogger(NFCAwareActivity.class);
	
	private static SecureElementApplet _cachedSecureElementApplet;
    
    private NfcAdapter _nfcAdapter;
    private PendingIntent _pendingIntent;
    private IntentFilter[] _intentFiltersArray;
    private String[][] _techListsArray;
	
    private AlertDialog _promptForTapAlertDialog;
    private AlertDialog _promptForPasswordDialog;
    private AlertDialog _tapToFinishDialog;
    
    private String _pendingCardPassword;
    private boolean _pendingUseExistingSessionIfPossible;
    private String _pendingLabel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        _pendingIntent = PendingIntent.getActivity(
                this, 0, new Intent(this, getClass()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), 0);

        _nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        // if (mNfcAdapter == null) {
        //     This will never happen because we're requiring in the manifest that the device is NFC capable.
        // }

        // IntentFilter ndef = new IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED);
        // try {
        //     ndef.addDataType("*/*");    // Handles all MIME based dispatches. You should specify only the ones that you need
        // }
        // catch (MalformedMimeTypeException e) {
        //     throw new RuntimeException("fail", e);
        // }
        // intentFiltersArray = new IntentFilter[] {ndef, };
        
        IntentFilter techTypeFilter = new IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED);
        _intentFiltersArray = new IntentFilter[] {techTypeFilter, };
        // TODO: stop using technology filters and use NDEF filters
        _techListsArray = new String[][] { new String[] { IsoDep.class.getName() } };

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
    protected void onDestroy() {
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        _nfcAdapter.enableForegroundDispatch(this, _pendingIntent, _intentFiltersArray, _techListsArray);
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
    
    public boolean processIntent(Intent intent) {
		_logger.info("onNewIntent: called");
		if (intent == null) {
			return false;
		}
		// TODO: something about preventing this screen from coming to the top of the stack
        // TODO: use NDEF instead of tech discovered
        if (intent.getAction() == NfcAdapter.ACTION_TECH_DISCOVERED) {
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
                boolean serviceNeedsToClearAndRestart = walletGlobals.synchronizeKeys(this, IntegrationConnector.getWallet(this), _ecPublicKeyEntries);
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
                	if (_tapToFinishDialog == null || cardIdentifierWasChanged ) {
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


	        	if (_promptForPasswordDialog != null) {
	        		// we're currently prompting the user to enter the password
	        		// update the dialog to have the number of password attempts left
	        		String passwordAttemptsLeftText = generatePasswordAttemptsLeftText();
	        		_promptForPasswordDialog.setMessage(passwordAttemptsLeftText);
	        		return true;
	        	}

	        	boolean tapRequested = false;
	        	if (_promptForTapAlertDialog != null) {
	        		// if we were prompting the user to tap the card, dismiss the dialog
	        		_promptForTapAlertDialog.dismiss();
	        		_promptForTapAlertDialog = null;
	        		tapRequested = true;
	        		
	        		if (_pendingCardPassword != null) {
	        			// we requested the user to tap the card so that we could log the user in
	        			// let the loginToCard function take care of logging in and then notifying the
	        			// subclass that there's a smart card session
	        			loginToCard(_pendingCardPassword);
	        			return true;
	        		}
	        	}
	        	
	        	if (_tapToFinishDialog != null) {
	        		// We were showing a tap to finish dialog - where we were asking the user to tap so we could
	        		// synchronize the keys.  That has already been done by the time we ge here, so nothing to do here.
	        		_tapToFinishDialog.dismiss();
	        		_tapToFinishDialog = null;
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
	
	protected SecureElementApplet getSecureElementAppletPromptIfNeeded(boolean requirePassword, boolean useExistingSessionIfPossible) {
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
					showPromptForTapDialog();
				}
				return _cachedSecureElementApplet;
			}
		} catch(IOException e) {
			_cachedSecureElementApplet = null;
			return _cachedSecureElementApplet;
		}
	}
	
	private String generatePasswordAttemptsLeftText() {
		int passwordAttemptsLeft = -1;
		SecureElementApplet secureElementApplet = getCachedSecureElementApplet();
		if (secureElementApplet != null) {
			// see if we know how many connection attempts are left because we're connected to the secure element
			try {
				passwordAttemptsLeft = secureElementApplet.getNumberPasswordAttemptsLeft();
			} catch (IOException e) {
			    _logger.info("generatePasswordAttemptsLeftText: IOException trying to read password attempts left");
				_cachedSecureElementApplet = null;
			}
		}
		
		String alertDialogMessage = getResources().getString(R.string.nfc_aware_activity_prompt_for_password_message) + " ";
		if (passwordAttemptsLeft == -1) {
			// we don't know how many password attempts left
			alertDialogMessage += getResources().getString(R.string.nfc_aware_activity_prompt_for_password_number_attempts_left_unknown); 
		} else if (passwordAttemptsLeft == 1) {
			alertDialogMessage += getResources().getString(R.string.nfc_aware_activity_prompt_for_password_number_attempts_warning_only_1_left);
		} else {
			String appendToAlertDialogMessage = String.format(getResources().getString(R.string.nfc_aware_activity_prompt_for_password_number_attempts_left), passwordAttemptsLeft);
			alertDialogMessage += appendToAlertDialogMessage;
		}
		return alertDialogMessage;
	}
	
	private void showPromptForPasswordDialog() {
		AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(this);

		// set title
		alertDialogBuilder.setTitle(getResources().getString(R.string.nfc_aware_activity_prompt_for_password_title));

		// Set an EditText view to get user input
		final EditText input = new EditText(this);
		input.setSingleLine(true);
		InputFilter[] filterArray = new InputFilter[1];
		// TODO: using this input filter is preventing the use of the backspace key once you've typed 8 characters
		// use a different system to prevent more than 8 characters
		filterArray[0] = new InputFilter.LengthFilter(8); // 8 characters at most
		input.setFilters(filterArray);
		alertDialogBuilder.setView(input);

		String alertDialogMessage = generatePasswordAttemptsLeftText();
	    // set dialog message
		alertDialogBuilder
			.setMessage(alertDialogMessage)
			.setCancelable(false)
			.setPositiveButton(getResources().getString(R.string.general_ok), new DialogInterface.OnClickListener() {
				public void onClick(DialogInterface dialog, int id) {
					// now try to login to the card
				    _logger.info("showPromptForPasswordDialog: proceeding");
				    userProceededOnPasswordDialog(input);
				  }
				})
			.setNegativeButton(getResources().getString(R.string.general_cancel), new DialogInterface.OnClickListener() {
				public void onClick(DialogInterface dialog, int id) {
					// if this button is clicked, just close
					// the dialog box and do nothing
					_promptForPasswordDialog = null;
					_pendingLabel = null;
					dialog.cancel();
					userCanceledSecureElementPrompt();
				  }
				});
 
		_promptForPasswordDialog = alertDialogBuilder.create();

		// Make it pressing enter on the confirm new password field will try to initialize the card
		input.setOnKeyListener(new OnKeyListener() {
		    public boolean onKey(View v, int keyCode, KeyEvent event) {
		        if(keyCode == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_UP) {
		        	userProceededOnPasswordDialog(input);
		        	return true;
		        }
		        return false;
		    }
		});

		
		// create alert dialog and show it
		_promptForPasswordDialog.show();
	}
	
	private void userProceededOnPasswordDialog(EditText input) {
	    _logger.info("userProceededOnPasswordDialog: called");
	    _promptForPasswordDialog.dismiss();
	    _promptForPasswordDialog = null;
	    loginToCard(input.getText().toString());
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

	protected SecureElementApplet getCachedSecureElementApplet() {
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

	private void showTapToFinishDialog() {
		if (_tapToFinishDialog != null) {
			_logger.error("showTapToFinishDialog: ignoring request to show dialog, dialog already showing");
			return;
		}
		
		AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(this);
		 
		// set title
		alertDialogBuilder.setTitle(getResources().getString(R.string.nfc_aware_activity_tap_to_continue_dialog_title));
 
			// set dialog message
		alertDialogBuilder
			.setMessage(getResources().getString(R.string.nfc_aware_activity_tap_to_continue_dialog_message))
			.setCancelable(false);
 
		// create alert dialog
		_tapToFinishDialog = alertDialogBuilder.create();
 
		// show it
		_tapToFinishDialog.show();
	}

	
	private void showPromptForTapDialog() {
		if (_promptForTapAlertDialog != null) {
			_logger.error("showPromptForTapDialog: ignoring request to show dialog, dialog already showing");
			return;
		}
		
		AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(this);
 
		// set title
		alertDialogBuilder.setTitle(getResources().getString(R.string.nfc_aware_activity_prompt_for_tap_dialog_title));
 
			// set dialog message
		alertDialogBuilder
			.setMessage(getResources().getString(R.string.nfc_aware_activity_prompt_for_tap_dialog_message))
			.setCancelable(false)
			.setNegativeButton(getResources().getString(R.string.general_cancel), new DialogInterface.OnClickListener() {
				public void onClick(DialogInterface dialog, int id) {
					// if this button is clicked, just close
					// the dialog box and do nothing
					dialog.cancel();
					_pendingCardPassword = null;
					_promptForTapAlertDialog = null;
					_pendingLabel = null;
					userCanceledSecureElementPrompt();
				  }
				});
 
		// create alert dialog
		_promptForTapAlertDialog = alertDialogBuilder.create();
 
		// show it
		_promptForTapAlertDialog.show();
	}
	
	public void promptToAddKey() {
		if (!checkIfNFCRadioOnPromptUser(true)) {
			// the NFC radio isn't on, prompt the user to turn it on and abort
			return;
		}
		
		_pendingLabel = null;
		AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(this);
		 
		// set title
		alertDialogBuilder.setTitle(getResources().getString(R.string.nfc_aware_activity_prompt_enter_label_title));
 
		// Set an EditText view to get user input
		final EditText input = new EditText(this);
		input.setSingleLine(true);
		InputFilter[] filterArray = new InputFilter[1];
		// TODO: using this input filter is preventing the use of the backspace key once you've typed 8 characters
		// use a different system to prevent more than 8 characters
		filterArray[0] = new InputFilter.LengthFilter(16); // 8 characters at most
		input.setFilters(filterArray);
		alertDialogBuilder.setView(input);
		
	    // set dialog message
		alertDialogBuilder
			.setMessage(getResources().getString(R.string.nfc_aware_activity_prompt_enter_label_message))
			.setCancelable(false)
			.setPositiveButton(getResources().getString(R.string.general_ok), new DialogInterface.OnClickListener() {
				public void onClick(DialogInterface dialog, int id) {
					if (input.getText().length() == 0) {
						// ask the user to enter a label
				    	Toast.makeText(NFCAwareActivity.this, getResources().getString(R.string.nfc_aware_activity_prompt_enter_label_error), Toast.LENGTH_SHORT).show();
						return;
					}
					dialog.dismiss();
					promptForLabelOKClicked(input);
				}
				})
			.setNegativeButton(getResources().getString(R.string.general_cancel), new DialogInterface.OnClickListener() {
				public void onClick(DialogInterface dialog, int id) {
					// if this button is clicked, just close
					// the dialog box and do nothing
					dialog.cancel();
				  }
				});
		
		final AlertDialog alertDialog = alertDialogBuilder.create();
		
		// Make it pressing enter on the label field will have the same effect as clicking ok
		input.setOnKeyListener(new OnKeyListener() {
		    public boolean onKey(View v, int keyCode, KeyEvent event) {
		        if(keyCode == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_UP) {
					if (input.getText().length() == 0) {
						// ask the user to enter a label
				    	Toast.makeText(NFCAwareActivity.this, getResources().getString(R.string.nfc_aware_activity_prompt_enter_label_error), Toast.LENGTH_SHORT).show();
						return true;
					}
		        	alertDialog.dismiss();
		        	promptForLabelOKClicked(input);
		        	return true;
		        }
		        return false;
		    }
		});

		// create alert dialog and show it
		alertDialog.show();
	}
	
	private void promptForLabelOKClicked(EditText editText) {
		// get a secure element session that is authenticated (authenticated session needed to add a key)
		String labelForKey = editText.getText().toString();
		SecureElementApplet secureElementApplet = this.getSecureElementAppletPromptIfNeeded(true, true);
		if (secureElementApplet == null) {
			// there was no authenticated session established - the user is now being prompted to provide one, so just bail out for now
		    _logger.info("promptForLabelOKClicked: waiting for authenticated session");
		    _pendingLabel = labelForKey;
		    return;
		}
		
		// otherwise we can just keep going and create the key
	    _logger.info("promptForLabelOKClicked: have authenticated session, creating key");
	    generateKeyOnSecureElement(secureElementApplet, labelForKey);
	}
	
	private void generateKeyOnSecureElement(SecureElementApplet secureElementApplet, String labelForKey) {
		_logger.info("generateKeyOnSecureElement: called");
		try {
			ECKeyEntry keyFromSecureElementToAddToCachedWallet = secureElementApplet.createOrInjectKey(labelForKey, null, null);
			// we just generated a key on the card and got back the public key bytes
			// add them to the cached wallet.  Add it assuming we don't need to restart the peergroup to see updates
			// to the key
			WalletGlobals.addECKeyEntryToWallet(this, IntegrationConnector.getWallet(this), keyFromSecureElementToAddToCachedWallet);
		} catch (IOException e) {
			if (e instanceof TagLostException) {
				// On some phones like Nexus 5, generating a key results in a tag lost exception because the phone couldn't sustain enough
				// power for the card. However, the card actually generated the key - so prompt the user to retap so we get
				// at the key
				_logger.info("generateKeyOnSecureElement: TagLostException while generating key - prompting for re-tap");
				showTapToFinishDialog();
				
			} else {
				showException(e);
			}
		}
	}
	
	protected void handleCardDetectedSuper(SecureElementApplet secureElementApplet, boolean tapRequested, boolean authenticated, String password) {
		if (_pendingLabel != null && authenticated) {
			// we had a request to add a key to the card, do that instead
			_logger.info("handleCardDetectedSuper: generating key with label");
			String pendingLabel = _pendingLabel;
			_pendingLabel = null;
			generateKeyOnSecureElement(secureElementApplet, pendingLabel);
			return;
		}
		handleCardDetected(secureElementApplet, tapRequested, authenticated, password);
	}
	
	protected void handleCardDetected(SecureElementApplet secureElementApplet, boolean tapRequested, boolean authenticated, String password) {
		// default implementation does nothing, override to hear about card detection events
	}

	protected void userCanceledSecureElementPrompt() {
		// default implementation does nothing, override to hear about dialog cancellation events
	}

	// utility method for subclasses to show errors
	public void showException(IOException e) {
		String errorMessage;
		
		if (e instanceof WrongPasswordException) {
    		// Toast.makeText(this, this.getResources().getString("Wrong password"), Toast.LENGTH_LONG).show();
			errorMessage = getResources().getString(R.string.nfc_aware_activity_error_dialog_message_wrong_password);
    	} else if (e instanceof SmartCardFullException) {
    		errorMessage = getResources().getString(R.string.nfc_aware_activity_error_dialog_message_smartcard_full);
    	} else if (e instanceof TagLostException) {
    		errorMessage = getResources().getString(R.string.nfc_aware_activity_error_dialog_message_tag_lost);
    	} else {
    		errorMessage = getResources().getString(R.string.nfc_aware_activity_error_dialog_message_unknown);
    	}
		
		Toast.makeText(this, errorMessage, Toast.LENGTH_SHORT).show();
	}
}

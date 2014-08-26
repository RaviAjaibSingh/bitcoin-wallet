package com.helioscard.wallet.bitcoin.ui;

import java.util.ArrayList;
import java.util.List;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.View.OnKeyListener;
import android.widget.EditText;
import android.widget.Toast;

import android.support.v4.app.DialogFragment;
import android.support.v4.app.FragmentManager;
import android.text.InputType;

import com.helioscard.wallet.bitcoin.Constants;
import com.helioscard.wallet.bitcoin.IntegrationConnector;
import com.helioscard.wallet.bitcoin.R;
import com.helioscard.wallet.bitcoin.secureelement.ECKeyEntry;
import com.helioscard.wallet.bitcoin.secureelement.SecureElementApplet.PINState;

import com.google.bitcoin.core.ECKey;
import com.google.bitcoin.core.Wallet;

public class PromptToSaveBackupDataDialogFragment extends DialogFragment {
    public static final String TAG = "PromptToSaveBackupDataDialogFragment";
    
    private String _sourceCardIdentifier;
    private final List<ECKeyEntry> _listOfKeys;
    private String _password;
    
    private PromptToSaveBackupDataDialogFragment(String sourceCardIdentifier, List<ECKeyEntry> listOfKeys, String password) {
    	_sourceCardIdentifier = sourceCardIdentifier;
    	_listOfKeys = listOfKeys;
    	_password = password;
    }

	public static void prompt(FragmentManager fragmentManager, String sourceCardIdentifier, List<ECKeyEntry> listOfKeys, String password) {
		PromptToSaveBackupDataDialogFragment frag = new PromptToSaveBackupDataDialogFragment(sourceCardIdentifier, listOfKeys, password);
    	frag.show(fragmentManager, TAG);
	}
	
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setRetainInstance(true); // retain instance so we keep the list of keys
	}
	
	@Override
	public void onDestroyView() {
		// we have retain instance state turned on, avoid having the dialog disappear on rotation
	    if (getDialog() != null && getRetainInstance()) {
	        getDialog().setDismissMessage(null);
	    }
	    super.onDestroyView();
	}

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
    	final NFCAwareActivity nfcAwareActivity = (NFCAwareActivity)getActivity();

    	AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(nfcAwareActivity);
    	// can't set a message on a multi choice dialog, just a title
        alertDialogBuilder.setTitle(getResources().getString(R.string.nfc_aware_activity_prompt_to_save_backup_data_dialog_title));
        alertDialogBuilder.setMessage(getResources().getString(R.string.nfc_aware_activity_prompt_to_save_backup_data_dialog_message));

        
		// Set an EditText view to get user input
		final EditText input = new EditText(nfcAwareActivity);
		input.setSingleLine(true);
		input.setInputType(InputType.TYPE_TEXT_VARIATION_PASSWORD);
		input.setHint(getResources().getString(R.string.nfc_aware_activity_prompt_to_save_backup_data_dialog_hint_enter_password_to_encrypt_file_against));

		alertDialogBuilder.setView(input);
		
        alertDialogBuilder.setPositiveButton(getResources().getString(R.string.nfc_aware_activity_prompt_to_save_backup_data_dialog_save_to_file), new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface dialog, int id) {
				handleSaveToFile(input.getText().toString());
			}
		});
        alertDialogBuilder.setNegativeButton(getResources().getString(R.string.general_cancel), new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface dialog, int id) {
				// if this button is clicked, just close
				// the dialog box and do nothing
				dialog.cancel();
				((NFCAwareActivity)getActivity()).resetState();
			  }
		});
        
		// Make it pressing enter on the confirm new password field will try to initialize the card
		input.setOnKeyListener(new OnKeyListener() {
		    public boolean onKey(View v, int keyCode, KeyEvent event) {
		        if(keyCode == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_UP) {
		        	handleSaveToFile(input.getText().toString());
		        	return true;
		        }
		        return false;
		    }
		});

        
        this.setCancelable(false);

        return alertDialogBuilder.create();
    }
    
    public String getSourceCardIdentifier() {
    	return _sourceCardIdentifier;
    }
    
    public List<ECKeyEntry> getKeysToBackup() {
    	return _listOfKeys;
    }
    
    public String getPassword() {
    	return _password;
    }
    
    private void handleSaveToFile(String password) {
		if (password == null || password.isEmpty()) {
			Toast.makeText(getActivity(), getResources().getString(R.string.nfc_aware_activity_prompt_to_save_backup_data_dialog_no_password_entered), Toast.LENGTH_LONG).show();
			return;
		}
		this.dismiss();
		
		// generate a wallet based on the keys we currently have
		Wallet wallet = new Wallet(Constants.NETWORK_PARAMETERS);
		for (int i = 0; i < _listOfKeys.size(); i++) {
			ECKeyEntry ecKeyEntry = _listOfKeys.get(i);
			ECKey ecKey = new ECKey(ecKeyEntry.getPrivateKeyBytes(), ecKeyEntry.getPublicKeyBytes());
			wallet.addKey(ecKey);
		}
		
		IntegrationConnector.backupWallet(getActivity(), password, wallet);
		
    }
}

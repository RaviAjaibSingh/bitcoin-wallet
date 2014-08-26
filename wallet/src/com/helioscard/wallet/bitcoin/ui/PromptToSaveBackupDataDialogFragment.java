package com.helioscard.wallet.bitcoin.ui;

import java.util.ArrayList;
import java.util.List;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.View;

import android.support.v4.app.DialogFragment;
import android.support.v4.app.FragmentManager;

import com.helioscard.wallet.bitcoin.Constants;
import com.helioscard.wallet.bitcoin.R;
import com.helioscard.wallet.bitcoin.secureelement.ECKeyEntry;
import com.helioscard.wallet.bitcoin.secureelement.SecureElementApplet.PINState;

import com.google.bitcoin.core.ECKey;

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
        
        /*
        alertDialogBuilder.setPositiveButton(getResources().getString(R.string.general_ok), new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface dialog, int id) {
				dialog.dismiss();
				((NFCAwareActivity)getActivity()).promptSaveBackupData(_sourceCardIdentifier, selectedList);
			}
		});
		*/
        alertDialogBuilder.setNegativeButton(getResources().getString(R.string.general_cancel), new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface dialog, int id) {
				// if this button is clicked, just close
				// the dialog box and do nothing
				dialog.cancel();
				((NFCAwareActivity)getActivity()).resetState();
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
}

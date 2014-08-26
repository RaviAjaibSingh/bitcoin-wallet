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

import com.google.bitcoin.core.ECKey;

public class ChooseKeysToBackupDialogFragment extends DialogFragment {
    public static final String TAG = "ChooseKeysToBackupDialogFragment";
    
    private String _sourceCardIdentifier;
    private final List<ECKeyEntry> _listOfKeys;
    private String _password;
    
    private ChooseKeysToBackupDialogFragment(String sourceCardIdentifier, List<ECKeyEntry> listOfKeys, String password) {
    	_sourceCardIdentifier = sourceCardIdentifier;
    	_listOfKeys = listOfKeys;
    	_password = password;
    }

	public static void prompt(FragmentManager fragmentManager, String sourceCardIdentifier, List<ECKeyEntry> listOfKeys, String password) {
		ChooseKeysToBackupDialogFragment frag = new ChooseKeysToBackupDialogFragment(sourceCardIdentifier, listOfKeys, password);
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
        alertDialogBuilder.setTitle(getResources().getString(R.string.nfc_aware_activity_choose_keys_to_backup_dialog_title));
        
        
        final int numKeys = _listOfKeys.size();
        String keyLabels[] = new String[numKeys];
        final boolean checkedItems[] = new boolean[numKeys];
        String unformattedKeyLabelString = getResources().getString(R.string.nfc_aware_activity_choose_keys_to_backup_dialog_label); 
        for (int i = 0; i < numKeys; i++) {
        	checkedItems[i] = true;
        	ECKeyEntry ecKeyEntry = _listOfKeys.get(i);
        	keyLabels[i] = String.format(unformattedKeyLabelString, ecKeyEntry.getFriendlyName(), new ECKey(null, ecKeyEntry.getPublicKeyBytes()).toAddress(Constants.NETWORK_PARAMETERS));
        }

        alertDialogBuilder.setMultiChoiceItems(keyLabels, checkedItems, null);

        alertDialogBuilder.setPositiveButton(getResources().getString(R.string.general_ok), new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface dialog, int id) {
				dialog.dismiss();
				List<ECKeyEntry> selectedList = new ArrayList<ECKeyEntry>(0);
			    for (int i = 0; i < numKeys; i++) {
			    	if (checkedItems[i]) {
			    		selectedList.add(_listOfKeys.get(i));
			    	}
			    }
				((NFCAwareActivity)getActivity()).promptSaveBackupData(_sourceCardIdentifier, selectedList, _password);
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
        
        this.setCancelable(false);

        return alertDialogBuilder.create();
    }
    
}

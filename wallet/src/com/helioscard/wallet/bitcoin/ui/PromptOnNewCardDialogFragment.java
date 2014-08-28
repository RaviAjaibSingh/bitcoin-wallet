package com.helioscard.wallet.bitcoin.ui;

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

public class PromptOnNewCardDialogFragment extends DialogFragment {
    public static final String TAG = "PromptOnNewCardDialogFragment";
    
    private String _cardBeingPromptedToSwitchToIdentifier;
    private List<ECKeyEntry> _ecPublicKeyEntries; 
    
    public PromptOnNewCardDialogFragment(String cardBeingPromptedToSwitchToIdentifier, List<ECKeyEntry> ecPublicKeyEntries) {
    	_cardBeingPromptedToSwitchToIdentifier = cardBeingPromptedToSwitchToIdentifier;
    	_ecPublicKeyEntries = ecPublicKeyEntries;
    }

	public static void prompt(FragmentManager fragmentManager, String cardBeingPromptedToSwitchToIdentifier, List<ECKeyEntry> ecPublicKeyEntries) {
		PromptOnNewCardDialogFragment frag = new PromptOnNewCardDialogFragment(cardBeingPromptedToSwitchToIdentifier, ecPublicKeyEntries);
    	frag.show(fragmentManager, TAG);
	}
	
	public String getCardBeingPromptedToSwitchToIdentifier() {
		return _cardBeingPromptedToSwitchToIdentifier;
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
		
		String alertMessage = String.format(getResources().getString(R.string.nfc_aware_activity_prompt_on_new_card_dialog_message), _cardBeingPromptedToSwitchToIdentifier); 
		int numKeys = _ecPublicKeyEntries.size();
		alertMessage += "\n\n";
		if (numKeys > 0) {
			alertMessage += getResources().getString(R.string.nfc_aware_activity_prompt_on_new_card_dialog_message_contains_keys);
			for (int i = 0; i < numKeys; i++) {
				ECKeyEntry ecKeyEntry = _ecPublicKeyEntries.get(i);
				alertMessage += "\n";
				alertMessage += String.format(getResources().getString(R.string.nfc_aware_activity_choose_keys_to_backup_dialog_label), ecKeyEntry.getFriendlyName(), new ECKey(null, ecKeyEntry.getPublicKeyBytes()).toAddress(Constants.NETWORK_PARAMETERS)); 
			}
		} else {
			alertMessage += getResources().getString(R.string.nfc_aware_activity_prompt_on_new_card_dialog_message_no_keys);
		}
		alertDialogBuilder.setMessage(alertMessage);
        alertDialogBuilder.setTitle(getResources().getString(R.string.nfc_aware_activity_prompt_on_new_card_dialog_title));
        alertDialogBuilder.setPositiveButton(getResources().getString(R.string.nfc_aware_activity_prompt_for_backup_or_restore_dialog_title), new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface dialog, int id) {
				dialog.dismiss();
				((NFCAwareActivity)getActivity()).promptForBackupOrRestore();
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
       
        // prevent us from being cancelable via the back button or clicking outside the bounds of the dialog
        this.setCancelable(false);

        return alertDialogBuilder.create();
    }
    
}

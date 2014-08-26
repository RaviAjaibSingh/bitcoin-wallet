package com.helioscard.wallet.bitcoin.ui;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.View;

import android.support.v4.app.DialogFragment;
import android.support.v4.app.FragmentManager;

import com.helioscard.wallet.bitcoin.R;

public class PromptForTapDialogFragment extends DialogFragment {
    public static final String TAG = "PromptForTapDialogFragment";

    public static final String FIELD_TYPE = "type";
    
    public static final int TYPE_NORMAL = 0;
    public static final int TYPE_REPOSITION = 1;
    public static final int TYPE_BACKUP = 2;

	public static void prompt(FragmentManager fragmentManager, int type) {
		PromptForTapDialogFragment frag = new PromptForTapDialogFragment();
		
		Bundle arguments = new Bundle();
		arguments.putInt(FIELD_TYPE, type);
		frag.setArguments(arguments);
		
    	frag.show(fragmentManager, TAG);
	}

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
    	final NFCAwareActivity nfcAwareActivity = (NFCAwareActivity)getActivity();
    	
		AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(nfcAwareActivity);
		 
		int type = getArguments().getInt(FIELD_TYPE);
		
		// set title
		alertDialogBuilder.setTitle(getResources().getString(type == TYPE_BACKUP ? R.string.nfc_aware_activity_prompt_for_tap_dialog_title_backup_card_text : R.string.nfc_aware_activity_prompt_for_tap_dialog_title));
 
		String alertDialogMessage;
		if (type == TYPE_NORMAL) {
			alertDialogMessage = getResources().getString(R.string.nfc_aware_activity_prompt_for_tap_dialog_message);
		} else if (type == TYPE_REPOSITION) {
			alertDialogMessage = getResources().getString(R.string.nfc_aware_activity_prompt_for_tap_dialog_message_reposition);
		} else /*if (type == TYPE_BACKUP)*/ {
			alertDialogMessage = getResources().getString(R.string.nfc_aware_activity_prompt_for_tap_dialog_message_backup_card_text);			
		}
		
			// set dialog message
		alertDialogBuilder
			.setMessage(alertDialogMessage)
			.setCancelable(false)
			.setNegativeButton(getResources().getString(R.string.general_cancel), new DialogInterface.OnClickListener() {
				public void onClick(DialogInterface dialog, int id) {
					// if this button is clicked, just close
					// the dialog box and do nothing
					dialog.cancel();
					((NFCAwareActivity)getActivity()).resetState();
				  }
				});
 
		this.setCancelable(false); // prevent the user from using the back button to dismiss this dialog
		
		// create alert dialog
		return alertDialogBuilder.create();
    }
}

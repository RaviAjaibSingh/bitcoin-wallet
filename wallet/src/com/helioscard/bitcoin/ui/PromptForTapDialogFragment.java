package com.helioscard.bitcoin.ui;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.View;

import android.support.v4.app.DialogFragment;
import android.support.v4.app.FragmentManager;

import com.helioscard.bitcoin.R;

public class PromptForTapDialogFragment extends DialogFragment {
    public static final String TAG = "PromptForTapDialogFragment";

    public static final String FIELD_TYPE = "type";
	public static void prompt(FragmentManager fragmentManager, boolean type) {
		PromptForTapDialogFragment frag = new PromptForTapDialogFragment();
		
		Bundle arguments = new Bundle();
		arguments.putBoolean(FIELD_TYPE, type);
		frag.setArguments(arguments);
		
    	frag.show(fragmentManager, TAG);
	}

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
    	final NFCAwareActivity nfcAwareActivity = (NFCAwareActivity)getActivity();
    	
		AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(nfcAwareActivity);
		 
		// set title
		alertDialogBuilder.setTitle(getResources().getString(R.string.nfc_aware_activity_prompt_for_tap_dialog_title));
 
		String alertDialogMessage;
		boolean type = getArguments().getBoolean(FIELD_TYPE);
		if (type == true) {
			alertDialogMessage = getResources().getString(R.string.nfc_aware_activity_prompt_for_tap_dialog_message);
		} else {
			alertDialogMessage = getResources().getString(R.string.nfc_aware_activity_prompt_for_tap_reposition);
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
					nfcAwareActivity.userCanceledSecureElementPromptSuper();
				  }
				});
 
		// create alert dialog
		return alertDialogBuilder.create();
    }
}

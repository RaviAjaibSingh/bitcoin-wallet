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

	public static void prompt(FragmentManager fragmentManager) {
		PromptForTapDialogFragment frag = new PromptForTapDialogFragment();
    	frag.show(fragmentManager, TAG);
	}

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
    	final NFCAwareActivity nfcAwareActivity = (NFCAwareActivity)getActivity();
    	
		AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(nfcAwareActivity);
		 
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
					nfcAwareActivity.userCanceledSecureElementPrompt();
				  }
				});
 
		// create alert dialog
		return alertDialogBuilder.create();
    }
}

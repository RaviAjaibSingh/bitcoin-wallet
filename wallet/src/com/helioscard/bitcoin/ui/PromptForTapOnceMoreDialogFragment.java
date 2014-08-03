package com.helioscard.bitcoin.ui;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.View;

import android.support.v4.app.DialogFragment;
import android.support.v4.app.FragmentManager;

import com.helioscard.bitcoin.R;

public class PromptForTapOnceMoreDialogFragment extends DialogFragment {
    public static final String TAG = "PromptForTapOnceMoreDialogFragment";

	public static void prompt(FragmentManager fragmentManager) {
		PromptForTapOnceMoreDialogFragment frag = new PromptForTapOnceMoreDialogFragment();
    	frag.show(fragmentManager, TAG);
	}

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
    	final NFCAwareActivity nfcAwareActivity = (NFCAwareActivity)getActivity();
    	
		AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(nfcAwareActivity);
		 
		// set title
		alertDialogBuilder.setTitle(getResources().getString(R.string.nfc_aware_activity_tap_to_continue_dialog_title));
 
			// set dialog message
		alertDialogBuilder
			.setMessage(getResources().getString(R.string.nfc_aware_activity_tap_to_continue_dialog_message));
		
		this.setCancelable(false); // prevent the user from using the back button to dismiss this dialog
 
		// create alert dialog
		return alertDialogBuilder.create();
    }
}

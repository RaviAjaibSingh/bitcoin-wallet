package com.helioscard.wallet.bitcoin.ui;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.text.InputFilter;
import android.view.KeyEvent;
import android.view.View;
import android.view.View.OnKeyListener;
import android.widget.EditText;
import android.widget.Toast;

import android.support.v4.app.DialogFragment;
import android.support.v4.app.FragmentManager;

import com.helioscard.wallet.bitcoin.R;

public class PromptForLabelDialogFragment extends DialogFragment {
    private static final String TAG = "PromptForLabelDialogFragment";
		    
	public static void prompt(FragmentManager fragmentManager) {
    	PromptForLabelDialogFragment frag = new PromptForLabelDialogFragment();
    	frag.show(fragmentManager, TAG);
	}

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
    	final NFCAwareActivity nfcAwareActivity = (NFCAwareActivity)getActivity();
    	
		AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(nfcAwareActivity);
		 
		// set title
		alertDialogBuilder.setTitle(getResources().getString(R.string.nfc_aware_activity_prompt_enter_label_title));
 
		// Set an EditText view to get user input
		final EditText input = new EditText(nfcAwareActivity);
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
				    	Toast.makeText(nfcAwareActivity, getResources().getString(R.string.nfc_aware_activity_prompt_enter_label_error), Toast.LENGTH_SHORT).show();
						return;
					}
					dialog.dismiss();
					nfcAwareActivity.createKeyPreTap(input.getText().toString());
				}
				})
			.setNegativeButton(getResources().getString(R.string.general_cancel), new DialogInterface.OnClickListener() {
				public void onClick(DialogInterface dialog, int id) {
					// if this button is clicked, just close
					// the dialog box and do nothing
					dialog.cancel();
					nfcAwareActivity.userCanceledSecureElementPromptSuper();
				  }
				});
		
		final AlertDialog alertDialog = alertDialogBuilder.create();
		
		// Make it pressing enter on the label field will have the same effect as clicking ok
		input.setOnKeyListener(new OnKeyListener() {
		    public boolean onKey(View v, int keyCode, KeyEvent event) {
		        if(keyCode == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_UP) {
					if (input.getText().length() == 0) {
						// ask the user to enter a label
				    	Toast.makeText(nfcAwareActivity, getResources().getString(R.string.nfc_aware_activity_prompt_enter_label_error), Toast.LENGTH_SHORT).show();
						return true;
					}
		        	alertDialog.dismiss();
		        	nfcAwareActivity.createKeyPreTap(input.getText().toString());
		        	return true;
		        }
		        return false;
		    }
		});

		this.setCancelable(false); // prevent the user from using the back button to dismiss this dialog
		
		// create alert dialog and show it
		return alertDialog;
    }
}

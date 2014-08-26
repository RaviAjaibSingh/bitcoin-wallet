package com.helioscard.wallet.bitcoin.ui;

import java.io.IOException;

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
import com.helioscard.wallet.bitcoin.secureelement.SecureElementApplet;

public class PromptForPasswordDialogFragment extends DialogFragment {
    public static final String TAG = "PromptForPasswordDialogFragment";
    
    private AlertDialog _alertDialog;
    
    private static final String SHOW_BACKUP_CARD_TEXT = "SHOW_BACKUP_CARD_TEXT";
    
	public static void prompt(FragmentManager fragmentManager, boolean showBackupCardText) {
		PromptForPasswordDialogFragment frag = new PromptForPasswordDialogFragment();
		Bundle args = new Bundle();
		args.putBoolean(SHOW_BACKUP_CARD_TEXT, showBackupCardText);
		frag.setArguments(args);
    	frag.show(fragmentManager, TAG);
	}

	@Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
    	final NFCAwareActivity nfcAwareActivity = (NFCAwareActivity)getActivity();
    	
    	boolean showBackupCardText = getArguments().getBoolean(SHOW_BACKUP_CARD_TEXT);
    	
		AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(nfcAwareActivity);
		 
		// set title
		alertDialogBuilder.setTitle(getResources().getString(showBackupCardText ? R.string.nfc_aware_activity_prompt_for_password_title_backup_card_text : R.string.nfc_aware_activity_prompt_for_password_title));

		// Set an EditText view to get user input
		final EditText input = new EditText(nfcAwareActivity);
		input.setSingleLine(true);
		
		/*
		InputFilter[] filterArray = new InputFilter[1];
		// TODO: using this input filter is preventing the use of the backspace key once you've typed 8 characters
		// use a different system to prevent more than 8 characters
		filterArray[0] = new InputFilter.LengthFilter(8); // 8 characters at most
		input.setFilters(filterArray);
		*/

		alertDialogBuilder.setView(input);

	    // set dialog message
		alertDialogBuilder
			.setCancelable(false)
			.setPositiveButton(getResources().getString(R.string.general_ok), new DialogInterface.OnClickListener() {
				public void onClick(DialogInterface dialog, int id) {
					// now try to login to the card
					dialog.dismiss();
					((NFCAwareActivity)getActivity()).userProceededOnPasswordDialog(input.getText().toString());
				  }
				})
			.setNegativeButton(getResources().getString(R.string.general_cancel), new DialogInterface.OnClickListener() {
				public void onClick(DialogInterface dialog, int id) {
					// if this button is clicked, just close
					// the dialog box and do nothing
					dialog.cancel();
					((NFCAwareActivity)getActivity()).resetState();
				  }
				});

		_alertDialog = alertDialogBuilder.create();
 		generatePasswordAttemptsLeftText();

		// Make it pressing enter on the confirm new password field will try to initialize the card
		input.setOnKeyListener(new OnKeyListener() {
		    public boolean onKey(View v, int keyCode, KeyEvent event) {
		        if(keyCode == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_UP) {
		        	_alertDialog.dismiss();
		        	((NFCAwareActivity)getActivity()).userProceededOnPasswordDialog(input.getText().toString());
		        	return true;
		        }
		        return false;
		    }
		});

		
		this.setCancelable(false); // prevent the user from using the back button to dismiss this dialog
		
		// create alert dialog and show it
		return _alertDialog;
    }

	public void generatePasswordAttemptsLeftText() {
    	final NFCAwareActivity nfcAwareActivity = (NFCAwareActivity)getActivity();
		
		int passwordAttemptsLeft = -1;
		SecureElementApplet secureElementApplet = nfcAwareActivity.getCachedSecureElementApplet();
		if (secureElementApplet != null) {
			// see if we know how many connection attempts are left because we're connected to the secure element
			try {
				passwordAttemptsLeft = secureElementApplet.getNumberPasswordAttemptsLeft();
			} catch (IOException e) {
			}
		}
    	boolean showBackupCardText = getArguments().getBoolean(SHOW_BACKUP_CARD_TEXT);		
		String alertDialogMessage = getResources().getString(showBackupCardText ? R.string.nfc_aware_activity_prompt_for_password_message_backup_card_text : R.string.nfc_aware_activity_prompt_for_password_message) + " ";
		if (passwordAttemptsLeft == -1) {
			// we don't know how many password attempts left
			alertDialogMessage += getResources().getString(R.string.nfc_aware_activity_prompt_for_password_number_attempts_left_unknown); 
		} else if (passwordAttemptsLeft == 1) {
			alertDialogMessage += getResources().getString(R.string.nfc_aware_activity_prompt_for_password_number_attempts_warning_only_1_left);
		} else {
			String appendToAlertDialogMessage = String.format(getResources().getString(R.string.nfc_aware_activity_prompt_for_password_number_attempts_left), passwordAttemptsLeft);
			alertDialogMessage += appendToAlertDialogMessage;
		}
		
		_alertDialog.setMessage(alertDialogMessage);
	}

}

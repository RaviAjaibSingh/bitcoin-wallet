package com.helioscard.wallet.bitcoin.ui;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;

import android.support.v4.app.DialogFragment;
import android.support.v4.app.FragmentManager;

import com.helioscard.wallet.bitcoin.R;

public class PromptForGetStartedDialogFragment extends DialogFragment {
    public static final String TAG = "PromptForGetStartedDialogFragment";

	public static void prompt(FragmentManager fragmentManager) {
		PromptForGetStartedDialogFragment frag = new PromptForGetStartedDialogFragment();
    	frag.show(fragmentManager, TAG);
	}

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
    	final NFCAwareActivity nfcAwareActivity = (NFCAwareActivity)getActivity();
    	
		AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(nfcAwareActivity);
		alertDialogBuilder.setMessage(getResources().getString(R.string.nfc_aware_activity_get_started_dialog_message));
        alertDialogBuilder.setTitle(getResources().getString(R.string.nfc_aware_activity_get_started_dialog_title));
        alertDialogBuilder.setNegativeButton(getResources().getString(R.string.nfc_aware_activity_get_started_dialog_create_new_key), null);
        alertDialogBuilder.setPositiveButton(getResources().getString(R.string.nfc_aware_activity_prompt_for_backup_or_restore_dialog_title), null);
        
        // prevent us from being cancelable, we want to force the user to create or import a key
        this.setCancelable(false);

        final AlertDialog alertDialog = alertDialogBuilder.create();
        
        // override the buttons this way so that we prevent the dialog from closing when the user hits the buttons
        alertDialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface dialog) {

                Button b = alertDialog.getButton(AlertDialog.BUTTON_POSITIVE);
                b.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
        				// dialog.dismiss(); // don't dismiss ourselves - we'll get dismissed once the user has a key
        				((NFCAwareActivity)getActivity()).promptForBackupOrRestore();
                    }
                });

                b = alertDialog.getButton(AlertDialog.BUTTON_NEGATIVE);
                b.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
        				// dialog.dismiss(); // don't dismiss ourselves - we'll get dismissed once the user has a key
        				((NFCAwareActivity)getActivity()).promptToAddKey();
                    }
                });

            }
        });

        return alertDialog;
    }
    
}

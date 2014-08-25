package com.helioscard.wallet.bitcoin.ui;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.View;

import android.support.v4.app.DialogFragment;
import android.support.v4.app.FragmentManager;

import com.helioscard.wallet.bitcoin.R;

public class PromptForBackupOrRestoreDialogFragment extends DialogFragment {
    public static final String TAG = "PromptForBackupOrRestoreDialogFragment";

	public static void prompt(FragmentManager fragmentManager) {
		PromptForBackupOrRestoreDialogFragment frag = new PromptForBackupOrRestoreDialogFragment();
    	frag.show(fragmentManager, TAG);
	}

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
    	final NFCAwareActivity nfcAwareActivity = (NFCAwareActivity)getActivity();
    	
		AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(nfcAwareActivity);
		alertDialogBuilder.setMessage(getResources().getString(R.string.nfc_aware_activity_prompt_for_backup_or_restore_dialog_message));
        alertDialogBuilder.setTitle(getResources().getString(R.string.nfc_aware_activity_prompt_for_backup_or_restore_dialog_title));
        alertDialogBuilder.setNegativeButton(getResources().getString(R.string.nfc_aware_activity_prompt_for_backup_or_restore_dialog_backup_from_card), new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface dialog, int id) {
				dialog.dismiss();
			}
		});
        alertDialogBuilder.setPositiveButton(getResources().getString(R.string.nfc_aware_activity_prompt_for_backup_or_restore_dialog_import_from_file), new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface dialog, int id) {
				dialog.dismiss();
			}
		});
        alertDialogBuilder.setNeutralButton(getResources().getString(R.string.general_cancel), new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface dialog, int id) {
				// if this button is clicked, just close
				// the dialog box and do nothing
				dialog.cancel();
				nfcAwareActivity.userCanceledSecureElementPromptSuper();
			  }
		});
        
        this.setCancelable(false);

        return alertDialogBuilder.create();
    }
    
}

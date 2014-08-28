package com.helioscard.wallet.bitcoin.ui;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.LinearLayout;

import android.support.v4.app.DialogFragment;
import android.support.v4.app.FragmentManager;

import com.helioscard.wallet.bitcoin.IntegrationConnector;
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
        LinearLayout linearLayout = new LinearLayout(nfcAwareActivity);
        linearLayout.setOrientation(LinearLayout.VERTICAL);
        Button button = new Button(nfcAwareActivity);
        button.setText(getResources().getString(R.string.nfc_aware_activity_prompt_for_backup_or_restore_dialog_backup_from_card));
        button.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				// TODO Auto-generated method stub
				dismiss();
				((NFCAwareActivity)getActivity()).backupCardPreTap();				
			}
        	
        });
        linearLayout.addView(button);
        
        button = new Button(nfcAwareActivity);
        button.setText(getResources().getString(R.string.nfc_aware_activity_prompt_for_backup_or_restore_dialog_import_from_file));
        button.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				// TODO Auto-generated method stub
				dismiss();
				IntegrationConnector.showRestoreWalletFromFileDialog(getActivity());				
			}
        });
        linearLayout.addView(button);
        
        button = new Button(nfcAwareActivity);
        button.setText(getResources().getString(R.string.general_cancel));
        button.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				// TODO Auto-generated method stub
				dismiss();
				((NFCAwareActivity)getActivity()).resetState();				
			}
        });
        linearLayout.addView(button);
        alertDialogBuilder.setView(linearLayout);        

        this.setCancelable(false);

        return alertDialogBuilder.create();
    }
    
}

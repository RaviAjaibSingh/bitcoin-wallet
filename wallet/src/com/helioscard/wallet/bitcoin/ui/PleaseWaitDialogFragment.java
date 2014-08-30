package com.helioscard.wallet.bitcoin.ui;

import android.app.Activity;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.View;

import android.support.v4.app.DialogFragment;
import android.support.v4.app.FragmentManager;

import com.helioscard.wallet.bitcoin.R;

public class PleaseWaitDialogFragment extends DialogFragment {
    public static final String TAG = "PleaseWaitDialogFragment";

    public static PleaseWaitDialogFragment show(FragmentManager fragmentManager) {
		PleaseWaitDialogFragment frag = new PleaseWaitDialogFragment();		
    	frag.show(fragmentManager, TAG);
    	return frag;
	}

	@Override
    public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setRetainInstance(true); // retain instance so we can be easily dismissed
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
    	final Activity myActivity = getActivity();
    	
    	final ProgressDialog dialog = new ProgressDialog(myActivity);
		dialog.setMessage(getResources().getString(R.string.please_wait_dialog_fragment_please_wait));
		dialog.setIndeterminate(true);
		dialog.setCancelable(false);
  
		this.setCancelable(false); // prevent the user from using the back button to dismiss this dialog
		
		// create alert dialog
		return dialog;
    }
}

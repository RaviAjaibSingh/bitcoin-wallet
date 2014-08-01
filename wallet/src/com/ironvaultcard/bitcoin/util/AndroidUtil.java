package com.ironvaultcard.bitcoin.util;

import com.ironvaultcard.bitcoin.R;

import android.app.AlertDialog;
import android.content.Context;

public class AndroidUtil {
	public static void showErrorDialog(Context context, String title, String message) {
		AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(context);
		alertDialogBuilder
		    .setTitle(title)
			.setMessage(message)
			.setCancelable(false)
			.setPositiveButton(context.getResources().getString(R.string.general_ok), null)
			.show();
	}
}

package com.ravsing.securecoincard.wallet;

import static com.google.bitcoin.core.Utils.uint32ToByteStreamLE;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.bitcoin.core.ECKey;
import com.google.bitcoin.core.ECKey.ECDSASignature;
import com.google.bitcoin.core.ScriptException;
import com.google.bitcoin.core.Transaction;
import com.google.bitcoin.core.TransactionInput;
import com.google.bitcoin.core.TransactionOutput;
import com.google.bitcoin.core.UnsafeByteArrayOutputStream;
import com.google.bitcoin.core.Transaction.SigHash;
import com.google.bitcoin.core.Wallet;
import com.google.bitcoin.crypto.TransactionSignature;
import com.google.bitcoin.script.Script;
import com.google.bitcoin.script.ScriptBuilder;
import com.google.bitcoin.script.ScriptOpCodes;
import com.ravsing.securecoincard.secureelement.ECUtil;
import com.ravsing.securecoincard.secureelement.SecureElementApplet;

public class BitcoinJUtils {
	private static Logger _logger = LoggerFactory.getLogger(BitcoinJUtils.class);
	
	/*
     * This function is a combination of the core bitcoinj functions Transaction.signInputs,
     * Transaction.calculateSignature and Transaction.hashForSignature.  We alter the functions to assume that we only operate
     * in SigHash.ALL mode (e.g. we are signing the transaction in a standard way, mandating both the inputs and the outputs are covered
     * by our signature), and anyonecanpay mode is off.
     */
    public static void signTransaction(SecureElementApplet secureElementApplet, Transaction transaction, Wallet wallet, String password) throws IOException {
        _logger.info("signTransaction: attempting to sign transaction");

        List<TransactionInput> inputs = transaction.getInputs();
        List<TransactionOutput> outputs = transaction.getOutputs();
        checkState(inputs.size() > 0);
        checkState(outputs.size() > 0);
        
        // The transaction is signed with the input scripts empty except for the input we are signing. In the case
        // where addInput has been used to set up a new transaction, they are already all empty. The input being signed
        // has to have the connected OUTPUT program in it when the hash is calculated!
        //
        // Note that each input may be claiming an output sent to a different key. So we have to look at the outputs
        // to figure out which key to sign with.

        TransactionSignature[] signatures = new TransactionSignature[inputs.size()];
        ECKey[] signingKeys = new ECKey[inputs.size()];

        // clear all the input scripts, if there were any.
        // in SigAll hash mode, you sign such that all inputs are cleared (it would be impossible to sign otherwise
        // since the signatures end up in the input scriptSig).  Except for the quirk later on in this code
        // where we copy in the scriptPub of the connected output into the input.
        for (int i = 0; i < inputs.size(); i++) {
            TransactionInput input = inputs.get(i);
            input.setScriptSig(new Script(TransactionInput.EMPTY_ARRAY));
        }
        
        for (int i = 0; i < inputs.size(); i++) {
            TransactionInput input = inputs.get(i);
            // We don't have the connected output, we assume it was signed already and move on
            if (input.getOutpoint().getConnectedOutput() == null) {
                _logger.warn("signTransaction: Missing connected output, assuming input {} is already signed.", i);
                continue;
            }
            try {
                // We assume if its already signed, its hopefully got a SIGHASH type that will not invalidate when
                // we sign missing pieces (to check this would require either assuming any signatures are signing
                // standard output types or a way to get processed signatures out of script execution)
                input.getScriptSig().correctlySpends(transaction, i, input.getOutpoint().getConnectedOutput().getScriptPubKey(), true);
                _logger.warn("signTransaction: Input {} already correctly spends output, assuming SIGHASH type used will be safe and skipping signing.", i);
                continue;
            } catch (ScriptException e) {
                // Expected.
            }
            if (input.getScriptBytes().length != 0)
                _logger.warn("signTransaction: Re-signing an already signed transaction! Be sure this is what you want.");
            // Find the signing key we'll need to use.

            // find the key in the local cached wallet that matches this (note the local cached wallet only has the public key)
            ECKey key = input.getOutpoint().getConnectedKey(wallet);

            // This assert should never fire. If it does, it means the wallet is inconsistent.
            checkNotNull(key, "signTransaction: Transaction exists in wallet that we cannot redeem: %s", input.getOutpoint().getHash());
            // Keep the key around for the script creation step below.
            signingKeys[i] = key;
            // The anyoneCanPay feature isn't used at the moment.
            byte[] connectedPubKeyScript = input.getOutpoint().getConnectedOutput().getScriptBytes();
            
            // The SIGHASH flags are used in the design of contracts, please see this page for a further understanding of
            // the purposes of the code in this method:
            //
            //   https://en.bitcoin.it/wiki/Contracts

            // This step has no purpose beyond being synchronized with the reference clients bugs. OP_CODESEPARATOR
            // is a legacy holdover from a previous, broken design of executing scripts that shipped in Bitcoin 0.1.
            // It was seriously flawed and would have let anyone take anyone elses money. Later versions switched to
            // the design we use today where scripts are executed independently but share a stack. This left the
            // OP_CODESEPARATOR instruction having no purpose as it was only meant to be used internally, not actually
            // ever put into scripts. Deleting OP_CODESEPARATOR is a step that should never be required but if we don't
            // do it, we could split off the main chain.
            connectedPubKeyScript = Script.removeAllInstancesOfOp(connectedPubKeyScript, ScriptOpCodes.OP_CODESEPARATOR);

            // Set the input to the script of its output. Satoshi does this but the step has no obvious purpose as
            // the signature covers the hash of the prevout transaction which obviously includes the output script
            // already. Perhaps it felt safer to him in some way, or is another leftover from how the code was written.
            // inputs.get(i).setScriptBytes(connectedPubKeyScript);
            input.setScriptSig(new Script(connectedPubKeyScript));
            
            ByteArrayOutputStream bos = new UnsafeByteArrayOutputStream(256);
            transaction.bitcoinSerialize(bos);
            // We also have to write a hash type (sigHashType is actually an unsigned char)
            byte sigHashType = (byte)TransactionSignature.calcSigHashValue(SigHash.ALL, false);
            uint32ToByteStreamLE(0x000000ff & sigHashType, bos);
            // Note that this is NOT reversed to ensure it will be signed correctly. If it were to be printed out
            // however then we would expect that it is IS reversed.

            
            // Sha256Hash hash = new Sha256Hash(doubleDigest(bos.toByteArray()));
            // bos.close();
            
            // we have the bytes to sign
            byte[] bytesToSignNonHashed = bos.toByteArray();
            bos.close();
            
            // hash the bytes once
            MessageDigest digest;
			try {
				digest = MessageDigest.getInstance("SHA-256");
			} catch (NoSuchAlgorithmException e) {
				throw new RuntimeException("No SHA-256 hashing on this device");
			}
            digest.update(bytesToSignNonHashed);
            byte[] bytesToSignHashedOnce = digest.digest();
            
            // now sign with the smart card - it will hash the bytes a second time
            // before actually signing, which is what we want because that's what bitcoin does- double hashed signature
            byte[] signatureFromSecureElement = secureElementApplet.doSimpleSign(password, ECUtil.getPublicKeyBytesFromEncoding(key.getPubKey(), false), bytesToSignHashedOnce);
            ECDSASignature ecdsaSignature = ECDSASignature.decodeFromDER(signatureFromSecureElement);
            
            // inputs.get(i).setScriptBytes(TransactionInput.EMPTY_ARRAY);
            input.setScriptSig(new Script(TransactionInput.EMPTY_ARRAY));
            
            // signatures[i] = new TransactionSignature(key.sign(hash), SigHash.ALL, false);
            signatures[i] = new TransactionSignature(ecdsaSignature, SigHash.ALL, false);
        }

        // Now we have calculated each signature, go through and create the scripts. Reminder: the script consists:
        // 1) For pay-to-address outputs: a signature (over a hash of the simplified transaction) and the complete
        //    public key needed to sign for the connected output. The output script checks the provided pubkey hashes
        //    to the address and then checks the signature.
        // 2) For pay-to-key outputs: just a signature.
        for (int i = 0; i < inputs.size(); i++) {
            if (signatures[i] == null)
                continue;
            TransactionInput input = inputs.get(i);
            final TransactionOutput connectedOutput = input.getOutpoint().getConnectedOutput();
            checkNotNull(connectedOutput);  // Quiet static analysis: is never null here but cannot be statically proven
            Script scriptPubKey = connectedOutput.getScriptPubKey();
            if (scriptPubKey.isSentToAddress()) {
                input.setScriptSig(ScriptBuilder.createInputScript(signatures[i], signingKeys[i]));
            } else if (scriptPubKey.isSentToRawPubKey()) {
                input.setScriptSig(ScriptBuilder.createInputScript(signatures[i]));
            } else {
                // Should be unreachable - if we don't recognize the type of script we're trying to sign for, we should
                // have failed above when fetching the key to sign with.
                throw new RuntimeException("Do not understand script type: " + scriptPubKey);
            }
        }
        
        _logger.info("signTransaction: successfully signed transaction");
    }
    
    public static long calculateFee(Transaction transaction) {
        _logger.info("calculateFee: called");

        List<TransactionInput> inputs = transaction.getInputs();
        List<TransactionOutput> outputs = transaction.getOutputs();
        checkState(inputs.size() > 0);
        checkState(outputs.size() > 0);

        // we have all the inputs and outputs.  The difference between the inputs
        // and the outputs is the transaction fee
        BigInteger fee = BigInteger.valueOf(0);
        for (TransactionInput input : inputs) {
        	fee = fee.add(input.getConnectedOutput().getValue());
        }
        
        for (TransactionOutput output : outputs) {
        	fee = fee.subtract(output.getValue());
        }
        
    	return fee.longValue();
    }
}

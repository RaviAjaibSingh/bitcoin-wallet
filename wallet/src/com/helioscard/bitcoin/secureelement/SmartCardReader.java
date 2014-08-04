package com.helioscard.bitcoin.secureelement;

import java.io.IOException;

public interface SmartCardReader {
	public byte[] exchangeAPDU(byte[] commandAPDU) throws IOException;
	public void close();
	public boolean checkConnection();
}
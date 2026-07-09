package com.creepsinc.clanbank;

/** Mirrors the success JSON shape of POST /borrow, /return, and /pay-loan. */
class ActionResponse
{
	boolean success;
	String itemName;
	long quantity;
	long value;
	long amountGp;
}

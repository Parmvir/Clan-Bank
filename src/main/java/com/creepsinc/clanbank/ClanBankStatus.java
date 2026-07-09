package com.creepsinc.clanbank;

import java.util.List;

/**
 * Mirrors the JSON shape returned by the bot's GET /status?rsn=... endpoint.
 * Field names match exactly since Gson deserializes by name.
 */
public class ClanBankStatus
{
	public boolean linked;
	public String clanName;
	public String discordTag;
	public long loanCapGp;
	public long totalOutstandingGp;
	public int pendingCount;
	public List<LoanEntry> loans;

	public static class LoanEntry
	{
		public String itemName;
		public String repaymentType; // "item" or "gp"
		public long quantityOwed;
		public long gpOwed;
		public String finalProduct;
	}
}

package com.creepsinc.clanbank;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * A bot API call reached the server and got a well-formed error response
 * (bad quantity, item not found, over the loan cap, etc.) — as opposed to
 * a plain IOException, which means the bot itself wasn't reachable at all.
 */
class ClanBankApiException extends IOException
{
	private final List<String> options;
	private final List<String> suggestions;

	ClanBankApiException(String message, List<String> options, List<String> suggestions)
	{
		super(message);
		this.options = options == null ? Collections.emptyList() : options;
		this.suggestions = suggestions == null ? Collections.emptyList() : suggestions;
	}

	List<String> getOptions()
	{
		return options;
	}

	List<String> getSuggestions()
	{
		return suggestions;
	}
}

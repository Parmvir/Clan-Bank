package com.creepsinc.clanbank;

import java.util.List;

/**
 * Shape of an error JSON body from any bot API endpoint — carried inside a
 * ClanBankApiException so the panel can show the exact reason (and, where
 * relevant, valid options/suggestions) instead of a generic failure.
 */
class ApiErrorResponse
{
	String error;
	List<String> options;
	List<String> suggestions;
}

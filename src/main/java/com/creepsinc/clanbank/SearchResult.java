package com.creepsinc.clanbank;

/** One row of the JSON shape returned by GET /search?query=... */
class SearchResult
{
	String name;
	long price;
	long stock;
	boolean lowStock;
}

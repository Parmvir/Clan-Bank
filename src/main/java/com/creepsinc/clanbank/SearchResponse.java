package com.creepsinc.clanbank;

import java.util.List;

/** Mirrors the JSON shape of GET /search?query=... */
class SearchResponse
{
	List<SearchResult> results;
}

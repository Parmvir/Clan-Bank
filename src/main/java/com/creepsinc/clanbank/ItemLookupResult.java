package com.creepsinc.clanbank;

import java.util.List;

/** Mirrors the JSON shape of GET /item?name=... */
class ItemLookupResult
{
	String name;
	long price;
	long stock;
	boolean lowStock;
	List<String> finalProducts;
	String notes;
}

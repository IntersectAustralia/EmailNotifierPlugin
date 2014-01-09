package com.googlecode.fascinator.portal.process;

import com.googlecode.fascinator.api.indexer.Indexer;
import com.googlecode.fascinator.api.indexer.SearchRequest;
import com.googlecode.fascinator.common.JsonSimple;
import com.googlecode.fascinator.common.solr.SolrDoc;
import com.googlecode.fascinator.common.solr.SolrResult;
import org.json.simple.JSONArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.lang.String;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Created with IntelliJ IDEA.
 * User: htxiong
 * Date: 9/01/2014
 * Time: 3:51 PM
 * To change this template use File | Settings | File Templates.
 */
public class DoiRecordProcessor  implements Processor {

	private Logger log = LoggerFactory.getLogger(DoiRecordProcessor.class);

	@Override
	public boolean process(String id, String inputKey, String outputKey,
						   String stage, String configFilePath, HashMap dataMap) throws Exception {
		log.debug("PASSED PARAMS-> ID:" + id + ", INPUTKEY: " + inputKey + ", OUTPUTKEY:" + outputKey + ", STAGE: " + stage + ", CONFIGFILEPATH:" + configFilePath);
		if ("pre".equalsIgnoreCase(stage)) {
			return getDoiRecords(id, outputKey, configFilePath, dataMap);
		} else if ("post".equalsIgnoreCase(stage)) {
			return postProcess(id, inputKey, configFilePath, dataMap);
		}
		return false;
	}

	private boolean getDoiRecords(String id, String outputKey, String configFilePath, HashMap dataMap) throws Exception {
		Indexer indexer = (Indexer) dataMap.get("indexer");
		File configFile = new File(configFilePath);
		JsonSimple config = new JsonSimple(configFile);

		// get processedRecords.
		JSONArray processedRecordsArr = config.getArray("processedRecords");
		List<String> processedRecords = new ArrayList<String>();
		if (processedRecordsArr != null && processedRecordsArr.size() > 0) {
			processedRecords.addAll(processedRecordsArr);
		}

		String solrQuery = config.getString("", "query");
		log.debug("Using solrQuery:" + solrQuery);
		SearchRequest searchRequest = new SearchRequest(solrQuery);
		int start = 0;
		int pageSize = 10;
		searchRequest.setParam("start", "" + start);
		searchRequest.setParam("rows", "" + pageSize);
		ByteArrayOutputStream result = new ByteArrayOutputStream();
		indexer.search(searchRequest, result);
		SolrResult resultObject = new SolrResult(result.toString());
		int numFound = resultObject.getNumFound();
		log.debug("Number found in Query result:" + numFound);
		ArrayList<String> foundRecords = new ArrayList<String>();
		while (true) {
			List<SolrDoc> results = resultObject.getResults();
			for (SolrDoc docObject : results) {
				String oid = docObject.getString(null, "id");
				if (oid != null) {

					if(!processedRecords.contains(oid)) {
						log.debug("Embargo date reached record found: " + oid);
						foundRecords.add(oid);
						processedRecords.add(oid);
					}
				} else {
					log.debug("Record returned but has no id.");
					log.debug(docObject.toString());
				}
			}
			start += pageSize;
			if (start > numFound) {
				break;
			}
			searchRequest.setParam("start", "" + start);
			result = new ByteArrayOutputStream();
			indexer.search(searchRequest, result);
			resultObject = new SolrResult(result.toString());
		}
		log.debug("Number will be processed:" + foundRecords.size());

		// get the exception list..
		JSONArray includedArr = config.getArray("includeList");
		if (includedArr != null && includedArr.size() > 0) {
			foundRecords.addAll(includedArr);
		}
		dataMap.put(outputKey, foundRecords);
		dataMap.put("processedRecords", processedRecords);

		return true;
	}

	private boolean postProcess(String id, String inputKey, String configFilePath, HashMap dataMap) throws Exception {
		File configFile = new File(configFilePath);
		SimpleDateFormat dtFormat = new SimpleDateFormat("yyy-MM-dd'T'HH:mm:ss'Z'");
		JsonSimple config = new JsonSimple(configFile);
		config.getJsonObject().put("lastrun", dtFormat.format(new Date()));
		List<String> oids = (List<String>) dataMap.get(inputKey);
		JSONArray includedArr = config.getArray("includeList");
		if (oids != null && oids.size() > 0) {
			// some oids failed, writing it to inclusion list so it can be sent next time...
			if (includedArr == null) {
				includedArr = config.writeArray("includeList");
			}
			includedArr.clear();
			for (String oid : oids) {
				includedArr.add(oid);
			}
		} else {
			// no oids failed, all good, clearing the list...
			if (includedArr != null && includedArr.size() > 0) {
				includedArr.clear();
			}
		}

		JSONArray processedRecordsArr = config.getArray("processedRecords");
		List<String> processedRecords =  (List<String>) dataMap.get("processedRecords");
		if (processedRecords != null && processedRecords.size() > 0) {
			// some oids failed, writing it to inclusion list so it can be sent next time...
			if (processedRecordsArr == null) {
				processedRecordsArr = config.writeArray("processedRecords");
			}
			processedRecordsArr.clear();
			for (String recordStr : processedRecords) {
				processedRecordsArr.add(recordStr);
			}
		} else {
			if (processedRecordsArr != null && processedRecordsArr.size() > 0) {
				processedRecordsArr.clear();
			}
		}

		FileWriter writer = new FileWriter(configFile);
		writer.write(config.toString(true));
		writer.close();
		return true;
	}
}

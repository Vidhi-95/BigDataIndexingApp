package info7255.messageQueue;

import org.apache.http.HttpHost;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.delete.DeleteResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.client.indices.CreateIndexRequest;
import org.elasticsearch.client.indices.GetIndexRequest;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentType;
import org.json.JSONArray;
import org.json.JSONObject;
import redis.clients.jedis.Jedis;

import java.io.IOException;
import java.util.*;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class ConsumerMessageQueue {
	private static Jedis jedis;
	private static RestHighLevelClient client = new RestHighLevelClient(
			RestClient.builder(new HttpHost("localhost", 9200, "http")));
	private static final String IndexName="planindex"
			+ "";

	public static void main(String args[]) throws IOException {
		jedis = new Jedis();
		System.out.println("Consumer MQ started");
		while (true) {
			String message = jedis.rpoplpush("messageQueue", "WorkingMQ");
			if (message == null) {
				continue;
			}
			JSONObject result = new JSONObject(message);
			
			// Get action
			Object obj = result.get("isDelete");
			System.out.println("isDelete: " + obj.toString());
						
			boolean isDelete = Boolean.parseBoolean(obj.toString());
			if(!isDelete) {
				JSONObject plan= new JSONObject(result.get("message").toString());
				System.out.println(plan.toString());
				postDocument(plan);
			}else {
				System.out.println(result.get("message").toString());
				JSONObject plan= new JSONObject(result.get("message").toString());
				
				deleteDocument(plan);
			}
		}
	}
	
	private static boolean indexExists() throws IOException {
		GetIndexRequest request = new GetIndexRequest(IndexName); 
		boolean exists = client.indices().exists(request, RequestOptions.DEFAULT);
		return exists;
	}
	
	private static String postDocument(JSONObject plan) throws IOException {
		if(!indexExists()) {
			createElasticIndex();
		}			
		IndexRequest request = new IndexRequest(IndexName);
		System.out.println(plan.toString());
		request.source(plan.toString(), XContentType.JSON);
		request.id(plan.get("objectId").toString());
		if (plan.has("parent_id")) {
		request.routing(plan.get("parent_id").toString());}
		IndexResponse indexResponse = client.index(request, RequestOptions.DEFAULT);
		System.out.println("response id: "+indexResponse.getId());
		return indexResponse.getResult().name();
	}

	private static void createElasticIndex() throws IOException {
		CreateIndexRequest request = new CreateIndexRequest(IndexName);
		request.settings(Settings.builder().put("index.number_of_shards", 1).put("index.number_of_replicas", 2));
		String mapping = getMapping();
		request.mapping(mapping, XContentType.JSON);

		client.indices().create(request, RequestOptions.DEFAULT);
	}
	
	private static void deleteDocument(JSONObject documentId) throws IOException {
		/*
		 * DeleteRequest request = new DeleteRequest(IndexName, documentId); try {
		 * 
		 * //TODO: delete indexers based on the id client.delete(request,
		 * RequestOptions.DEFAULT);
		 * 
		 * } catch (Exception e) {
		 * System.out.println("Error: check if mapping already deleted."); }
		 */
		ArrayList<String> listOfKeys = new ArrayList<String>();
		convertToKeys(documentId,listOfKeys);
        for(String key : listOfKeys){
            DeleteRequest request = new DeleteRequest(IndexName, key);
            DeleteResponse deleteResponse = client.delete(
                    request, RequestOptions.DEFAULT);
            
        }
	}

	// create mapping for parent-child relationship
	private static String getMapping() {
		String mapping = "{\r\n" +
				"    \"properties\": {\r\n" +
				"      \"objectId\": {\r\n" +
				"        \"type\": \"keyword\"\r\n" +
				"      },\r\n" +
				"      \"plan_service\":{\r\n" +
				"        \"type\": \"join\",\r\n" +
				"        \"relations\":{\r\n" +
				"          \"plan\": [\"membercostshare\", \"planservice\"],\r\n" +
				"          \"planservice\": [\"service\", \"planservice_membercostshare\"]\r\n" +
				"        }\r\n" +
				"      }\r\n" +
				"    }\r\n" +
				"  }\r\n" +
				"}";

		return mapping;
	}
	
	
	
    private static Map<String, Map<String, Object>> convertToKeys(JSONObject jsonObject, ArrayList<String> listOfKeys){

        Map<String, Map<String, Object>> map = new HashMap<String, Map<String, Object>>();
        Map<String, Object> valueMap = new HashMap<String, Object>();
        Iterator<String> iterator = jsonObject.keys();
        while (iterator.hasNext()){

            String key = iterator.next();
            String redisKey = jsonObject.get("objectId").toString();
            Object value = jsonObject.get(key);

            if (value instanceof JSONObject) {

                convertToKeys((JSONObject) value, listOfKeys);

            } else if (value instanceof JSONArray) {

                convertToKeysList((JSONArray) value, listOfKeys);

            } else {
                valueMap.put(key, value);
                map.put(redisKey, valueMap);
            }
        }

        listOfKeys.add(jsonObject.get("objectId").toString());
        return map;

    }

    private static List<Object> convertToKeysList(JSONArray array, ArrayList<String> listOfKeys) {
        List<Object> list = new ArrayList<Object>();
        for (int i = 0; i < array.length(); i++) {
            Object value = array.get(i);
            if (value instanceof JSONArray) {
                value = convertToKeysList((JSONArray) value, listOfKeys);
            } else if (value instanceof JSONObject) {
                value = convertToKeys((JSONObject) value,listOfKeys);
            }
            list.add(value);
        }
        return list;
    }
}

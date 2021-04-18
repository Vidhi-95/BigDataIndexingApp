package info7255.service;


import info7255.dao.InsurancePlanDao;
import org.apache.commons.codec.digest.DigestUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import redis.clients.jedis.exceptions.JedisException;

import java.util.*;


@Service
public class PlanService {

    @Autowired
    InsurancePlanDao planDao;

    @Autowired
    MessageQueueService messageQueueService;

    private Map<String,String> relationMap = new HashMap<>();

    /**
     * add a new plan with Etag
     * */
    public String addPlanETag(JSONObject planObject, String objectID) {

        //save json object
        Map<String, Object> savedPlanMap = savePlan(objectID, planObject);
        String savedPlan = new JSONObject(savedPlanMap).toString();

        indexQueue(planObject, objectID);

        //create and save eTag
        String newEtag = DigestUtils.md5Hex(savedPlan);
        System.out.println("eTag: " + newEtag);
        planDao.hSet(objectID, "eTag", newEtag);

        return newEtag;
    }


    public Map<String, Object> savePlan(String key, JSONObject planObject){
        //step1: store each object as a Hash: {redisKey, key, value}
        store(planObject);
        //step2: fetch, organize and operate all Hash as the request
        Map<String, Object> outputMap = new HashMap<>();
        processNestedJSONObject(key, outputMap, false);

        return outputMap;
    }

    /**
     * get a plan by objectID
     * */
    public Map<String, Object> getPlan(String key){
        Map<String, Object> outputMap = new HashMap<>();
        processNestedJSONObject(key, outputMap, false);
        return outputMap;
    }

    /**
     * delete a plan by objectID
     * */
    public void deletePlan(String key) {
        processNestedJSONObject(key, null, true);
    }


    private void indexQueue(JSONObject jsonObject, String uuid) {

        try {

            Map<String,String> simpleMap = new HashMap<>();


            for(Object key : jsonObject.keySet()) {
                String attributeKey = String.valueOf(key);
                Object attributeVal = jsonObject.get(String.valueOf(key));
                String edge = attributeKey;

                if(attributeVal instanceof JSONObject) {
                    JSONObject embdObject = (JSONObject) attributeVal;

                    JSONObject joinObj = new JSONObject();
                    if(edge.equals("planserviceCostShares") && embdObject.getString("objectType").equals("membercostshare")){
                        joinObj.put("name", "planservice_membercostshare");
                    } else {
                        joinObj.put("name", embdObject.getString("objectType"));
                    }

                    joinObj.put("parent", uuid);
                    embdObject.put("plan_service", joinObj);
                    embdObject.put("parent_id", uuid);
                    System.out.println(embdObject.toString());
                    messageQueueService.addToMessageQueue(embdObject.toString(), false);

                } else if (attributeVal instanceof JSONArray) {

                    JSONArray jsonArray = (JSONArray) attributeVal;
                    Iterator<Object> jsonIterator = jsonArray.iterator();

                    while(jsonIterator.hasNext()) {
                        JSONObject embdObject = (JSONObject) jsonIterator.next();
                        embdObject.put("parent_id", uuid);
                        System.out.println(embdObject.toString());

                        String embd_uuid = embdObject.getString("objectId");
                        relationMap.put(embd_uuid, uuid);

                        indexQueue(embdObject, embd_uuid);
                    }

                } else {
                    simpleMap.put(attributeKey, String.valueOf(attributeVal));
                }
            }

            JSONObject joinObj = new JSONObject();
            joinObj.put("name", simpleMap.get("objectType"));

            if(!simpleMap.containsKey("planType")){
                joinObj.put("parent", relationMap.get(uuid));
            }

            JSONObject obj1 = new JSONObject(simpleMap);
            obj1.put("plan_service", joinObj);
            obj1.put("parent_id", relationMap.get(uuid));
            System.out.println(obj1.toString());
            messageQueueService.addToMessageQueue(obj1.toString(), false);


        }
        catch(JedisException e) {
            e.printStackTrace();
        }
    }

    /**
     * store structured data into redis
     * */
    private Map<String, Map<String, Object>> store(JSONObject object) {

        Map<String, Map<String, Object>> map = new HashMap<>();
        Map<String, Object> valueMap = new HashMap<>();
        //get all keys from the object
        Iterator<String> iterator = object.keySet().iterator();
        System.out.println("iterators: " + object.keySet().toString());//TODO:only for testing

        while (iterator.hasNext()) {
            //store as {redisKey, key, value} | redisKey: "objectType_objectID"
            String redisKey = object.get("objectType") + "_" + object.get("objectId");
            String key = iterator.next();
            Object value = object.get(key);

            //save values with various types: object, array, string
            //1. save object: e.g. planCostShares:{k-v}
            if (value instanceof JSONObject) {
                value = store((JSONObject) value);
                //E.g. <membercostshare_1234xxxxx-501, <5 kv-pairs>>
                HashMap<String, Map<String, Object>> val = (HashMap<String, Map<String, Object>>) value;
                //save set (redisKey_key, objectID),e.g. (redisKey_planCostShares, membercostshare_1234xxxxx-501);
                planDao.addSetValue(redisKey + "_" + key, val.entrySet().iterator().next().getKey());


            //2. save list: e.g. linkedPlanServices:[{k-v},{k-v}]
            } else if (value instanceof JSONArray) {
                value = convertToList((JSONArray) value);
                for (HashMap<String, HashMap<String, Object>> entry : (List<HashMap<String, HashMap<String, Object>>>) value) {
                    for (String listKey : entry.keySet()) {
                        //save 2 linkedPlanServices
                        planDao.addSetValue(redisKey + "_" + key, listKey);
                    }
                }

            //3. save string: e.g. {objectType: plan}
            } else {
                planDao.hSet(redisKey, key, value.toString());
                valueMap.put(key, value);
                map.put(redisKey, valueMap);

            }
        }
        System.out.println("map: "+ map.toString());
        return map;

    }

    private List<Object> convertToList(JSONArray array) {
        List<Object> list = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            Object value = array.get(i);
            if (value instanceof JSONArray) {
                value = convertToList((JSONArray) value);
            } else if (value instanceof JSONObject) {
                value = store((JSONObject) value);
            }
            list.add(value);
        }
        return list;
    }

    /**
     * process requests of get and delete
     * */
    private Map<String, Object> processNestedJSONObject(String redisKey, Map<String, Object> outputMap, boolean isDelete) {

        Set<String> keys = planDao.getKeys(redisKey + "_*");
        keys.add(redisKey);
        for (String key : keys) {

            //Case 1. KV pairs with redisKey
            if (key.equals(redisKey)) {
                if (isDelete) {
                    //1. delete plan
                    planDao.deleteKeys(new String[] {key});
                    
                    
                } else {
                    //2. get kv pairs stored
                    Map<String, String> val = planDao.getAllValuesByKey(key);
                    for (String name : val.keySet()) {
                        //put kv into outMap except eTag
                        if (!name.equalsIgnoreCase("eTag")) {
                            outputMap.put(name, isStringDouble(val.get(name)) ? Double.parseDouble(val.get(name)) : val.get(name));
                        }
                    }
                }

            //operate on sub-objects
            } else {

                //get the key for sub-object
                String curKey = key.substring((redisKey + "_").length());
                //get all keys
                Set<String> members = planDao.sMembers(key);

                //Case 2. Nested Object List
                if (members.size() > 1) {
                    List<Object> listObj = new ArrayList<>();

                    //recursion to the kv-pairs level for data operation
                    for (String member : members) {
                        if (isDelete) {
                            processNestedJSONObject(member, null, true);
                        } else {
                            Map<String, Object> listMap = new HashMap<>();
                            listObj.add(processNestedJSONObject(member, listMap, false));

                        }
                    }
                    if (isDelete) {
                        planDao.deleteKeys(new String[] {key});
                    } else {
                        outputMap.put(curKey, listObj);
                    }

                //Case 3. Nested Object
                } else {
                    if (isDelete) {
                        planDao.deleteKeys(new String[]{members.iterator().next(), key});
                    } else {
                        Map<String, String> val = planDao.getAllValuesByKey(members.iterator().next());
                        Map<String, Object> newMap = new HashMap<>();
                        for (String name : val.keySet()) {
                            newMap.put(name, isStringDouble(val.get(name)) ? Double.parseDouble(val.get(name)) : val.get(name));
                        }
                        outputMap.put(curKey, newMap);
                    }
                }
            }
        }
        return outputMap;
    }

    private boolean isStringDouble(String s) {
        try {
            Double.parseDouble(s);
            return true;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    public boolean isKeyExists(String key){
        return planDao.checkIfKeyExist(key);
    }

    public String getEtag(String key, String field) {
        return planDao.hGet(key, field);
    }



}

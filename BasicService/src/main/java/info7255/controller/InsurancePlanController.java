package info7255.controller;



import info7255.service.MessageQueueService;
import info7255.service.PlanService;
import info7255.validator.JsonValidator;
import org.everit.json.schema.ValidationException;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;


import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.validation.Valid;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.util.Date;
import java.util.Map;


@RestController
@RequestMapping(path = "/")
public class InsurancePlanController {

    @Autowired
    JsonValidator validator;

    @Autowired
    PlanService planservice;

    @Autowired
    InsurancePlanController mc;

    @Autowired
    private MessageQueueService messageQueueService;
    
    private RSAKey rsaPublicJWK;


    @PostMapping(path ="/token", produces = "application/json")
    public ResponseEntity<Object> createToken(@RequestHeader("authorization") String idToken, @Valid @RequestBody(required = false) String medicalPlan) throws Exception {
        if (medicalPlan == null || medicalPlan.isEmpty()){
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new JSONObject().put("Error", "Body is Empty. Kindly provide the JSON").toString());
        }

        return ResponseEntity.ok().body(idToken);
    }


    @PostMapping(path ="/plan", produces = "application/json")
    public ResponseEntity<Object> createPlan(@RequestHeader("authorization") String idToken, @RequestHeader HttpHeaders headers, @Valid @RequestBody(required = false) String medicalPlan) throws Exception {
        if (medicalPlan == null || medicalPlan.isEmpty()){
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new JSONObject().put("Error", "Body is Empty. Kindly provide the JSON").toString());
        }

        //Authorize
        Boolean returnValue = mc.ifAuthorized(headers);
        if (!returnValue)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new JSONObject().put("Authetication Error:  ", "Invalid Token").toString());

        JSONObject plan = new JSONObject(medicalPlan);
        try{
            validator.validateJson(plan);
        }catch(ValidationException ex){
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new JSONObject().put("Error",ex.getErrorMessage()).toString());
        }

        //create a key for plan: objecyType + objectID
        String key = plan.get("objectType").toString() + "_" + plan.get("objectId").toString();
        //check if plan exists
        if(planservice.isKeyExists(key)){
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new JSONObject().put("Message", "Plan already exist").toString());
        }

        //save the plan if not exist
        String newEtag = planservice.addPlanETag(plan, plan.get("objectId").toString());
        String res = "{ObjectId: " + plan.get("objectId") + ", ObjectType: " + plan.get("objectType") + "}";
        return ResponseEntity.ok().eTag(newEtag).body(new JSONObject(res).toString());//TODO: test
    }

    @PatchMapping(path = "/plan/{objectId}", produces = "application/json")
    public ResponseEntity<Object> patchPlan(@RequestHeader("authorization") String idToken, @RequestHeader HttpHeaders headers, @Valid @RequestBody String medicalPlan, @PathVariable String objectId) throws IOException, ParseException, JOSEException {

        //Authorize
    	Boolean returnValue = mc.ifAuthorized(headers);
        if (!returnValue)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new JSONObject().put("Authetication Error:  ", "Invalid Token").toString());
        
        JSONObject plan = new JSONObject(medicalPlan);
        String key = "plan_" + objectId;
        if (!planservice.isKeyExists(key)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new JSONObject().put("Message", "ObjectId does not exist").toString());
        }

        //return status 412 if a mid-air update occurs (e.g. etag/header is different from etag/in-processing)
        String actualEtag = planservice.getEtag(objectId, "eTag");
        String eTag = headers.getFirst("If-Match");
        if (eTag != null && !eTag.equals(actualEtag)) {
            return ResponseEntity.status(HttpStatus.PRECONDITION_FAILED).eTag(actualEtag).build();
        }

        //update if the plan already created
        String newEtag = planservice.addPlanETag(plan, plan.get("objectId").toString());
        return ResponseEntity.ok().eTag(newEtag).body(new JSONObject().put("Message ", "Updated successfully").toString());
    }


    @GetMapping(path = "/{type}/{objectId}",produces = "application/json ")
    public ResponseEntity<Object> getPlan(@RequestHeader("authorization") String idToken, @RequestHeader HttpHeaders headers, @PathVariable String objectId,@PathVariable String type) throws JSONException, Exception {

        String key = type + "_" + objectId;
        if (!planservice.isKeyExists(key)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new JSONObject().put("Message", "ObjectId does not exist").toString());
        }

        //Authorize
        Boolean returnValue = mc.ifAuthorized(headers);
        if (!returnValue)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new JSONObject().put("Authetication Error:  ", "Invalid Token").toString());
        
        String actualEtag = null;
        if (type.equals("plan")) {
            actualEtag = planservice.getEtag(objectId, "eTag");
            String eTag = headers.getFirst("if-none-match");
            //if not updated -> 304
            if (actualEtag.equals(eTag)){
                return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(actualEtag).build();
            }
        }

        Map<String, Object> plan = planservice.getPlan(key);
        if (type.equals("plan")) {
            return ResponseEntity.ok().eTag(actualEtag).body(new JSONObject(plan).toString());
        }

        return ResponseEntity.ok().body(new JSONObject(plan).toString());
    }

    @PutMapping(path = "/plan/{objectId}", produces = "application/json")
    public ResponseEntity<Object> updatePlan(@RequestHeader("authorization") String idToken, @RequestHeader HttpHeaders headers, @Valid @RequestBody String medicalPlan, @PathVariable String objectId) throws IOException, ParseException, JOSEException {

        //Authorize
    	Boolean returnValue = mc.ifAuthorized(headers);
        if (!returnValue)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new JSONObject().put("Authetication Error:  ", "Invalid Token").toString());
        JSONObject plan = new JSONObject(medicalPlan);
        try {
            validator.validateJson(plan);
        } catch (ValidationException ex) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new JSONObject().put("Validation Error", ex.getMessage()).toString());
        }

        String key = "plan_" + objectId;
        //check if the target for update exist
        if (!planservice.isKeyExists(key)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new JSONObject().put("Message", "ObjectId does not exist").toString());
        }

        // return status 412 if a mid-air update occurs (e.g. etag/header is different from etag/in-processing)
        String actualEtag = planservice.getEtag(objectId, "eTag");
        String eTag = headers.getFirst("If-Match");
        if (eTag != null && !eTag.equals(actualEtag)) {
            return ResponseEntity.status(HttpStatus.PRECONDITION_FAILED).eTag(actualEtag).build();
        }

        planservice.deletePlan("plan" + "_" + objectId);
        String newEtag = planservice.addPlanETag(plan, plan.get("objectId").toString());
        return ResponseEntity.ok().eTag(newEtag).body(new JSONObject().put("Message: ", "Updated successfully").toString());
    }


    @DeleteMapping("/plan/{objectId}")
    public ResponseEntity<Object> getPlan(@RequestHeader HttpHeaders headers, String idToken, @PathVariable String objectId) throws ParseException, JOSEException{

        if (!planservice.isKeyExists("plan"+ "_" + objectId)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new JSONObject().put("Message", "ObjectId does not exist").toString());
        }

        Boolean returnValue = mc.ifAuthorized(headers);
        if (!returnValue)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new JSONObject().put("Authetication Error:  ", "Invalid Token").toString());
        
        Map<String, Object> plan = planservice.getPlan("plan"+ "_" + objectId);
        
        planservice.deletePlan("plan" + "_" + objectId);

        
        messageQueueService.addToMessageQueue(new JSONObject(plan).toString(), true);
        return ResponseEntity.noContent().build();

    }
    
    @GetMapping(value = "/getToken")
    public ResponseEntity<String> getToken()
            throws UnsupportedEncodingException, NoSuchAlgorithmException, NoSuchPaddingException, InvalidKeyException,
            InvalidAlgorithmParameterException, IllegalBlockSizeException, BadPaddingException,JOSEException, ParseException {

    		// RSA signatures require a public and private RSA key pair, the public key 
    		// must be made known to the JWS recipient in order to verify the signatures
    		RSAKey rsaJWK = new RSAKeyGenerator(2048).keyID("123").generate();
    		System.out.println(rsaJWK);
    		rsaPublicJWK = rsaJWK.toPublicJWK();
    		// verifier = new RSASSAVerifier(rsaPublicJWK);
    		System.out.println(rsaPublicJWK);
    		// Create RSA-signer with the private key
    		JWSSigner signer = new RSASSASigner(rsaJWK);

    		// Prepare JWT with claims set
    		int expireTime = 30000; // seconds
    		
    		JWTClaimsSet claimsSet = new JWTClaimsSet.Builder().expirationTime(new Date(new Date().getTime() + expireTime * 1000)) // milliseconds
    		    .build();

    		SignedJWT signedJWT = new SignedJWT(
    		    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsaJWK.getKeyID()).build(),
    		    claimsSet);

    		// Compute the RSA signature
    		signedJWT.sign(signer);
    		
    		String token = signedJWT.serialize();
    		
    		String res = "{\"status\": \"Successful\",\"token\": \"" + token + "\"}";
    		
        return new ResponseEntity<String>(res, HttpStatus.OK);
    }
    
    private boolean ifAuthorized(HttpHeaders requestHeaders) throws ParseException, JOSEException {
		String token = requestHeaders.getFirst("Authorization").substring(7);
		// On the consumer side, parse the JWS and verify its RSA signature
		SignedJWT signedJWT = SignedJWT.parse(token);

		JWSVerifier verifier = new RSASSAVerifier(rsaPublicJWK);
		// Retrieve / verify the JWT claims according to the app requirements
		if (!signedJWT.verify(verifier)) {
			return false;
		}
		JWTClaimsSet claimset = signedJWT.getJWTClaimsSet();
		Date exp = 	claimset.getExpirationTime();

		return new Date().before(exp);
	}
    

}

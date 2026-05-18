package org.sunbird.workflow.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.sunbird.workflow.models.Response;
import org.sunbird.workflow.models.SearchCriteria;
import org.sunbird.workflow.service.WorkFlowServiceV2;
import org.sunbird.workflow.service.Workflowservice;

import java.util.Map;

@RestController
@RequestMapping("/v2/workflow")
public class WorkFlowControllerV2 {

    @Autowired
    @Qualifier("workflowServiceImpl")
    private Workflowservice workflowService;

    @Autowired
    private WorkFlowServiceV2 workFlowServiceV2;

    @PostMapping(path = "/getUserWFApplicationFields", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Response> getUserWFApplicationFieldsV2(@RequestHeader String rootOrg, @RequestHeader String org,
                                                                 @RequestHeader String wid, @RequestBody SearchCriteria searchCriteria) {
        Response response = workflowService.getUserWFApplicationFieldsV2(rootOrg, org, wid, searchCriteria);
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    @PostMapping("/transition")
    public ResponseEntity<Response> wfTransition(@RequestHeader String rootOrg, @RequestHeader String org,
                                                 @RequestBody Map<String, Object> requestBody) {
        Response response = workFlowServiceV2.workflowTransition(rootOrg, org, requestBody);
        return new ResponseEntity<>(response, HttpStatus.OK);
    }
}

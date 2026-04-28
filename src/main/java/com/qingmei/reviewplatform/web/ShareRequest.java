package com.qingmei.reviewplatform.web;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ShareRequest(@JsonProperty("expiry_hours") int expiryHours,
                           @JsonProperty("annotations_json") String annotationsJson) {
}

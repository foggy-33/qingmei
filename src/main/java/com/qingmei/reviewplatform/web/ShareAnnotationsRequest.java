package com.qingmei.reviewplatform.web;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ShareAnnotationsRequest(@JsonProperty("annotations_json") String annotationsJson) {
}

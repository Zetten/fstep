package com.cgi.eoss.fstep.search.fstep.opensearch;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class RestoSearchPropertiesLink {
    @JsonProperty("rel")
    private String relation;
    @JsonProperty("href")
    private String href;
    @JsonProperty("type")
    private String type;
}
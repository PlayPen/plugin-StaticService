package io.playpen.plugin.staticservice;

import io.playpen.core.p3.P3Package;
import lombok.Data;

import java.util.Map;

@Data
public class StaticService {
    private String packageId;
    private String packageVersion = "promoted";

    private String name;
    private String coordinator;

    private Map<String, String> strings;
}
